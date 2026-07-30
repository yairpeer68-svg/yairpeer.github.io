"""Token counting and context-window budgeting.

DeepSeek's window is large but finite, and a long conversation plus a long
document plus retrieved sources will overflow it.  :class:`ContextBuilder`
allocates the budget in priority order — system prompt, current question,
retrieved sources, then as much conversation history as still fits — and drops
the oldest turns first.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from functools import lru_cache

from app.core.logging import get_logger
from app.services.ai.deepseek import ChatMessage

logger = get_logger(__name__)


@lru_cache(maxsize=1)
def _encoder() -> object | None:
    """Load a tiktoken encoder once, tolerating its absence."""
    try:
        import tiktoken

        return tiktoken.get_encoding("cl100k_base")
    except Exception as exc:
        logger.warning("tiktoken_unavailable", error=str(exc))
        return None


def count_tokens(text: str) -> int:
    """Estimate the token count of ``text``.

    Falls back to a character heuristic when tiktoken cannot be loaded.  Hebrew
    encodes to noticeably more tokens per character than English, so the
    fallback uses ~2.2 chars/token rather than the usual 4.
    """
    if not text:
        return 0
    encoder = _encoder()
    if encoder is not None:
        return len(encoder.encode(text))  # type: ignore[attr-defined]
    ascii_chars = sum(1 for ch in text if ord(ch) < 128)
    other_chars = len(text) - ascii_chars
    return int(ascii_chars / 4 + other_chars / 2.2) + 1


def truncate_to_tokens(text: str, max_tokens: int) -> str:
    """Cut ``text`` down to roughly ``max_tokens``, on a word boundary."""
    if max_tokens <= 0:
        return ""
    if count_tokens(text) <= max_tokens:
        return text
    words = text.split(" ")
    low, high = 0, len(words)
    while low < high:
        mid = (low + high + 1) // 2
        if count_tokens(" ".join(words[:mid])) <= max_tokens:
            low = mid
        else:
            high = mid - 1
    return " ".join(words[:low])


@dataclass(slots=True)
class ContextBudget:
    """How the prompt budget was spent — surfaced for logging and tests."""

    total: int
    system_tokens: int = 0
    question_tokens: int = 0
    history_tokens: int = 0
    dropped_messages: int = 0

    @property
    def used(self) -> int:
        return self.system_tokens + self.question_tokens + self.history_tokens

    @property
    def remaining(self) -> int:
        return max(self.total - self.used, 0)


class ContextBuilder:
    """Assembles the message list sent to the model within a token budget."""

    # Reserve room for the answer itself plus per-message framing overhead.
    _MESSAGE_OVERHEAD = 4

    def __init__(self, budget: int, reserved_for_completion: int) -> None:
        self._budget = max(budget - reserved_for_completion, 1_000)

    def build(
        self,
        *,
        system_prompt: str,
        history: Sequence[tuple[str, str]],
        question: str,
    ) -> tuple[list[ChatMessage], ContextBudget]:
        """Assemble ``[system, *history, user]`` within the budget.

        Args:
            system_prompt: Instructions plus rendered source blocks.
            history: ``(role, content)`` pairs, oldest first.
            question: The current user turn — never dropped, only truncated.

        Returns:
            The message list and a report of how the budget was spent.
        """
        report = ContextBudget(total=self._budget)

        report.system_tokens = count_tokens(system_prompt) + self._MESSAGE_OVERHEAD
        available = self._budget - report.system_tokens

        # The current question outranks everything except the system prompt.
        question_allowance = max(int(available * 0.5), 512)
        question_text = truncate_to_tokens(question, question_allowance)
        report.question_tokens = count_tokens(question_text) + self._MESSAGE_OVERHEAD
        available -= report.question_tokens

        # Walk history newest-first so the most recent turns survive.
        kept: list[ChatMessage] = []
        for role, content in reversed(history):
            cost = count_tokens(content) + self._MESSAGE_OVERHEAD
            if cost > available:
                report.dropped_messages += 1
                continue
            kept.append(ChatMessage(role=role, content=content))
            available -= cost
            report.history_tokens += cost
        kept.reverse()

        messages = [
            ChatMessage(role="system", content=system_prompt),
            *kept,
            ChatMessage(role="user", content=question_text),
        ]

        if report.dropped_messages:
            logger.info(
                "context_history_trimmed",
                dropped=report.dropped_messages,
                kept=len(kept),
                used_tokens=report.used,
            )
        return messages, report
