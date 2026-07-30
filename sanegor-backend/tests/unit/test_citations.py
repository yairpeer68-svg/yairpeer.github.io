"""Citation-engine tests.

These cover the product's hardest requirement: the system must never present a
reference it cannot resolve to a row in the corpus.
"""

from __future__ import annotations

from app.services.ai.citations import (
    Citation,
    StreamingCitationGuard,
    validate_and_collect,
)
from app.services.ai.prompts import SourceBlock


def _blocks(count: int) -> list[SourceBlock]:
    return [
        SourceBlock(
            index=i,
            citation_key=f"key-{i}",
            title=f"חוק לדוגמה {i}",
            heading=f"סעיף {i}",
            content=f"תוכן {i}",
        )
        for i in range(1, count + 1)
    ]


def _citations(count: int) -> dict[int, Citation]:
    return {
        i: Citation(
            index=i,
            citation_key=f"key-{i}",
            title=f"חוק לדוגמה {i}",
            source_type="legislation",
        )
        for i in range(1, count + 1)
    }


class TestValidateAndCollect:
    def test_keeps_valid_markers(self) -> None:
        outcome = validate_and_collect(
            "לפי הדין [מקור 1] וגם [מקור 2].", _blocks(2), _citations(2)
        )
        assert "[מקור 1]" in outcome.text
        assert "[מקור 2]" in outcome.text
        assert [c.index for c in outcome.citations] == [1, 2]
        assert not outcome.had_hallucinated_markers

    def test_strips_markers_outside_the_retrieved_set(self) -> None:
        """A model citing source 7 when only 2 were supplied is hallucinating."""
        outcome = validate_and_collect(
            "כך נקבע [מקור 7] ובנוסף [מקור 1].", _blocks(2), _citations(2)
        )
        assert "[מקור 7]" not in outcome.text
        assert "[מקור 1]" in outcome.text
        assert outcome.removed_markers == [7]
        assert outcome.had_hallucinated_markers
        assert [c.index for c in outcome.citations] == [1]

    def test_partially_valid_group_keeps_only_real_indices(self) -> None:
        outcome = validate_and_collect(
            "ראו [מקור 1, 9].", _blocks(2), _citations(2)
        )
        assert "[מקור 1]" in outcome.text
        assert "9" not in outcome.text
        assert outcome.removed_markers == [9]

    def test_no_sources_means_no_citations_survive(self) -> None:
        """With an empty corpus every marker must be removed."""
        outcome = validate_and_collect("לפי החוק [מקור 1].", [], {})
        assert "[מקור" not in outcome.text
        assert outcome.citations == []
        assert outcome.removed_markers == [1]

    def test_deduplicates_repeated_citations(self) -> None:
        outcome = validate_and_collect(
            "[מקור 1] וגם שוב [מקור 1].", _blocks(1), _citations(1)
        )
        assert len(outcome.citations) == 1

    def test_plain_text_is_untouched(self) -> None:
        text = "תשובה ללא אסמכתאות כלל."
        assert validate_and_collect(text, _blocks(2), _citations(2)).text == text


class TestStreamingCitationGuard:
    def test_passes_valid_marker_through(self) -> None:
        guard = StreamingCitationGuard({1, 2})
        out = guard.feed("לפי [מקור 1] הדין") + guard.flush()
        assert "[מקור 1]" in out
        assert guard.used_indices == {1}

    def test_marker_split_across_deltas_is_reassembled(self) -> None:
        """Token boundaries must not let a marker slip through unchecked."""
        guard = StreamingCitationGuard({1})
        out = "".join(guard.feed(part) for part in ["לפי [מק", "ור ", "1] הדין"])
        out += guard.flush()
        assert "[מקור 1]" in out
        assert guard.used_indices == {1}

    def test_invalid_marker_split_across_deltas_is_removed(self) -> None:
        guard = StreamingCitationGuard({1})
        out = "".join(guard.feed(part) for part in ["ראו [מקו", "ר 8] וגם"])
        out += guard.flush()
        assert "8" not in out
        assert guard.removed_indices == {8}

    def test_ordinary_bracket_is_not_swallowed(self) -> None:
        guard = StreamingCitationGuard({1})
        out = guard.feed("רשימה [א] ועוד") + guard.flush()
        assert "[א]" in out

    def test_flush_emits_trailing_partial_text(self) -> None:
        guard = StreamingCitationGuard({1})
        guard.feed("סוף המשפט [")
        assert guard.flush() == "["

    def test_output_text_is_preserved_apart_from_markers(self) -> None:
        guard = StreamingCitationGuard({1})
        chunks = ["שלום ", "עולם ", "[מקור 5] ", "סוף"]
        out = "".join(guard.feed(c) for c in chunks) + guard.flush()
        assert "שלום" in out and "עולם" in out and "סוף" in out
        assert "מקור 5" not in out
