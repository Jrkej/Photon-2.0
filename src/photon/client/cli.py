"""photon-cli: the script client. Drives every server path before (and without) a phone app.

    photon-cli --profile alice login                 # dev provider
    photon-cli --profile alice create
    photon-cli --profile bob join ABCD2345
    photon-cli --profile alice upload IMG_0001.jpg
    photon-cli --profile bob listen --save-dir ./received
    photon-cli --profile bob keys                    # security codes, held epochs

Photos are end-to-end encrypted (e2ee.md): each profile keeps its identity key and session keys in
$PHOTON_HOME/<profile>.keys.json (mode 600), and the server stores only encrypted files.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import mimetypes
import os
import sys
import time
from pathlib import Path
from typing import Any

from photon.client.api import ClientError, PhotonClient
from photon.client.keyring import Keyring
from photon.client.listener import Listener
from photon.client.uploader import upload_bytes
from photon.common import e2ee
from photon.common.pb import photon_pb2 as pb

HOME = Path(os.environ.get("PHOTON_HOME", Path.home() / ".photon"))
MIMES = {".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".webp": "image/webp",
         ".heic": "image/heic", ".heif": "image/heif"}


def state_file(profile: str) -> Path:
    return HOME / f"{profile}.session.json"


def load_state(profile: str) -> dict[str, Any]:
    p = state_file(profile)
    return json.loads(p.read_text()) if p.exists() else {}


def save_state(profile: str, **values: Any) -> None:
    HOME.mkdir(parents=True, exist_ok=True)
    state = {**load_state(profile), **values}
    state_file(profile).write_text(json.dumps(state))


def current_sid(args: argparse.Namespace) -> str:
    sid = args.session or load_state(args.profile).get("sid")
    if not sid:
        sys.exit("no session: create or join one first (or pass --session)")
    return str(sid)


def show(info: pb.SessionInfo) -> None:
    print(f"session  {info.session_id}\nrole     {info.role}\ncode     {info.join_code}\n"
          f"invite   {info.invite_url}\nmembers  {', '.join(info.member_ids)}")


def describe(ev: pb.PhotoEvent) -> str:
    kind = pb.PhotoEvent.Type.Name(ev.type)
    if ev.sha256:
        return f"{ev.event_id or '(snapshot)'} {kind} {ev.sha256.hex()[:16]} by {ev.uploader_id} ({ev.size} B)"
    if ev.type in (pb.PhotoEvent.KEY_SHARED, pb.PhotoEvent.KEY_ROTATED):
        return f"{ev.event_id} {kind} {ev.member_id} epoch {ev.key_epoch}"
    return f"{ev.event_id} {kind} {ev.member_id}"


EXT = {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp", "image/heic": ".heic", "image/heif": ".heif"}


def pick(photo: e2ee.Photo, size: str) -> tuple[bytes, str]:
    """The decrypted bytes for `size`, and a file extension for them."""
    if size == "thumb" and photo.thumb:
        return photo.thumb, ".webp"
    if size == "preview" and photo.preview:
        return photo.preview, ".webp"
    return photo.original or b"", EXT.get(str(photo.meta.get("mime")), ".bin")


async def wait_for_key(c: PhotonClient, sid: str, seconds: float) -> None:
    deadline = time.monotonic() + seconds
    while True:
        state = await c.sync_keys(sid)
        if c.keys.has(sid, state.epoch):
            print(f"key      epoch {state.epoch} received")
            return
        if time.monotonic() >= deadline:
            print("key      waiting for a member to share the session key (run `keys` or `listen` later)")
            return
        await asyncio.sleep(1.0)


async def run(args: argparse.Namespace) -> int:
    client = PhotonClient(args.api, args.image, token_file=HOME / f"{args.profile}.json",
                          keyring=Keyring(HOME / f"{args.profile}.keys.json"))
    try:
        return await dispatch(client, args)
    except ClientError as e:
        print(f"error: {e.status} {e.code}: {e.error.message}", file=sys.stderr)
        return 1
    finally:
        await client.aclose()


async def dispatch(c: PhotonClient, args: argparse.Namespace) -> int:
    cmd = args.cmd
    if cmd != "login" and not c.tokens.access_token:
        print(f"not logged in: run photon-cli --profile {args.profile} login", file=sys.stderr)
        return 2
    if cmd == "login":
        tokens = await c.login_dev(args.user or args.profile)
        print(f"logged in as {tokens.user_id}")
    elif cmd == "create":
        info = await c.create_session(args.max_members)
        save_state(args.profile, sid=info.session_id)
        show(info)
    elif cmd == "join":
        target = args.code_or_link
        info = await (c.join(invite_token=target.rstrip("/").rsplit("/", 1)[1]) if "/j/" in target
                      else c.join(code=target))
        save_state(args.profile, sid=info.session_id)
        show(info)
        await wait_for_key(c, info.session_id, args.wait)
    elif cmd == "keys":
        sid = current_sid(args)
        state = await c.sync_keys(sid)
        held = sorted(c.keys.bundles(sid))
        print(f"you      {c.uid}  {e2ee.security_code(c.keys.member_key)}\nepoch    {state.epoch}"
              f"{'  (rotation required)' if state.rotation_required else ''}\nheld     {held or 'none yet'}")
        for m in state.members:
            print(f"member   {m.member_id}  {e2ee.security_code(m.member_key)}  epoch {m.key_epoch}")
    elif cmd == "info":
        show(await c.session(current_sid(args)))
    elif cmd == "leave":
        await c.leave(current_sid(args))
        save_state(args.profile, sid=None)
    elif cmd == "end":
        await c.end(current_sid(args))
        save_state(args.profile, sid=None)
    elif cmd == "kick":
        await c.kick(current_sid(args), args.member_id)
    elif cmd == "rotate":
        show(await c.rotate_invites(current_sid(args)))
    elif cmd == "upload":
        sid = current_sid(args)
        failed = 0
        for path in args.files:
            p = Path(path)
            mime = args.mime or MIMES.get(p.suffix.lower()) or mimetypes.guess_type(p.name)[0] or ""
            taken = int(p.stat().st_mtime * 1000)
            res = await upload_bytes(c, sid, p.read_bytes(), mime, taken)
            print(f"{p.name}: {res.outcome} {res.sha256[:16]}")
            failed += res.outcome not in ("STORED", "ALREADY_UPLOADED", "IN_PROGRESS")
        return 1 if failed else 0
    elif cmd == "listen":
        sid = current_sid(args)
        save_dir = Path(args.save_dir) if args.save_dir else None
        if save_dir:
            save_dir.mkdir(parents=True, exist_ok=True)

        waiting: list[pb.PhotoEvent] = []  # photos that arrived before this member had their epoch's key

        async def save(ev: pb.PhotoEvent) -> None:
            assert save_dir is not None
            try:
                photo = await c.download_photo(sid, ev.sha256.hex(), args.size, ev.photo_tag)
            except (e2ee.E2eeError, ClientError) as e:
                if not c.keys.has(sid, ev.key_epoch):
                    print(f"  waiting for the epoch {ev.key_epoch} key", flush=True)
                    waiting.append(ev)
                else:
                    print(f"  not saved: {e}", flush=True)
                return
            data, ext = pick(photo, args.size)
            (save_dir / f"{ev.sha256.hex()[:16]}{ext}").write_bytes(data)

        async def on_event(ev: pb.PhotoEvent) -> None:
            print(describe(ev), flush=True)
            if not save_dir:
                return
            if ev.type == pb.PhotoEvent.PHOTO_STORED and ev.uploader_id != c.uid:
                await save(ev)
            elif ev.type in (pb.PhotoEvent.KEY_SHARED, pb.PhotoEvent.KEY_ROTATED) and waiting:
                ready = [w for w in waiting if c.keys.has(sid, w.key_epoch)]
                for w in ready:
                    waiting.remove(w)
                    await save(w)

        why = await Listener(c, sid, on_event).run()
        print(f"stopped: {why}")
    elif cmd == "catchup":
        sid = current_sid(args)
        since = args.since or (await c.snapshot(sid)).cursor
        batch = await c.events_since(sid, since)
        for ev in batch.events:
            print(describe(ev))
        print(f"cursor {batch.cursor}")
    elif cmd == "snapshot":
        snap = await c.snapshot(current_sid(args))
        print(f"cursor {snap.cursor}\nmembers {', '.join(snap.member_ids)}")
        for ev in snap.live_photos:
            print(describe(ev))
        for h in snap.recalled_sha256:
            print(f"recalled {h.hex()[:16]}")
    elif cmd == "recall":
        await c.recall(current_sid(args), args.sha)
    elif cmd == "report":
        await c.report(current_sid(args), args.sha, args.reason)
    elif cmd == "download":
        sid = current_sid(args)
        if args.raw:
            data, ext = (await c.download(sid, args.sha, args.size)).content, ".phe"
        else:
            try:
                data, ext = pick(await c.download_photo(sid, args.sha, args.size), args.size)
            except e2ee.E2eeError as e:
                print(f"error: {e}", file=sys.stderr)
                return 1
        Path(args.output or f"{args.sha[:16]}{ext}").write_bytes(data)
    elif cmd == "device":
        await c.register_device(args.platform, args.token)
    return 0


def parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="photon-cli", description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--api", default=os.environ.get("PHOTON_API", "http://localhost:8080"))
    ap.add_argument("--image", default=os.environ.get("PHOTON_IMAGE", "http://localhost:8081"))
    ap.add_argument("--profile", default=os.environ.get("PHOTON_PROFILE", "default"))
    ap.add_argument("--session", help="session id (default: the last one created or joined)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("login").add_argument("--user", help="dev username (default: the profile name)")
    sub.add_parser("create").add_argument("--max-members", type=int, default=0)
    jn = sub.add_parser("join")
    jn.add_argument("code_or_link")
    jn.add_argument("--wait", type=float, default=15.0, help="seconds to wait for a member to share the key")
    for name in ("info", "leave", "end", "rotate", "snapshot", "keys"):
        sub.add_parser(name)
    sub.add_parser("kick").add_argument("member_id")
    up = sub.add_parser("upload")
    up.add_argument("files", nargs="+")
    up.add_argument("--mime")
    ls = sub.add_parser("listen")
    ls.add_argument("--save-dir")
    ls.add_argument("--size", choices=["thumb", "preview", "full"], default="full")
    sub.add_parser("catchup").add_argument("--since")
    sub.add_parser("recall").add_argument("sha")
    rp = sub.add_parser("report")
    rp.add_argument("sha")
    rp.add_argument("reason")
    dl = sub.add_parser("download")
    dl.add_argument("sha")
    dl.add_argument("--size", choices=["thumb", "preview", "full"], default="full")
    dl.add_argument("-o", "--output")
    dl.add_argument("--raw", action="store_true", help="save the encrypted file as served")
    dv = sub.add_parser("device")
    dv.add_argument("--platform", choices=["android", "ios"], required=True)
    dv.add_argument("--token", required=True)
    return ap


def main() -> None:
    args = parser().parse_args()
    try:
        sys.exit(asyncio.run(run(args)))
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
