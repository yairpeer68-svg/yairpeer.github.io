import uuid
from datetime import UTC, datetime, timedelta

import jwt
import pytest

from app.core.config import Settings
from app.core.errors import AppError
from app.core.logging import redact
from app.security.passwords import hash_password, validate_password_policy, verify_password
from app.security.tokens import create_access_token, decode_access_token, generate_opaque_token, hash_token


def settings():
    return Settings(APP_ENV="test", JWT_SECRET="x" * 80)


def test_argon2_password_roundtrip():
    encoded = hash_password("correct horse battery staple")
    assert encoded.startswith("$argon2id$")
    assert verify_password("correct horse battery staple", encoded)
    assert not verify_password("wrong password", encoded)


@pytest.mark.parametrize("password", ["short", "password123", "aaaaaaaaaaa"])
def test_weak_password_rejected(password):
    with pytest.raises(AppError):
        validate_password_policy(password)


def test_access_token_roundtrip_and_claims():
    s = settings(); uid = uuid.uuid4(); sid = uuid.uuid4()
    token = create_access_token(s, uid, sid, True)
    claims = decode_access_token(s, token)
    assert claims["sub"] == str(uid)
    assert claims["sid"] == str(sid)
    assert claims["adm"] is True
    assert "jti" in claims


def test_expired_access_token_rejected():
    s = settings(); now = datetime.now(UTC)
    token = jwt.encode({"sub": str(uuid.uuid4()), "sid": str(uuid.uuid4()), "adm": False,
                        "iat": int((now-timedelta(minutes=2)).timestamp()),
                        "exp": int((now-timedelta(minutes=1)).timestamp()), "iss": s.APP_NAME},
                       s.JWT_SECRET, algorithm=s.JWT_ALGORITHM)
    with pytest.raises(AppError) as exc:
        decode_access_token(s, token)
    assert exc.value.code == "TOKEN_EXPIRED"


def test_opaque_tokens_are_random_and_hash_only():
    a, b = generate_opaque_token(), generate_opaque_token()
    assert a != b and len(a) >= 32
    assert hash_token(a) != a
    assert len(hash_token(a)) == 64


def test_sensitive_logging_redaction():
    value = redact({"password":"secret","Authorization":"Bearer abc","nested":{"api_key":"key","ok":1}})
    assert value["password"] == "[REDACTED]"
    assert value["Authorization"] == "[REDACTED]"
    assert value["nested"]["api_key"] == "[REDACTED]"
    assert value["nested"]["ok"] == 1


def test_production_rejects_wildcard_cors():
    with pytest.raises(ValueError):
        Settings(
            APP_ENV="production",
            JWT_SECRET="z" * 80,
            DATABASE_URL="postgresql+asyncpg://user:password@db/app",
            REDIS_URL="redis://redis:6379/0",
            APP_BASE_URL="https://app.example.com",
            DEEPSEEK_BASE_URL="https://api.deepseek.com",
            CORS_ORIGINS="*",
            TRUSTED_HOSTS="app.example.com",
        )


def test_text_redaction_removes_bearer_and_provider_key():
    from app.core.logging import redact_text

    fake_provider_key = "sk-" + ("a" * 22)
    text = redact_text(f"Authorization: Bearer abc.def.ghi api={fake_provider_key}")
    assert "abc.def.ghi" not in text
    assert fake_provider_key not in text
    assert "[REDACTED]" in text
