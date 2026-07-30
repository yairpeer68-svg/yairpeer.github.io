"""Shared test fixtures.

The suite runs against SQLite (aiosqlite) so it needs neither PostgreSQL nor
Redis. The portability shims in ``app.db.types`` and the SQLite branches in the
retriever exist precisely so the same models can be exercised here; the
PostgreSQL-only paths (pgvector ANN, full-text search) are covered by the
integration compose stack rather than by unit tests.
"""

from __future__ import annotations

import os
from collections.abc import AsyncIterator

import pytest

# Configuration must be set before anything imports Settings.
os.environ.update(
    {
        "ENVIRONMENT": "development",
        "DEBUG": "true",
        "DATABASE_URL": "sqlite+aiosqlite:///:memory:",
        "REDIS_URL": "redis://localhost:6379/15",
        "SECRET_KEY": "test-secret-key-that-is-definitely-long-enough-for-tests",
        "ENCRYPTION_KEY": "dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcy1sb25nISE=",
        "EMBEDDING_PROVIDER": "hashing",
        "EMBEDDING_DIMENSIONS": "256",
        "RAG_ENABLED": "true",
        "OCR_ENABLED": "false",
        "STORAGE_DIR": "",  # replaced per-session by the tmp_path fixture
        "DEEPSEEK_API_KEY": "test-key",
        "LOG_LEVEL": "WARNING",
    }
)

import httpx  # noqa: E402
from httpx import ASGITransport  # noqa: E402
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine  # noqa: E402

from app.core.config import get_settings  # noqa: E402
from app.db.base import Base  # noqa: E402
from app.db import models  # noqa: E402,F401 - registers tables
from app.services.ai.embeddings import HashingEmbeddings  # noqa: E402


@pytest.fixture(scope="session")
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture(scope="session", autouse=True)
def _storage_dir(tmp_path_factory: pytest.TempPathFactory) -> str:
    """Point file storage at a throwaway directory for the whole session."""
    path = tmp_path_factory.mktemp("sanegor-storage")
    os.environ["STORAGE_DIR"] = str(path)
    get_settings.cache_clear()
    return str(path)


@pytest.fixture
def settings():  # noqa: ANN201
    get_settings.cache_clear()
    return get_settings()


@pytest.fixture
async def engine() -> AsyncIterator[object]:
    """A fresh in-memory database per test."""
    engine = create_async_engine(
        "sqlite+aiosqlite:///:memory:", echo=False, future=True
    )
    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest.fixture
async def session(engine) -> AsyncIterator[AsyncSession]:  # noqa: ANN001
    factory = async_sessionmaker(bind=engine, class_=AsyncSession, expire_on_commit=False)
    async with factory() as session:
        yield session
        await session.rollback()


@pytest.fixture
def embeddings() -> HashingEmbeddings:
    return HashingEmbeddings(dimensions=256)


@pytest.fixture
async def app(engine, settings):  # noqa: ANN001, ANN201
    """A fully wired app whose database points at the test engine."""
    from sqlalchemy.ext.asyncio import async_sessionmaker

    from app.main import create_app

    application = create_app(settings)

    # Replace the lifespan-built Database with one bound to the test engine.
    class _TestDatabase:
        def __init__(self) -> None:
            self.engine = engine
            self.session_factory = async_sessionmaker(
                bind=engine, class_=AsyncSession, expire_on_commit=False
            )

        def session(self):  # noqa: ANN202
            from contextlib import asynccontextmanager

            @asynccontextmanager
            async def _scope() -> AsyncIterator[AsyncSession]:
                async with self.session_factory() as session:
                    try:
                        yield session
                        await session.commit()
                    except Exception:
                        await session.rollback()
                        raise

            return _scope()

        async def dispose(self) -> None:
            return None

    from app.core.rate_limit import RateLimiter
    from app.core.security import TextCipher
    from app.services.ai.deepseek import DeepSeekClient
    from app.services.cache import CacheService
    from app.services.documents.extractor import DocumentExtractor
    from app.services.documents.ocr import OcrService
    from app.services.storage import FileStorage

    state = application.state
    state.database = _TestDatabase()
    state.redis = None
    state.cache = CacheService(None)
    state.rate_limiter = RateLimiter(None)
    state.deepseek = DeepSeekClient(settings)
    state.embeddings = HashingEmbeddings(settings.embedding_dimensions)
    state.cipher = TextCipher(settings.encryption_key)
    state.storage = FileStorage(settings.storage_dir)
    state.ocr = OcrService(enabled=False)
    state.extractor = DocumentExtractor(state.ocr)

    yield application
    await state.deepseek.aclose()


@pytest.fixture
async def client(app) -> AsyncIterator[httpx.AsyncClient]:  # noqa: ANN001
    """HTTP client bound to the app, bypassing the lifespan."""
    async with httpx.AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        yield client


@pytest.fixture
async def registered_user(client: httpx.AsyncClient) -> dict[str, str]:
    """A registered account plus its access token."""
    response = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "dana@example.co.il",
            "password": "Sisma-Hazaka-2026",
            "full_name": "דנה כהן",
        },
    )
    assert response.status_code == 201, response.text
    body = response.json()
    return {
        "email": body["user"]["email"],
        "user_id": body["user"]["id"],
        "access_token": body["tokens"]["access_token"],
        "refresh_token": body["tokens"]["refresh_token"],
    }


@pytest.fixture
def auth_headers(registered_user: dict[str, str]) -> dict[str, str]:
    return {"Authorization": f"Bearer {registered_user['access_token']}"}
