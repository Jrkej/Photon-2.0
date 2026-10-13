"""Identifiers: session ids, upload ids, join codes, invite tokens."""

from __future__ import annotations

import base64
import secrets
import uuid

CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"


def new_sid() -> str:
    # 22 url-safe characters; never contains "{" or "}", so it is safe inside a Redis hash tag
    return base64.urlsafe_b64encode(secrets.token_bytes(16)).rstrip(b"=").decode()


def new_upload_id() -> str:
    return uuid.uuid4().hex


def new_join_code() -> str:
    return "".join(secrets.choice(CROCKFORD) for _ in range(8))


def normalize_code(code: str) -> str:
    """Accepts what people type: lower case, dashes, spaces, and the letters Crockford maps to digits."""
    cleaned = code.strip().upper().replace("-", "").replace(" ", "")
    return cleaned.translate(str.maketrans("ILO", "110"))


def new_invite_token() -> str:
    return secrets.token_urlsafe(16)


def is_valid_sid(value: str) -> bool:
    return 16 <= len(value) <= 64 and all(c.isalnum() or c in "-_" for c in value)
