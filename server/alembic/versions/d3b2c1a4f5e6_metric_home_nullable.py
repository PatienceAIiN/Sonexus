"""Allow global metrics (metrics.home_id nullable) — model training accuracy.

Revision ID: d3b2c1a4f5e6
Revises: c2a1b3d4e5f6
"""
import sqlalchemy as sa
from alembic import op

revision = "d3b2c1a4f5e6"
down_revision = "c2a1b3d4e5f6"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("metrics") as batch:
        batch.alter_column("home_id", existing_type=sa.Integer(), nullable=True)


def downgrade() -> None:
    with op.batch_alter_table("metrics") as batch:
        batch.alter_column("home_id", existing_type=sa.Integer(), nullable=False)
