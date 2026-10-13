#!lua name=photon
--
-- PHOTON state transitions. Every transition is one function, so every check and write in it is atomic.
--
-- Session functions get the same six keys, all tagged with the session id {sid}:
--   KEYS[1] session:{sid}           hash   host, status, created_at, expires_at, max_members, stored_bytes,
--                                          reserved_bytes, join_code, invite_token, last_event_id, trimmed_before
--   KEYS[2] session:{sid}:members   hash   uid -> {role, joined_at, strikes}
--   KEYS[3] session:{sid}:photos    hash   sha256 hex -> {status, uploader, upload_id, size, mime, taken_at,
--                                          reserved_at, stored_at, expires_at}
--   KEYS[4] session:{sid}:live      hash   upload_id -> {sha, uploader, size, reserved_at}
--   KEYS[5] session:{sid}:events    stream
--   KEYS[6] session:{sid}:kicked    set
-- ARGV always starts with sid, now_ms, retention_ms. Lease keys upload:{sid}:<upload_id> are built here with the
-- same tag, so they stay in the session's slot. Results are {'OK', ...} or {'ERR', CODE}.

local VERSION = '3'
local HOUR = 3600000

local function ok(...) return {'OK', ...} end
local function err(code) return {'ERR', code} end
local function lease_key(sid, upload_id) return 'upload:{' .. sid .. '}:' .. upload_id end

local function get_json(key, field)
  local raw = redis.call('HGET', key, field)
  if not raw then return nil end
  return cjson.decode(raw)
end

local function put_json(key, field, value)
  redis.call('HSET', key, field, cjson.encode(value))
end

local function parse_id(id)
  if type(id) ~= 'string' then return nil end
  local ms, seq = string.match(id, '^(%d+)-(%d+)$')
  if not ms then return nil end
  return tonumber(ms), tonumber(seq)
end

local function id_lt(a, b)
  local am, as = parse_id(a)
  local bm, bs = parse_id(b)
  if am ~= bm then return am < bm end
  return as < bs
end

local function is_active(keys)
  return redis.call('HGET', keys[1], 'status') == 'ACTIVE'
end

-- Appends one event. Ids come from the caller's clock (ms-*) but never go backwards, so trimming by MINID
-- and the resync rule use the same time as every other timestamp.
local function append_event(keys, now, retention, fields)
  local last = redis.call('HGET', keys[1], 'last_event_id')
  local ms = now
  local last_ms = parse_id(last)
  if last_ms and last_ms > ms then ms = last_ms end
  local min_ms = now - retention - HOUR
  if min_ms < 0 then min_ms = 0 end
  local minid = min_ms .. '-0'
  local id = redis.call('XADD', keys[5], 'MINID', '~', minid, ms .. '-*', unpack(fields))
  redis.call('HSET', keys[1], 'last_event_id', id, 'trimmed_before', minid)
  return id
end

-- Clears one reservation. Bytes are released only if this upload_id still owns the hash, so never twice.
local function release_reservation(keys, sid, sha, upload_id)
  local e = get_json(keys[3], sha)
  if e and e.status == 'UPLOADING' and e.upload_id == upload_id then
    redis.call('HDEL', keys[3], sha)
    redis.call('HINCRBY', keys[1], 'reserved_bytes', -e.size)
  end
  redis.call('HDEL', keys[4], upload_id)
  redis.call('DEL', lease_key(sid, upload_id))
end

local function touch(keys, now, idle_ms)
  local exp = now + idle_ms
  redis.call('HSET', keys[1], 'expires_at', exp)
  for i = 1, 6 do redis.call('PEXPIREAT', keys[i], exp + HOUR) end
  return exp
end

local function remove_member(keys, sid, uid, now, retention)
  local live = redis.call('HGETALL', keys[4])
  for i = 1, #live, 2 do
    local r = cjson.decode(live[i + 1])
    if r.uploader == uid then release_reservation(keys, sid, r.sha, live[i]) end
  end
  redis.call('HDEL', keys[2], uid)
  append_event(keys, now, retention, {'t', 'MEMBER_LEFT', 'm', uid})
