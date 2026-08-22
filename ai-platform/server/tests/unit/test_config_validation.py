from pathlib import Path

import pytest
from pydantic import ValidationError

from app.core.config import Settings

REPO_ROOT = Path(__file__).resolve().parents[3]

PRODUCTION = dict(
    APP_ENV='production',
    JWT_SECRET='p' * 70,
    CORS_ORIGINS='https://admin.example.com',
    TRUSTED_HOSTS='api.example.com',
    APP_BASE_URL='https://api.example.com',
    DATABASE_URL='postgresql+asyncpg://u:p@db:5432/app',
    REDIS_URL='redis://redis:6379/0',
    ENGINEERING_RUNNER_TOKEN='r' * 40,
)


def test_a_valid_production_configuration_is_accepted():
    assert Settings(**PRODUCTION).APP_ENV == 'production'


@pytest.mark.parametrize('override,fragment', [
    ({'CORS_ORIGINS': '*'}, 'Wildcard CORS'),
    ({'TRUSTED_HOSTS': '*'}, 'Wildcard trusted hosts'),
    ({'AI_PROVIDER_MODE': 'mock'}, 'Mock AI provider'),
    ({'APP_BASE_URL': 'http://api.example.com'}, 'HTTPS'),
    ({'DEEPSEEK_BASE_URL': 'http://api.deepseek.com'}, 'HTTPS'),
    ({'ENGINEERING_RUNNER_TOKEN': 'CHANGE_ME_but_long_enough_to_pass_length'}, 'strong secret'),
    ({'ENGINEERING_ALLOW_LOCAL_EXECUTION': True}, 'Local engineering command execution'),
    ({'JWT_SECRET': 'short'}, 'JWT_SECRET'),
])
def test_unsafe_production_configurations_are_refused(override, fragment):
    with pytest.raises(ValidationError) as excinfo:
        Settings(**{**PRODUCTION, **override})
    assert fragment in str(excinfo.value)


def test_prompt_retention_without_a_key_is_refused_in_production():
    """Retention that cannot encrypt must not silently store or 503 at request time."""
    with pytest.raises(ValidationError) as excinfo:
        Settings(**{**PRODUCTION, 'PROMPT_LOGGING_ENABLED': True})
    assert 'PROMPT_RETENTION_ENCRYPTION_KEY' in str(excinfo.value)


def test_the_default_model_must_be_allow_listed():
    with pytest.raises(ValidationError):
        Settings(APP_ENV='test', JWT_SECRET='x' * 64,
                 DEEPSEEK_MODEL='not-listed', DEEPSEEK_ALLOWED_MODELS='deepseek-chat')


def test_a_per_file_limit_above_the_archive_budget_is_refused():
    with pytest.raises(ValidationError):
        Settings(APP_ENV='test', JWT_SECRET='x' * 64,
                 ENGINEERING_MAX_FILE_BYTES=10_000, ENGINEERING_MAX_EXTRACTED_BYTES=1_000)


def test_development_defaults_load():
    settings = Settings(APP_ENV='development')
    assert settings.ENGINEERING_STRICT_TOOLCHAINS is False
    assert settings.APP_VERSION.count('.') == 2


def test_the_default_version_matches_the_release_file():
    """APP_VERSION drifted a full minor behind VERSION in 2.1.0; keep them pinned together."""
    version_file = REPO_ROOT / 'VERSION'
    if not version_file.is_file():
        pytest.skip('VERSION is not present in this checkout layout')
    assert Settings(APP_ENV='development').APP_VERSION == version_file.read_text().strip()
