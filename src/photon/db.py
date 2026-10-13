"""Postgres schema (accounts and reports). Alembic migrations in migrations/ create exactly these tables."""

from __future__ import annotations

from sqlalchemy import (
    BigInteger,
    CheckConstraint,
    Column,
    DateTime,
    ForeignKey,
    MetaData,
    PrimaryKeyConstraint,
    Table,
    Text,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

metadata = MetaData()

users = Table(
    "users",
    metadata,
    Column("id", UUID(as_uuid=False), primary_key=True, server_default=text("gen_random_uuid()")),
    Column("display_name", Text, nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
)

# No email column on purpose: accounts are never merged by email (an unverified email could take one over).
identities = Table(
    "identities",
    metadata,
    Column("provider", Text, nullable=False),
    Column("provider_user_id", Text, nullable=False),
    Column("user_id", UUID(as_uuid=False), ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    PrimaryKeyConstraint("provider", "provider_user_id"),
)

devices = Table(
    "devices",
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("user_id", UUID(as_uuid=False), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True),
    Column("platform", Text, nullable=False),
    Column("push_token", Text, nullable=False, unique=True),
    Column("updated_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
    CheckConstraint("platform IN ('android', 'ios')", name="devices_platform_check"),
)

photo_reports = Table(
    "photo_reports",
    metadata,
    Column("id", BigInteger, primary_key=True, autoincrement=True),
    Column("session_id", Text, nullable=False),
    Column("sha256", Text, nullable=False),
    Column("reporter_id", Text, nullable=False),
    Column("uploader_id", Text, nullable=False),
    Column("reason", Text, nullable=False),
    Column("created_at", DateTime(timezone=True), nullable=False, server_default=func.now()),
)


def make_engine(url: str) -> AsyncEngine:
    return create_async_engine(url, pool_size=10, max_overflow=10, pool_pre_ping=True)