end

local function transfer_host(keys, now, retention)
  local all = redis.call('HGETALL', keys[2])
  local best_uid, best = nil, nil
  for i = 1, #all, 2 do
    local m = cjson.decode(all[i + 1])
    if not best or m.joined_at < best.joined_at or (m.joined_at == best.joined_at and all[i] < best_uid) then
      best_uid, best = all[i], m
    end
  end
  if not best then return nil end
  best.role = 'host'
  put_json(keys[2], best_uid, best)
  redis.call('HSET', keys[1], 'host', best_uid)
  append_event(keys, now, retention, {'t', 'HOST_CHANGED', 'm', best_uid})
  return best_uid
end

-- Ends the session: ENDED, photos tombstoned, reservations released, SESSION_ENDED appended, 1-hour tombstone.
-- Returns {hashes whose files must be deleted, members, join_code, invite_token}.
local function do_end(keys, sid, now, retention)
  local info = redis.call('HMGET', keys[1], 'join_code', 'invite_token')
  redis.call('HSET', keys[1], 'status', 'ENDED', 'ended_at', now, 'stored_bytes', 0, 'reserved_bytes', 0)
  local to_delete = {}
  local photos = redis.call('HGETALL', keys[3])
  for i = 1, #photos, 2 do
    local sha, e = photos[i], cjson.decode(photos[i + 1])
    if e.status == 'UPLOADING' then
      redis.call('HDEL', keys[3], sha)
      redis.call('DEL', lease_key(sid, e.upload_id))
    else
      if e.status == 'STORED' then
        e.status = 'EXPIRED'
        e.expired_at = now
        put_json(keys[3], sha, e)
      end
      table.insert(to_delete, sha)
    end
  end
  redis.call('DEL', keys[4])
  append_event(keys, now, retention, {'t', 'SESSION_ENDED'})
  local members = redis.call('HKEYS', keys[2])
  for i = 1, 6 do redis.call('PEXPIRE', keys[i], HOUR) end
  return {to_delete, members, info[1] or '', info[2] or ''}
end

-- A cursor can be continued only if it is not older than the trimmed part, the stream still exists (or never
-- had events), and it is not ahead of the last event (a client that saw events Redis has since lost).
local function needs_resync(keys, cursor)
  if not parse_id(cursor) then return true end
  local s = redis.call('HMGET', keys[1], 'trimmed_before', 'last_event_id')
  local trimmed, last = s[1] or '0-0', s[2] or '0-0'
  if id_lt(cursor, trimmed) then return true end
  if redis.call('EXISTS', keys[5]) == 0 and last ~= '0-0' then return true end
  if id_lt(last, cursor) then return true end
  return false
end

local function member_check(keys, uid)
  if not is_active(keys) then return err('SESSION_ENDED') end
  if redis.call('HEXISTS', keys[2], uid) == 0 then return err('NOT_A_MEMBER') end
  return nil
end

----------------------------------------------------------------------------------------------- sessions

local function f_version() return VERSION end

-- ARGV: sid, now, retention, uid, idle_ms, max_members, join_code, invite_token
local function f_create_session(keys, args)
  local sid, now, retention, uid = args[1], tonumber(args[2]), tonumber(args[3]), args[4]
  if redis.call('EXISTS', keys[1]) == 1 then return err('CONFLICT') end
  redis.call('HSET', keys[1], 'host', uid, 'status', 'ACTIVE', 'created_at', now, 'max_members', args[6],
             'stored_bytes', 0, 'reserved_bytes', 0, 'join_code', args[7], 'invite_token', args[8],
             'last_event_id', '0-0', 'trimmed_before', '0-0')
  put_json(keys[2], uid, {role = 'host', joined_at = now, strikes = 0})
  append_event(keys, now, retention, {'t', 'MEMBER_JOINED', 'm', uid})
  return ok(touch(keys, now, tonumber(args[5])))
end

