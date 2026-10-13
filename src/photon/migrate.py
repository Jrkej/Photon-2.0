"""Runs the Alembic migrations programmatically (deploy job, dev launcher, tests)."""

from __future__ import annotations

import os
from pathlib import Path

from alembic import command
from alembic.config import Config

# The repository root in development; PHOTON_APP_ROOT in the container image (where alembic.ini lives).
ROOT = Path(os.environ.get("PHOTON_APP_ROOT", Path(__file__).resolve().parents[2]))


def config(url: str) -> Config:
    cfg = Config(str(ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(ROOT / "migrations"))
    cfg.set_main_option("sqlalchemy.url", url)
    return cfg


def upgrade(url: str, revision: str = "head") -> None:
    command.upgrade(config(url), revision)


def downgrade(url: str, revision: str = "base") -> None:
    command.downgrade(config(url), revision)


if __name__ == "__main__":
    upgrade(os.environ["PHOTON_DATABASE_URL"])
