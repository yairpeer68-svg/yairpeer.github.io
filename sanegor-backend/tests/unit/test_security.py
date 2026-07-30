"""Password, token and encryption tests."""

from __future__ import annotations

from datetime import timedelta

import pytest

from app.core.config import Settings
from app.core.errors import AuthenticationError, ValidationError
from app.core.security import (
    TextCipher,
    TokenService,
    hash_password,
    validate_password_strength,
    verify_password,
)


@pytest.fixture
def settings() -> Settings:
    return Settings(
        secret_key="a-test-secret-key-long-enough-for-hs256-signing",
        app_name="Sanegor Test",
        access_token_ttl_minutes=15,
        refresh_token_ttl_days=7,
    )


class TestPasswordHashing:
    def test_round_trip(self) -> None:
        hashed = hash_password("Sisma-Hazaka-2026")
        assert hashed != "Sisma-Hazaka-2026"
        assert verify_password("Sisma-Hazaka-2026", hashed)

    def test_wrong_password_rejected(self) -> None:
        assert not verify_password("wrong", hash_password("Sisma-Hazaka-2026"))

    def test_salted_hashes_differ(self) -> None:
        assert hash_password("same-password-1") != hash_password("same-password-1")

    def test_malformed_hash_returns_false_instead_of_raising(self) -> None:
        assert not verify_password("anything", "not-a-hash")


class TestPasswordPolicy:
    def test_accepts_a_strong_password(self) -> None:
        validate_password_strength("Sisma-Hazaka-2026", 10)

    @pytest.mark.parametrize(
        "password",
        ["short1", "alllettersonly", "1234567890123", "password1"],
    )
    def test_rejects_weak_passwords(self, password: str) -> None:
        with pytest.raises(ValidationError):
            validate_password_strength(password, 10)


class TestTokenService:
    def test_pair_round_trip(self, settings: Settings) -> None:
        service = TokenService(settings)
        pair, jti = service.create_pair("user-123", "lawyer")

        claims = service.decode(pair.access_token, "access")
        assert claims.subject == "user-123"
        assert claims.role == "lawyer"

        refresh_claims = service.decode(pair.refresh_token, "refresh")
        assert refresh_claims.jti == jti

    def test_access_token_rejected_as_refresh(self, settings: Settings) -> None:
        """Token confusion must not be possible across token types."""
        service = TokenService(settings)
        pair, _ = service.create_pair("user-123")
        with pytest.raises(AuthenticationError):
            service.decode(pair.access_token, "refresh")

    def test_tampered_token_rejected(self, settings: Settings) -> None:
        service = TokenService(settings)
        pair, _ = service.create_pair("user-123")
        with pytest.raises(AuthenticationError):
            service.decode(pair.access_token + "x", "access")

    def test_token_signed_with_another_key_rejected(self, settings: Settings) -> None:
        other = Settings(
            secret_key="a-completely-different-secret-key-for-this-test",
            app_name=settings.app_name,
        )
        foreign, _ = TokenService(other).create_pair("user-123")
        with pytest.raises(AuthenticationError):
            TokenService(settings).decode(foreign.access_token, "access")

    def test_expired_token_rejected(self, settings: Settings) -> None:
        service = TokenService(settings)
        token = service.create_action_token(
            "user-123", "reset_password", timedelta(seconds=-10)
        )
        with pytest.raises(AuthenticationError):
            service.decode(token, "reset_password")


class TestTextCipher:
    def test_round_trip(self) -> None:
        cipher = TextCipher("dGVzdC1lbmNyeXB0aW9uLWtleS0zMi1ieXRlcy1sb25nISE=")
        secret = "חוזה שכירות בין דנה כהן לבין יוסי לוי"
        encrypted = cipher.encrypt(secret)

        assert encrypted != secret
        assert secret not in encrypted
        assert cipher.decrypt(encrypted) == secret

    def test_passphrase_key_is_derived(self) -> None:
        cipher = TextCipher("any passphrase at all")
        assert cipher.decrypt(cipher.encrypt("סוד")) == "סוד"

    def test_disabled_cipher_is_pass_through(self) -> None:
        cipher = TextCipher("")
        assert not cipher.enabled
        assert cipher.encrypt("טקסט") == "טקסט"
        assert cipher.decrypt("טקסט") == "טקסט"

    def test_plaintext_written_before_a_key_existed_still_reads(self) -> None:
        """Enabling encryption later must not corrupt existing rows."""
        cipher = TextCipher("some-key")
        assert cipher.decrypt("legacy plaintext") == "legacy plaintext"
