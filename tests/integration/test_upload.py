"""Phase 3: chunked upload, idempotent complete, hash verification, crash recovery, and hostile files. Every file
is end-to-end encrypted (e2ee.md): the image server checks the hash and the plaintext header, nothing else."""

from __future__ import annotations

import hashlib
import io
from collections.abc import AsyncIterator, Callable
from dataclasses import dataclass

import httpx
import pytest
import pytest_asyncio
from PIL import Image

from photon.client.api import ClientError, PhotonClient
from photon.client.uploader import crc32c_b64, upload_bytes, upload_file
from photon.common import clock, e2ee
from photon.common.pb import photon_pb2 as pb
from photon.imageserver import upload_state as U
from photon.imageserver.outbox import Outbox
from photon.orchestrator import keys as K
from tests.conftest import Login, Stack, announce_built, build, eventually, group, jpeg


@dataclass
class Session:
    sid: str
    host: PhotonClient
    member: PhotonClient


@pytest_asyncio.fixture(loop_scope="session")
async def session(login: Login) -> Session:
    sid, (host, member) = await group(login, "host", "member")  # both hold the session key
    return Session(sid, host, member)


async def announce(c: PhotonClient, sid: str, original: bytes,
                   claimed: bytes | None = None) -> tuple[pb.AnnounceResponse, bytes]:
    """Encrypts `original` and announces the file; returns the answer and the file to upload."""
    built = await build(c, sid, original)
    ann = await announce_built(c, sid, built, claimed=claimed)
    assert ann.result == pb.AnnounceResponse.UPLOAD
    return ann, built.file


async def announce_raw(c: PhotonClient, sid: str, file: bytes) -> pb.AnnounceResponse:
    """Announces arbitrary bytes as an encrypted file of the current epoch, with their own hash and size."""
    ann = await c.announce(sid, hashlib.sha256(file).digest(), len(file), e2ee.MIME, clock.now_ms(),
                           hashlib.sha256(b"tag" + file).digest(), 1)
    assert ann.result == pb.AnnounceResponse.UPLOAD
    return ann


async def put(c: PhotonClient, ann: pb.AnnounceResponse, n: int, data: bytes) -> None:
    await c.put_chunk(ann.upload_url, ann.upload_token, n, data, crc32c_b64(data))


def chunks(data: bytes, size: int) -> list[bytes]:
    return [data[i:i + size] for i in range(0, len(data), size)]


@pytest_asyncio.fixture(loop_scope="session")
async def orchestrator_answers(
        env: Stack) -> AsyncIterator[Callable[[Callable[[httpx.Request], httpx.Response]], None]]:
    """Lets a test script the orchestrator's answers to the image server's reports."""
    outbox: Outbox = env.img.state.outbox
    real = outbox.http

    def install(handler: Callable[[httpx.Request], httpx.Response]) -> None:
        outbox.http = httpx.AsyncClient(transport=httpx.MockTransport(handler))

    yield install
    outbox.http = real


def error_response(status: int, code: str) -> httpx.Response:
    return httpx.Response(status, content=pb.Error(code=code).SerializeToString())


async def test_repeated_chunks_are_harmless(session: Session) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(1, (300, 200)))
    parts = chunks(data, 1000)
    assert len(parts) > 2
    for i, part in enumerate(parts):
        await put(session.member, ann, i, part)
    await put(session.member, ann, 0, parts[0])
    st = await session.member.upload_status(ann.upload_url, ann.upload_token)
    assert list(st.received_chunks) == list(range(len(parts))) and st.received_bytes == len(data)
    code, resp = await session.member.complete(ann.upload_url, ann.upload_token, len(parts))
    assert code == 200 and resp and resp.state == "STORED"
    # complete is idempotent: the same answer again
    again = await session.member.complete(ann.upload_url, ann.upload_token, len(parts))
    assert again[1] == resp
    with pytest.raises(ClientError) as e:
        await put(session.member, ann, 0, parts[0])
    assert e.value.code == "UPLOAD_FINALIZED"


async def test_crc_mismatch_is_retryable(session: Session) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(2))
    with pytest.raises(ClientError) as e:
        await session.member.put_chunk(ann.upload_url, ann.upload_token, 0, data, crc32c_b64(b"other"))
    assert e.value.status == 400 and e.value.retryable


async def test_more_bytes_than_announced(session: Session) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(3))
    with pytest.raises(ClientError) as e:
        await put(session.member, ann, 0, data + b"extra")
    assert e.value.code == "TOO_LARGE"


async def test_complete_lists_missing_chunks(session: Session) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(4, (200, 200)))
    parts = chunks(data, 500)
    for i in (0, 2):
        await put(session.member, ann, i, parts[i])
    with pytest.raises(ClientError) as e:
        await session.member.complete(ann.upload_url, ann.upload_token, len(parts))
    assert e.value.code == "INVALID_REQUEST"
    assert list(e.value.error.missing_chunks) == [i for i in range(len(parts)) if i not in (0, 2)]


