#!/usr/bin/env python3
"""Load legal sources into the corpus from a JSON file.

The system may only cite material that exists in ``legal_sources``, so this
script is how a deployment gets anything to cite.  **No legal text ships with
this repository** — statutes and rulings have their own licensing and currency
requirements, and bundling a stale snapshot would be worse than shipping none.

Input format — a JSON array of objects::

    [
      {
        "citation_key": "חוק-החוזים-תרופות-1970",
        "title": "חוק החוזים (תרופות בשל הפרת חוזה), התשל\\"א-1970",
        "source_type": "legislation",
        "domain": "contracts",
        "published_at": "1971-01-01",
        "publisher": "ספר החוקים",
        "source_url": "https://...",
        "content": "<full text>"
      }
    ]

Usage::

    python scripts/seed_corpus.py corpus/legislation.json
    python scripts/seed_corpus.py corpus/*.json --force
    python scripts/seed_corpus.py --check      # report corpus state and exit
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
from datetime import date
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core.config import get_settings
from app.core.logging import configure_logging, get_logger
from app.db.models.legal_source import (
    CourtLevel,
    LegalDomain,
    LegalSource,
    SourceType,
)
from app.db.session import Database
from app.services.ai.embeddings import build_embedding_provider
from app.services.rag.ingest import CorpusIngestor, SourceDraft
from sqlalchemy import func, select

logger = get_logger("seed")

REQUIRED_FIELDS = ("citation_key", "title", "content", "source_type", "publisher")


def _parse_date(value: Any) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(str(value))
    except ValueError:
        logger.warning("seed_bad_date", value=value)
        return None


def _to_draft(record: dict[str, Any], index: int) -> SourceDraft:
    """Validate one record and turn it into a :class:`SourceDraft`."""
    missing = [f for f in REQUIRED_FIELDS if not record.get(f)]
    if missing:
        raise ValueError(f"record #{index}: missing required fields {missing}")

    try:
        source_type = SourceType(record["source_type"])
    except ValueError as exc:
        valid = [t.value for t in SourceType]
        raise ValueError(f"record #{index}: source_type must be one of {valid}") from exc

    domain = LegalDomain.OTHER
    if raw_domain := record.get("domain"):
        try:
            domain = LegalDomain(raw_domain)
        except ValueError:
            logger.warning("seed_unknown_domain", domain=raw_domain)

    court: CourtLevel | None = None
    if raw_court := record.get("court"):
        try:
            court = CourtLevel(raw_court)
        except ValueError:
            logger.warning("seed_unknown_court", court=raw_court)

    return SourceDraft(
        citation_key=record["citation_key"],
        title=record["title"],
        content=record["content"],
        source_type=source_type,
        domain=domain,
        short_title=record.get("short_title"),
        case_number=record.get("case_number"),
        court=court,
        judges=list(record.get("judges", [])),
        parties=record.get("parties"),
        proceeding_type=record.get("proceeding_type"),
        section_range=record.get("section_range"),
        amendment=record.get("amendment"),
        published_at=_parse_date(record.get("published_at")),
        source_url=record.get("source_url"),
        publisher=record["publisher"],
        extra=record.get("extra", {}),
    )


async def report_state() -> None:
    """Print what the corpus currently holds."""
    settings = get_settings()
    database = Database(settings)
    try:
        async with database.session() as session:
            rows = (
                await session.execute(
                    select(LegalSource.source_type, func.count()).group_by(LegalSource.source_type)
                )
            ).all()
            chunks = (
                await session.execute(select(func.coalesce(func.sum(LegalSource.chunk_count), 0)))
            ).scalar_one()

        total = sum(int(c) for _, c in rows)
        print(f"מקורות במאגר: {total}")
        print(f"קטעים (chunks): {int(chunks)}")
        for source_type, count in rows:
            print(f"  {source_type}: {count}")
        if total == 0:
            print(
                "\nהמאגר ריק — המערכת תענה ברמה עקרונית ולא תציג אסמכתאות.\n"
                "יש לטעון חקיקה ופסיקה לפני שימוש בייצור."
            )
    finally:
        await database.dispose()


async def seed(paths: list[Path], *, force: bool) -> int:
    """Ingest every record in ``paths``. Returns the number of sources loaded."""
    settings = get_settings()

    if settings.embedding_provider == "hashing":
        print(
            "אזהרה: EMBEDDING_PROVIDER=hashing הוא מנגנון פיתוח בלבד וללא הבנה "
            "סמנטית. לטעינה לייצור יש להגדיר ספק Embeddings אמיתי.",
            file=sys.stderr,
        )

    drafts: list[SourceDraft] = []
    for path in paths:
        if not path.is_file():
            print(f"קובץ לא נמצא: {path}", file=sys.stderr)
            return 0
        try:
            records = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            print(f"JSON שגוי בקובץ {path}: {exc}", file=sys.stderr)
            return 0
        if not isinstance(records, list):
            print(f"{path}: הקובץ חייב להכיל מערך JSON", file=sys.stderr)
            return 0
        for index, record in enumerate(records):
            drafts.append(_to_draft(record, index))

    database = Database(settings)
    embeddings = build_embedding_provider(settings)
    loaded = 0
    try:
        async with database.session() as session:
            ingestor = CorpusIngestor(
                session,
                embeddings,
                chunk_tokens=settings.rag_chunk_tokens,
                overlap_tokens=settings.rag_chunk_overlap_tokens,
            )
            for draft in drafts:
                source = await ingestor.ingest(draft, force=force)
                loaded += 1
                print(f"✓ {source.citation_key} ({source.chunk_count} קטעים)")
    finally:
        await embeddings.aclose()
        await database.dispose()

    return loaded


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed the Sanegor legal corpus")
    parser.add_argument("files", nargs="*", type=Path, help="JSON files to ingest")
    parser.add_argument(
        "--force", action="store_true", help="re-embed sources whose content is unchanged"
    )
    parser.add_argument("--check", action="store_true", help="report corpus state and exit")
    args = parser.parse_args()

    settings = get_settings()
    configure_logging(settings.log_level, settings.log_json)

    if args.check:
        asyncio.run(report_state())
        return 0

    if not args.files:
        parser.print_help()
        print(
            "\nלא צורפו מקורות משפטיים למאגר בכוונה — יש לספק חקיקה ופסיקה " "ממקור מורשה ועדכני.",
            file=sys.stderr,
        )
        return 1

    try:
        loaded = asyncio.run(seed(args.files, force=args.force))
    except ValueError as exc:
        print(f"שגיאה: {exc}", file=sys.stderr)
        return 1

    print(f"\nנטענו {loaded} מקורות.")
    print("על PostgreSQL, לאחר טעינה גדולה הריצו: REINDEX INDEX ix_legal_chunks_embedding;")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
