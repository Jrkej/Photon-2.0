"""deploy/users.acl.example on a real Redis: each service can do its job and nothing else (0.2, Security/Transport)."""

from __future__ import annotations

import shutil
import socket
import subprocess
import time
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Any

import pytest
import redis

from photon.common import e2ee
from photon.common.redisutil import library_code
from photon.orchestrator import keys as K
from tests.conftest import REDIS_SERVER, ROOT, free_port

SID = "abcdefghijklmnopqrstuv"
UP = "f" * 32
SHA = "a" * 64
TAG = "b" * 64
PK = "c" * 64
BOX = "d" * (2 * e2ee.BOX_LEN)


@pytest.fixture(scope="module")
def acl_port(tmp_path_factory: pytest.TempPathFactory) -> Iterator[int]:
    d: Path = tmp_path_factory.mktemp("acl")
    shutil.copy(ROOT / "deploy" / "users.acl.example", d / "users.acl")
    port = free_port()
    proc = subprocess.Popen([str(REDIS_SERVER), "--port", str(port), "--aclfile", str(d / "users.acl"),
                             "--dir", str(d), "--save", ""], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    for _ in range(100):
        with socket.socket() as s:
            if s.connect_ex(("127.0.0.1", port)) == 0:
                break
        time.sleep(0.05)
    assert proc.poll() is None, "redis refused the ACL file"
    yield port
    proc.terminate()
    proc.wait(5)


def as_user(port: int, user: str) -> redis.Redis:
    return redis.Redis(port=port, username=user, password=f"change-me-{user}", decode_responses=True)


def refused(f: Callable[[], Any]) -> bool:
    try:
        f()
    except redis.exceptions.RedisError:  # NoPermission, or authentication for a disabled user
        return True
    return False


def test_service_permissions(acl_port: int) -> None:
    now = int(time.time() * 1000)
    keys = K.session_keys(SID)
    n = len(keys)
    assert n == 8
    orch, img, auth = as_user(acl_port, "orchestrator"), as_user(acl_port, "imageserver"), as_user(acl_port, "auth")
    orch.function_load(library_code(), replace=True)
    assert orch.fcall("create_session", n, *keys, SID, now, 1_800_000, "u1", 3_600_000, 10, "C", "T", PK)[0] == "OK"
    assert orch.fcall("join", n, *keys, SID, now, 1_800_000, "u2", 3_600_000, PK)[1] == "JOINED"
    # The orchestrator relays sealed keys (e2ee.md 5)
    assert orch.fcall("put_keys", n, *keys, SID, now, 1_800_000, "u1", 1, "0", 1, "u2", BOX) == ["OK", 1, 1]
    assert orch.fcall_ro("key_state", n, *keys, SID, now, 1_800_000, "u2")[4] == [[1, "u1", BOX]]
    assert orch.fcall("announce", n, *keys, SID, now, 1_800_000, "u1", SHA, 100, e2ee.MIME, now, UP, 10**9,
                      3, 20, 3, 900_000, 60_000, TAG, 1)[1] == "UPLOAD"
    # The image server: read session state, refresh leases, nothing else
    assert img.fcall("touch_lease", 1, K.lease(SID, UP), SID, now, 1_800_000, UP, 900_000, "RECEIVING", SHA) == \
        ["OK", 1]
    assert img.fcall_ro("upload_check", n, *keys, SID, now, 1_800_000, "u1", UP, SHA)[1] == "ACTIVE"
    assert img.fcall_ro("download_check", n, *keys, SID, now, 1_800_000, "u1", SHA)[3] == "UPLOADING"
    assert img.hget(keys[0], "status") == "ACTIVE" and img.exists(K.lease(SID, UP))
    assert refused(lambda: img.hset(keys[0], "status", "ENDED"))
    assert refused(lambda: img.fcall("report_stored", n, *keys, SID, now, 1_800_000, SHA, UP, now, now + 1))
    # nor can it plant sealed keys
    assert refused(lambda: img.fcall("put_keys", n, *keys, SID, now, 1_800_000, "u1", 1, "0", 1, "u2", BOX))
    assert refused(lambda: img.fcall("touch_lease", 1, keys[0], SID, now, 1, UP, 1, "X", SHA))
    assert refused(lambda: img.delete(K.lease(SID, UP)))
    assert refused(img.flushall)
    # Auth: its own keys only
    assert auth.set("oauth:s", "1", ex=10) and auth.getdel("oauth:s") == "1"
    assert auth.register_script("return redis.call('HGET', KEYS[1], 'uid')")(keys=["rfam:{x}"]) is None
    assert refused(lambda: auth.get(keys[0]))
    # The orchestrator: everything for its job, nothing destructive
    assert refused(orch.flushall) and refused(lambda: orch.keys("*")) and refused(orch.function_flush)
    assert refused(lambda: as_user(acl_port, "default").ping())