async def test_hash_mismatch_is_rejected_and_never_announced(session: Session, env: Stack) -> None:
    real, other = await build(session.member, session.sid, jpeg(5)), await build(session.member, session.sid, jpeg(6))
    # Other bytes under the real file's hash, size and photo tag
    sent = other.file[:len(real.file)].ljust(len(real.file), b"\0")
    res = await upload_file(session.member, session.sid, sent, clock.now_ms(), real.tag, real.epoch,
                            announced_sha=real.sha)
    assert res.outcome == "HASH_MISMATCH"
    assert not any(env.settings.storage_root.joinpath("photos").rglob("file"))
    snap = await session.host.snapshot(session.sid)
    assert not snap.live_photos

    async def released() -> bool:
        return int(await env.redis.hget(K.session(session.sid), "reserved_bytes")) == 0

    await eventually(released)
    member = await env.redis.hget(K.members(session.sid), session.member.uid)
    assert '"strikes":1' in member
    # The real photo can still be uploaded: neither its hash nor its tag stays reserved
    assert (await upload_bytes(session.host, session.sid, real.original, "image/jpeg",
                               clock.now_ms())).outcome == "STORED"


async def test_repeated_complete_after_rejection_returns_the_same_422(session: Session) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(8), claimed=hashlib.sha256(b"x").digest())
    await put(session.member, ann, 0, data)
    for _ in range(2):
        with pytest.raises(ClientError) as e:
            await session.member.complete(ann.upload_url, ann.upload_token, 1)
        assert (e.value.status, e.value.code) == (422, "HASH_MISMATCH")


@pytest.mark.parametrize("data_factory, mime", [
    (lambda: jpeg(9, (256, 256))[:700], "image/jpeg"),  # truncated
    (lambda: _png(), "image/jpeg"),  # a PNG declared as JPEG
    (lambda: b"not an image at all", ""),
])
async def test_invalid_images_are_refused_by_the_client(
        session: Session, env: Stack, data_factory: Callable[[], bytes], mime: str) -> None:
    """The server can't see inside an encrypted file, so the uploader's client screens its own photo: nothing is
    announced."""
    res = await upload_bytes(session.member, session.sid, data_factory(), mime, clock.now_ms())
    assert (res.outcome, res.sha256) == ("INVALID_IMAGE", "")
    assert await env.redis.hlen(K.photos(session.sid)) == 0


async def test_unsupported_type_is_refused_by_the_client(session: Session, env: Stack) -> None:
    res = await upload_bytes(session.member, session.sid, jpeg(9), "image/gif", clock.now_ms())
    assert res.outcome == "UNSUPPORTED_FORMAT"
    assert await env.redis.hlen(K.photos(session.sid)) == 0


def _bad_magic(f: bytes) -> bytes:
    return b"PHE9" + f[4:]


def _bad_version(f: bytes) -> bytes:
    return f[:4] + b"\x02" + f[5:]


@pytest.mark.parametrize("mutate", [
    lambda f: f[:-10],  # truncated: the header's lengths add up to more than the file
    lambda f: f + b"\0" * 10,  # extended: trailing bytes no section accounts for
    _bad_magic,
    _bad_version,
    lambda f: jpeg(40),  # a plaintext photo announced as encrypted
])
async def test_files_without_a_valid_header_are_rejected(
        session: Session, env: Stack, mutate: Callable[[bytes], bytes]) -> None:
    """The hash matches (it is of the bytes sent), but the plaintext header doesn't describe them: 422, and the
    same answer on every repeat."""
    built = await build(session.member, session.sid, jpeg(41))
    file = mutate(built.file)
    ann = await announce_raw(session.member, session.sid, file)
    await put(session.member, ann, 0, file)
    for _ in range(2):
        with pytest.raises(ClientError) as e:
            await session.member.complete(ann.upload_url, ann.upload_token, 1)
        assert (e.value.status, e.value.code) == (422, "INVALID_IMAGE")
    assert not env.img.state.store.staged_dir(ann.upload_id).exists()
    assert not any(env.settings.storage_root.joinpath("photos").rglob("file"))


def _png() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (16, 16)).save(buf, "PNG")
    return buf.getvalue()


