"""Every Prometheus metric, defined once (a process may host several apps in tests and in the dev launcher)."""

from __future__ import annotations

from prometheus_client import CONTENT_TYPE_LATEST, Counter, Gauge, Histogram, generate_latest
from starlette.requests import Request
from starlette.responses import Response

LAG_BUCKETS = (1, 5, 15, 30, 60, 120, 300, 600, 900, 1800, 3600)
DURATION_BUCKETS = (0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120, 300)

# Deletion
deletes_total = Counter("photon_deletes_total", "Image-server deletes requested", ["result"])
deletion_lag_seconds = Histogram("photon_deletion_lag_seconds", "Delete time minus expires_at", buckets=LAG_BUCKETS)
files_past_expiry = Gauge("photon_files_past_expiry", "Photo files still on disk after expires_at")
files_overdue_total = Counter("photon_files_overdue_total", "Files deleted more than 15 minutes after expiry")
orphans_found_total = Counter("photon_orphans_found_total", "Orphans removed by the sweeper", ["kind"])
expiry_backlog_oldest_seconds = Gauge("photon_expiry_backlog_oldest_seconds", "Age of the oldest due expiry")

# Reports
outbox_pending = Gauge("photon_outbox_pending", "Pending stored/rejected reports")
outbox_oldest_seconds = Gauge("photon_outbox_oldest_seconds", "Age of the oldest pending report")
reports_total = Counter("photon_reports_total", "Reports handled by the orchestrator", ["kind", "answer"])
report_auth_failures_total = Counter("photon_report_auth_failures_total", "Reports refused for credentials")

# Uploads
announce_total = Counter("photon_announce_total", "Announce results", ["result"])
takeovers_total = Counter("photon_takeovers_total", "Stale reservations taken over")
stale_reservations_released_total = Counter("photon_stale_reservations_released_total", "Stale releases")
tmp_disk_bytes = Gauge("photon_tmp_disk_bytes", "Bytes under the image server's tmp directory")
quota_rejections_total = Counter("photon_quota_rejections_total", "Announces refused by quota or limits", ["code"])
busy_responses_total = Counter("photon_busy_responses_total", "503 BUSY answers")
verifying_seconds = Histogram("photon_verifying_seconds", "Time in complete()", buckets=DURATION_BUCKETS)
rejections_total = Counter("photon_rejections_total", "Uploads rejected by the image server", ["reason"])
quota_correction_bytes = Histogram(
    "photon_quota_correction_bytes", "Absolute counter drift fixed by the recount", ["counter"],
    buckets=(0, 1, 1024, 1024**2, 1024**3),
)

# Delivery
upload_duration_seconds = Histogram("photon_upload_duration_seconds", "Reservation to stored", buckets=DURATION_BUCKETS)
stored_to_delivered_seconds = Histogram(
    "photon_stored_to_delivered_seconds", "Event time to WebSocket send", buckets=DURATION_BUCKETS
)
ws_open = Gauge("photon_ws_open", "Open WebSockets")
resync_required_total = Counter("photon_resync_required_total", "RESYNC_REQUIRED answers")
push_sent_total = Counter("photon_push_sent_total", "Push hints", ["result"])
clock_skew_reports_total = Counter("photon_clock_skew_reports_total", "Reports with stored_at far from now")
refresh_reuse_total = Counter("photon_refresh_reuse_total", "Refresh token replays that revoked a family")
blocking_pool_in_use = Gauge("photon_redis_blocking_pool_in_use", "Blocking Redis connections in use")
photo_reports_total = Counter("photon_photo_reports_total", "Photos reported by members")


async def metrics_endpoint(_: Request) -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
