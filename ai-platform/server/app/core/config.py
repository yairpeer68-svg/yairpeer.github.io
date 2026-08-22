from functools import lru_cache
from typing import Literal

from pydantic import field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore", case_sensitive=True)

    APP_ENV: Literal["development", "test", "staging", "production"] = "development"
    APP_NAME: str = "AI Platform"
    APP_HOST: str = "0.0.0.0"
    APP_PORT: int = 8080
    APP_VERSION: str = "2.1.1"
    GIT_COMMIT: str = "dev"
    BUILD_TIME: str = "unknown"
    DATABASE_URL: str = "postgresql+asyncpg://ai_platform:change@localhost:5432/ai_platform"
    REDIS_URL: str = "redis://localhost:6379/0"
    JWT_SECRET: str = "development-only-change-me-please-64-characters-minimum-not-production"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_MINUTES: int = 15
    REFRESH_TOKEN_DAYS: int = 30
    DEEPSEEK_API_KEY: str = ""
    DEEPSEEK_BASE_URL: str = "https://api.deepseek.com"
    DEEPSEEK_MODEL: str = "deepseek-chat"
    DEEPSEEK_ALLOWED_MODELS: str = "deepseek-chat,deepseek-reasoner"
    AI_PROVIDER_MODE: Literal["deepseek", "mock"] = "deepseek"
    DEEPSEEK_TIMEOUT_SECONDS: float = 45
    DEEPSEEK_MAX_RETRIES: int = 3
    CORS_ORIGINS: str = "http://localhost:5173"
    TRUSTED_HOSTS: str = "localhost,127.0.0.1"
    AI_RATE_LIMIT_PER_MINUTE: int = 20
    AUTH_RATE_LIMIT_PER_MINUTE: int = 10
    ADMIN_RATE_LIMIT_PER_MINUTE: int = 60
    AI_CACHE_TTL_SECONDS: int = 300
    AI_MAX_PROMPT_CHARS: int = 20000
    AI_MAX_RESPONSE_TOKENS: int = 4096
    AI_GLOBAL_MAX_RESPONSE_TOKENS: int = 8192
    ADMIN_EMAIL: str = ""
    ADMIN_INITIAL_PASSWORD: str = ""
    LOG_LEVEL: str = "INFO"
    SENTRY_DSN: str = ""
    PROMETHEUS_ENABLED: bool = True
    METRICS_TOKEN: str = ""
    BACKUP_RETENTION_DAYS: int = 14
    AUDIT_RETENTION_DAYS: int = 365
    AI_METADATA_RETENTION_DAYS: int = 90
    PROMPT_LOGGING_ENABLED: bool = False
    PROMPT_RETENTION_ENCRYPTION_KEY: str = ""
    PLAY_INTEGRITY_PROJECT_NUMBER: str = ""
    PLAY_INTEGRITY_CREDENTIALS_JSON: str = ""
    FCM_CREDENTIALS_JSON: str = ""
    SMTP_HOST: str = ""
    SMTP_PORT: int = 587
    SMTP_USERNAME: str = ""
    SMTP_PASSWORD: str = ""
    SMTP_FROM: str = ""
    SMTP_STARTTLS: bool = True
    PAYMENT_PROVIDER: str = "mock"
    APP_BASE_URL: str = "http://localhost:8080"
    ENGINEERING_ENABLED: bool = True
    ENGINEERING_WORKSPACE_ROOT: str = "/workspaces"
    ENGINEERING_MAX_PROJECT_FILES: int = 5000
    ENGINEERING_MAX_ARCHIVE_BYTES: int = 100_000_000
    ENGINEERING_MAX_EXTRACTED_BYTES: int = 500_000_000
    ENGINEERING_MAX_FILE_BYTES: int = 2_000_000
    ENGINEERING_COMMAND_TIMEOUT_SECONDS: int = 180
    ENGINEERING_MAX_REPAIR_ATTEMPTS: int = 3
    ENGINEERING_AUTO_EXECUTE_COMMANDS: bool = False
    ENGINEERING_RUNNER_URL: str = "http://runner:8090"
    ENGINEERING_RUNNER_TOKEN: str = "development-runner-token-change-me-32chars"
    ENGINEERING_ALLOW_LOCAL_EXECUTION: bool = False
    ENGINEERING_ALLOWED_COMMANDS: str = "python,python3,pytest,ruff,mypy,git,npm,npx,node,dart,flutter,gradle,./gradlew"
    ENGINEERING_STRICT_TOOLCHAINS: bool = False
    ENGINEERING_MAX_WORKSPACE_BYTES: int = 2_000_000_000
    ENGINEERING_MAX_PROJECTS_PER_USER: int = 20
    ENGINEERING_MAX_ACTIVE_RUNS_PER_USER: int = 2
    ENGINEERING_RUN_TIMEOUT_SECONDS: int = 3600
    RETENTION_SWEEP_INTERVAL_SECONDS: int = 86400

    @field_validator("JWT_SECRET")
    @classmethod
    def validate_jwt_secret(cls, value: str, info):
        if info.data.get("APP_ENV") == "production" and (len(value) < 64 or "change" in value.lower()):
            raise ValueError("JWT_SECRET must be at least 64 strong characters in production")
        return value

    @model_validator(mode="after")
    def validate_production_security(self):
        if self.APP_ENV == "production":
            if "*" in self.cors_origins:
                raise ValueError("Wildcard CORS is forbidden in production")
            if "*" in self.trusted_hosts:
                raise ValueError("Wildcard trusted hosts are forbidden in production")
            if self.AI_PROVIDER_MODE != "deepseek":
                raise ValueError("Mock AI provider is forbidden in production")
            if not self.DATABASE_URL.startswith("postgresql+asyncpg://"):
                raise ValueError("Production DATABASE_URL must use PostgreSQL asyncpg")
            if not self.REDIS_URL.startswith(("redis://", "rediss://")):
                raise ValueError("Production REDIS_URL must use Redis")
            if not self.APP_BASE_URL.startswith("https://"):
                raise ValueError("Production APP_BASE_URL must use HTTPS")
            if not self.DEEPSEEK_BASE_URL.startswith("https://"):
                raise ValueError("DEEPSEEK_BASE_URL must use HTTPS in production")
            if self.PROMPT_LOGGING_ENABLED and not self.PROMPT_RETENTION_ENCRYPTION_KEY:
                raise ValueError("Prompt retention requires PROMPT_RETENTION_ENCRYPTION_KEY in production")
            if self.ENGINEERING_ENABLED:
                if not self.ENGINEERING_RUNNER_URL:
                    raise ValueError("ENGINEERING_RUNNER_URL is required in production when engineering is enabled")
                if len(self.ENGINEERING_RUNNER_TOKEN) < 32 or "change" in self.ENGINEERING_RUNNER_TOKEN.lower():
                    raise ValueError("ENGINEERING_RUNNER_TOKEN must be a strong secret in production")
                if self.ENGINEERING_ALLOW_LOCAL_EXECUTION:
                    raise ValueError("Local engineering command execution is forbidden in production")
        if self.JWT_ALGORITHM not in {"HS256", "HS384", "HS512"}:
            raise ValueError("Unsupported JWT algorithm")
        if self.DEEPSEEK_MODEL not in self.allowed_models:
            raise ValueError("DEEPSEEK_MODEL must appear in DEEPSEEK_ALLOWED_MODELS")
        if self.ENGINEERING_MAX_FILE_BYTES > self.ENGINEERING_MAX_EXTRACTED_BYTES:
            raise ValueError("ENGINEERING_MAX_FILE_BYTES cannot exceed ENGINEERING_MAX_EXTRACTED_BYTES")
        return self

    @property
    def email_configured(self) -> bool:
        return bool(self.SMTP_HOST and self.SMTP_FROM)

    @property
    def allowed_models(self) -> list[str]:
        return [x.strip() for x in self.DEEPSEEK_ALLOWED_MODELS.split(",") if x.strip()]

    @property
    def cors_origins(self) -> list[str]:
        return [x.strip() for x in self.CORS_ORIGINS.split(",") if x.strip()]

    @property
    def trusted_hosts(self) -> list[str]:
        return [x.strip() for x in self.TRUSTED_HOSTS.split(",") if x.strip()]

    @property
    def engineering_allowed_commands(self) -> set[str]:
        return {x.strip() for x in self.ENGINEERING_ALLOWED_COMMANDS.split(",") if x.strip()}


@lru_cache
def get_settings() -> Settings:
    return Settings()