-- ARGV: sid, now, retention, uid, idle_ms
local function f_join(keys, args)
  local sid, now, retention, uid = args[1], tonumber(args[2]), tonumber(args[3]), args[4]
  local st = redis.call('HMGET', keys[1], 'status', 'max_members')
  if st[1] ~= 'ACTIVE' then return err('SESSION_ENDED') end
  if redis.call('HEXISTS', keys[2], uid) == 1 then return ok('ALREADY_MEMBER') end
  if redis.call('SISMEMBER', keys[6], uid) == 1 then return err('KICKED') end
  if redis.call('HLEN', keys[2]) >= tonumber(st[2]) then return err('SESSION_FULL') end
  put_json(keys[2], uid, {role = 'member', joined_at = now, strikes = 0})
  append_event(keys, now, retention, {'t', 'MEMBER_JOINED', 'm', uid})
  touch(keys, now, tonumber(args[5]))
  return ok('JOINED')
end

-- ARGV: sid, now, retention, uid  ->  {status, host, expires_at, join_code, invite_token, role, members}
local function f_session_info(keys, args)
  local uid = args[4]
  local s = redis.call('HMGET', keys[1], 'status', 'host', 'expires_at', 'join_code', 'invite_token')
  if not s[1] then return err('SESSION_NOT_FOUND') end
  if s[1] ~= 'ACTIVE' then return err('SESSION_ENDED') end
  local m = get_json(keys[2], uid)
  if not m then return err('NOT_A_MEMBER') end
  return ok(s[1], s[2], s[3], s[4], s[5], m.role, redis.call('HKEYS', keys[2]))
end

-- ARGV: sid, now, retention, uid
local function f_leave(keys, args)
  local sid, now, retention, uid = args[1], tonumber(args[2]), tonumber(args[3]), args[4]
  if not is_active(keys) then return ok('NOOP') end
  local m = get_json(keys[2], uid)
  if not m then return ok('NOOP') end
  remove_member(keys, sid, uid, now, retention)
  if redis.call('HLEN', keys[2]) == 0 then
    local r = do_end(keys, sid, now, retention)
    return ok('ENDED', r[1], r[2], r[3], r[4])
  end
  if m.role == 'host' then transfer_host(keys, now, retention) end
  return ok('LEFT')
end

-- ARGV: sid, now, retention, caller, target
local function f_kick(keys, args)
  local sid, now, retention, caller, target = args[1], tonumber(args[2]), tonumber(args[3]), args[4], args[5]
  if not is_active(keys) then return err('SESSION_ENDED') end
  local c = get_json(keys[2], caller)
  if not c then return err('NOT_A_MEMBER') end
  if c.role ~= 'host' then return err('NOT_HOST') end
  if target == caller then return err('INVALID_REQUEST') end
  redis.call('SADD', keys[6], target)
  if redis.call('HEXISTS', keys[2], target) == 0 then return ok('NOOP') end
  remove_member(keys, sid, target, now, retention)
  return ok('KICKED')
end

-- ARGV: sid, now, retention, caller, mode ('host' or 'idle')
local function f_end_session(keys, args)
  local sid, now, retention, caller, mode = args[1], tonumber(args[2]), tonumber(args[3]), args[4], args[5]
  local st = redis.call('HMGET', keys[1], 'status', 'expires_at')
  if st[1] ~= 'ACTIVE' then return ok('NOOP') end
  if mode == 'idle' then
    if tonumber(st[2]) > now then return ok('NOT_DUE', st[2]) end
  else
    local c = get_json(keys[2], caller)
    if not c then return err('NOT_A_MEMBER') end
    if c.role ~= 'host' then return err('NOT_HOST') end
  end
  local r = do_end(keys, sid, now, retention)
  return ok('ENDED', r[1], r[2], r[3], r[4])
end

-- ARGV: sid, now, retention, idle_ms
local function f_touch_session(keys, args)
  if not is_active(keys) then return err('SESSION_ENDED') end
  return ok(touch(keys, tonumber(args[2]), tonumber(args[4])))
end

