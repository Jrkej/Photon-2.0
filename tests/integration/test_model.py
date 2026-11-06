"""4.9 model-based test: random sequences of every upload transition against the real Lua functions, with the
quota, fencing and event invariants checked after every step (findings 1, 2, 7, 25). Since end-to-end encryption
(e2ee.md), every announce carries a photo tag and the key epoch, and a kick makes key rotation due."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator
from typing import Any

import pytest
import redis as redis_sync
from hypothesis import HealthCheck, settings
from hypothesis import strategies as st
from hypothesis.stateful import Bundle, RuleBasedStateMachine, invariant, rule

from photon.common import e2ee, ids
from photon.common.redisutil import library_code
from photon.orchestrator import keys as K

RETENTION = 30 * 60 * 1000
CAP = 5_000
MEMBERS = ["host", "m1", "m2"]
HASHES = [f"{i:064x}" for i in range(1, 6)]
SIZES = [700, 1300, 2100]
BOX = "ab" * e2ee.BOX_LEN  # the Lua functions store boxes as given; the route checks their size


def member_key(uid: str) -> str:
    return hashlib.sha256(b"member key " + uid.encode()).hexdigest()


def tag(sha: str) -> str:
    """One tag per hash: each modelled file is a distinct photo, so tag dedupe agrees with hash dedupe."""
    return hashlib.sha256(b"tag" + bytes.fromhex(sha)).hexdigest()

_redis: list[redis_sync.Redis] = []


@pytest.fixture(scope="module", autouse=True)
def sync_redis(redis_url: str) -> Iterator[None]:
    r = redis_sync.Redis.from_url(redis_url, decode_responses=True, db=1)
    r.function_load(library_code(), replace=True)
    _redis[:] = [r]
    yield
    r.flushdb()
    r.close()


class UploadMachine(RuleBasedStateMachine):
    uploads = Bundle("uploads")

    def __init__(self) -> None:
        super().__init__()
        self.r = _redis[0]
        self.sid = ids.new_sid()
        self.keys = K.session_keys(self.sid)
        self.now = 1_800_000_000_000
        self.ended = False
        self.ever_stored: set[str] = set()
        self.members = set(MEMBERS)
        self.epoch, self.rotation_due = 1, False
        self.call("create_session", "host", 12 * 3600 * 1000, 10, "CODE", "TOKEN", member_key("host"))
        for m in MEMBERS[1:]:
            self.call("join", m, 12 * 3600 * 1000, member_key(m))
        pairs = [x for m in MEMBERS[1:] for x in (m, BOX)]
        assert self.call("put_keys", "host", 1, "0", len(MEMBERS) - 1, *pairs) == ["OK", 1, 2]

    def call(self, name: str, *args: Any) -> list[Any]:
        return list(self.r.fcall(name, len(self.keys), *self.keys, self.sid, self.now, RETENTION, *args))

    def tick(self, ms: int = 10) -> None:
        self.now += ms

    @rule(target=uploads, member=st.sampled_from(MEMBERS), sha=st.sampled_from(HASHES), size=st.sampled_from(SIZES))
    def announce(self, member: str, sha: str, size: int) -> str:
        self.tick()
        res = self.call("announce", member, sha, size, e2ee.MIME, self.now, ids.new_upload_id(), CAP, 3, 20, 3,
                        900_000, 60_000, tag(sha), self.epoch)
        if self.ended:
            assert res == ["ERR", "SESSION_ENDED"]
        elif member not in self.members:
            assert res == ["ERR", "NOT_A_MEMBER"]
        elif self.rotation_due:
            assert res == ["ERR", "KEY_ROTATION_REQUIRED"]
        if res[0] == "OK" and res[1] == "UPLOAD":
            return f"{sha}:{res[2]}"
        return f"{sha}:none"

    @rule(u=uploads)
    def stored(self, u: str) -> None:
        sha, upload_id = u.split(":")
        if upload_id == "none":
            return
        self.tick()
        before = self.entry(sha)
        res = self.call("report_stored", sha, upload_id, self.now, self.now + RETENTION)
        if res[0] == "OK" and res[1] == "APPLIED":
            assert before and before["upload_id"] == upload_id and before["status"] == "UPLOADING"
            self.ever_stored.add(sha)
        elif res[0] == "ERR":
            assert res[1] in ("UPLOAD_SUPERSEDED", "SESSION_ENDED")
            if before and before["upload_id"] != upload_id:
                assert self.entry(sha) == before  # a fenced-out report changes nothing

    @rule(u=uploads)
    def rejected(self, u: str) -> None:
        sha, upload_id = u.split(":")
        if upload_id == "none":
            return
        self.tick()
        before = self.entry(sha)
        res = self.call("report_rejected", sha, upload_id, "HASH_MISMATCH")
        if res[0] == "ERR" and before and before["upload_id"] != upload_id:
            assert self.entry(sha) == before

    @rule(u=uploads)
    def lease_expires(self, u: str) -> None:
        _sha, upload_id = u.split(":")
        if upload_id != "none":
            self.r.delete(K.lease(self.sid, upload_id))
            self.tick(61_000)

    @rule(member=st.sampled_from(MEMBERS[1:]))
    def kick(self, member: str) -> None:
        self.tick()
        res = self.call("kick", "host", member)
        if res == ["OK", "KICKED"]:
            self.members.discard(member)
            self.rotation_due = True

    @rule()
    def rotate(self) -> None:
        """The host starts the next epoch, sealed for every remaining member (e2ee.md 5.4)."""
        self.tick()
        others = sorted(self.members - {"host"})
        res = self.call("put_keys", "host", self.epoch + 1, "1", len(others), *[x for m in others for x in (m, BOX)])
        if self.ended:
            assert res == ["ERR", "SESSION_ENDED"]
            return
        assert res == ["OK", self.epoch + 1, len(others)]
        self.epoch, self.rotation_due = self.epoch + 1, False
        stale = self.call("put_keys", "host", self.epoch, "1", len(others), *[x for m in others for x in (m, BOX)])
        assert stale == ["ERR", "EPOCH_TAKEN"]  # a second rotation from the same epoch loses

    @rule(sha=st.sampled_from(HASHES))
    def recall(self, sha: str) -> None:
        self.tick()
        self.call("recall", "host", sha)

    @rule(sha=st.sampled_from(HASHES))
    def expire(self, sha: str) -> None:
        e = self.entry(sha)
        if e and e["status"] == "STORED":
            self.now = max(self.now, int(e["expires_at"]))
        self.call("expire_photo", sha)

    @rule()
    def recount(self) -> None:
        res = self.call("recount", 60_000)
        assert res[0] == "OK"
        assert int(res[1]) == 0 and int(res[2]) == 0, f"counter drift {res}"

    @rule()
    def end(self) -> None:
        self.tick()
        self.call("end_session", "host", "host")
        self.ended = True

    def entry(self, sha: str) -> dict[str, Any] | None:
        raw = self.r.hget(self.keys[2], sha)
        return json.loads(raw) if raw else None

    def photos(self) -> dict[str, dict[str, Any]]:
        return {k: json.loads(v) for k, v in self.r.hgetall(self.keys[2]).items()}

    @invariant()
    def counters_match_entries(self) -> None:
        photos = self.photos()
        stored, reserved = (int(x or 0) for x in self.r.hmget(self.keys[0], "stored_bytes", "reserved_bytes"))
        assert reserved == sum(e["size"] for e in photos.values() if e["status"] == "UPLOADING")
        assert stored == sum(e["size"] for e in photos.values() if e["status"] == "STORED")
        assert stored + reserved <= CAP

    @invariant()
    def one_event_per_stored_hash(self) -> None:
        events = self.r.xrange(self.keys[4])
        per_sha: dict[str, int] = {}
        for _, f in events:
            if f.get("t") == "PHOTO_STORED":
                per_sha[f["sha"]] = per_sha.get(f["sha"], 0) + 1
        assert all(n == 1 for n in per_sha.values())
        assert set(per_sha) == self.ever_stored

    @invariant()
    def tags_point_at_their_photos(self) -> None:
        photos = self.photos()
        for t, sha in self.r.hgetall(self.keys[6]).items():
            assert t == tag(str(sha))
            assert sha in photos or self.ended  # ending drops unfinished entries; their tags die with the session

    @invariant()
    def leases_and_live_entries_are_consistent(self) -> None:
        photos = self.photos()
        live = {k: json.loads(v) for k, v in self.r.hgetall(self.keys[3]).items()}
        uploading = {e["upload_id"]: sha for sha, e in photos.items() if e["status"] == "UPLOADING"}
        assert set(live) == set(uploading)
        lease_shas = [self.r.hget(k, "sha256") for k in self.r.scan_iter(f"upload:{{{self.sid}}}:*")]
        assert len(lease_shas) == len(set(lease_shas))  # never two live leases for one hash
        if self.ended:
            assert not uploading and not lease_shas


TestUploadMachine = UploadMachine.TestCase
TestUploadMachine.settings = settings(max_examples=60, stateful_step_count=40, deadline=None,
                                      suppress_health_check=[HealthCheck.too_slow])
