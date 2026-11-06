"""This device's end-to-end keys (e2ee.md 3): one X25519 identity, and per session the epoch bundles it holds.

In memory by default. With a path, the keyring lives in a JSON file readable only by its owner, so the CLI keeps its
identity between runs. Two processes may share one file (`listen` in one terminal, `upload` in another): bundles never
change once made, so every save merges with what is on disk, and a lookup that misses reads the file again.
"""

from __future__ import annotations

import contextlib
import json
import os
from pathlib import Path
from typing import Any

from photon.common import e2ee


class Keyring:
    def __init__(self, path: Path | None = None) -> None:
        self.path = path
        self._sessions: dict[str, dict[int, e2ee.Bundle]] = {}
        raw = self._read()
        if raw.get("identity"):
            self.identity = e2ee.identity_from_bytes(bytes.fromhex(raw["identity"]))
            self._merge(raw)
        else:
            self.identity = e2ee.new_identity()
            self._save()

    @property
    def member_key(self) -> bytes:
        """The public key sent when creating or joining a session."""
        return e2ee.public_bytes(self.identity)

    def bundles(self, sid: str) -> dict[int, e2ee.Bundle]:
        return dict(self._sessions.get(sid, {}))

    def epoch_keys(self, sid: str) -> dict[int, bytes]:
        held = self.bundles(sid)
        return {epoch: b.key for epoch, b in held.items()}

    def latest(self, sid: str) -> e2ee.Bundle | None:
        held = self._sessions.get(sid)
        return held[max(held)] if held else None

    def has(self, sid: str, epoch: int) -> bool:
        if epoch not in self._sessions.get(sid, {}):
            self.reload()
        return epoch in self._sessions.get(sid, {})

    def add(self, sid: str, bundle: e2ee.Bundle) -> None:
        self._sessions.setdefault(sid, {})[bundle.epoch] = bundle
        self._save()

    def forget(self, sid: str) -> None:
        """Drops a session's keys after leaving or ending it."""
        self._sessions.pop(sid, None)
        if self.path:
            raw = self._read()
            raw.get("sessions", {}).pop(sid, None)
            self._write(raw.get("sessions", {}))

    def reload(self) -> None:
        if self.path:
            self._merge(self._read())

    # ------------------------------------------------------------------ file

    def _read(self) -> dict[str, Any]:
        if not self.path or not self.path.exists():
            return {}
        with contextlib.suppress(ValueError):
            data = json.loads(self.path.read_text())
            if isinstance(data, dict):
                return data
        return {}

    def _merge(self, raw: dict[str, Any]) -> None:
        for sid, epochs in raw.get("sessions", {}).items():
            held = self._sessions.setdefault(sid, {})
            for bundle_hex in epochs.values():
                with contextlib.suppress(ValueError, e2ee.E2eeError):
                    b = e2ee.Bundle.decode(bytes.fromhex(bundle_hex))
                    held.setdefault(b.epoch, b)

    def _save(self) -> None:
        if not self.path:
            return
        self._merge(self._read())
        sessions = {sid: {str(e): b.encode().hex() for e, b in held.items()} for sid, held in self._sessions.items()}
        self._write(sessions)

    def _write(self, sessions: dict[str, Any]) -> None:
        assert self.path is not None
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(f".{self.path.name}.{os.getpid()}.tmp")
        body = json.dumps({"v": 1, "identity": e2ee.private_bytes(self.identity).hex(), "sessions": sessions})
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(body)
        os.replace(tmp, self.path)
