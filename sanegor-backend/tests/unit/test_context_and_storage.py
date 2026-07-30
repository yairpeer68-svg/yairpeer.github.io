"""Context budgeting, upload validation and template tests."""

from __future__ import annotations

import pytest

from app.core.errors import UnsupportedMediaTypeError, ValidationError
from app.services.ai.context import ContextBuilder, count_tokens, truncate_to_tokens
from app.services.legal.templates import (
    CONTRACT_TEMPLATES,
    LETTER_TEMPLATES,
    list_templates,
)
from app.services.storage import FileStorage, sanitise_filename, sniff_content_type

PDF_BYTES = b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\nfake pdf body"
PNG_BYTES = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
JPEG_BYTES = b"\xff\xd8\xff\xe0" + b"\x00" * 32


class TestTokenCounting:
    def test_empty_string_is_zero(self) -> None:
        assert count_tokens("") == 0

    def test_hebrew_text_counted(self) -> None:
        assert count_tokens("שלום עולם משפטי") > 0

    def test_longer_text_costs_more(self) -> None:
        assert count_tokens("מילה " * 100) > count_tokens("מילה")

    def test_truncate_respects_budget(self) -> None:
        text = "מילה " * 500
        truncated = truncate_to_tokens(text, 50)
        assert count_tokens(truncated) <= 50
        assert truncated  # not emptied entirely

    def test_truncate_leaves_short_text_alone(self) -> None:
        assert truncate_to_tokens("קצר", 100) == "קצר"


class TestContextBuilder:
    def test_question_and_system_prompt_always_present(self) -> None:
        builder = ContextBuilder(8_000, 1_000)
        messages, _ = builder.build(
            system_prompt="הוראות מערכת", history=[], question="מהן זכויותיי?"
        )
        assert messages[0].role == "system"
        assert messages[-1].role == "user"
        assert messages[-1].content == "מהן זכויותיי?"

    def test_history_is_included_when_it_fits(self) -> None:
        builder = ContextBuilder(20_000, 1_000)
        history = [("user", "שאלה קודמת"), ("assistant", "תשובה קודמת")]
        messages, report = builder.build(
            system_prompt="הוראות", history=history, question="שאלה חדשה"
        )
        assert len(messages) == 4
        assert report.dropped_messages == 0

    def test_oldest_turns_are_dropped_first_under_pressure(self) -> None:
        """History is sacrificed before the system prompt or the question."""
        builder = ContextBuilder(1_500, 1_000)  # ~500 tokens of usable budget
        history = [
            ("user", "שאלה ישנה מאוד " * 200),
            ("assistant", "תשובה ישנה מאוד " * 200),
            ("user", "קצר"),
        ]
        messages, report = builder.build(
            system_prompt="הוראות", history=history, question="שאלה"
        )
        assert report.dropped_messages >= 1
        assert messages[0].role == "system"
        assert messages[-1].content == "שאלה"
        # The most recent turn is the one that survives.
        assert any(m.content == "קצר" for m in messages)

    def test_budget_report_is_consistent(self) -> None:
        builder = ContextBuilder(10_000, 1_000)
        _, report = builder.build(
            system_prompt="הוראות ארוכות " * 50, history=[], question="שאלה"
        )
        assert report.used <= report.total
        assert report.remaining == report.total - report.used


class TestUploadValidation:
    def test_pdf_signature_accepted(self) -> None:
        assert sniff_content_type(PDF_BYTES, "application/pdf") == "application/pdf"

    def test_png_signature_accepted(self) -> None:
        assert sniff_content_type(PNG_BYTES, "image/png") == "image/png"

    def test_content_type_mismatch_rejected(self) -> None:
        """A script renamed to .pdf must not be accepted as a PDF."""
        with pytest.raises(UnsupportedMediaTypeError):
            sniff_content_type(PNG_BYTES, "application/pdf")

    def test_unknown_binary_rejected(self) -> None:
        with pytest.raises(UnsupportedMediaTypeError):
            sniff_content_type(b"\x00\x01\x02\x03", "application/pdf")

    def test_utf8_text_accepted(self) -> None:
        assert sniff_content_type("שלום".encode(), "text/plain") == "text/plain"

    @pytest.mark.parametrize(
        ("raw", "forbidden"),
        [
            ("../../etc/passwd", ".."),
            ("file\x00.pdf", "\x00"),
            ("a/b/c.pdf", "/"),
        ],
    )
    def test_filename_sanitisation(self, raw: str, forbidden: str) -> None:
        assert forbidden not in sanitise_filename(raw)

    def test_hebrew_filename_preserved(self) -> None:
        assert "חוזה" in sanitise_filename("חוזה שכירות.pdf")

    def test_empty_filename_gets_a_default(self) -> None:
        assert sanitise_filename("") == "document"


class TestFileStorage:
    async def test_save_and_read_round_trip(self, tmp_path) -> None:  # noqa: ANN001
        storage = FileStorage(tmp_path)
        stored = await storage.save(b"hello", owner_id="user-1", suffix=".txt")

        assert await storage.read(stored.storage_key) == b"hello"
        assert stored.size_bytes == 5

    async def test_traversal_key_is_refused(self, tmp_path) -> None:  # noqa: ANN001
        storage = FileStorage(tmp_path)
        with pytest.raises(ValidationError):
            await storage.read("../../../etc/passwd")

    async def test_delete_is_idempotent(self, tmp_path) -> None:  # noqa: ANN001
        storage = FileStorage(tmp_path)
        stored = await storage.save(b"data", owner_id="user-1")
        await storage.delete(stored.storage_key)
        await storage.delete(stored.storage_key)
        assert not await storage.exists(stored.storage_key)

    async def test_empty_upload_rejected(self, tmp_path) -> None:  # noqa: ANN001
        with pytest.raises(ValidationError):
            await FileStorage(tmp_path).save(b"", owner_id="user-1")


class TestTemplateCatalogue:
    def test_all_nine_contract_types_exist(self) -> None:
        assert len(CONTRACT_TEMPLATES) == 9

    def test_all_ten_letter_types_exist(self) -> None:
        assert len(LETTER_TEMPLATES) == 10

    def test_every_template_has_fields_and_a_name(self) -> None:
        for template in list_templates():
            assert template.fields, template.key
            assert template.name
            assert template.category in {"contract", "letter"}

    def test_serialisation_is_json_safe(self) -> None:
        import json

        for template in list_templates():
            json.dumps(template.to_dict(), ensure_ascii=False)

    def test_instruction_block_mentions_required_sections(self) -> None:
        rental = CONTRACT_TEMPLATES["rental"]
        block = rental.instruction_block()
        assert "חוזה שכירות" in block
        assert "תקופת השכירות" in block
