"""End-to-end API tests through the ASGI transport."""

from __future__ import annotations

import httpx
import pytest

pytestmark = pytest.mark.integration


class TestHealth:
    async def test_liveness(self, client: httpx.AsyncClient) -> None:
        response = await client.get("/health/live")
        assert response.status_code == 200
        assert response.json()["status"] == "alive"

    async def test_health_reports_feature_flags(self, client: httpx.AsyncClient) -> None:
        body = (await client.get("/health")).json()
        assert body["status"] == "ok"
        assert "deepseek" in body["features"]

    async def test_root_carries_the_legal_notice(self, client: httpx.AsyncClient) -> None:
        body = (await client.get("/")).json()
        assert "ייעוץ משפטי" in body["notice"]

    async def test_security_headers_present(self, client: httpx.AsyncClient) -> None:
        headers = (await client.get("/health/live")).headers
        assert headers["X-Content-Type-Options"] == "nosniff"
        assert headers["X-Frame-Options"] == "DENY"
        assert "X-Request-ID" in headers


class TestRegistrationAndLogin:
    async def test_register_returns_tokens(self, client: httpx.AsyncClient) -> None:
        response = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "new@example.co.il",
                "password": "Sisma-Hazaka-2026",
                "full_name": "יוסי לוי",
            },
        )
        assert response.status_code == 201
        body = response.json()
        assert body["user"]["email"] == "new@example.co.il"
        assert body["tokens"]["access_token"]
        assert body["user"]["role"] == "user"

    async def test_password_is_never_returned(self, client: httpx.AsyncClient) -> None:
        response = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "leak@example.co.il",
                "password": "Sisma-Hazaka-2026",
                "full_name": "בדיקה",
            },
        )
        assert "password" not in response.text.lower()

    async def test_weak_password_rejected(self, client: httpx.AsyncClient) -> None:
        response = await client.post(
            "/api/v1/auth/register",
            json={"email": "weak@example.co.il", "password": "12345678", "full_name": "בדיקה"},
        )
        assert response.status_code == 422

    async def test_duplicate_email_conflicts(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        response = await client.post(
            "/api/v1/auth/register",
            json={
                "email": registered_user["email"],
                "password": "Sisma-Hazaka-2026",
                "full_name": "כפילות",
            },
        )
        assert response.status_code == 409
        assert response.json()["error"]["code"] == "conflict"

    async def test_login_succeeds(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": "Sisma-Hazaka-2026"},
        )
        assert response.status_code == 200
        assert response.json()["tokens"]["access_token"]

    async def test_wrong_password_rejected(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        response = await client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": "wrong-password-1"},
        )
        assert response.status_code == 401

    async def test_unknown_and_known_emails_are_indistinguishable(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        """The login endpoint must not leak which addresses are registered."""
        unknown = await client.post(
            "/api/v1/auth/login",
            json={"email": "nobody@example.co.il", "password": "whatever-123"},
        )
        known = await client.post(
            "/api/v1/auth/login",
            json={"email": registered_user["email"], "password": "whatever-123"},
        )
        assert unknown.status_code == known.status_code == 401
        assert unknown.json()["error"]["message"] == known.json()["error"]["message"]


class TestTokenLifecycle:
    async def test_me_requires_a_token(self, client: httpx.AsyncClient) -> None:
        assert (await client.get("/api/v1/auth/me")).status_code == 401

    async def test_me_returns_the_account(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get("/api/v1/auth/me", headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["email"] == "dana@example.co.il"

    async def test_garbage_token_rejected(self, client: httpx.AsyncClient) -> None:
        response = await client.get(
            "/api/v1/auth/me", headers={"Authorization": "Bearer not-a-jwt"}
        )
        assert response.status_code == 401

    async def test_refresh_rotates_the_token(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        response = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": registered_user["refresh_token"]},
        )
        assert response.status_code == 200
        assert response.json()["tokens"]["refresh_token"] != registered_user["refresh_token"]

    async def test_refresh_token_is_single_use(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        """A replayed refresh token must be rejected after rotation."""
        payload = {"refresh_token": registered_user["refresh_token"]}
        assert (await client.post("/api/v1/auth/refresh", json=payload)).status_code == 200
        assert (await client.post("/api/v1/auth/refresh", json=payload)).status_code == 401

    async def test_access_token_cannot_be_used_as_refresh(
        self, client: httpx.AsyncClient, registered_user: dict[str, str]
    ) -> None:
        response = await client.post(
            "/api/v1/auth/refresh",
            json={"refresh_token": registered_user["access_token"]},
        )
        assert response.status_code == 401


class TestProfile:
    async def test_update_profile(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.patch(
            "/api/v1/auth/me",
            headers=auth_headers,
            json={"full_name": "דנה כהן-לוי", "preferences": {"theme": "dark"}},
        )
        assert response.status_code == 200
        assert response.json()["full_name"] == "דנה כהן-לוי"
        assert response.json()["preferences"]["theme"] == "dark"

    async def test_preferences_merge_rather_than_replace(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        await client.patch(
            "/api/v1/auth/me", headers=auth_headers, json={"preferences": {"theme": "dark"}}
        )
        response = await client.patch(
            "/api/v1/auth/me", headers=auth_headers, json={"preferences": {"locale": "he"}}
        )
        preferences = response.json()["preferences"]
        assert preferences["theme"] == "dark"
        assert preferences["locale"] == "he"


class TestTemplateEndpoints:
    async def test_contract_templates_listed(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get("/api/v1/contracts/templates", headers=auth_headers)
        assert response.status_code == 200
        templates = response.json()
        assert len(templates) == 9
        assert {t["key"] for t in templates} >= {"rental", "employment", "nda"}

    async def test_letter_templates_listed(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get("/api/v1/letters/templates", headers=auth_headers)
        assert response.status_code == 200
        assert len(response.json()) == 10

    async def test_templates_require_authentication(self, client: httpx.AsyncClient) -> None:
        assert (await client.get("/api/v1/contracts/templates")).status_code == 401


class TestSearch:
    async def test_empty_corpus_is_reported_explicitly(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        """The app must be able to say 'nothing is loaded', not 'nothing found'."""
        response = await client.post(
            "/api/v1/search", headers=auth_headers, json={"query": "חוזה שכירות"}
        )
        assert response.status_code == 200
        body = response.json()
        assert body["corpus_empty"] is True
        assert body["notice"]
        assert body["sources"] == []

    async def test_stats_reports_an_empty_corpus(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        body = (await client.get("/api/v1/search/stats", headers=auth_headers)).json()
        assert body["sources_total"] == 0
        assert body["corpus_empty"] is True

    async def test_unknown_citation_key_is_404(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get("/api/v1/search/sources/does-not-exist", headers=auth_headers)
        assert response.status_code == 404


class TestAuthorisation:
    async def test_admin_endpoints_refused_to_ordinary_users(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get("/api/v1/admin/users", headers=auth_headers)
        assert response.status_code == 403
        assert response.json()["error"]["code"] == "forbidden"

    async def test_one_user_cannot_read_another_users_document(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        upload = await client.post(
            "/api/v1/documents/upload",
            headers=auth_headers,
            files={"file": ("private.txt", "סודי ביותר".encode(), "text/plain")},
        )
        assert upload.status_code == 201
        document_id = upload.json()["document"]["id"]

        other = await client.post(
            "/api/v1/auth/register",
            json={
                "email": "other@example.co.il",
                "password": "Sisma-Hazaka-2026",
                "full_name": "אחר",
            },
        )
        other_headers = {"Authorization": f"Bearer {other.json()['tokens']['access_token']}"}
        response = await client.get(f"/api/v1/documents/{document_id}", headers=other_headers)
        assert response.status_code == 404


class TestDocumentUpload:
    async def test_text_upload_extracts_content(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        content = "חוזה שכירות בין המשכיר לשוכר.\n\n1. דמי השכירות יעמדו על 4,500 ש״ח."
        response = await client.post(
            "/api/v1/documents/upload",
            headers=auth_headers,
            files={"file": ("contract.txt", content.encode(), "text/plain")},
        )
        assert response.status_code == 201
        document = response.json()["document"]
        assert document["status"] == "ready"
        assert document["word_count"] > 0
        assert document["language"] == "he"

    async def test_extracted_text_can_be_read_back(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        content = "טקסט מוצפן במנוחה"
        upload = await client.post(
            "/api/v1/documents/upload",
            headers=auth_headers,
            files={"file": ("note.txt", content.encode(), "text/plain")},
        )
        document_id = upload.json()["document"]["id"]
        response = await client.get(f"/api/v1/documents/{document_id}/text", headers=auth_headers)
        assert response.status_code == 200
        assert content in response.json()["text"]

    async def test_disallowed_type_rejected(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.post(
            "/api/v1/documents/upload",
            headers=auth_headers,
            files={"file": ("evil.exe", b"MZ\x90\x00", "application/x-msdownload")},
        )
        assert response.status_code == 415

    async def test_deleted_document_is_gone(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        upload = await client.post(
            "/api/v1/documents/upload",
            headers=auth_headers,
            files={"file": ("temp.txt", "זמני".encode(), "text/plain")},
        )
        document_id = upload.json()["document"]["id"]

        assert (
            await client.delete(f"/api/v1/documents/{document_id}", headers=auth_headers)
        ).status_code == 200
        assert (
            await client.get(f"/api/v1/documents/{document_id}", headers=auth_headers)
        ).status_code == 404

    async def test_document_list_is_scoped_to_the_owner(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        await client.post(
            "/api/v1/documents/upload",
            headers=auth_headers,
            files={"file": ("mine.txt", "שלי".encode(), "text/plain")},
        )
        response = await client.get("/api/v1/documents", headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["total"] >= 1


class TestHistory:
    async def test_empty_history(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get("/api/v1/history", headers=auth_headers)
        assert response.status_code == 200
        assert response.json()["total"] == 0

    async def test_unknown_conversation_is_404(
        self, client: httpx.AsyncClient, auth_headers: dict[str, str]
    ) -> None:
        response = await client.get(
            "/api/v1/history/00000000-0000-0000-0000-000000000000", headers=auth_headers
        )
        assert response.status_code == 404


class TestErrorEnvelope:
    async def test_validation_errors_use_the_standard_shape(
        self, client: httpx.AsyncClient
    ) -> None:
        response = await client.post("/api/v1/auth/login", json={"email": "not-an-email"})
        assert response.status_code == 422
        error = response.json()["error"]
        assert error["code"] == "validation_error"
        assert "fields" in error["details"]

    async def test_errors_carry_a_request_id(self, client: httpx.AsyncClient) -> None:
        response = await client.get("/api/v1/auth/me")
        assert response.json()["error"]["request_id"]

    async def test_unknown_route_is_404(self, client: httpx.AsyncClient) -> None:
        assert (await client.get("/api/v1/nope")).status_code == 404


class TestOpenApi:
    async def test_schema_is_generated(self, client: httpx.AsyncClient) -> None:
        response = await client.get("/openapi.json")
        assert response.status_code == 200
        schema = response.json()
        assert schema["info"]["title"]
        # Every endpoint the SRS lists must be present.
        for path in (
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/chat",
            "/api/v1/documents/upload",
            "/api/v1/contracts/generate",
            "/api/v1/letters/generate",
            "/api/v1/analysis/document",
            "/api/v1/analysis/contract",
            "/api/v1/history",
            "/api/v1/documents",
        ):
            assert path in schema["paths"], path
