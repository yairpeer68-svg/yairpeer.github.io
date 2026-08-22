import json
import logging
import re
import sys
from datetime import UTC, datetime
from typing import Any

SENSITIVE_KEYS = re.compile(r"(password|authorization|token|secret|api[_-]?key|cookie)", re.I)
BEARER_RE = re.compile(r"Bearer\s+[A-Za-z0-9._~+\-/]+=*", re.I)
KEY_RE = re.compile(r"\b(?:sk-|ds-)[A-Za-z0-9_-]{16,}\b", re.I)


def redact_text(value: str) -> str:
    return KEY_RE.sub("[REDACTED]", BEARER_RE.sub("Bearer [REDACTED]", value))


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {k: "[REDACTED]" if SENSITIVE_KEYS.search(str(k)) else redact(v) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v) for v in value]
    return value


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": datetime.now(UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "message": redact_text(record.getMessage()),
        }
        for key in ("request_id", "user_id", "path", "status", "latency_ms", "event"):
            if hasattr(record, key):
                payload[key] = getattr(record, key)
        return json.dumps(redact(payload), ensure_ascii=False, separators=(",", ":"))


def configure_logging(level: str = "INFO") -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(level.upper())