-- ARGV: sid, now, retention, caller, new_code, new_token  ->  {old_code, old_token}
local function f_set_invites(keys, args)
  if not is_active(keys) then return err('SESSION_ENDED') end
  local c = get_json(keys[2], args[4])
  if not c then return err('NOT_A_MEMBER') end
  if c.role ~= 'host' then return err('NOT_HOST') end
  local old = redis.call('HMGET', keys[1], 'join_code', 'invite_token')
  redis.call('HSET', keys[1], 'join_code', args[5], 'invite_token', args[6])
  return ok(old[1] or '', old[2] or '')
end

------------------------------------------------------------------------------------------------- photos

-- ARGV: sid, now, retention, uid, sha, size, mime, taken_at, new_upload_id, cap_bytes, max_live_user,
--       max_live_session, max_strikes, lease_ms, takeover_ms
local function f_announce(keys, args)
  local sid, now = args[1], tonumber(args[2])
  local uid, sha, size, mime, taken_at, new_id =
        args[4], args[5], tonumber(args[6]), args[7], tonumber(args[8]), args[9]
  local cap, max_user, max_session, max_strikes =
        tonumber(args[10]), tonumber(args[11]), tonumber(args[12]), tonumber(args[13])
  local lease_ms, takeover_ms = tonumber(args[14]), tonumber(args[15])

  if not is_active(keys) then return err('SESSION_ENDED') end
  local m = get_json(keys[2], uid)
  if not m then return err('NOT_A_MEMBER') end
  if (m.strikes or 0) >= max_strikes then return err('UPLOADS_BLOCKED') end

  local takeover = 0
  local e = get_json(keys[3], sha)
  if e then
    if e.status == 'STORED' or e.status == 'EXPIRED' then return ok('ALREADY_UPLOADED') end
    if e.status == 'RECALLED' then return err('PHOTO_RECALLED') end
    if redis.call('EXISTS', lease_key(sid, e.upload_id)) == 1 then
      if e.uploader == uid then return ok('UPLOAD', e.upload_id, 0) end   -- same id, fresh token
      return ok('IN_PROGRESS')
    end
    if now - e.reserved_at < takeover_ms then return ok('IN_PROGRESS') end
    release_reservation(keys, sid, sha, e.upload_id)                      -- stale: take over
    takeover = 1
  end

  -- Live-upload limits. Reservations whose lease is gone are stale and released here.
  local live, n_user, n_session = redis.call('HGETALL', keys[4]), 0, 0
  for i = 1, #live, 2 do
    local r = cjson.decode(live[i + 1])
    if redis.call('EXISTS', lease_key(sid, live[i])) == 0 and now - r.reserved_at >= takeover_ms then
      release_reservation(keys, sid, r.sha, live[i])
    else
      n_session = n_session + 1
      if r.uploader == uid then n_user = n_user + 1 end
    end
  end
  if n_user >= max_user or n_session >= max_session then return err('TOO_MANY_UPLOADS') end

  local b = redis.call('HMGET', keys[1], 'stored_bytes', 'reserved_bytes')
  if tonumber(b[1]) + tonumber(b[2]) + size > cap then return err('QUOTA_EXCEEDED') end

  if redis.call('HSETNX', keys[3], sha, cjson.encode({status = 'UPLOADING', uploader = uid, upload_id = new_id,
      size = size, mime = mime, taken_at = taken_at, reserved_at = now})) == 0 then
    return ok('IN_PROGRESS')
  end
  put_json(keys[4], new_id, {sha = sha, uploader = uid, size = size, reserved_at = now})
  redis.call('HINCRBY', keys[1], 'reserved_bytes', size)
  local lk = lease_key(sid, new_id)
  redis.call('HSET', lk, 'uploader', uid, 'sha256', sha, 'size', size, 'state', 'RECEIVING', 'created_at', now)
  redis.call('PEXPIRE', lk, lease_ms)
  return ok('UPLOAD', new_id, takeover)
end

