"""Cross-device user settings blob.

Revision ID: c2a1b3d4e5f6
Revises: b1f2c3d4e5a6
"""
import sqlalchemy as sa
from alembic import op

revision = "c2a1b3d4e5f6"
down_revision = "b1f2c3d4e5a6"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("users", sa.Column("settings", sa.JSON(), nullable=True))


def downgrade() -> None:
    op.drop_column("users", "settings")
