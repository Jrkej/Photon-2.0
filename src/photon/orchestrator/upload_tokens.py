"""Upload tokens: the orchestrator's short-lived ticket for one upload_id (aud=image-server)."""

from __future__ import annotations

from photon.common import clock, jwtutil


def mint(
    key: jwtutil.SigningKey,
    ttl_s: int,
    *,
    sid: str,
    sha: str,
    size: int,
    mime: str,
    taken_at_ms: int,
    uploader: str,
    upload_id: str,
    key_epoch: int,
) -> str:
    now_s = clock.now_ms() // 1000
    return jwtutil.sign(
        {
            "iss": "photon-orchestrator",
            "aud": "image-server",
            "iat": now_s,
            "exp": now_s + ttl_s,
            "sid": sid,
            "sha256": sha,
            "size": size,
            "mime": mime,
            "taken_at_ms": taken_at_ms,
            "uploader": uploader,
            "upload_id": upload_id,
            "key_epoch": key_epoch,  # the image server checks the file header names this epoch
        },
        key,
    )