-- ARGV: sid, now, retention, sha, upload_id, stored_at, expires_at
local function f_report_stored(keys, args)
  local sid, now, retention = args[1], tonumber(args[2]), tonumber(args[3])
  local sha, upload_id, stored_at, expires_at = args[4], args[5], tonumber(args[6]), tonumber(args[7])
  if not is_active(keys) then return err('SESSION_ENDED') end
  local e = get_json(keys[3], sha)
  if not e or e.upload_id ~= upload_id then return err('UPLOAD_SUPERSEDED') end   -- fencing
  if e.status ~= 'UPLOADING' then return ok('NOOP', '', e.reserved_at) end      -- duplicate report
  if redis.call('HEXISTS', keys[2], e.uploader) == 0 then
    release_reservation(keys, sid, sha, upload_id)
    return err('UPLOAD_SUPERSEDED')
  end
  e.status, e.stored_at, e.expires_at = 'STORED', stored_at, expires_at
  put_json(keys[3], sha, e)
  redis.call('HINCRBY', keys[1], 'reserved_bytes', -e.size)
  redis.call('HINCRBY', keys[1], 'stored_bytes', e.size)
  redis.call('HDEL', keys[4], upload_id)
  redis.call('DEL', lease_key(sid, upload_id))
  local id = append_event(keys, now, retention, {'t', 'PHOTO_STORED', 'sha', sha, 'up', e.uploader,
                          'sz', e.size, 'ta', e.taken_at, 'ex', expires_at})
  return ok('APPLIED', id, e.reserved_at)
end

-- ARGV: sid, now, retention, sha, upload_id, reason
local function f_report_rejected(keys, args)
  local sid, sha, upload_id = args[1], args[4], args[5]
  if not is_active(keys) then return err('SESSION_ENDED') end
  local e = get_json(keys[3], sha)
  if not e or e.upload_id ~= upload_id or e.status ~= 'UPLOADING' then return err('UPLOAD_SUPERSEDED') end
  release_reservation(keys, sid, sha, upload_id)
  local m = get_json(keys[2], e.uploader)
  if m then
    m.strikes = (m.strikes or 0) + 1
    put_json(keys[2], e.uploader, m)
  end
  return ok('APPLIED')
end

-- ARGV: sid, now, retention, caller, sha
local function f_recall(keys, args)
  local sid, now, retention, caller, sha = args[1], tonumber(args[2]), tonumber(args[3]), args[4], args[5]
  if not is_active(keys) then return err('SESSION_ENDED') end
  local c = get_json(keys[2], caller)
  if not c then return err('NOT_A_MEMBER') end
  local e = get_json(keys[3], sha)
  if not e or e.status == 'UPLOADING' then return err('PHOTO_NOT_FOUND') end
  if e.status == 'RECALLED' then return ok('NOOP') end
  if e.status == 'EXPIRED' or tonumber(e.expires_at) <= now then return err('PHOTO_EXPIRED') end
  if e.uploader ~= caller and c.role ~= 'host' then return err('FORBIDDEN') end
  e.status, e.recalled_at = 'RECALLED', now
  put_json(keys[3], sha, e)
  redis.call('HINCRBY', keys[1], 'stored_bytes', -e.size)
  local id = append_event(keys, now, retention, {'t', 'PHOTO_RECALLED', 'sha', sha, 'up', e.uploader})
  return ok('RECALLED', id)
end

-- ARGV: sid, now, retention, sha
local function f_expire_photo(keys, args)
  local now, sha = tonumber(args[2]), args[4]
  local e = get_json(keys[3], sha)
  if not e or e.status ~= 'STORED' then return ok('GONE') end
  if tonumber(e.expires_at) > now then return ok('NOT_DUE', e.expires_at) end
  e.status, e.expired_at = 'EXPIRED', now
  put_json(keys[3], sha, e)
  redis.call('HINCRBY', keys[1], 'stored_bytes', -e.size)
  return ok('EXPIRED')
end

