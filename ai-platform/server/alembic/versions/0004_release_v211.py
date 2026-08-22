"""release metadata for 2.1.1

Revision ID: 0004_release_v211
Revises: 0003_engineering_v21
"""
from alembic import op
import sqlalchemy as sa

revision = '0004_release_v211'
down_revision = '0003_engineering_v21'
branch_labels = None
depends_on = None

NOTES = (
    'Corrective release: archive import works through the gateway, the admin console loads, '
    'missing build toolchains no longer fail every run, and project import is available in the client.'
)


def upgrade():
    # UPDATE only touches an existing row; a fresh install has no android row yet, which is
    # why the insert branch exists. minimum_supported_version is deliberately left alone:
    # raising it locks out installed clients and must be an explicit operator decision.
    connection = op.get_bind()
    updated = connection.execute(sa.text(
        "UPDATE app_versions SET latest_version=:latest, release_notes=:notes WHERE platform='android'"
    ), {'latest': '2.1.1', 'notes': NOTES})
    if not updated.rowcount:
        connection.execute(sa.text(
            "INSERT INTO app_versions (id, platform, minimum_supported_version, latest_version, "
            "force_update, release_notes, created_at) "
            "VALUES (gen_random_uuid(), 'android', '2.0.0', :latest, false, :notes, now())"
        ), {'latest': '2.1.1', 'notes': NOTES})


def downgrade():
    op.execute(sa.text(
        "UPDATE app_versions SET latest_version='2.1.0' "
        "WHERE platform='android' AND latest_version='2.1.1'"
    ))
