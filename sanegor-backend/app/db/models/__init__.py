"""ORM models.

Importing this package registers every table on ``Base.metadata`` — Alembic's
autogenerate relies on that, so new model modules must be re-exported here.
"""

from app.db.models.audit import AuditAction, AuditLog
from app.db.models.conversation import (
    Conversation,
    ConversationKind,
    Message,
    MessageRole,
)
from app.db.models.document import (
    AnalysisKind,
    AnalysisResult,
    Document,
    DocumentStatus,
    GeneratedDocument,
)
from app.db.models.legal_source import (
    CourtLevel,
    LegalChunk,
    LegalDomain,
    LegalSource,
    SourceType,
)
from app.db.models.user import AuthProvider, RefreshToken, User, UserRole

__all__ = [
    "AnalysisKind",
    "AnalysisResult",
    "AuditAction",
    "AuditLog",
    "AuthProvider",
    "Conversation",
    "ConversationKind",
    "CourtLevel",
    "Document",
    "DocumentStatus",
    "GeneratedDocument",
    "LegalChunk",
    "LegalDomain",
    "LegalSource",
    "Message",
    "MessageRole",
    "RefreshToken",
    "SourceType",
    "User",
    "UserRole",
]
