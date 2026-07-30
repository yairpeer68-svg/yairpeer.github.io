"""Citation engine.

The model is shown numbered ``[מקור N]`` blocks and is told to reference them.
Nothing stops a language model from emitting ``[מקור 9]`` when only four blocks
were supplied, so this module treats the model's output as untrusted:

* every marker is matched against the set of blocks that were actually
  retrieved for this request;
* markers pointing outside that set are removed from the answer text;
* the returned citation list is built from the retrieved rows — never from
  anything the model wrote.

The result is that a citation shown in the app always resolves to a row in
``legal_sources``.
"""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from typing import Any

from app.core.logging import get_logger
from app.services.ai.prompts import SourceBlock

logger = get_logger(__name__)

# Matches [מקור 3] and [מקור 3, 5] / [מקור 3,5]
_MARKER_RE = re.compile(r"\[מקור\s*([\d\s,،]+)\]")


@dataclass(frozen=True, slots=True)
class Citation:
    """A source the answer actually relies on, safe to render in the app."""

    index: int
    citation_key: str
    title: str
    source_type: str
    heading: str | None = None
    court: str | None = None
    case_number: str | None = None
    published_at: str | None = None
    url: str | None = None
    snippet: str = ""
    score: float = 0.0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class CitationOutcome:
    """Result of validating an answer against its retrieved sources."""

    text: str
    citations: list[Citation]
    removed_markers: list[int]

    @property
    def had_hallucinated_markers(self) -> bool:
        return bool(self.removed_markers)


def _parse_marker_group(raw: str) -> list[int]:
    """Split the digits inside a single ``[מקור ...]`` marker."""
    return [int(part) for part in re.split(r"[,،\s]+", raw.strip()) if part.isdigit()]


def validate_and_collect(
    answer: str,
    blocks: list[SourceBlock],
    citations_by_index: dict[int, Citation],
) -> CitationOutcome:
    """Strip unknown markers from ``answer`` and return the citations it uses.

    Args:
        answer: Raw model output.
        blocks: The source blocks that were placed in the prompt.
        citations_by_index: Citation metadata keyed by the same 1-based index.

    Returns:
        The cleaned answer plus the citations it legitimately references.
    """
    valid_indices = {block.index for block in blocks}
    removed: list[int] = []
    used: list[int] = []

    def _replace(match: re.Match[str]) -> str:
        indices = _parse_marker_group(match.group(1))
        kept = [i for i in indices if i in valid_indices]
        removed.extend(i for i in indices if i not in valid_indices)
        if not kept:
            return ""
        used.extend(kept)
        return "[מקור " + ", ".join(str(i) for i in kept) + "]"

    cleaned = _MARKER_RE.sub(_replace, answer)
    # Collapse whitespace left behind by removed markers.
    cleaned = re.sub(r"[ \t]{2,}", " ", cleaned)
    cleaned = re.sub(r"\s+([.,;:!?])", r"\1", cleaned)

    if removed:
        logger.warning(
            "citation_markers_removed",
            removed=sorted(set(removed)),
            available=sorted(valid_indices),
        )

    seen: set[int] = set()
    citations: list[Citation] = []
    for index in used:
        if index in seen:
            continue
        seen.add(index)
        if (citation := citations_by_index.get(index)) is not None:
            citations.append(citation)

    return CitationOutcome(
        text=cleaned.strip(), citations=citations, removed_markers=sorted(set(removed))
    )


class StreamingCitationGuard:
    """Removes invalid ``[מקור N]`` markers from a token stream.

    A marker can straddle two SSE deltas, so text is only released once it can
    no longer be part of an in-progress marker.  ``flush`` must be called at the
    end of the stream to emit the tail.
    """

    _OPEN = "["
    # Longest prefix we might have to hold back, e.g. "[מקור 12, 3"
    _MAX_HOLD = 24

    def __init__(self, valid_indices: set[int]) -> None:
        self._valid = valid_indices
        self._buffer = ""
        self.used_indices: set[int] = set()
        self.removed_indices: set[int] = set()

    def feed(self, delta: str) -> str:
        """Consume a stream delta and return the text that is safe to emit."""
        self._buffer += delta
        out: list[str] = []

        while True:
            open_at = self._buffer.find(self._OPEN)
            if open_at == -1:
                out.append(self._buffer)
                self._buffer = ""
                break

            out.append(self._buffer[:open_at])
            candidate = self._buffer[open_at:]

            match = _MARKER_RE.match(candidate)
            if match:
                self._buffer = candidate[match.end() :]
                out.append(self._render(match))
                continue

            # No complete marker yet. Hold on only while it could still become
            # one; otherwise the '[' was ordinary text.
            if self._could_become_marker(candidate):
                self._buffer = candidate
                break
            out.append(self._OPEN)
            self._buffer = candidate[1:]

        return "".join(out)

    def flush(self) -> str:
        """Emit whatever is still buffered at the end of the stream."""
        remaining = self._buffer
        self._buffer = ""
        match = _MARKER_RE.match(remaining)
        if match:
            return self._render(match) + remaining[match.end() :]
        return remaining

    def _render(self, match: re.Match[str]) -> str:
        indices = _parse_marker_group(match.group(1))
        kept = [i for i in indices if i in self._valid]
        self.removed_indices.update(i for i in indices if i not in self._valid)
        if not kept:
            return ""
        self.used_indices.update(kept)
        return "[מקור " + ", ".join(str(i) for i in kept) + "]"

    def _could_become_marker(self, candidate: str) -> bool:
        if len(candidate) > self._MAX_HOLD:
            return False
        prefix = "[מקור "
        shorter = min(len(candidate), len(prefix))
        return candidate[:shorter] == prefix[:shorter]
