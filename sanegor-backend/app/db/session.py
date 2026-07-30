"""Async engine / session factory management."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.core.config import Settings
from app.core.logging import get_logger

logger = get_logger(__name__)


class Database:
    """Owns the engine and session factory for the process lifetime."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        kwargs: dict[str, object] = {
            "echo": settings.db_echo,
            "pool_pre_ping": True,
            "future": True,
        }
        # SQLite (used by the test-suite) has no connection pool sizing.
        if not settings.sqlalchemy_url.startswith("sqlite"):
            kwargs |= {
                "pool_size": settings.db_pool_size,
                "max_overflow": settings.db_max_overflow,
                "pool_recycle": 1800,
            }
        self._engine: AsyncEngine = create_async_engine(settings.sqlalchemy_url, **kwargs)
        self._session_factory = async_sessionmaker(
            bind=self._engine,
            class_=AsyncSession,
            expire_on_commit=False,
            autoflush=False,
        )

    @property
    def engine(self) -> AsyncEngine:
        return self._engine

    @property
    def session_factory(self) -> async_sessionmaker[AsyncSession]:
        return self._session_factory

    @asynccontextmanager
    async def session(self) -> AsyncIterator[AsyncSession]:
        """Transactional scope: commit on success, roll back on failure."""
        async with self._session_factory() as session:
            try:
                yield session
                await session.commit()
            except Exception:
                await session.rollback()
                raise

    async def dispose(self) -> None:
        await self._engine.dispose()
        logger.info("database_disposed")
