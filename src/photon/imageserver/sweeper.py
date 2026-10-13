"""The image server's own sweeper. It decides from its own files (meta.json, state.json, outbox records), never
from Redis alone, so losing Redis can neither delete a valid file early nor keep a dead one forever."""

from __future__ import annotations

import asyncio
import os
import shutil
from pathlib import Path

from photon.common import clock, logs, metrics
from photon.imageserver import upload_state as U
from photon.imageserver.access import Access
from photon.imageserver.pipeline import Pipeline
from photon.imageserver.store import Store, read_json

log = logs.get("sweeper")
OVERDUE_S = 15 * 60


class Sweeper:
    def __init__(self, store: Store, access: Access, pipeline: Pipeline) -> None:
        self.store, self.access, self.pipeline = store, access, pipeline
        self.settings = pipeline.settings

    async def sweep(self) -> None:
        await asyncio.to_thread(self._sweep_photos)
        await self._sweep_tmp()
        await asyncio.to_thread(self._empty_trash)
        self.pipeline.scan_usage()

    def _sweep_photos(self) -> None:
        now = clock.now_ms()
        past = 0
        for sid_entry in list(os.scandir(self.store.photos)):
            sid_dir = Path(sid_entry.path)
            for sha_entry in list(os.scandir(sid_dir)):
                d = Path(sha_entry.path)
                meta = read_json(d / "meta.json")
                if meta is not None:
                    lag_s = (now - int(meta["expires_at_ms"])) / 1000
                    if lag_s >= 0:
                        if self.store.to_trash(d):
                            metrics.deletion_lag_seconds.observe(lag_s)
                            if lag_s > OVERDUE_S:
                                metrics.files_overdue_total.inc()
                                log.warning("file_deleted_overdue", path=str(d), lag_s=lag_s)
                elif now / 1000 - d.stat().st_mtime > self.settings.orphan_age_s:
                    if self.store.to_trash(d):
                        metrics.orphans_found_total.labels("photo_without_meta").inc()
            if not any(sid_dir.iterdir()):
                try:
                    sid_dir.rmdir()
                except OSError:
                    pass
        # What is still on disk past its expiry after this pass (should be 0)
        for meta_path in self.store.photos.glob("*/*/meta.json"):
            meta = read_json(meta_path)
            if meta and int(meta["expires_at_ms"]) <= now:
                past += 1
        metrics.files_past_expiry.set(past)

    async def _sweep_tmp(self) -> None:
        now = clock.now_ms()
        s = self.settings
        for entry in list(os.scandir(self.store.tmp)):
            upload_id = entry.name
            if len(upload_id) != 32 or self.store.outbox_file(upload_id).exists():
                continue  # a pending report keeps its files
            async with U.locked(self.store, upload_id):
                if self.store.outbox_file(upload_id).exists():
                    continue
                st = U.load(self.store, upload_id)
                d = self.store.upload_dir(upload_id)
                if st is None:
                    if now / 1000 - d.stat().st_mtime > s.stale_tmp_s:
                        self._drop(upload_id, "tmp_without_state")
                    continue
                if st.state in U.FINAL:
                    if now - st.updated_at > s.final_state_keep_s * 1000:
                        self._drop(upload_id, None)
                    continue
                if await self.access.session_status(st.sid) != "ACTIVE":
                    self._drop(upload_id, "tmp_session_ended")
                    continue
                if await self.access.lease_exists(st.sid, upload_id):
                    if st.lease_missing_since:
                        st.lease_missing_since = 0
                        U.save(self.store, st)
                    continue
                if not st.lease_missing_since:
                    st.lease_missing_since = now
                    U.save(self.store, st)
                elif now - st.lease_missing_since > s.stale_tmp_s * 1000:
                    self._drop(upload_id, "tmp_lease_gone")

    def _drop(self, upload_id: str, orphan_kind: str | None) -> None:
        self.store.to_trash(self.store.upload_dir(upload_id))
        self.pipeline.forget(upload_id)
        if orphan_kind:
            metrics.orphans_found_total.labels(orphan_kind).inc()

    def _empty_trash(self) -> None:
        for entry in os.scandir(self.store.trash):
            shutil.rmtree(entry.path, ignore_errors=True)
