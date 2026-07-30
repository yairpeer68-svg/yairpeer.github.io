"""Initial schema: users, conversations, documents, legal corpus, audit log.

Revision ID: 0001
Revises:
Create Date: 2026-01-01
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

from app.core.config import get_settings

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

EMBEDDING_DIMENSIONS = get_settings().embedding_dimensions

# IVFFlat needs a list count; ~sqrt(rows) is the usual starting point and 100
# is a sane default for a corpus in the low hundreds of thousands of chunks.
IVFFLAT_LISTS = 100


def upgrade() -> None:
    op.execute('CREATE EXTENSION IF NOT EXISTS "uuid-ossp"')
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm")

    user_role = sa.Enum(
        "guest", "user", "lawyer", "admin", name="user_role", create_type=True
    )
    auth_provider = sa.Enum(
        "local", "google", "apple", name="auth_provider", create_type=True
    )

    # ------------------------------------------------------------------ users
    op.create_table(
        "users",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("email", sa.String(320), nullable=False),
        sa.Column("hashed_password", sa.String(255)),
        sa.Column("full_name", sa.String(120), nullable=False, server_default=""),
        sa.Column("phone", sa.String(32)),
        sa.Column("role", user_role, nullable=False, server_default="user"),
        sa.Column("provider", auth_provider, nullable=False, server_default="local"),
        sa.Column("provider_subject", sa.String(255)),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column(
            "is_email_verified", sa.Boolean, nullable=False, server_default=sa.false()
        ),
        sa.Column("last_login_at", sa.DateTime(timezone=True)),
        sa.Column("failed_login_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("locked_until", sa.DateTime(timezone=True)),
        sa.Column(
            "preferences", postgresql.JSONB, nullable=False, server_default="{}"
        ),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
        comment="Application accounts",
    )
    op.create_index("ix_users_email_active", "users", ["email"], unique=True)
    op.create_index("ix_users_role", "users", ["role"])
    op.create_index("ix_users_provider_subject", "users", ["provider_subject"])
    op.create_index("ix_users_created_at", "users", ["created_at"])
    op.create_index("ix_users_deleted_at", "users", ["deleted_at"])

    op.create_table(
        "refresh_tokens",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("jti", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True)),
        sa.Column("user_agent", sa.String(255)),
        sa.Column("ip_address", sa.String(64)),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.UniqueConstraint("jti", name="uq_refresh_tokens_jti"),
    )
    op.create_index("ix_refresh_tokens_user_id", "refresh_tokens", ["user_id"])
    op.create_index("ix_refresh_tokens_created_at", "refresh_tokens", ["created_at"])

    # ---------------------------------------------------------- conversations
    op.create_table(
        "conversations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("title", sa.String(200), nullable=False, server_default="שיחה חדשה"),
        sa.Column("kind", sa.String(32), nullable=False, server_default="chat"),
        sa.Column("is_pinned", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("is_favorite", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("message_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("total_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column("extra", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
    )
    op.create_index("ix_conversations_user_id", "conversations", ["user_id"])
    op.create_index(
        "ix_conversations_user_updated", "conversations", ["user_id", "updated_at"]
    )
    op.create_index(
        "ix_conversations_user_pinned", "conversations", ["user_id", "is_pinned"]
    )
    op.create_index("ix_conversations_created_at", "conversations", ["created_at"])
    op.create_index("ix_conversations_deleted_at", "conversations", ["deleted_at"])

    op.create_table(
        "messages",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "conversation_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("conversations.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("role", sa.String(16), nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("citations", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("attachments", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("is_pinned", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("token_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("model", sa.String(64)),
        sa.Column("latency_ms", sa.Integer),
        sa.Column("error", sa.Text),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
    )
    op.create_index("ix_messages_conversation_id", "messages", ["conversation_id"])
    op.create_index(
        "ix_messages_conversation_created", "messages", ["conversation_id", "created_at"]
    )
    op.create_index("ix_messages_created_at", "messages", ["created_at"])
    # Trigram index so in-conversation ILIKE search stays fast as history grows.
    op.execute(
        "CREATE INDEX ix_messages_content_trgm ON messages "
        "USING gin (content gin_trgm_ops)"
    )

    # -------------------------------------------------------------- documents
    op.create_table(
        "documents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("filename", sa.String(255), nullable=False),
        sa.Column("content_type", sa.String(128), nullable=False),
        sa.Column("size_bytes", sa.Integer, nullable=False),
        sa.Column("checksum_sha256", sa.String(64), nullable=False),
        sa.Column("storage_key", sa.String(512), nullable=False),
        sa.Column("status", sa.String(16), nullable=False, server_default="pending"),
        sa.Column("error", sa.Text),
        sa.Column("extracted_text", sa.Text),
        sa.Column("page_count", sa.Integer),
        sa.Column("word_count", sa.Integer),
        sa.Column("language", sa.String(8)),
        sa.Column("used_ocr", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("extra", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
    )
    op.create_index("ix_documents_user_id", "documents", ["user_id"])
    op.create_index("ix_documents_user_created", "documents", ["user_id", "created_at"])
    op.create_index("ix_documents_checksum_sha256", "documents", ["checksum_sha256"])
    op.create_index("ix_documents_status", "documents", ["status"])
    op.create_index("ix_documents_created_at", "documents", ["created_at"])
    op.create_index("ix_documents_deleted_at", "documents", ["deleted_at"])

    op.create_table(
        "analysis_results",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "document_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("documents.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("kind", sa.String(24), nullable=False),
        sa.Column("summary", sa.Text, nullable=False, server_default=""),
        sa.Column("payload", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("citations", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("complexity_score", sa.Integer),
        sa.Column("risk_score", sa.Integer),
        sa.Column("model", sa.String(64)),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
    )
    op.create_index("ix_analysis_results_document_id", "analysis_results", ["document_id"])
    op.create_index("ix_analysis_results_user_id", "analysis_results", ["user_id"])
    op.create_index("ix_analysis_document_kind", "analysis_results", ["document_id", "kind"])
    op.create_index("ix_analysis_results_created_at", "analysis_results", ["created_at"])

    op.create_table(
        "generated_documents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("category", sa.String(16), nullable=False),
        sa.Column("template_key", sa.String(64), nullable=False),
        sa.Column("title", sa.String(200), nullable=False),
        sa.Column("body_markdown", sa.Text, nullable=False),
        sa.Column("inputs", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column("citations", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("model", sa.String(64)),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True)),
    )
    op.create_index("ix_generated_documents_user_id", "generated_documents", ["user_id"])
    op.create_index("ix_generated_user_created", "generated_documents", ["user_id", "created_at"])
    op.create_index("ix_generated_documents_template_key", "generated_documents", ["template_key"])
    op.create_index("ix_generated_documents_created_at", "generated_documents", ["created_at"])
    op.create_index("ix_generated_documents_deleted_at", "generated_documents", ["deleted_at"])

    # ----------------------------------------------------------- legal corpus
    op.create_table(
        "legal_sources",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("citation_key", sa.String(160), nullable=False),
        sa.Column("title", sa.String(400), nullable=False),
        sa.Column("short_title", sa.String(200)),
        sa.Column("source_type", sa.String(24), nullable=False),
        sa.Column("domain", sa.String(24), nullable=False, server_default="other"),
        sa.Column("case_number", sa.String(80)),
        sa.Column("court", sa.String(32)),
        sa.Column("judges", postgresql.JSONB, nullable=False, server_default="[]"),
        sa.Column("parties", sa.String(400)),
        sa.Column("proceeding_type", sa.String(80)),
        sa.Column("section_range", sa.String(80)),
        sa.Column("amendment", sa.String(120)),
        sa.Column("published_at", sa.Date),
        sa.Column("source_url", sa.String(1000)),
        sa.Column("publisher", sa.String(200), nullable=False, server_default=""),
        sa.Column("language", sa.String(8), nullable=False, server_default="he"),
        sa.Column("checksum_sha256", sa.String(64)),
        sa.Column("chunk_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column("extra", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.UniqueConstraint("citation_key", name="uq_legal_sources_citation_key"),
    )
    op.create_index("ix_legal_sources_source_type", "legal_sources", ["source_type"])
    op.create_index("ix_legal_sources_domain", "legal_sources", ["domain"])
    op.create_index("ix_legal_sources_type_domain", "legal_sources", ["source_type", "domain"])
    op.create_index("ix_legal_sources_court", "legal_sources", ["court"])
    op.create_index("ix_legal_sources_case_number", "legal_sources", ["case_number"])
    op.create_index("ix_legal_sources_proceeding_type", "legal_sources", ["proceeding_type"])
    op.create_index("ix_legal_sources_published", "legal_sources", ["published_at"])
    op.create_index("ix_legal_sources_checksum_sha256", "legal_sources", ["checksum_sha256"])
    op.create_index("ix_legal_sources_created_at", "legal_sources", ["created_at"])
    op.execute(
        "CREATE INDEX ix_legal_sources_title_trgm ON legal_sources "
        "USING gin (title gin_trgm_ops)"
    )

    op.create_table(
        "legal_chunks",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "source_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("legal_sources.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("ordinal", sa.Integer, nullable=False),
        sa.Column("heading", sa.String(300)),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("token_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.UniqueConstraint("source_id", "ordinal", name="uq_legal_chunks_source_ordinal"),
    )
    # `vector(n)` has no portable SQLAlchemy spelling here, so the column is
    # added in raw SQL with the width taken from EMBEDDING_DIMENSIONS. Changing
    # that setting later requires a new migration — mixing vector spaces in one
    # column would make retrieval silently wrong.
    op.execute(f"ALTER TABLE legal_chunks ADD COLUMN embedding vector({EMBEDDING_DIMENSIONS})")

    op.create_index("ix_legal_chunks_source", "legal_chunks", ["source_id"])
    op.create_index("ix_legal_chunks_created_at", "legal_chunks", ["created_at"])
    # Cosine-distance ANN index. IVFFlat must be built on a populated table to
    # pick good centroids — after a bulk ingest, run:
    #   REINDEX INDEX ix_legal_chunks_embedding;
    op.execute(
        "CREATE INDEX ix_legal_chunks_embedding ON legal_chunks "
        f"USING ivfflat (embedding vector_cosine_ops) WITH (lists = {IVFFLAT_LISTS})"
    )
    # Full-text search over chunk bodies. The 'simple' configuration does no
    # stemming, which is the correct choice for Hebrew (PostgreSQL ships no
    # Hebrew stemmer) and for exact statute/case identifiers.
    op.execute(
        "CREATE INDEX ix_legal_chunks_content_fts ON legal_chunks "
        "USING gin (to_tsvector('simple', content))"
    )

    # ------------------------------------------------------------- audit logs
    op.create_table(
        "audit_logs",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
        ),
        sa.Column("action", sa.String(64), nullable=False),
        sa.Column("resource_type", sa.String(64)),
        sa.Column("resource_id", sa.String(64)),
        sa.Column("outcome", sa.String(16), nullable=False, server_default="success"),
        sa.Column("ip_address", sa.String(64)),
        sa.Column("user_agent", sa.String(255)),
        sa.Column("request_id", sa.String(64)),
        sa.Column("status_code", sa.Integer),
        sa.Column("metadata", postgresql.JSONB, nullable=False, server_default="{}"),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), nullable=False, server_default=sa.func.now()
        ),
    )
    op.create_index("ix_audit_logs_user_created", "audit_logs", ["user_id", "created_at"])
    op.create_index("ix_audit_logs_action_created", "audit_logs", ["action", "created_at"])
    op.create_index("ix_audit_logs_request_id", "audit_logs", ["request_id"])
    op.create_index("ix_audit_logs_created_at", "audit_logs", ["created_at"])


def downgrade() -> None:
    op.drop_table("audit_logs")
    op.drop_table("legal_chunks")
    op.drop_table("legal_sources")
    op.drop_table("generated_documents")
    op.drop_table("analysis_results")
    op.drop_table("documents")
    op.drop_table("messages")
    op.drop_table("conversations")
    op.drop_table("refresh_tokens")
    op.drop_table("users")
    op.execute("DROP TYPE IF EXISTS auth_provider")
    op.execute("DROP TYPE IF EXISTS user_role")
