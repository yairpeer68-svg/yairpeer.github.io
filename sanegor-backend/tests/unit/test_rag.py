"""Chunking, ranking and retrieval tests."""

from __future__ import annotations

import pytest
from app.db.models.legal_source import LegalDomain, SourceType
from app.services.ai.embeddings import HashingEmbeddings, cosine_similarity, l2_normalise
from app.services.rag.ingest import CorpusIngestor, SourceDraft, chunk_text
from app.services.rag.ranker import diversify, fuse, rank
from app.services.rag.retriever import LegalRetriever, RetrievedChunk

SAMPLE_STATUTE = """חוק לדוגמה, התשפ"ו-2026

1. הגדרות
בחוק זה, "נכס" - מקרקעין או מיטלטלין.

2. חובת תום לב
בקיום חיוב הנובע מחוזה יש לנהוג בדרך מקובלת ובתום לב.

3. תרופות
הופר חוזה, זכאי הנפגע לתבוע את אכיפתו או לבטל את החוזה.
"""


class TestChunking:
    def test_splits_on_section_markers(self) -> None:
        chunks = chunk_text(SAMPLE_STATUTE, max_tokens=700, overlap_tokens=100)
        assert len(chunks) >= 3
        headings = [heading for heading, _ in chunks if heading]
        assert any("1." in h for h in headings)
        assert any("2." in h for h in headings)

    def test_preserves_all_content(self) -> None:
        chunks = chunk_text(SAMPLE_STATUTE, max_tokens=700, overlap_tokens=100)
        joined = " ".join(body for _, body in chunks)
        assert "תום לב" in joined
        assert "אכיפתו" in joined

    def test_long_section_is_split_with_overlap(self) -> None:
        long_section = "1. סעיף ארוך\n\n" + "\n\n".join(
            f"פסקה מספר {i} עם תוכן משפטי ארוך מאוד " * 12 for i in range(30)
        )
        chunks = chunk_text(long_section, max_tokens=200, overlap_tokens=50)
        assert len(chunks) > 1

    def test_empty_input_yields_nothing(self) -> None:
        assert chunk_text("", max_tokens=700, overlap_tokens=100) == []


class TestEmbeddings:
    async def test_deterministic(self) -> None:
        provider = HashingEmbeddings(128)
        first = await provider.embed_one("חוזה שכירות")
        second = await provider.embed_one("חוזה שכירות")
        assert first == second

    async def test_dimension_respected(self) -> None:
        provider = HashingEmbeddings(64)
        assert len(await provider.embed_one("טקסט")) == 64

    async def test_similar_text_scores_higher_than_unrelated(self) -> None:
        provider = HashingEmbeddings(512)
        query = await provider.embed_one("חוזה שכירות דירה")
        close = await provider.embed_one("חוזה שכירות דירה למגורים")
        far = await provider.embed_one("רישיון נהיגה ובחינה תיאורטית")
        assert cosine_similarity(query, close) > cosine_similarity(query, far)

    def test_l2_normalise_unit_length(self) -> None:
        normalised = l2_normalise([3.0, 4.0])
        assert pytest.approx(sum(v * v for v in normalised), abs=1e-9) == 1.0

    def test_cosine_of_degenerate_input_is_zero(self) -> None:
        assert cosine_similarity([], [1.0]) == 0.0
        assert cosine_similarity([0.0, 0.0], [1.0, 1.0]) == 0.0


def _chunk(chunk_id: str, source_id: str, **kwargs: object) -> RetrievedChunk:
    defaults: dict[str, object] = {
        "ordinal": 0,
        "heading": None,
        "content": "תוכן",
        "citation_key": f"key-{source_id}",
        "title": f"מקור {source_id}",
        "source_type": "ruling",
        "court": None,
        "case_number": None,
        "published_at": None,
        "source_url": None,
    }
    defaults.update(kwargs)
    return RetrievedChunk(chunk_id=chunk_id, source_id=source_id, **defaults)  # type: ignore[arg-type]


class TestRanking:
    def test_fusion_rewards_agreement_between_both_lists(self) -> None:
        shared = _chunk("a", "s1")
        dense = [shared, _chunk("b", "s2")]
        lexical = [_chunk("a", "s1"), _chunk("c", "s3")]

        fused = fuse(dense, lexical)
        assert fused[0].chunk_id == "a"

    def test_legislation_outranks_a_ruling_at_equal_relevance(self) -> None:
        dense = [
            _chunk("ruling", "s1", source_type="ruling", court="magistrate"),
            _chunk("statute", "s2", source_type="legislation"),
        ]
        ranked = rank(dense, [], final_k=2, min_score=0.0)
        assert ranked[0].chunk_id == "statute"

    def test_low_scoring_chunks_are_dropped_not_padded(self) -> None:
        """An irrelevant 'source' is worse than returning fewer sources."""
        dense = [_chunk(f"c{i}", f"s{i}") for i in range(10)]
        ranked = rank(dense, [], final_k=10, min_score=0.9)
        assert len(ranked) < 10

    def test_diversify_caps_chunks_per_source(self) -> None:
        chunks = [_chunk(f"c{i}", "same-source") for i in range(5)]
        assert len(diversify(chunks, max_per_source=2)) == 2

    def test_empty_inputs_produce_empty_output(self) -> None:
        assert rank([], [], final_k=5, min_score=0.0) == []


class TestRetrieverAgainstDatabase:
    async def test_ingested_source_is_retrievable(self, session, embeddings) -> None:
        ingestor = CorpusIngestor(session, embeddings, chunk_tokens=300, overlap_tokens=50)
        await ingestor.ingest(
            SourceDraft(
                citation_key="חוק-לדוגמה-2026",
                title="חוק לדוגמה",
                content=SAMPLE_STATUTE,
                source_type=SourceType.LEGISLATION,
                domain=LegalDomain.CONTRACTS,
                publisher="בדיקה",
            )
        )
        await session.flush()

        retriever = LegalRetriever(session)
        query_vector = await embeddings.embed_one("חובת תום לב בקיום חוזה")
        results = await retriever.dense_search(query_vector, limit=5)

        assert results
        assert any("תום לב" in r.content for r in results)

    async def test_reingesting_identical_content_is_a_no_op(self, session, embeddings) -> None:
        ingestor = CorpusIngestor(session, embeddings)
        draft = SourceDraft(
            citation_key="חוק-לדוגמה-2026",
            title="חוק לדוגמה",
            content=SAMPLE_STATUTE,
            source_type=SourceType.LEGISLATION,
            publisher="בדיקה",
        )
        first = await ingestor.ingest(draft)
        chunk_count = first.chunk_count
        second = await ingestor.ingest(draft)

        assert second.id == first.id
        assert second.chunk_count == chunk_count

    async def test_lexical_search_finds_exact_terms(self, session, embeddings) -> None:
        ingestor = CorpusIngestor(session, embeddings)
        await ingestor.ingest(
            SourceDraft(
                citation_key="חוק-לדוגמה-2026",
                title="חוק לדוגמה",
                content=SAMPLE_STATUTE,
                source_type=SourceType.LEGISLATION,
                publisher="בדיקה",
            )
        )
        await session.flush()

        results = await LegalRetriever(session).lexical_search("אכיפתו", limit=5)
        assert results