async def test_report_retried_until_answered_then_promoted_once(
        session: Session, env: Stack, orchestrator_answers: Callable[..., None]) -> None:
    """The orchestrator is unreachable after the file is staged (a crash or outage): complete says 202, the outbox
    keeps the record, and once it gets through the photo is promoted exactly once."""
    calls = {"n": 0}

    def unreachable(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        raise httpx.ConnectError("down")

    orchestrator_answers(unreachable)
    env.settings.complete_report_wait_s = 0.5
    ann, data = await announce(session.member, session.sid, jpeg(10))
    await put(session.member, ann, 0, data)
    code, _ = await session.member.complete(ann.upload_url, ann.upload_token, 1)
    assert code == 202
    outbox: Outbox = env.img.state.outbox
    assert outbox.pending(ann.upload_id) and calls["n"] >= 1
    # "restart": a fresh outbox loads the record from disk and sends it through the real orchestrator
    await outbox.stop()
    fresh = Outbox(env.settings, outbox.store, outbox.tokens)
    fresh.on_final = outbox.on_final
    env.img.state.pipeline.outbox = fresh
    env.img.state.outbox = fresh
    fresh.start()
    try:
        async def stored() -> bool:
            return not fresh.pending(ann.upload_id)

        await eventually(stored)
        code, resp = await session.member.complete(ann.upload_url, ann.upload_token, 1)
        assert code == 200 and resp and resp.state == "STORED"
        sha = hashlib.sha256(data).hexdigest()
        assert (env.settings.storage_root / "photos" / session.sid / sha / "file").read_bytes() == data
        events = await env.redis.xrange(K.events(session.sid))
        assert sum(1 for _, f in events if f.get("t") == "PHOTO_STORED") == 1
    finally:
        env.img.state.outbox = outbox
        env.img.state.pipeline.outbox = outbox
        await fresh.stop()
        outbox.http = httpx.AsyncClient(timeout=10.0)
        outbox.start()


@pytest.mark.parametrize("status, code, state", [(409, "UPLOAD_SUPERSEDED", "SUPERSEDED"),
                                                 (410, "SESSION_ENDED", "ENDED")])
async def test_refused_reports_delete_only_that_upload(
        session: Session, env: Stack, orchestrator_answers: Callable[..., None],
        status: int, code: str, state: str) -> None:
    keep = await upload_bytes(session.host, session.sid, jpeg(11), "image/jpeg", clock.now_ms())
    assert keep.outcome == "STORED"
    orchestrator_answers(lambda request: error_response(status, code))
    ann, data = await announce(session.member, session.sid, jpeg(12))
    await put(session.member, ann, 0, data)
    with pytest.raises(ClientError) as e:
        await session.member.complete(ann.upload_url, ann.upload_token, 1)
    assert e.value.code == code
    tmp = env.settings.storage_root / "tmp" / ann.upload_id
    assert not (tmp / "staged").exists() and not (tmp / "chunks").exists()
    assert U.load(env.img.state.store, ann.upload_id).state == state  # type: ignore[union-attr]
    photos = list((env.settings.storage_root / "photos").rglob("file"))
    assert len(photos) == 1 and hashlib.sha256(photos[0].read_bytes()).hexdigest() == keep.sha256
    with pytest.raises(ClientError) as e:  # repeats answer the same
        await session.member.complete(ann.upload_url, ann.upload_token, 1)
    assert e.value.code == code


async def test_crash_during_assembly_asks_for_the_consumed_chunks(session: Session, env: Stack) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(13, (300, 300)))
    parts = chunks(data, 1000)
    for i, part in enumerate(parts):
        await put(session.member, ann, i, part)
    store = env.img.state.store
    st = U.load(store, ann.upload_id)
    assert st is not None
    (store.chunks_dir(ann.upload_id) / "0").unlink()  # consumed before the crash
    (store.upload_dir(ann.upload_id) / "assembled").write_bytes(parts[0])
    st.state = U.VERIFYING
    U.save(store, st)
    with pytest.raises(ClientError) as e:
        await session.member.complete(ann.upload_url, ann.upload_token, len(parts))
    assert list(e.value.error.missing_chunks) == [0]
    await put(session.member, ann, 0, parts[0])
    code, _ = await session.member.complete(ann.upload_url, ann.upload_token, len(parts))
    assert code == 200


async def test_disk_budget_gives_busy(session: Session, env: Stack) -> None:
    env.settings.tmp_disk_budget_bytes = 100
    ann, data = await announce(session.member, session.sid, jpeg(16))
    with pytest.raises(ClientError) as e:
        await put(session.member, ann, 0, data)
    assert (e.value.status, e.value.code, e.value.retryable) == (503, "BUSY", True)


async def test_ended_session_refuses_chunks(session: Session) -> None:
    ann, data = await announce(session.member, session.sid, jpeg(17))
    await session.host.end(session.sid)
    with pytest.raises(ClientError) as e:
        await put(session.member, ann, 0, data)
    assert e.value.code in ("SESSION_ENDED", "UPLOAD_EXPIRED")


async def test_token_is_bound_to_its_upload(session: Session) -> None:
    ann_a, a = await announce(session.member, session.sid, jpeg(18))
    ann_b, b = await announce(session.member, session.sid, jpeg(19))
    with pytest.raises(ClientError) as e:
        await session.member.put_chunk(ann_b.upload_url, ann_a.upload_token, 0, b, crc32c_b64(b))
    assert e.value.code == "UNKNOWN_UPLOAD"
    st = await session.member.upload_status(ann_a.upload_url, ann_a.upload_token)
    assert st.state == "RECEIVING" and not st.received_chunks
    clock.advance(11 * 60 * 1000)
    with pytest.raises(ClientError) as e:
        await put(session.member, ann_a, 0, a)
    assert e.value.code == "TOKEN_EXPIRED"