-- ARGV: sid, now, retention, takeover_ms  ->  {stored_drift, reserved_drift, released, expired_hashes}
local function f_recount(keys, args)
  local sid, now, takeover_ms = args[1], tonumber(args[2]), tonumber(args[4])
  if not is_active(keys) then return ok(0, 0, 0, {}) end
  local released = 0
  local live = redis.call('HGETALL', keys[4])
  for i = 1, #live, 2 do
    local r = cjson.decode(live[i + 1])
    if redis.call('EXISTS', lease_key(sid, live[i])) == 0 and now - r.reserved_at >= takeover_ms then
      release_reservation(keys, sid, r.sha, live[i])
      released = released + 1
    end
  end
  local stored, reserved, expired = 0, 0, {}
  local photos = redis.call('HGETALL', keys[3])
  for i = 1, #photos, 2 do
    local sha, e = photos[i], cjson.decode(photos[i + 1])
    if e.status == 'UPLOADING' then
      if redis.call('EXISTS', lease_key(sid, e.upload_id)) == 0 and now - e.reserved_at >= takeover_ms then
        redis.call('HDEL', keys[3], sha)
        redis.call('HDEL', keys[4], e.upload_id)
        redis.call('HINCRBY', keys[1], 'reserved_bytes', -e.size)
        released = released + 1
      else
        reserved = reserved + e.size
      end
    elseif e.status == 'STORED' then
      if tonumber(e.expires_at) <= now then
        e.status, e.expired_at = 'EXPIRED', now
        put_json(keys[3], sha, e)
        redis.call('HINCRBY', keys[1], 'stored_bytes', -e.size)
        table.insert(expired, sha)
      else
        stored = stored + e.size
      end
    end
  end
  local cur = redis.call('HMGET', keys[1], 'stored_bytes', 'reserved_bytes')
  redis.call('HSET', keys[1], 'stored_bytes', stored, 'reserved_bytes', reserved)
  return ok(stored - tonumber(cur[1]), reserved - tonumber(cur[2]), released, expired)
end

------------------------------------------------------------------------------------- image server access

