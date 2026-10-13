"""Alembic environment: async engine, URL from PHOTON_DATABASE_URL (or the -x url=... option)."""

from __future__ import annotations

import asyncio
import os

from alembic import context
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine

from photon.db import metadata

config = context.config
url = context.get_x_argument(as_dictionary=True).get("url") or config.get_main_option("sqlalchemy.url") \
    or os.environ.get("PHOTON_DATABASE_URL", "postgresql+asyncpg://photon@localhost/photon")


def run(connection: Connection) -> None:
    context.configure(connection=connection, target_metadata=metadata, compare_type=True)
    with context.begin_transaction():
        context.run_migrations()


async def run_online() -> None:
    engine = create_async_engine(url)
    async with engine.connect() as conn:
        await conn.run_sync(run)
    await engine.dispose()


if context.is_offline_mode():
    context.configure(url=url, target_metadata=metadata, literal_binds=True)
    with context.begin_transaction():
        context.run_migrations()
else:
    asyncio.run(run_online())
