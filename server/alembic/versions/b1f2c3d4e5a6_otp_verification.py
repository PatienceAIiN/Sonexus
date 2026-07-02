"""Email OTP verification + instant session teardown.

Revision ID: b1f2c3d4e5a6
Revises: ecfd09cb0445
"""
import sqlalchemy as sa
from alembic import op

revision = "b1f2c3d4e5a6"
down_revision = "ecfd09cb0445"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Existing accounts were created before verification existed — keep them valid.
    op.add_column("users", sa.Column("is_verified", sa.Boolean(), nullable=False, server_default=sa.true()))
    op.add_column("users", sa.Column("token_version", sa.Integer(), nullable=False, server_default="0"))
    op.create_table(
        "otp_codes",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("email", sa.String(200), nullable=False, index=True),
        sa.Column("code_hash", sa.String(64), nullable=False),
        sa.Column("purpose", sa.String(20), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
    )


def downgrade() -> None:
    op.drop_table("otp_codes")
    op.drop_column("users", "token_version")
    op.drop_column("users", "is_verified")
