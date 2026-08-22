"""initial production schema

Revision ID: 0001_initial
Revises: None
"""
from alembic import op
import sqlalchemy as sa

revision="0001_initial"
down_revision=None
branch_labels=None
depends_on=None


def upgrade():
    op.create_table("users",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("email",sa.String(320),nullable=False),
        sa.Column("password_hash",sa.String(512),nullable=False), sa.Column("display_name",sa.String(120)),
        sa.Column("is_admin",sa.Boolean(),nullable=False), sa.Column("is_active",sa.Boolean(),nullable=False),
        sa.Column("email_verified_at",sa.DateTime(timezone=True)), sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),
        sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False), sa.Column("deleted_at",sa.DateTime(timezone=True)),
        sa.UniqueConstraint("email"))
    op.create_index("ix_users_email","users",["email"],unique=True)
    op.create_index("ix_users_is_admin","users",["is_admin"]); op.create_index("ix_users_is_active","users",["is_active"])
    op.create_table("devices",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("device_id",sa.String(128),nullable=False),
        sa.Column("installation_id",sa.String(128),nullable=False), sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
        sa.Column("platform",sa.String(32),nullable=False), sa.Column("device_name",sa.String(128)), sa.Column("app_version",sa.String(32)),
        sa.Column("os_version",sa.String(64)), sa.Column("push_token",sa.Text()), sa.Column("trusted",sa.Boolean(),nullable=False),
        sa.Column("last_seen",sa.DateTime(timezone=True),nullable=False), sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),
        sa.Column("revoked_at",sa.DateTime(timezone=True)), sa.UniqueConstraint("user_id","installation_id",name="uq_device_user_installation"))
    op.create_index("ix_devices_user_id","devices",["user_id"]); op.create_index("ix_devices_device_id","devices",["device_id"])
    op.create_index("ix_devices_revoked_at","devices",["revoked_at"])
    op.create_table("sessions",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
        sa.Column("device_id",sa.Uuid(),sa.ForeignKey("devices.id",ondelete="SET NULL")), sa.Column("ip_address",sa.String(64)),
        sa.Column("user_agent",sa.String(512)), sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),
        sa.Column("last_seen",sa.DateTime(timezone=True),nullable=False), sa.Column("revoked_at",sa.DateTime(timezone=True)))
    op.create_index("ix_sessions_user_id","sessions",["user_id"]); op.create_index("ix_sessions_device_id","sessions",["device_id"]); op.create_index("ix_sessions_revoked_at","sessions",["revoked_at"])
    op.create_table("refresh_tokens",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("session_id",sa.Uuid(),sa.ForeignKey("sessions.id",ondelete="CASCADE"),nullable=False),
        sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False), sa.Column("device_id",sa.Uuid(),sa.ForeignKey("devices.id",ondelete="SET NULL")),
        sa.Column("token_hash",sa.String(64),nullable=False), sa.Column("family_id",sa.Uuid(),nullable=False),
        sa.Column("parent_id",sa.Uuid(),sa.ForeignKey("refresh_tokens.id",ondelete="SET NULL")), sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),
        sa.Column("expires_at",sa.DateTime(timezone=True),nullable=False), sa.Column("used_at",sa.DateTime(timezone=True)),
        sa.Column("revoked_at",sa.DateTime(timezone=True)), sa.Column("revoke_reason",sa.String(128)), sa.UniqueConstraint("token_hash"))
    op.create_index("ix_refresh_tokens_token_hash","refresh_tokens",["token_hash"],unique=True); op.create_index("ix_refresh_tokens_family_id","refresh_tokens",["family_id"])
    op.create_index("ix_refresh_user_active","refresh_tokens",["user_id","revoked_at","expires_at"])
    for name in ("password_reset_tokens","email_verification_tokens"):
        op.create_table(name, sa.Column("id",sa.Uuid(),primary_key=True),
            sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
            sa.Column("token_hash",sa.String(64),nullable=False,unique=True), sa.Column("expires_at",sa.DateTime(timezone=True),nullable=False),
            sa.Column("used_at",sa.DateTime(timezone=True)), sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
        op.create_index(f"ix_{name}_user_id",name,["user_id"]); op.create_index(f"ix_{name}_token_hash",name,["token_hash"],unique=True); op.create_index(f"ix_{name}_expires_at",name,["expires_at"])
    op.create_table("ai_requests",
        sa.Column("id",sa.Uuid(),primary_key=True), sa.Column("request_id",sa.String(64),nullable=False),
        sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False), sa.Column("device_id",sa.Uuid(),sa.ForeignKey("devices.id",ondelete="SET NULL")),
        sa.Column("model",sa.String(128),nullable=False), sa.Column("prompt_hash",sa.String(64),nullable=False), sa.Column("prompt_encrypted",sa.Text()),
        sa.Column("latency_ms",sa.Integer(),nullable=False), sa.Column("prompt_tokens",sa.Integer(),nullable=False), sa.Column("completion_tokens",sa.Integer(),nullable=False),
        sa.Column("total_tokens",sa.Integer(),nullable=False), sa.Column("status",sa.String(32),nullable=False), sa.Column("error_type",sa.String(128)),
        sa.Column("cache_hit",sa.Boolean(),nullable=False), sa.Column("response_size",sa.Integer(),nullable=False), sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    for col in ("request_id","user_id","device_id","model","prompt_hash","status","created_at"): op.create_index(f"ix_ai_requests_{col}","ai_requests",[col])
    op.create_table("ai_usage_daily",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
        sa.Column("usage_date",sa.Date(),nullable=False),sa.Column("requests",sa.Integer(),nullable=False),sa.Column("prompt_tokens",sa.Integer(),nullable=False),
        sa.Column("completion_tokens",sa.Integer(),nullable=False),sa.Column("total_tokens",sa.Integer(),nullable=False),sa.UniqueConstraint("user_id","usage_date",name="uq_ai_usage_user_date"))
    op.create_index("ix_ai_usage_daily_user_id","ai_usage_daily",["user_id"]); op.create_index("ix_ai_usage_daily_usage_date","ai_usage_daily",["usage_date"])
    op.create_table("ai_quota",sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),primary_key=True),sa.Column("requests_per_minute",sa.Integer(),nullable=False),
        sa.Column("requests_per_day",sa.Integer(),nullable=False),sa.Column("tokens_per_day",sa.Integer(),nullable=False),sa.Column("max_output_tokens",sa.Integer(),nullable=False),sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False))
    op.create_table("audit_logs",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("actor_user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="SET NULL")),
        sa.Column("action",sa.String(128),nullable=False),sa.Column("target_type",sa.String(64)),sa.Column("target_id",sa.String(128)),sa.Column("ip_address",sa.String(64)),
        sa.Column("device",sa.String(128)),sa.Column("request_id",sa.String(64)),sa.Column("metadata_json",sa.JSON(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    for col in ("actor_user_id","action","target_id","request_id","created_at"): op.create_index(f"ix_audit_logs_{col}","audit_logs",[col])
    op.create_table("security_events",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="SET NULL")),
        sa.Column("event_type",sa.String(128),nullable=False),sa.Column("severity",sa.String(16),nullable=False),sa.Column("ip_address",sa.String(64)),sa.Column("request_id",sa.String(64)),
        sa.Column("metadata_json",sa.JSON(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    for col in ("user_id","event_type","severity","request_id","created_at"): op.create_index(f"ix_security_events_{col}","security_events",[col])
    op.create_table("feature_flags",sa.Column("key",sa.String(100),primary_key=True),sa.Column("enabled",sa.Boolean(),nullable=False),sa.Column("rollout_percentage",sa.Integer(),nullable=False),
        sa.Column("description",sa.String(500)),sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False))
    op.create_table("user_feature_flags",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
        sa.Column("flag_key",sa.String(100),sa.ForeignKey("feature_flags.key",ondelete="CASCADE"),nullable=False),sa.Column("enabled",sa.Boolean(),nullable=False),sa.UniqueConstraint("user_id","flag_key",name="uq_user_feature_flag"))
    op.create_index("ix_user_feature_flags_user_id","user_feature_flags",["user_id"]); op.create_index("ix_user_feature_flags_flag_key","user_feature_flags",["flag_key"])
    op.create_table("notifications",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
        sa.Column("title",sa.String(200),nullable=False),sa.Column("body",sa.Text(),nullable=False),sa.Column("kind",sa.String(64),nullable=False),sa.Column("data_json",sa.JSON(),nullable=False),
        sa.Column("read_at",sa.DateTime(timezone=True)),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_notifications_user_id","notifications",["user_id"]); op.create_index("ix_notifications_read_at","notifications",["read_at"]); op.create_index("ix_notifications_created_at","notifications",["created_at"])
    op.create_table("system_settings",sa.Column("key",sa.String(128),primary_key=True),sa.Column("value_json",sa.JSON(),nullable=False),sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False))
    op.create_table("jobs",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("kind",sa.String(64),nullable=False),sa.Column("status",sa.String(32),nullable=False),sa.Column("payload_json",sa.JSON(),nullable=False),
        sa.Column("attempts",sa.Integer(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False),sa.Column("updated_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_jobs_kind","jobs",["kind"]);op.create_index("ix_jobs_status","jobs",["status"]);op.create_index("ix_jobs_created_at","jobs",["created_at"])
    op.create_table("job_results",sa.Column("job_id",sa.Uuid(),sa.ForeignKey("jobs.id",ondelete="CASCADE"),primary_key=True),sa.Column("result_json",sa.JSON(),nullable=False),sa.Column("error",sa.Text()),sa.Column("finished_at",sa.DateTime(timezone=True),nullable=False))
    op.create_table("subscriptions",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),sa.Column("plan",sa.String(32),nullable=False),
        sa.Column("status",sa.String(32),nullable=False),sa.Column("provider",sa.String(64),nullable=False),sa.Column("provider_reference",sa.String(200),unique=True),sa.Column("current_period_end",sa.DateTime(timezone=True)),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_subscriptions_user_id","subscriptions",["user_id"]);op.create_index("ix_subscriptions_plan","subscriptions",["plan"]);op.create_index("ix_subscriptions_status","subscriptions",["status"])
    op.create_table("payments",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),sa.Column("provider",sa.String(64),nullable=False),
        sa.Column("provider_reference",sa.String(200),unique=True),sa.Column("amount_minor",sa.Integer(),nullable=False),sa.Column("currency",sa.String(3),nullable=False),sa.Column("status",sa.String(32),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_payments_user_id","payments",["user_id"]);op.create_index("ix_payments_status","payments",["status"])
    op.create_table("webhooks",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("provider",sa.String(64),nullable=False),sa.Column("event_id",sa.String(200),nullable=False,unique=True),sa.Column("signature_valid",sa.Boolean(),nullable=False),
        sa.Column("payload_hash",sa.String(64),nullable=False),sa.Column("processed_at",sa.DateTime(timezone=True)),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_webhooks_provider","webhooks",["provider"]);op.create_index("ix_webhooks_event_id","webhooks",["event_id"],unique=True)
    op.create_table("app_versions",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("platform",sa.String(32),nullable=False),sa.Column("minimum_supported_version",sa.String(32),nullable=False),
        sa.Column("latest_version",sa.String(32),nullable=False),sa.Column("force_update",sa.Boolean(),nullable=False),sa.Column("release_notes",sa.Text()),sa.Column("download_url",sa.Text()),sa.Column("store_url",sa.Text()),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_app_versions_platform","app_versions",["platform"])
    op.create_table("admin_actions",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("admin_user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="RESTRICT"),nullable=False),sa.Column("action",sa.String(128),nullable=False),
        sa.Column("target_type",sa.String(64)),sa.Column("target_id",sa.String(128)),sa.Column("metadata_json",sa.JSON(),nullable=False),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_admin_actions_admin_user_id","admin_actions",["admin_user_id"]);op.create_index("ix_admin_actions_action","admin_actions",["action"]);op.create_index("ix_admin_actions_created_at","admin_actions",["created_at"])
    op.create_table("user_api_keys",sa.Column("id",sa.Uuid(),primary_key=True),sa.Column("user_id",sa.Uuid(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),sa.Column("name",sa.String(100),nullable=False),
        sa.Column("prefix",sa.String(16),nullable=False),sa.Column("key_hash",sa.String(64),nullable=False,unique=True),sa.Column("scopes_json",sa.JSON(),nullable=False),sa.Column("expires_at",sa.DateTime(timezone=True)),sa.Column("revoked_at",sa.DateTime(timezone=True)),sa.Column("created_at",sa.DateTime(timezone=True),nullable=False))
    op.create_index("ix_user_api_keys_user_id","user_api_keys",["user_id"]);op.create_index("ix_user_api_keys_prefix","user_api_keys",["prefix"]);op.create_index("ix_user_api_keys_key_hash","user_api_keys",["key_hash"],unique=True)
    flags = sa.table("feature_flags", sa.column("key",sa.String), sa.column("enabled",sa.Boolean), sa.column("rollout_percentage",sa.Integer), sa.column("description",sa.String), sa.column("updated_at",sa.DateTime(timezone=True)))
    from datetime import UTC, datetime
    now=datetime.now(UTC)
    op.bulk_insert(flags,[
        {"key":"new_ai_engine","enabled":False,"rollout_percentage":0,"description":"New AI engine rollout","updated_at":now},
        {"key":"new_dashboard","enabled":False,"rollout_percentage":0,"description":"New dashboard rollout","updated_at":now},
        {"key":"maintenance_mode","enabled":False,"rollout_percentage":0,"description":"Maintenance feature flag","updated_at":now},
        {"key":"beta_features","enabled":False,"rollout_percentage":0,"description":"Beta features","updated_at":now},
    ])
    versions = sa.table("app_versions", sa.column("id",sa.Uuid), sa.column("platform",sa.String), sa.column("minimum_supported_version",sa.String), sa.column("latest_version",sa.String), sa.column("force_update",sa.Boolean), sa.column("release_notes",sa.Text), sa.column("download_url",sa.Text), sa.column("store_url",sa.Text), sa.column("created_at",sa.DateTime(timezone=True)))
    import uuid
    op.bulk_insert(versions,[{"id":uuid.uuid4(),"platform":"android","minimum_supported_version":"1.0.0","latest_version":"1.0.0","force_update":False,"release_notes":"Initial release","download_url":None,"store_url":None,"created_at":now}])


def downgrade():
    for table in ["user_api_keys","admin_actions","app_versions","webhooks","payments","subscriptions","job_results","jobs","system_settings","notifications","user_feature_flags","feature_flags","security_events","audit_logs","ai_quota","ai_usage_daily","ai_requests","email_verification_tokens","password_reset_tokens","refresh_tokens","sessions","devices","users"]:
        op.drop_table(table)
