"""Every Redis key name. `{sid}` is a literal hash tag, so a session's keys share one slot."""

from __future__ import annotations

import zlib

SESSIONS_DUE = "sessions:due"


def session_keys(sid: str) -> list[str]:
    t = f"{{{sid}}}"
    return [
        f"session:{t}",
        f"session:{t}:members",
        f"session:{t}:photos",
        f"session:{t}:live",
        f"session:{t}:events",
        f"session:{t}:kicked",
    ]


def session(sid: str) -> str:
    return f"session:{{{sid}}}"


def events(sid: str) -> str:
    return f"session:{{{sid}}}:events"


def members(sid: str) -> str:
    return f"session:{{{sid}}}:members"


def photos(sid: str) -> str:
    return f"session:{{{sid}}}:photos"


def online(sid: str) -> str:
    return f"session:{{{sid}}}:online"


def lease(sid: str, upload_id: str) -> str:
    return f"upload:{{{sid}}}:{upload_id}"


def user_session(uid: str) -> str:
    return f"user:{{{uid}}}:session"


def code(value: str) -> str:
    return f"code:{value}"


def invite(token: str) -> str:
    return f"invite:{token}"


def shard(sid: str, shards: int) -> int:
    return zlib.crc32(sid.encode()) % shards


def expiry(n: int) -> str:
    return f"expiry:{n}"


def expiry_attempts(n: int) -> str:
    return f"expiry_attempts:{n}"


def lock(name: str) -> str:
    return f"lock:{name}"


def push(uid: str) -> str:
    return f"push:{uid}"


def rl(scope: str, ident: str) -> str:
    return f"rl:{scope}:{ident}"
