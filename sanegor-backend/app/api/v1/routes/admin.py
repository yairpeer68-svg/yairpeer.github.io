"""Administrative endpoints: corpus ingestion and user management.

Corpus ingestion is restricted to admins on purpose.  Everything the system is
allowed to cite enters through here, so the provenance of the corpus is only as
good as the control over this endpoint.
"""

from __future__ import annotations

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import Field
from sqlalchemy import select

from app.api.deps import (
    CurrentUser,
    SessionDep,
    SettingsDep,
    get_audit_service,
    get_embeddings,
    rate_limit_default,
    require_admin,
)
from app.core.errors import NotFoundError, ValidationError
from app.db.models.audit import AuditAction
from app.db.models.legal_source import CourtLevel, LegalDomain, LegalSource, SourceType
from app.db.models.user import User, UserRole
from app.schemas.auth import UserOut
from app.schemas.common import ApiModel, MessageResponse, Page
from app.services.ai.embeddings import EmbeddingProvider
from app.services.audit import AuditService
from app.services.rag.ingest import CorpusIngestor, SourceDraft

router = APIRouter(
    prefix="/admin",
    tags=["admin"],
    dependencies=[Depends(require_admin), Depends(rate_limit_default)],
)

AuditDep = Annotated[AuditService, Depends(get_audit_service)]
EmbeddingsDep = Annotated[EmbeddingProvider, Depends(get_embeddings)]


class IngestSourceRequest(ApiModel):
    """One legal source to load into the corpus.

    ``publisher`` is required: a citation the app shows must be traceable to
    where the text came from.
    """

    citation_key: Annotated[str, Field(min_length=3, max_length=160)]
    title: Annotated[str, Field(min_length=3, max_length=400)]
    content: Annotated[str, Field(min_length=50, max_length=4_000_000)]
    source_type: SourceType
    domain: LegalDomain = LegalDomain.OTHER
    short_title: Annotated[str, Field(max_length=200)] | None = None
    case_number: Annotated[str, Field(max_length=80)] | None = None
    court: CourtLevel | None = None
    judges: list[str] = []
    parties: Annotated[str, Field(max_length=400)] | None = None
    proceeding_type: Annotated[str, Field(max_length=80)] | None = None
    section_range: Annotated[str, Field(max_length=80)] | None = None
    amendment: Annotated[str, Field(max_length=120)] | None = None
    published_at: date | None = None
    source_url: Annotated[str, Field(max_length=1000)] | None = None
    publisher: Annotated[str, Field(min_length=2, max_length=200)]
    force: bool = False


class IngestResponse(ApiModel):
    source_id: str
    citation_key: str
    chunk_count: int


class ChangeRoleRequest(ApiModel):
    role: UserRole


@router.post("/corpus/ingest", response_model=IngestResponse, summary="טעינת מקור משפטי")
async def ingest_source(
    payload: IngestSourceRequest,
    admin: CurrentUser,
    session: SessionDep,
    settings: SettingsDep,
    embeddings: EmbeddingsDep,
    audit: AuditDep,
) -> IngestResponse:
    """Chunk, embed and store one legal source."""
    ingestor = CorpusIngestor(
        session,
        embeddings,
        chunk_tokens=settings.rag_chunk_tokens,
        overlap_tokens=settings.rag_chunk_overlap_tokens,
    )
    source = await ingestor.ingest(
        SourceDraft(
            citation_key=payload.citation_key,
            title=payload.title,
            content=payload.content,
            source_type=payload.source_type,
            domain=payload.domain,
            short_title=payload.short_title,
            case_number=payload.case_number,
            court=payload.court,
            judges=payload.judges,
            parties=payload.parties,
            proceeding_type=payload.proceeding_type,
            section_range=payload.section_range,
            amendment=payload.amendment,
            published_at=payload.published_at,
            source_url=payload.source_url,
            publisher=payload.publisher,
        ),
        force=payload.force,
    )
    await audit.record(
        AuditAction.CORPUS_INGEST,
        user_id=str(admin.id),
        resource_type="legal_source",
        resource_id=str(source.id),
        metadata={"citation_key": source.citation_key, "chunks": source.chunk_count},
    )
    return IngestResponse(
        source_id=str(source.id),
        citation_key=source.citation_key,
        chunk_count=source.chunk_count,
    )


@router.delete(
    "/corpus/{citation_key}", response_model=MessageResponse, summary="מחיקת מקור"
)
async def delete_source(
    citation_key: str, session: SessionDep, admin: CurrentUser, audit: AuditDep
) -> MessageResponse:
    """Remove a source and its chunks from the corpus."""
    source = (
        await session.execute(
            select(LegalSource).where(LegalSource.citation_key == citation_key)
        )
    ).scalar_one_or_none()
    if source is None:
        raise NotFoundError("המקור לא נמצא")

    await session.delete(source)
    await audit.record(
        AuditAction.CORPUS_INGEST,
        user_id=str(admin.id),
        resource_type="legal_source",
        resource_id=citation_key,
        outcome="deleted",
    )
    return MessageResponse(message="המקור נמחק מהמאגר")


@router.get("/users", response_model=Page[UserOut], summary="רשימת משתמשים")
async def list_users(
    session: SessionDep,
    _admin: CurrentUser,
    limit: int = 20,
    offset: int = 0,
) -> Page[UserOut]:
    """Paginated user list."""
    from sqlalchemy import func

    total = int(
        (
            await session.execute(
                select(func.count()).select_from(User).where(User.deleted_at.is_(None))
            )
        ).scalar_one()
    )
    rows = (
        await session.execute(
            select(User)
            .where(User.deleted_at.is_(None))
            .order_by(User.created_at.desc())
            .limit(min(limit, 100))
            .offset(offset)
        )
    ).scalars().all()
    return Page[UserOut](
        items=[UserOut.model_validate(u) for u in rows],
        total=total,
        limit=limit,
        offset=offset,
    )


@router.post("/users/{user_id}/role", response_model=UserOut, summary="שינוי הרשאה")
async def change_role(
    user_id: str,
    payload: ChangeRoleRequest,
    session: SessionDep,
    admin: CurrentUser,
    audit: AuditDep,
) -> UserOut:
    """Change a user's role.

    An admin cannot demote themselves — that is almost always a mistake and
    can leave a deployment with no administrator at all.
    """
    import uuid

    if str(admin.id) == user_id and payload.role is not UserRole.ADMIN:
        raise ValidationError("לא ניתן להוריד את ההרשאה של עצמך")

    try:
        identifier = uuid.UUID(user_id)
    except ValueError as exc:
        raise NotFoundError("המשתמש לא נמצא") from exc

    user = (
        await session.execute(select(User).where(User.id == identifier))
    ).scalar_one_or_none()
    if user is None:
        raise NotFoundError("המשתמש לא נמצא")

    previous = user.role
    user.role = payload.role
    await session.flush()
    await audit.record(
        AuditAction.ROLE_CHANGED,
        user_id=str(admin.id),
        resource_type="user",
        resource_id=user_id,
        metadata={"from": previous.value, "to": payload.role.value},
    )
    return UserOut.model_validate(user)
