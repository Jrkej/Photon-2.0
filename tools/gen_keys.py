#!/usr/bin/env python3
"""Writes the Ed25519 key pairs PHOTON needs into a directory (default ./keys):

    auth-access       access JWTs (Auth signs; everyone verifies)
    orch-upload       upload tokens (orchestrator signs; image server verifies)
    svc-orchestrator  orchestrator -> image server calls
    svc-imageserver   image server -> orchestrator reports

Each purpose gets <purpose>.pem (private, mode 0600), <purpose>.pub.pem and <purpose>.kid. Mount each private key
only into the service that owns it. With --next, writes <purpose>.next.* for a rotation instead.
"""

from __future__ import annotations

import argparse
import datetime
import os
import secrets
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

PURPOSES = ("auth-access", "orch-upload", "svc-orchestrator", "svc-imageserver")


def write_pair(out: Path, purpose: str, variant: str = "") -> None:
    key = Ed25519PrivateKey.generate()
    kid = f"{purpose}-{datetime.date.today():%Y%m%d}-{secrets.token_hex(2)}"
    priv = out / f"{purpose}{variant}.pem"
    priv.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                       serialization.NoEncryption()))
    os.chmod(priv, 0o600)
    (out / f"{purpose}{variant}.pub.pem").write_bytes(key.public_key().public_bytes(
        serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo))
    (out / f"{purpose}{variant}.kid").write_text(kid + "\n")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out", nargs="?", default="keys")
    ap.add_argument("--next", action="store_true", help="write the next keys for a rotation")
    ap.add_argument("--force", action="store_true", help="overwrite existing keys")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    variant = ".next" if args.next else ""
    for purpose in PURPOSES:
        if (out / f"{purpose}{variant}.pem").exists() and not args.force:
            print(f"keep {purpose}{variant} (exists)")
            continue
        write_pair(out, purpose, variant)
        print(f"wrote {purpose}{variant}")


if __name__ == "__main__":
    main()
