"""Stream entries -> PhotoEvent, and stream id helpers."""

from __future__ import annotations

from typing import Any

from photon.common.pb import photon_pb2 as pb

TYPES = {name: pb.PhotoEvent.Type.Value(name) for name in pb.PhotoEvent.Type.keys()}


def fields_of(raw: Any) -> dict[str, str]:
    """Stream fields arrive as a dict (redis-py XREAD) or a flat list (from a Lua function)."""
    if isinstance(raw, dict):
        return {str(k): str(v) for k, v in raw.items()}
    return {str(raw[i]): str(raw[i + 1]) for i in range(0, len(raw), 2)}


def entry_to_event(sid: str, entry_id: str, raw: Any) -> pb.PhotoEvent:
    f = fields_of(raw)
    ev = pb.PhotoEvent(event_id=entry_id, type=TYPES[f["t"]], session_id=sid)
    if "sha" in f:
        ev.sha256 = bytes.fromhex(f["sha"])
    if "up" in f:
        ev.uploader_id = f["up"]
    if "sz" in f:
        ev.size = int(float(f["sz"]))
    if "ta" in f:
        ev.taken_at_ms = int(float(f["ta"]))
    if "ex" in f:
        ev.expires_at_ms = int(float(f["ex"]))
    if "m" in f:
        ev.member_id = f["m"]
    if f.get("pk"):
        ev.member_key = bytes.fromhex(f["pk"])
    if f.get("tg"):
        ev.photo_tag = bytes.fromhex(f["tg"])
    if "ke" in f:
        ev.key_epoch = int(float(f["ke"]))
    return ev


def id_ms(entry_id: str) -> int:
    return int(entry_id.split("-", 1)[0])


def parse_id(value: str) -> tuple[int, int] | None:
    parts = value.split("-")
    if len(parts) != 2 or not all(p.isdigit() for p in parts):
        return None
    return int(parts[0]), int(parts[1])
