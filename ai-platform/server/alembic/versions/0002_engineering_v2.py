"""engineering runtime v2

Revision ID: 0002_engineering_v2
Revises: 0001_initial
"""
from alembic import op
import sqlalchemy as sa

revision = "0002_engineering_v2"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table("engineering_projects",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
        sa.Column("name",sa.String(160),nullable=False), sa.Column("description",sa.Text()), sa.Column("project_type",sa.String(32),nullable=False),
        sa.Column("workspace_key",sa.String(96),nullable=False), sa.Column("status",sa.String(32),nullable=False), sa.Column("settings_json",sa.JSON(),nullable=False),
        sa.Column("created_at",sa.DateTime(timezone=True),nullable=False), sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False),
        sa.UniqueConstraint("user_id","workspace_key",name="uq_engineering_project_workspace"))
    for c in ("user_id","project_type","workspace_key","status","created_at"): op.create_index(f"ix_engineering_projects_{c}","engineering_projects",[c])
    op.create_table("engineering_runs",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("project_id",sa.Uuid(),sa.ForeignKey("engineering_projects.id",ondelete="CASCADE"),nullable=False),
        sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False), sa.Column("goal",sa.Text(),nullable=False),
        sa.Column("status",sa.String(32),nullable=False), sa.Column("stage",sa.String(64),nullable=False), sa.Column("progress",sa.Integer(),nullable=False),
        sa.Column("plan_json",sa.JSON(),nullable=False), sa.Column("quality_json",sa.JSON(),nullable=False), sa.Column("repair_attempts",sa.Integer(),nullable=False),
        sa.Column("error",sa.Text()),sa.Column("cancel_requested",sa.Boolean(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),
        sa.Column("started_at",sa.DateTime(timezone=True)),sa.Column("finished_at",sa.DateTime(timezone=True)))
    for c in ("project_id","user_id","status","created_at"): op.create_index(f"ix_engineering_runs_{c}","engineering_runs",[c])
    op.create_table("engineering_tasks",
        sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("run_id",sa.Uuid(),sa.ForeignKey("engineering_runs.id",ondelete="CASCADE"),nullable=False),
        sa.Column("role",sa.String(48),nullable=False),sa.Column("title",sa.String(200),nullable=False),sa.Column("description",sa.Text(),nullable=False),
        sa.Column("status",sa.String(32),nullable=False),sa.Column("sequence",sa.Integer(),nullable=False),sa.Column("depends_on_json",sa.JSON(),nullable=False),
        sa.Column("input_json",sa.JSON(),nullable=False),sa.Column("output_json",sa.JSON(),nullable=False),sa.Column("attempts",sa.Integer(),nullable=False),
        sa.Column("started_at",sa.DateTime(timezone=True)),sa.Column("finished_at",sa.DateTime(timezone=True)))
    for c in ("run_id","role","status","sequence"): op.create_index(f"ix_engineering_tasks_{c}","engineering_tasks",[c])
    op.create_table("engineering_events",
        sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("run_id",sa.Uuid(),sa.ForeignKey("engineering_runs.id",ondelete="CASCADE"),nullable=False),
        sa.Column("task_id",sa.Uuid(),sa.ForeignKey("engineering_tasks.id",ondelete="SET NULL")),sa.Column("level",sa.String(16),nullable=False),
        sa.Column("event_type",sa.String(64),nullable=False),sa.Column("message",sa.Text(),nullable=False),sa.Column("data_json",sa.JSON(),nullable=False),
        sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    for c in ("run_id","task_id","level","event_type","created_at"): op.create_index(f"ix_engineering_events_{c}","engineering_events",[c])
    op.create_table("engineering_checkpoints",
        sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("project_id",sa.Uuid(),sa.ForeignKey("engineering_projects.id",ondelete="CASCADE"),nullable=False),
        sa.Column("run_id",sa.Uuid(),sa.ForeignKey("engineering_runs.id",ondelete="SET NULL")),sa.Column("label",sa.String(160),nullable=False),
        sa.Column("git_commit",sa.String(64)),sa.Column("manifest_hash",sa.String(64),nullable=False),sa.Column("metadata_json",sa.JSON(),nullable=False),
        sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    for c in ("project_id","run_id","git_commit","created_at"): op.create_index(f"ix_engineering_checkpoints_{c}","engineering_checkpoints",[c])
    op.create_table("engineering_approvals",
        sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("run_id",sa.Uuid(),sa.ForeignKey("engineering_runs.id",ondelete="CASCADE"),nullable=False),
        sa.Column("task_id",sa.Uuid(),sa.ForeignKey("engineering_tasks.id",ondelete="SET NULL")),sa.Column("kind",sa.String(64),nullable=False),
        sa.Column("reason",sa.Text(),nullable=False),sa.Column("status",sa.String(24),nullable=False),sa.Column("requested_by_agent",sa.String(48)),
        sa.Column("decision_by_user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="SET NULL")),sa.Column("decision_note",sa.Text()),
        sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),sa.Column("decided_at",sa.DateTime(timezone=True)))
    for c in ("run_id","task_id","kind","status","created_at"): op.create_index(f"ix_engineering_approvals_{c}","engineering_approvals",[c])
    op.create_table("project_memory",
        sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("project_id",sa.Uuid(),sa.ForeignKey("engineering_projects.id",ondelete="CASCADE"),nullable=False),
        sa.Column("kind",sa.String(48),nullable=False),sa.Column("key",sa.String(200),nullable=False),sa.Column("content",sa.Text(),nullable=False),
        sa.Column("metadata_json",sa.JSON(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False),
        sa.UniqueConstraint("project_id","key",name="uq_project_memory_key"))
    op.create_index("ix_project_memory_project_id","project_memory",["project_id"]); op.create_index("ix_project_memory_kind","project_memory",["kind"])
    op.create_table("engineering_artifacts",
        sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("project_id",sa.Uuid(),sa.ForeignKey("engineering_projects.id",ondelete="CASCADE"),nullable=False),
        sa.Column("run_id",sa.Uuid(),sa.ForeignKey("engineering_runs.id",ondelete="SET NULL")),sa.Column("kind",sa.String(48),nullable=False),
        sa.Column("path",sa.Text(),nullable=False),sa.Column("sha256",sa.String(64),nullable=False),sa.Column("size_bytes",sa.Integer(),nullable=False),
        sa.Column("metadata_json",sa.JSON(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    for c in ("project_id","run_id","kind","sha256","created_at"): op.create_index(f"ix_engineering_artifacts_{c}","engineering_artifacts",[c])
    versions=sa.table("app_versions",sa.column("id",sa.Uuid),sa.column("platform",sa.String),sa.column("minimum_supported_version",sa.String),sa.column("latest_version",sa.String),sa.column("force_update",sa.Boolean),sa.column("release_notes",sa.Text),sa.column("download_url",sa.Text),sa.column("store_url",sa.Text),sa.column("created_at",sa.DateTime(timezone=True)))
    from datetime import UTC, datetime
    import uuid
    op.bulk_insert(versions,[{"id":uuid.uuid4(),"platform":"android","minimum_supported_version":"1.0.0","latest_version":"2.0.0","force_update":False,"release_notes":"Autonomous Engineering Runtime v2","download_url":None,"store_url":None,"created_at":datetime.now(UTC)}])


def downgrade():
    for table in ["engineering_artifacts","project_memory","engineering_approvals","engineering_checkpoints","engineering_events","engineering_tasks","engineering_runs","engineering_projects"]:
        op.drop_table(table)
