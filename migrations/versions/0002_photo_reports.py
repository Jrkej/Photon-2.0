"""Photo reports from members (the hook for the takedown process).

Revision ID: 0002_photo_reports
Revises: 0001_accounts
"""

import sqlalchemy as sa
from alembic import op

revision = "0002_photo_reports"
down_revision = "0001_accounts"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "photo_reports",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("session_id", sa.Text, nullable=False),
        sa.Column("sha256", sa.Text, nullable=False),
        sa.Column("reporter_id", sa.Text, nullable=False),
        sa.Column("uploader_id", sa.Text, nullable=False),
        sa.Column("reason", sa.Text, nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()),
    )


def downgrade() -> None:
    op.drop_table("photo_reports")
