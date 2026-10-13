from google.protobuf.internal import containers as _containers
from google.protobuf.internal import enum_type_wrapper as _enum_type_wrapper
from google.protobuf import descriptor as _descriptor
from google.protobuf import message as _message
from collections.abc import Iterable as _Iterable, Mapping as _Mapping
from typing import ClassVar as _ClassVar, Optional as _Optional, Union as _Union

DESCRIPTOR: _descriptor.FileDescriptor

class AnnounceRequest(_message.Message):
    __slots__ = ("sha256", "size", "mime", "taken_at_ms", "session_id")
    SHA256_FIELD_NUMBER: _ClassVar[int]
    SIZE_FIELD_NUMBER: _ClassVar[int]
    MIME_FIELD_NUMBER: _ClassVar[int]
    TAKEN_AT_MS_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    sha256: bytes
    size: int
    mime: str
    taken_at_ms: int
    session_id: str
    def __init__(self, sha256: _Optional[bytes] = ..., size: _Optional[int] = ..., mime: _Optional[str] = ..., taken_at_ms: _Optional[int] = ..., session_id: _Optional[str] = ...) -> None: ...

class AnnounceResponse(_message.Message):
    __slots__ = ("result", "upload_id", "upload_url", "upload_token", "chunk_size_hint")
    class Result(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        UPLOAD: _ClassVar[AnnounceResponse.Result]
        ALREADY_UPLOADED: _ClassVar[AnnounceResponse.Result]
        IN_PROGRESS: _ClassVar[AnnounceResponse.Result]
    UPLOAD: AnnounceResponse.Result
    ALREADY_UPLOADED: AnnounceResponse.Result
    IN_PROGRESS: AnnounceResponse.Result
    RESULT_FIELD_NUMBER: _ClassVar[int]
    UPLOAD_ID_FIELD_NUMBER: _ClassVar[int]
    UPLOAD_URL_FIELD_NUMBER: _ClassVar[int]
    UPLOAD_TOKEN_FIELD_NUMBER: _ClassVar[int]
    CHUNK_SIZE_HINT_FIELD_NUMBER: _ClassVar[int]
    result: AnnounceResponse.Result
    upload_id: str
    upload_url: str
    upload_token: str
    chunk_size_hint: int
    def __init__(self, result: _Optional[_Union[AnnounceResponse.Result, str]] = ..., upload_id: _Optional[str] = ..., upload_url: _Optional[str] = ..., upload_token: _Optional[str] = ..., chunk_size_hint: _Optional[int] = ...) -> None: ...

class PhotoStored(_message.Message):
    __slots__ = ("session_id", "sha256", "upload_id", "uploader_id", "size", "mime", "taken_at_ms", "stored_at_ms", "expires_at_ms")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    UPLOAD_ID_FIELD_NUMBER: _ClassVar[int]
    UPLOADER_ID_FIELD_NUMBER: _ClassVar[int]
    SIZE_FIELD_NUMBER: _ClassVar[int]
    MIME_FIELD_NUMBER: _ClassVar[int]
    TAKEN_AT_MS_FIELD_NUMBER: _ClassVar[int]
    STORED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_MS_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    sha256: bytes
    upload_id: str
    uploader_id: str
    size: int
    mime: str
    taken_at_ms: int
    stored_at_ms: int
    expires_at_ms: int
    def __init__(self, session_id: _Optional[str] = ..., sha256: _Optional[bytes] = ..., upload_id: _Optional[str] = ..., uploader_id: _Optional[str] = ..., size: _Optional[int] = ..., mime: _Optional[str] = ..., taken_at_ms: _Optional[int] = ..., stored_at_ms: _Optional[int] = ..., expires_at_ms: _Optional[int] = ...) -> None: ...

class PhotoRejected(_message.Message):
    __slots__ = ("session_id", "sha256", "upload_id", "reason")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    UPLOAD_ID_FIELD_NUMBER: _ClassVar[int]
    REASON_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    sha256: bytes
    upload_id: str
    reason: str
    def __init__(self, session_id: _Optional[str] = ..., sha256: _Optional[bytes] = ..., upload_id: _Optional[str] = ..., reason: _Optional[str] = ...) -> None: ...

class PhotoEvent(_message.Message):
    __slots__ = ("event_id", "type", "session_id", "sha256", "uploader_id", "size", "taken_at_ms", "expires_at_ms", "member_id")
    class Type(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        PHOTO_STORED: _ClassVar[PhotoEvent.Type]
        PHOTO_RECALLED: _ClassVar[PhotoEvent.Type]
        MEMBER_JOINED: _ClassVar[PhotoEvent.Type]
        MEMBER_LEFT: _ClassVar[PhotoEvent.Type]
        SESSION_ENDED: _ClassVar[PhotoEvent.Type]
        HOST_CHANGED: _ClassVar[PhotoEvent.Type]
    PHOTO_STORED: PhotoEvent.Type
    PHOTO_RECALLED: PhotoEvent.Type
    MEMBER_JOINED: PhotoEvent.Type
    MEMBER_LEFT: PhotoEvent.Type
    SESSION_ENDED: PhotoEvent.Type
    HOST_CHANGED: PhotoEvent.Type
    EVENT_ID_FIELD_NUMBER: _ClassVar[int]
    TYPE_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    UPLOADER_ID_FIELD_NUMBER: _ClassVar[int]
    SIZE_FIELD_NUMBER: _ClassVar[int]
    TAKEN_AT_MS_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_MS_FIELD_NUMBER: _ClassVar[int]
    MEMBER_ID_FIELD_NUMBER: _ClassVar[int]
    event_id: str
    type: PhotoEvent.Type
    session_id: str
    sha256: bytes
    uploader_id: str
    size: int
    taken_at_ms: int
    expires_at_ms: int
    member_id: str
    def __init__(self, event_id: _Optional[str] = ..., type: _Optional[_Union[PhotoEvent.Type, str]] = ..., session_id: _Optional[str] = ..., sha256: _Optional[bytes] = ..., uploader_id: _Optional[str] = ..., size: _Optional[int] = ..., taken_at_ms: _Optional[int] = ..., expires_at_ms: _Optional[int] = ..., member_id: _Optional[str] = ...) -> None: ...

class Snapshot(_message.Message):
    __slots__ = ("cursor", "live_photos", "recalled_sha256", "member_ids")
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    LIVE_PHOTOS_FIELD_NUMBER: _ClassVar[int]
    RECALLED_SHA256_FIELD_NUMBER: _ClassVar[int]
    MEMBER_IDS_FIELD_NUMBER: _ClassVar[int]
    cursor: str
    live_photos: _containers.RepeatedCompositeFieldContainer[PhotoEvent]
    recalled_sha256: _containers.RepeatedScalarFieldContainer[bytes]
    member_ids: _containers.RepeatedScalarFieldContainer[str]
    def __init__(self, cursor: _Optional[str] = ..., live_photos: _Optional[_Iterable[_Union[PhotoEvent, _Mapping]]] = ..., recalled_sha256: _Optional[_Iterable[bytes]] = ..., member_ids: _Optional[_Iterable[str]] = ...) -> None: ...

class Error(_message.Message):
    __slots__ = ("code", "message", "retryable", "retry_after_s", "missing_chunks")
    CODE_FIELD_NUMBER: _ClassVar[int]
    MESSAGE_FIELD_NUMBER: _ClassVar[int]
    RETRYABLE_FIELD_NUMBER: _ClassVar[int]
    RETRY_AFTER_S_FIELD_NUMBER: _ClassVar[int]
    MISSING_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    code: str
    message: str
    retryable: bool
    retry_after_s: int
    missing_chunks: _containers.RepeatedScalarFieldContainer[int]
    def __init__(self, code: _Optional[str] = ..., message: _Optional[str] = ..., retryable: _Optional[bool] = ..., retry_after_s: _Optional[int] = ..., missing_chunks: _Optional[_Iterable[int]] = ...) -> None: ...

class OAuthStartRequest(_message.Message):
    __slots__ = ("provider", "redirect_uri", "code_challenge")
    PROVIDER_FIELD_NUMBER: _ClassVar[int]
    REDIRECT_URI_FIELD_NUMBER: _ClassVar[int]
    CODE_CHALLENGE_FIELD_NUMBER: _ClassVar[int]
    provider: str
    redirect_uri: str
    code_challenge: str
    def __init__(self, provider: _Optional[str] = ..., redirect_uri: _Optional[str] = ..., code_challenge: _Optional[str] = ...) -> None: ...

class OAuthStartResponse(_message.Message):
    __slots__ = ("authorization_url", "state")
    AUTHORIZATION_URL_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    authorization_url: str
    state: str
    def __init__(self, authorization_url: _Optional[str] = ..., state: _Optional[str] = ...) -> None: ...

class OAuthCallbackRequest(_message.Message):
    __slots__ = ("code", "state", "code_verifier", "redirect_uri")
    CODE_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    CODE_VERIFIER_FIELD_NUMBER: _ClassVar[int]
    REDIRECT_URI_FIELD_NUMBER: _ClassVar[int]
    code: str
    state: str
    code_verifier: str
    redirect_uri: str
    def __init__(self, code: _Optional[str] = ..., state: _Optional[str] = ..., code_verifier: _Optional[str] = ..., redirect_uri: _Optional[str] = ...) -> None: ...

class TokenPair(_message.Message):
    __slots__ = ("access_token", "refresh_token", "access_expires_at_ms", "user_id")
    ACCESS_TOKEN_FIELD_NUMBER: _ClassVar[int]
    REFRESH_TOKEN_FIELD_NUMBER: _ClassVar[int]
    ACCESS_EXPIRES_AT_MS_FIELD_NUMBER: _ClassVar[int]
    USER_ID_FIELD_NUMBER: _ClassVar[int]
    access_token: str
    refresh_token: str
    access_expires_at_ms: int
    user_id: str
    def __init__(self, access_token: _Optional[str] = ..., refresh_token: _Optional[str] = ..., access_expires_at_ms: _Optional[int] = ..., user_id: _Optional[str] = ...) -> None: ...

class RefreshRequest(_message.Message):
    __slots__ = ("refresh_token",)
    REFRESH_TOKEN_FIELD_NUMBER: _ClassVar[int]
    refresh_token: str
    def __init__(self, refresh_token: _Optional[str] = ...) -> None: ...

class DeviceRegistration(_message.Message):
    __slots__ = ("platform", "push_token")
    PLATFORM_FIELD_NUMBER: _ClassVar[int]
    PUSH_TOKEN_FIELD_NUMBER: _ClassVar[int]
    platform: str
    push_token: str
    def __init__(self, platform: _Optional[str] = ..., push_token: _Optional[str] = ...) -> None: ...

class CreateSessionRequest(_message.Message):
    __slots__ = ("max_members",)
    MAX_MEMBERS_FIELD_NUMBER: _ClassVar[int]
    max_members: int
    def __init__(self, max_members: _Optional[int] = ...) -> None: ...

class SessionInfo(_message.Message):
    __slots__ = ("session_id", "join_code", "invite_url", "qr_payload", "expires_at_ms", "role", "member_ids", "host_id")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    JOIN_CODE_FIELD_NUMBER: _ClassVar[int]
    INVITE_URL_FIELD_NUMBER: _ClassVar[int]
    QR_PAYLOAD_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_MS_FIELD_NUMBER: _ClassVar[int]
    ROLE_FIELD_NUMBER: _ClassVar[int]
    MEMBER_IDS_FIELD_NUMBER: _ClassVar[int]
    HOST_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    join_code: str
    invite_url: str
    qr_payload: str
    expires_at_ms: int
    role: str
    member_ids: _containers.RepeatedScalarFieldContainer[str]
    host_id: str
    def __init__(self, session_id: _Optional[str] = ..., join_code: _Optional[str] = ..., invite_url: _Optional[str] = ..., qr_payload: _Optional[str] = ..., expires_at_ms: _Optional[int] = ..., role: _Optional[str] = ..., member_ids: _Optional[_Iterable[str]] = ..., host_id: _Optional[str] = ...) -> None: ...

class JoinRequest(_message.Message):
    __slots__ = ("code", "invite_token")
    CODE_FIELD_NUMBER: _ClassVar[int]
    INVITE_TOKEN_FIELD_NUMBER: _ClassVar[int]
    code: str
    invite_token: str
    def __init__(self, code: _Optional[str] = ..., invite_token: _Optional[str] = ...) -> None: ...

class SessionRef(_message.Message):
    __slots__ = ("session_id",)
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    def __init__(self, session_id: _Optional[str] = ...) -> None: ...

class KickRequest(_message.Message):
    __slots__ = ("session_id", "member_id")
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    MEMBER_ID_FIELD_NUMBER: _ClassVar[int]
    session_id: str
    member_id: str
    def __init__(self, session_id: _Optional[str] = ..., member_id: _Optional[str] = ...) -> None: ...

class ReportPhotoRequest(_message.Message):
    __slots__ = ("reason",)
    REASON_FIELD_NUMBER: _ClassVar[int]
    reason: str
    def __init__(self, reason: _Optional[str] = ...) -> None: ...

class UploadStatus(_message.Message):
    __slots__ = ("upload_id", "state", "received_chunks", "received_bytes", "server_has_file")
    UPLOAD_ID_FIELD_NUMBER: _ClassVar[int]
    STATE_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_CHUNKS_FIELD_NUMBER: _ClassVar[int]
    RECEIVED_BYTES_FIELD_NUMBER: _ClassVar[int]
    SERVER_HAS_FILE_FIELD_NUMBER: _ClassVar[int]
    upload_id: str
    state: str
    received_chunks: _containers.RepeatedScalarFieldContainer[int]
    received_bytes: int
    server_has_file: bool
    def __init__(self, upload_id: _Optional[str] = ..., state: _Optional[str] = ..., received_chunks: _Optional[_Iterable[int]] = ..., received_bytes: _Optional[int] = ..., server_has_file: _Optional[bool] = ...) -> None: ...

class CompleteRequest(_message.Message):
    __slots__ = ("chunk_count",)
    CHUNK_COUNT_FIELD_NUMBER: _ClassVar[int]
    chunk_count: int
    def __init__(self, chunk_count: _Optional[int] = ...) -> None: ...

class CompleteResponse(_message.Message):
    __slots__ = ("state", "sha256", "stored_at_ms", "expires_at_ms")
    STATE_FIELD_NUMBER: _ClassVar[int]
    SHA256_FIELD_NUMBER: _ClassVar[int]
    STORED_AT_MS_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_MS_FIELD_NUMBER: _ClassVar[int]
    state: str
    sha256: bytes
    stored_at_ms: int
    expires_at_ms: int
    def __init__(self, state: _Optional[str] = ..., sha256: _Optional[bytes] = ..., stored_at_ms: _Optional[int] = ..., expires_at_ms: _Optional[int] = ...) -> None: ...

class ReportAck(_message.Message):
    __slots__ = ("result", "event_id")
    class Result(int, metaclass=_enum_type_wrapper.EnumTypeWrapper):
        __slots__ = ()
        APPLIED: _ClassVar[ReportAck.Result]
        NOOP: _ClassVar[ReportAck.Result]
    APPLIED: ReportAck.Result
    NOOP: ReportAck.Result
    RESULT_FIELD_NUMBER: _ClassVar[int]
    EVENT_ID_FIELD_NUMBER: _ClassVar[int]
    result: ReportAck.Result
    event_id: str
    def __init__(self, result: _Optional[_Union[ReportAck.Result, str]] = ..., event_id: _Optional[str] = ...) -> None: ...

class EventBatch(_message.Message):
    __slots__ = ("events", "cursor")
    EVENTS_FIELD_NUMBER: _ClassVar[int]
    CURSOR_FIELD_NUMBER: _ClassVar[int]
    events: _containers.RepeatedCompositeFieldContainer[PhotoEvent]
    cursor: str
    def __init__(self, events: _Optional[_Iterable[_Union[PhotoEvent, _Mapping]]] = ..., cursor: _Optional[str] = ...) -> None: ...

class Hello(_message.Message):
    __slots__ = ("access_token", "session_id", "last_event_id")
    ACCESS_TOKEN_FIELD_NUMBER: _ClassVar[int]
    SESSION_ID_FIELD_NUMBER: _ClassVar[int]
    LAST_EVENT_ID_FIELD_NUMBER: _ClassVar[int]
    access_token: str
    session_id: str
    last_event_id: str
    def __init__(self, access_token: _Optional[str] = ..., session_id: _Optional[str] = ..., last_event_id: _Optional[str] = ...) -> None: ...

class ClientFrame(_message.Message):
    __slots__ = ("hello", "ping")
    HELLO_FIELD_NUMBER: _ClassVar[int]
    PING_FIELD_NUMBER: _ClassVar[int]
    hello: Hello
    ping: int
    def __init__(self, hello: _Optional[_Union[Hello, _Mapping]] = ..., ping: _Optional[int] = ...) -> None: ...

class ServerFrame(_message.Message):
    __slots__ = ("event", "error", "pong", "ready_cursor")
    EVENT_FIELD_NUMBER: _ClassVar[int]
    ERROR_FIELD_NUMBER: _ClassVar[int]
    PONG_FIELD_NUMBER: _ClassVar[int]
    READY_CURSOR_FIELD_NUMBER: _ClassVar[int]
    event: PhotoEvent
    error: Error
    pong: int
    ready_cursor: str
    def __init__(self, event: _Optional[_Union[PhotoEvent, _Mapping]] = ..., error: _Optional[_Union[Error, _Mapping]] = ..., pong: _Optional[int] = ..., ready_cursor: _Optional[str] = ...) -> None: ...

class AuthConfig(_message.Message):
    __slots__ = ("google_client_id", "dev_login", "github_login")
    GOOGLE_CLIENT_ID_FIELD_NUMBER: _ClassVar[int]
    DEV_LOGIN_FIELD_NUMBER: _ClassVar[int]
    GITHUB_LOGIN_FIELD_NUMBER: _ClassVar[int]
    google_client_id: str
    dev_login: bool
    github_login: bool
    def __init__(self, google_client_id: _Optional[str] = ..., dev_login: _Optional[bool] = ..., github_login: _Optional[bool] = ...) -> None: ...

class NonceResponse(_message.Message):
    __slots__ = ("nonce", "expires_at_ms")
    NONCE_FIELD_NUMBER: _ClassVar[int]
    EXPIRES_AT_MS_FIELD_NUMBER: _ClassVar[int]
    nonce: str
    expires_at_ms: int
    def __init__(self, nonce: _Optional[str] = ..., expires_at_ms: _Optional[int] = ...) -> None: ...

class GoogleSignInRequest(_message.Message):
    __slots__ = ("id_token", "nonce")
    ID_TOKEN_FIELD_NUMBER: _ClassVar[int]
    NONCE_FIELD_NUMBER: _ClassVar[int]
    id_token: str
    nonce: str
    def __init__(self, id_token: _Optional[str] = ..., nonce: _Optional[str] = ...) -> None: ...

class DevLoginRequest(_message.Message):
    __slots__ = ("username",)
    USERNAME_FIELD_NUMBER: _ClassVar[int]
    username: str
    def __init__(self, username: _Optional[str] = ...) -> None: ...
