"""Accounts: users, identities, devices. No email column: accounts are never merged by email.

Revision ID: 0001_accounts
Revises:
"""

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision = "0001_accounts"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "users",
        sa.Column("id", UUID(as_uuid=False), primary_key=True, server_default=sa.text("gen_random_uuid()")),
        sa.Column("display_name", sa.Text, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )
    op.create_table(
        "identities",
        sa.Column("provider", sa.Text, nullable=False),
        sa.Column("provider_user_id", sa.Text, nullable=False),
        sa.Column("user_id", UUID(as_uuid=False), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.PrimaryKeyConstraint("provider", "provider_user_id"),
    )
    op.create_table(
        "devices",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("user_id", UUID(as_uuid=False), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("platform", sa.Text, nullable=False),
        sa.Column("push_token", sa.Text, nullable=False, unique=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
        sa.CheckConstraint("platform IN ('android', 'ios')", name="devices_platform_check"),
    )
    op.create_index("ix_devices_user_id", "devices", ["user_id"])


def downgrade() -> None:
    op.drop_table("devices")
    op.drop_table("identities")
    op.drop_table("users")
