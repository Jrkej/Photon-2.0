"""Chunk upload, resume status and the idempotent complete(): assemble, hash, check the header, report.
A photo is announced to the session only after its SHA-256 matched and the file was saved. Files are end-to-end
encrypted (e2ee.md): the server reads only their plaintext header and never sees a photo."""

from __future__ import annotations

import asyncio
import base64
import hashlib
import os
import uuid
from pathlib import Path
from typing import Any

import google_crc32c
from fastapi import Request

from photon.common import clock, e2ee, logs, metrics
from photon.common.config import Settings
from photon.common.errors import ApiError
from photon.common.pb import photon_pb2 as pb
from photon.imageserver import upload_state as U
from photon.imageserver.access import Access, UploadClaims
from photon.imageserver.outbox import APPLIED, NOOP, SUPERSEDED, Outbox, Record
from photon.imageserver.store import Store, fsync_dir, read_json, write_json

log = logs.get("pipeline")
BLOCK = 1024 * 1024


class Pipeline:
    def __init__(self, settings: Settings, store: Store, access: Access, outbox: Outbox) -> None:
        self.settings, self.store, self.access, self.outbox = settings, store, access, outbox
        outbox.on_final = self.apply_final
        self.usage: dict[str, int] = {}  # bytes under tmp/{upload_id}, for the temporary-disk budget
        self.writing = 0

    # ------------------------------------------------------------------ disk budget

    def scan_usage(self) -> None:
        self.usage = {e.name: self.store.dir_bytes(Path(e.path)) for e in os.scandir(self.store.tmp) if e.is_dir()}
        metrics.tmp_disk_bytes.set(self.tmp_bytes)

    @property
    def tmp_bytes(self) -> int:
        return sum(self.usage.values())

    def _account(self, upload_id: str, delta: int) -> None:
        self.usage[upload_id] = max(0, self.usage.get(upload_id, 0) + delta)
        metrics.tmp_disk_bytes.set(self.tmp_bytes)

    def forget(self, upload_id: str) -> None:
        self.usage.pop(upload_id, None)
        metrics.tmp_disk_bytes.set(self.tmp_bytes)

    # ------------------------------------------------------------------ files

    def existing_meta(self, sid: str, sha: str) -> dict[str, Any] | None:
        meta = read_json(self.store.photo_dir(sid, sha) / "meta.json")
        if meta and int(meta["expires_at_ms"]) > clock.now_ms():
            return dict(meta)
        return None

    # ------------------------------------------------------------------ PUT chunk

    async def put_chunk(self, c: UploadClaims, n: int, request: Request) -> None:
        s = self.settings
        if not 0 <= n < s.max_chunks:
            raise ApiError("INVALID_REQUEST", f"Chunk index must be below {s.max_chunks}")
        try:
            length = int(request.headers["content-length"])
        except (KeyError, ValueError) as e:
            raise ApiError("INVALID_REQUEST", "Content-Length is required") from e
        if length > s.max_chunk_bytes:
            raise ApiError("TOO_LARGE", f"Chunks are limited to {s.max_chunk_bytes} bytes")
        try:
            want_crc = base64.b64decode(request.headers["x-chunk-crc32c"], validate=True)
            if len(want_crc) != 4:
                raise ValueError
        except (KeyError, ValueError) as e:
            raise ApiError("INVALID_REQUEST", "X-Chunk-CRC32C must be base64 of 4 bytes") from e

        async with U.locked(self.store, c.upload_id):
            st = U.load(self.store, c.upload_id)
            if st is None:
                st = U.UploadState(c.upload_id, c.sid, c.sha256, c.size, c.mime, c.taken_at_ms, c.uploader,
                                   key_epoch=c.key_epoch)
                U.save(self.store, st)
            if st.state != U.RECEIVING:
                raise ApiError("UPLOAD_FINALIZED")
        await self.access.check_chunk(c, U.RECEIVING)

        if self.tmp_bytes + length > s.tmp_disk_budget_bytes or self.writing >= s.chunk_write_concurrency:
            metrics.busy_responses_total.inc()
            raise ApiError("BUSY", "Upload capacity reached, retry shortly", retry_after_s=5)
        sizes = self.store.chunk_sizes(c.upload_id)
        if sum(v for k, v in sizes.items() if k != n) + length > c.size:
            raise ApiError("TOO_LARGE", "More bytes than the announced size")

        chunks = self.store.chunks_dir(c.upload_id)
        chunks.mkdir(parents=True, exist_ok=True)
        part = chunks / f"{n}.part.{uuid.uuid4().hex[:8]}"
        self.writing += 1
        self._account(c.upload_id, length)
        try:
            crc = google_crc32c.Checksum()  # type: ignore[no-untyped-call]
            received = 0
            with open(part, "wb") as f:
                async for piece in request.stream():
                    received += len(piece)
                    if received > length:
                        raise ApiError("TOO_LARGE", "Body longer than Content-Length")
                    crc.update(piece)  # type: ignore[no-untyped-call]
                    f.write(piece)
                f.flush()
                os.fsync(f.fileno())
            if received != length:
                raise ApiError("INVALID_REQUEST", "Body shorter than Content-Length", retryable=True)
            if crc.digest() != want_crc:  # type: ignore[no-untyped-call]
                raise ApiError("INVALID_REQUEST", "Chunk CRC32C mismatch", retryable=True)
            async with U.locked(self.store, c.upload_id):
                st = U.load(self.store, c.upload_id)
                if st is None or st.state != U.RECEIVING:
                    raise ApiError("UPLOAD_FINALIZED")
                old = sizes.get(n, 0)
                os.replace(part, chunks / str(n))  # a repeated chunk simply replaces the old one
                fsync_dir(chunks)
                self._account(c.upload_id, -old)
        except BaseException:
            part.unlink(missing_ok=True)
            self._account(c.upload_id, -length)
            raise
        finally:
            self.writing -= 1

    # ------------------------------------------------------------------ GET status

    async def status(self, c: UploadClaims) -> pb.UploadStatus:
        st = U.load(self.store, c.upload_id)
        has_file = self.existing_meta(c.sid, c.sha256) is not None
        if st is None:
            if await self.access.lease_exists(c.sid, c.upload_id):
                return pb.UploadStatus(upload_id=c.upload_id, state=U.RECEIVING, server_has_file=has_file)
            raise ApiError("UNKNOWN_UPLOAD")
        sizes = self.store.chunk_sizes(c.upload_id)
        return pb.UploadStatus(upload_id=c.upload_id, state=st.state, received_chunks=sorted(sizes),
                               received_bytes=sum(sizes.values()), server_has_file=has_file)

    # ------------------------------------------------------------------ complete

    def _answer(self, st: U.UploadState) -> pb.CompleteResponse | None:
        """The stored result of a finished upload; raises the same error for a refused one."""
        if st.state == U.STORED:
            return pb.CompleteResponse(state=U.STORED, sha256=bytes.fromhex(st.sha256),
                                       stored_at_ms=int(st.result["stored_at_ms"]),
                                       expires_at_ms=int(st.result["expires_at_ms"]))
        if st.state == U.REJECTED:
            raise ApiError(st.result.get("code", "INVALID_IMAGE"))
        if st.state == U.SUPERSEDED:
            raise ApiError("UPLOAD_SUPERSEDED")
        if st.state == U.ENDED:
            raise ApiError("SESSION_ENDED")
        return None

    async def complete(self, c: UploadClaims, chunk_count: int) -> tuple[int, pb.CompleteResponse | None]:
        """(200, response) when stored, (202, None) while the report is still on its way."""
        with metrics.verifying_seconds.time():
            async with U.locked(self.store, c.upload_id):
                st = U.load(self.store, c.upload_id)
                if st is None:
                    # No chunk yet. Legitimate when the server already holds the verified file (server_has_file);
                    # otherwise the chunk check below lists every chunk as missing.
                    if not await self.access.lease_exists(c.sid, c.upload_id):
                        raise ApiError("UNKNOWN_UPLOAD")
                    st = U.UploadState(c.upload_id, c.sid, c.sha256, c.size, c.mime, c.taken_at_ms, c.uploader,
                                       key_epoch=c.key_epoch)
                    U.save(self.store, st)
                done = self._answer(st)
                if done is not None:
                    return 200, done
                if st.state != U.REPORTING:
                    await self._verify_and_stage(st, c, chunk_count)
            if not await self.outbox.wait(c.upload_id, self.settings.complete_report_wait_s):
                return 202, None
            st = U.load(self.store, c.upload_id)
            done = self._answer(st) if st else None
            return (200, done) if done is not None else (202, None)

    async def _verify_and_stage(self, st: U.UploadState, c: UploadClaims, chunk_count: int) -> None:
        """Runs under the upload lock; leaves the upload REPORTING with an outbox record, or raises."""
        store = self.store
        if st.state == U.VERIFYING:  # a crash mid-assembly: consumed chunks are gone, the client resends them
            (store.upload_dir(st.upload_id) / "assembled").unlink(missing_ok=True)
            st.state = U.RECEIVING
            U.save(store, st)
        await self.access.check_uncached(c)

        meta = self.existing_meta(c.sid, c.sha256)
        if meta is not None:
            # A verified file for this session and hash exists (Redis lost the reservation): reuse it, keep its
            # expiry, and report it under the new upload_id. No re-hash, no second copy.
            st.result = {"stored_at_ms": int(meta["stored_at_ms"]), "expires_at_ms": int(meta["expires_at_ms"])}
            store.clear_upload_files(st.upload_id)
            self._report_stored(st)
            return

        sizes = store.chunk_sizes(st.upload_id)
        missing = [i for i in range(chunk_count) if i not in sizes]
        if chunk_count <= 0 or missing:
            raise ApiError("INVALID_REQUEST", "Chunks missing", missing_chunks=missing or [0])
        if any(i >= chunk_count for i in sizes):
            raise ApiError("INVALID_REQUEST", "Chunks beyond chunk_count were received")
        if sum(sizes.values()) != st.size:
            raise ApiError("INVALID_REQUEST", "Received bytes do not add up to the announced size")
        st.state = U.VERIFYING
        U.save(store, st)
        digest = await asyncio.to_thread(self._assemble, st.upload_id, chunk_count)
        assembled = store.upload_dir(st.upload_id) / "assembled"
        if digest != st.sha256:
            self._reject(st, "HASH_MISMATCH")
        header = self._check_header(st, assembled)
        staged = store.staged_dir(st.upload_id)
        staged.mkdir(parents=True, exist_ok=True)
        os.replace(assembled, staged / "file")
        fsync_dir(staged)

        now = clock.now_ms()
        meta = {
            "sid": st.sid, "sha256": st.sha256, "size": st.size, "mime": st.mime, "uploader": st.uploader,
            "upload_id": st.upload_id, "taken_at_ms": st.taken_at_ms, "key_epoch": header.epoch,
            "header_len": e2ee.HEADER_LEN, "lengths": list(header.lengths), "stored_at_ms": now,
            "expires_at_ms": now + self.settings.retention_ms,
        }
        write_json(staged / "meta.json", meta)
        st.result = {"stored_at_ms": meta["stored_at_ms"], "expires_at_ms": meta["expires_at_ms"]}
        self._report_stored(st)

    def _check_header(self, st: U.UploadState, assembled: Path) -> e2ee.Header:
        """The only look the server gets: the encrypted file's plaintext header (e2ee.md 8). The photo itself, its
        type and dimensions are encrypted, so the server can't decode or screen it; receivers decode defensively."""
        with open(assembled, "rb") as f:
            head = f.read(e2ee.HEADER_LEN)
        try:
            header = e2ee.parse_header(head)
        except e2ee.E2eeError as e:
            log.info("invalid_file", upload_id=st.upload_id, error=str(e))
            self._reject(st, "INVALID_IMAGE")
            raise  # unreachable: _reject raises
        if header.size != st.size or header.epoch != st.key_epoch:
            log.info("invalid_file", upload_id=st.upload_id, header_size=header.size, size=st.size,
                     header_epoch=header.epoch, epoch=st.key_epoch)
            self._reject(st, "INVALID_IMAGE")
        return header

    def _assemble(self, upload_id: str, chunk_count: int) -> str:
        """Streams the chunks into one file while hashing, deleting each chunk once consumed."""
        chunks = self.store.chunks_dir(upload_id)
        h = hashlib.sha256()
        with open(self.store.upload_dir(upload_id) / "assembled", "wb") as out:
            for i in range(chunk_count):
                path = chunks / str(i)
                with open(path, "rb") as f:
                    while block := f.read(BLOCK):
                        h.update(block)
                        out.write(block)
                path.unlink()
            out.flush()
            os.fsync(out.fileno())
        return h.hexdigest()

    def _report_stored(self, st: U.UploadState) -> None:
        msg = pb.PhotoStored(
            session_id=st.sid, sha256=bytes.fromhex(st.sha256), upload_id=st.upload_id, uploader_id=st.uploader,
            size=st.size, mime=st.mime, taken_at_ms=st.taken_at_ms,
            stored_at_ms=int(st.result["stored_at_ms"]), expires_at_ms=int(st.result["expires_at_ms"]),
        )
        self.outbox.put(st.upload_id, "stored", msg)
        st.state = U.REPORTING
        U.save(self.store, st)

    def _reject(self, st: U.UploadState, reason: str) -> None:
        self.store.clear_upload_files(st.upload_id)
        self.forget(st.upload_id)
        st.state, st.result = U.REJECTED, {"code": reason}
        U.save(self.store, st)
        self.outbox.put(st.upload_id, "rejected", pb.PhotoRejected(
            session_id=st.sid, sha256=bytes.fromhex(st.sha256), upload_id=st.upload_id, reason=reason))
        metrics.rejections_total.labels(reason).inc()
        raise ApiError(reason)

    # ------------------------------------------------------------------ orchestrator answers

    async def apply_final(self, rec: Record, answer: str) -> None:
        """Effects of a final report answer: each step is safe to repeat (effect, then state, then the record)."""
        if rec.kind != "stored":
            return
        msg = pb.PhotoStored()
        msg.ParseFromString(rec.body)
        async with U.locked(self.store, rec.upload_id):
            st = U.load(self.store, rec.upload_id)
            if answer in (APPLIED, NOOP):
                self._promote(rec.upload_id, msg.session_id, msg.sha256.hex())
                new_state = U.STORED
            else:
                self.store.clear_upload_files(rec.upload_id)
                new_state = U.SUPERSEDED if answer == SUPERSEDED else U.ENDED
            self.forget(rec.upload_id)
            if st is not None and st.state not in U.FINAL:
                st.state = new_state
                U.save(self.store, st)

    def _promote(self, upload_id: str, sid: str, sha: str) -> None:
        staged = self.store.staged_dir(upload_id)
        if not staged.exists():
            return  # the reuse path, or already promoted
        target = self.store.photo_dir(sid, sha)
        if target.exists():
            self.store.to_trash(staged)  # a verified copy is already there
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        os.replace(staged, target)
        fsync_dir(target.parent)
