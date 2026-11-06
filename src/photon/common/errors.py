"""The design's error contract, in one table. Every error a service returns goes through ApiError."""

from __future__ import annotations

from photon.common.pb import photon_pb2 as pb

# code -> (HTTP status, retryable)
CONTRACT: dict[str, tuple[int, bool]] = {
    "INVALID_REQUEST": (400, False),
    "TOKEN_EXPIRED": (401, True),
    "UNAUTHENTICATED": (401, False),
    "NOT_A_MEMBER": (403, False),
    "FORBIDDEN": (403, False),
    "UNKNOWN_UPLOAD": (404, False),
    "PHOTO_NOT_FOUND": (404, False),
    "SESSION_NOT_FOUND": (404, False),
    "ALREADY_IN_SESSION": (409, False),
    "UPLOAD_SUPERSEDED": (409, False),
    "UPLOAD_FINALIZED": (409, False),
    "KEY_ROTATION_REQUIRED": (409, False),  # e2ee.md 5.4: a member left; start a new key epoch, then re-announce
    "KEY_EPOCH_STALE": (409, False),  # the file was encrypted for an old epoch; fetch keys and rebuild it
    "EPOCH_TAKEN": (409, False),  # another member started this epoch first; fetch its key instead
    "KEY_REQUIRED": (403, False),  # no key for the current epoch yet; wait for KEY_SHARED
    "SESSION_ENDED": (410, False),
    "PHOTO_EXPIRED": (410, False),
    "PHOTO_RECALLED": (410, False),
    "UPLOAD_EXPIRED": (410, False),
    "RESYNC_REQUIRED": (410, False),
    "TOO_LARGE": (413, False),
    "UNSUPPORTED_FORMAT": (415, False),
    "HASH_MISMATCH": (422, False),
    "INVALID_IMAGE": (422, False),
    "RATE_LIMITED": (429, True),
    "TOO_MANY_UPLOADS": (429, True),
    "QUOTA_EXCEEDED": (429, True),
    "BUSY": (503, True),
    "PROCESSING_FAILED": (503, True),
}

# Codes a Lua function may return that are not in the contract: code -> (contract code, message)
FUNCTION_ERRORS: dict[str, tuple[str, str]] = {
    "SESSION_FULL": ("FORBIDDEN", "Session is full"),
    "UPLOADS_BLOCKED": ("FORBIDDEN", "Uploads blocked in this session"),
    "NOT_HOST": ("FORBIDDEN", "Only the host can do this"),
    "KICKED": ("FORBIDDEN", "You were removed from this session"),
    "CONFLICT": ("BUSY", "Conflict, try again"),
    "KEYS_INCOMPLETE": ("INVALID_REQUEST", "A new key epoch needs a sealed key for every other member"),
}

DEFAULT_RETRY_AFTER: dict[str, int] = {
    "TOO_MANY_UPLOADS": 10,
    "QUOTA_EXCEEDED": 60,
    "BUSY": 5,
    "PROCESSING_FAILED": 5,
}


class ApiError(Exception):
    def __init__(
        self,
        code: str,
        message: str | None = None,
        *,
        retry_after_s: int | None = None,
        missing_chunks: list[int] | None = None,
        retryable: bool | None = None,
    ) -> None:
        if code in FUNCTION_ERRORS:
            code, default_message = FUNCTION_ERRORS[code]
            message = message or default_message
        if code not in CONTRACT:
            raise ValueError(f"unknown error code {code}")
        self.code = code
        self.status, default_retryable = CONTRACT[code]
        self.retryable = default_retryable if retryable is None else retryable
        self.message = message or code.replace("_", " ").capitalize()
        self.retry_after_s = retry_after_s if retry_after_s is not None else DEFAULT_RETRY_AFTER.get(code)
        self.missing_chunks = missing_chunks or []
        super().__init__(f"{code}: {self.message}")

    def to_pb(self) -> pb.Error:
        return pb.Error(
            code=self.code,
            message=self.message,
            retryable=self.retryable,
            retry_after_s=self.retry_after_s or 0,
            missing_chunks=self.missing_chunks,
        )

    def headers(self) -> dict[str, str]:
        return {"Retry-After": str(self.retry_after_s)} if self.retry_after_s else {}
