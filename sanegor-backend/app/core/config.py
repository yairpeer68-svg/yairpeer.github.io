"""Application settings.

All configuration is read from the environment (or a local ``.env``) exactly
once and exposed through :func:`get_settings`, which is cached so that the rest
of the code base can import it freely without re-parsing the environment.
"""

from __future__ import annotations

import secrets
from functools import lru_cache
from typing import Literal

from pydantic import Field, computed_field, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

Environment = Literal["development", "staging", "production"]
EmbeddingProvider = Literal["openai_compatible", "hashing"]


class RateLimitRule:
    """A parsed ``<limit>/<window-seconds>`` rate-limit rule."""

    __slots__ = ("limit", "window")

    def __init__(self, limit: int, window: int) -> None:
        self.limit = limit
        self.window = window

    @classmethod
    def parse(cls, raw: str) -> RateLimitRule:
        limit, _, window = raw.partition("/")
        try:
            return cls(int(limit), int(window or 60))
        except ValueError as exc:  # pragma: no cover - configuration error
            raise ValueError(f"invalid rate limit rule: {raw!r}") from exc

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"RateLimitRule({self.limit}/{self.window}s)"


def _split_csv(value: str | list[str]) -> list[str]:
    if isinstance(value, list):
        return value
    return [item.strip() for item in value.split(",") if item.strip()]


class Settings(BaseSettings):
    """Typed view over the process environment."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ---------------------------------------------------------------- general
    app_name: str = "Sanegor Legal AI"
    api_v1_prefix: str = "/api/v1"
    environment: Environment = "development"
    debug: bool = False
    log_level: str = "INFO"
    log_json: bool = False

    cors_origins: list[str] = Field(default_factory=lambda: ["http://localhost:3000"])
    allowed_hosts: list[str] = Field(default_factory=lambda: ["*"])
    force_https: bool = False

    # --------------------------------------------------------------- database
    postgres_user: str = "sanegor"
    postgres_password: str = "sanegor"
    postgres_db: str = "sanegor"
    postgres_host: str = "postgres"
    postgres_port: int = 5432
    database_url: str = ""
    db_pool_size: int = 10
    db_max_overflow: int = 20
    db_echo: bool = False

    # ------------------------------------------------------------------ redis
    redis_url: str = "redis://redis:6379/0"
    cache_ttl_seconds: int = 900

    # --------------------------------------------------------------- security
    secret_key: str = Field(default_factory=lambda: secrets.token_urlsafe(64))
    encryption_key: str = ""
    jwt_algorithm: str = "HS256"
    access_token_ttl_minutes: int = 30
    refresh_token_ttl_days: int = 30
    password_min_length: int = 10

    rate_limit_default: str = "120/60"
    rate_limit_auth: str = "10/60"
    rate_limit_ai: str = "30/60"
    rate_limit_upload: str = "20/300"

    # ------------------------------------------------------------------ oauth
    google_client_id: str = ""
    google_client_secret: str = ""
    apple_client_id: str = ""
    apple_team_id: str = ""
    apple_key_id: str = ""
    apple_private_key: str = ""

    # --------------------------------------------------------------- deepseek
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_chat_model: str = "deepseek-chat"
    deepseek_timeout_seconds: float = 120.0
    deepseek_max_tokens: int = 4096
    deepseek_temperature: float = 0.2
    ai_context_token_budget: int = 48_000

    # ------------------------------------------------------------- embeddings
    embedding_provider: EmbeddingProvider = "hashing"
    embedding_base_url: str = "http://embeddings:8080/v1"
    embedding_api_key: str = ""
    embedding_model: str = "intfloat/multilingual-e5-large"
    embedding_dimensions: int = 1024

    # -------------------------------------------------------------------- rag
    rag_enabled: bool = True
    rag_top_k: int = 24
    rag_final_k: int = 6
    rag_min_score: float = 0.25
    rag_chunk_tokens: int = 700
    rag_chunk_overlap_tokens: int = 100

    # ---------------------------------------------------------------- uploads
    storage_dir: str = "/var/lib/sanegor/storage"
    max_upload_mb: int = 25
    allowed_upload_types: list[str] = Field(
        default_factory=lambda: [
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "image/jpeg",
            "image/png",
        ]
    )

    # -------------------------------------------------------------------- ocr
    ocr_enabled: bool = True
    ocr_languages: str = "heb+eng"
    tesseract_cmd: str = "/usr/bin/tesseract"

    # ----------------------------------------------------------------- celery
    celery_enabled: bool = False
    celery_broker_url: str = "redis://redis:6379/1"
    celery_result_backend: str = "redis://redis:6379/2"

    # -------------------------------------------------------------- bootstrap
    bootstrap_admin_email: str = ""
    bootstrap_admin_password: str = ""

    # -------------------------------------------------------------- validators
    @field_validator("cors_origins", "allowed_hosts", "allowed_upload_types", mode="before")
    @classmethod
    def _csv_lists(cls, value: str | list[str]) -> list[str]:
        return _split_csv(value)

    @field_validator("log_level")
    @classmethod
    def _upper_log_level(cls, value: str) -> str:
        return value.upper()

    @model_validator(mode="after")
    def _production_guardrails(self) -> Settings:
        """Fail fast on configurations that are unsafe outside development."""
        if self.environment != "production":
            return self

        problems: list[str] = []
        if len(self.secret_key) < 32:
            problems.append("SECRET_KEY must be at least 32 characters in production")
        if self.debug:
            problems.append("DEBUG must be false in production")
        if "*" in self.cors_origins:
            problems.append("CORS_ORIGINS must not contain '*' in production")
        if not self.force_https:
            problems.append("FORCE_HTTPS must be true in production")
        if self.embedding_provider == "hashing" and self.rag_enabled:
            problems.append(
                "EMBEDDING_PROVIDER=hashing is a development stub; configure a real "
                "embedding service or disable RAG"
            )
        if problems:
            raise ValueError("invalid production configuration: " + "; ".join(problems))
        return self

    # --------------------------------------------------------------- computed
    @computed_field  # type: ignore[prop-decorator]
    @property
    def sqlalchemy_url(self) -> str:
        """Async SQLAlchemy DSN."""
        if self.database_url:
            return self.database_url
        return (
            f"postgresql+asyncpg://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )

    @computed_field  # type: ignore[prop-decorator]
    @property
    def alembic_url(self) -> str:
        """Sync DSN used by Alembic (psycopg-free: asyncpg driver via async engine)."""
        return self.sqlalchemy_url

    @computed_field  # type: ignore[prop-decorator]
    @property
    def max_upload_bytes(self) -> int:
        return self.max_upload_mb * 1024 * 1024

    @computed_field  # type: ignore[prop-decorator]
    @property
    def is_production(self) -> bool:
        return self.environment == "production"

    def rate_limit(self, name: str) -> RateLimitRule:
        """Return the parsed rule for ``default``/``auth``/``ai``/``upload``."""
        raw = getattr(self, f"rate_limit_{name}", self.rate_limit_default)
        return RateLimitRule.parse(raw)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Cached settings singleton."""
    return Settings()
