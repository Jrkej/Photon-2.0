"""Settings shared by every PHOTON service. Every limit from the design is a setting with its proposed default."""

from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

MIB = 1024 * 1024
MINUTE_MS = 60_000
HOUR_MS = 60 * MINUTE_MS


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="PHOTON_", env_file=".env", extra="ignore")

    env: Literal["dev", "test", "prod"] = "dev"

    # Stores
    redis_url: str = "redis://localhost:6379/0"
    redis_blocking_pool: int = 200
    database_url: str = "postgresql+asyncpg://photon@localhost/photon"

    # Where things are
    public_base_url: str = "https://photon.example"  # invite links, App Links
    api_base_url: str = "http://localhost:8080"  # used by the dev OAuth page
    image_base_url: str = "http://localhost:8081"  # put in AnnounceResponse.upload_url
    orchestrator_internal_url: str = "http://localhost:8080"  # image server -> orchestrator
    imageserver_internal_url: str = "http://localhost:8081"  # orchestrator -> image server
    storage_root: Path = Path("/var/lib/photon")

    # Keys: a directory of PEM files written by tools/gen_keys.py
    keys_dir: Path = Path("/run/secrets/photon")
    auth_jwks_url: str | None = None  # verifiers fetch Auth's JWKS when set, else read keys_dir
    upload_jwks_url: str | None = None  # image server fetches the orchestrator's upload JWKS when set

    # OAuth
    oauth_redirect_uris: list[str] = ["https://photon.example/auth/cb"]
    dev_redirect_uris: list[str] = ["photon://auth/cb"]
    google_client_id: str = ""
    google_client_secret: str = ""
    android_cert_sha256: list[str] = []
    ios_app_id: str = "TEAMID.com.photon.app"

    # Tokens
    access_token_ttl_s: int = 15 * 60
    refresh_ttl_s: int = 30 * 24 * 3600
    refresh_grace_ms: int = 30_000
    upload_token_ttl_s: int = 10 * 60
    service_token_ttl_s: int = 60

    # Sessions
    max_members: int = 10
    session_idle_ms: int = 12 * HOUR_MS
    code_ttl_s: int = 2 * 3600
    invite_ttl_s: int = 12 * 3600

    # Photos and uploads
    retention_ms: int = 30 * MINUTE_MS
    max_photo_bytes: int = 25 * MIB
    max_pixels: int = 100_000_000
    session_quota_bytes: int = 1024 * MIB
    max_live_uploads_user: int = 3
    max_live_uploads_session: int = 20
    max_strikes: int = 3
    lease_ms: int = 15 * MINUTE_MS
    takeover_ms: int = MINUTE_MS
    chunk_size_hint: int = MIB
    max_chunk_bytes: int = 8 * MIB
    max_chunks: int = 4096
    tmp_disk_budget_bytes: int = 2048 * MIB
    chunk_write_concurrency: int = 64
    member_cache_s: float = 5.0
    complete_report_wait_s: float = 5.0
    final_state_keep_s: int = 3600
    orphan_age_s: int = 3600
    stale_tmp_s: int = 15 * 60
    allowed_mimes: list[str] = ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"]

    # Image processing
    decode_concurrency: int = max(1, os.cpu_count() or 1)
    decode_mem_bytes: int = 1536 * MIB
    decode_cpu_s: int = 20
    decode_wall_s: float = 30.0
    thumb_px: int = 320
    preview_px: int = 1600
    thumb_attempts: int = 3

    # Rate limits
    announce_per_minute: int = 20
    join_per_minute: int = 5
    create_per_hour: int = 10
    oauth_per_minute: int = 20
    refresh_per_minute: int = 10
    trusted_proxies: list[str] = ["127.0.0.1", "::1"]

    # Background jobs
    expiry_shards: int = 16
    sweeper_interval_s: float = 60.0
    session_sweeper_interval_s: float = 30.0
    recount_interval_s: float = 300.0
    imageserver_sweep_interval_s: float = 60.0
    run_jobs: bool = True

    # Events and push
    ws_hello_timeout_s: float = 10.0
    ws_block_ms: int = 15_000
    activity_throttle_s: float = 60.0
    presence_stale_s: int = 90
    push_coalesce_s: int = 30
    fcm_credentials_file: str | None = None  # Android devices (FCM)
    apns_key_file: str | None = None  # iOS devices (APNs token auth, .p8)
    apns_key_id: str = ""
    apns_team_id: str = ""
    apns_topic: str = "com.photon.app"
    apns_sandbox: bool = False

    @property
    def redirect_uris(self) -> list[str]:
        if self.env == "prod":
            return list(self.oauth_redirect_uris)
        return [*self.oauth_redirect_uris, *self.dev_redirect_uris]