-- KEYS[1] the lease upload:{sid}:<upload_id> (the only key it writes, so the image server's ACL can allow it);
-- ARGV: sid, now, retention, upload_id, lease_ms, state, sha  ->  {1} or {0, 'SUPERSEDED'|'EXPIRED'}
local function f_touch_lease(keys, args)
  local sid, upload_id = args[1], args[4]
  local lk = keys[1]
  if redis.call('EXISTS', lk) == 1 then
    redis.call('PEXPIRE', lk, tonumber(args[5]))
    redis.call('HSET', lk, 'state', args[6])
    return ok(1)
  end
  local e = get_json('session:{' .. sid .. '}:photos', args[7])  -- same slot; read only
  if e and e.upload_id ~= upload_id then return ok(0, 'SUPERSEDED') end
  return ok(0, 'EXPIRED')
end

-- ARGV: sid, now, retention, uid, upload_id, sha  ->  {status, is_member, lease_alive, superseded}
local function f_upload_check(keys, args)
  local sid, uid, upload_id, sha = args[1], args[4], args[5], args[6]
  local status = redis.call('HGET', keys[1], 'status') or ''
  local member = redis.call('HEXISTS', keys[2], uid)
  local alive = redis.call('EXISTS', lease_key(sid, upload_id))
  local superseded = 0
  if alive == 0 then
    local e = get_json(keys[3], sha)
    if e and e.upload_id ~= upload_id then superseded = 1 end
  end
  return ok(status, member, alive, superseded)
end

-- ARGV: sid, now, retention, uid, sha  ->  {status, is_member, photo_status, upload_id, mime, expires_at}
local function f_download_check(keys, args)
  local uid, sha = args[4], args[5]
  local status = redis.call('HGET', keys[1], 'status') or ''
  local member = redis.call('HEXISTS', keys[2], uid)
  local e = get_json(keys[3], sha)
  if not e then return ok(status, member, '', '', '', 0) end
  return ok(status, member, e.status, e.upload_id or '', e.mime or '', e.expires_at or 0)
end

------------------------------------------------------------------------------------------------- events

-- ARGV: sid, now, retention, uid  ->  {cursor, members, live {sha, uploader, size, taken_at, expires_at}, recalled}
local function f_snapshot(keys, args)
  local now, uid = tonumber(args[2]), args[4]
  local bad = member_check(keys, uid)
  if bad then return bad end
  local cursor = redis.call('HGET', keys[1], 'last_event_id') or '0-0'
  local live, recalled = {}, {}
  local photos = redis.call('HGETALL', keys[3])
  for i = 1, #photos, 2 do
    local sha, e = photos[i], cjson.decode(photos[i + 1])
    if e.status == 'STORED' and tonumber(e.expires_at) > now then
      table.insert(live, {sha, e.uploader, e.size, e.taken_at, e.expires_at})
    elseif e.status == 'RECALLED' then
      table.insert(recalled, sha)
    end
  end
  return ok(cursor, redis.call('HKEYS', keys[2]), live, recalled)
end

-- ARGV: sid, now, retention, uid, since, limit  ->  {entries}
local function f_events_since(keys, args)
  local uid, since, limit = args[4], args[5], args[6]
  local bad = member_check(keys, uid)
  if bad then return bad end
  if needs_resync(keys, since) then return err('RESYNC_REQUIRED') end
  return ok(redis.call('XRANGE', keys[5], '(' .. since, '+', 'COUNT', limit))
end

-- ARGV: sid, now, retention, uid, cursor
local function f_ws_check(keys, args)
  local bad = member_check(keys, args[4])
  if bad then return bad end
  if needs_resync(keys, args[5]) then return err('RESYNC_REQUIRED') end
  return ok()
end

----------------------------------------------------------------------------------- one-session pointer

-- KEYS[1] user:{uid}:session; ARGV: old, new, ttl_ms
local function f_ptr_cas(keys, args)
  if redis.call('GET', keys[1]) ~= args[1] then return 0 end
  redis.call('SET', keys[1], args[2], 'PX', tonumber(args[3]))
  return 1
end

-- KEYS[1] user:{uid}:session; ARGV: sid
local function f_ptr_del(keys, args)
  if redis.call('GET', keys[1]) ~= args[1] then return 0 end
  redis.call('DEL', keys[1])
  return 1
end

-- KEYS[1] user:{uid}:session; ARGV: sid, ttl_ms
local function f_ptr_touch(keys, args)
  if redis.call('GET', keys[1]) ~= args[1] then return 0 end
  redis.call('PEXPIRE', keys[1], tonumber(args[2]))
  return 1
end

local RO = {'no-writes'}
redis.register_function{function_name = 'version', callback = f_version, flags = RO}
redis.register_function('create_session', f_create_session)
redis.register_function('join', f_join)
redis.register_function{function_name = 'session_info', callback = f_session_info, flags = RO}
redis.register_function('leave', f_leave)
redis.register_function('kick', f_kick)
redis.register_function('end_session', f_end_session)
redis.register_function('touch_session', f_touch_session)
redis.register_function('set_invites', f_set_invites)
redis.register_function('announce', f_announce)
redis.register_function('report_stored', f_report_stored)
redis.register_function('report_rejected', f_report_rejected)
redis.register_function('recall', f_recall)
redis.register_function('expire_photo', f_expire_photo)
redis.register_function('recount', f_recount)
redis.register_function('touch_lease', f_touch_lease)
redis.register_function{function_name = 'upload_check', callback = f_upload_check, flags = RO}
redis.register_function{function_name = 'download_check', callback = f_download_check, flags = RO}
redis.register_function{function_name = 'snapshot', callback = f_snapshot, flags = RO}
redis.register_function{function_name = 'events_since', callback = f_events_since, flags = RO}
redis.register_function{function_name = 'ws_check', callback = f_ws_check, flags = RO}
redis.register_function('ptr_cas', f_ptr_cas)
redis.register_function('ptr_del', f_ptr_del)
redis.register_function('ptr_touch', f_ptr_touch)
