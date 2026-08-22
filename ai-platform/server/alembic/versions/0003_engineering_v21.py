"""engineering runtime v2.1 release metadata

Revision ID: 0003_engineering_v21
Revises: 0002_engineering_v2
"""
from alembic import op
import sqlalchemy as sa

revision = '0003_engineering_v21'
down_revision = '0002_engineering_v2'
branch_labels = None
depends_on = None


def upgrade():
    op.execute(sa.text("UPDATE app_versions SET latest_version='2.1.0', release_notes='Autonomous Engineering Runtime v2.1: resumable approvals, parallel verification, hybrid code search and run diffs' WHERE platform='android'"))


def downgrade():
    op.execute(sa.text("UPDATE app_versions SET latest_version='2.0.0', release_notes='Autonomous Engineering Runtime v2' WHERE platform='android' AND latest_version='2.1.0'"))
