"""Full-text search across every finding of a scan (feature 48).

Flattens every module's result into ``module / field / value`` rows and matches
a query against them — so "password", "CVE-2021", "admin", an IP or a hostname
finds every place it appears, across all modules at once. Ranking is simple and
deterministic: exact field/value hits first, then substring hits, with a short
highlighted snippet per row.
"""

from __future__ import annotations

from typing import Any, Dict, List

from .core import Result
from .reporting import _flatten


def _rows(results: List[Result]) -> List[Dict[str, str]]:
    rows: List[Dict[str, str]] = []
    for r in results:
        flat: Dict[str, Any] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        module = getattr(r, "module", "")
        target = str(getattr(r, "target", ""))
        for field, value in flat.items():
            rows.append({"module": module, "target": target,
                         "field": field, "value": str(value)})
    return rows


def _snippet(text: str, q: str, width: int = 90) -> str:
    low = text.lower()
    i = low.find(q.lower())
    if i < 0:
        return text[:width]
    start = max(0, i - width // 3)
    end = min(len(text), i + len(q) + width // 2)
    s = text[start:end]
    return ("…" if start else "") + s + ("…" if end < len(text) else "")


def full_text_search(results: List[Result], query: str,
                     limit: int = 100) -> Dict[str, Any]:
    """Search every finding for ``query``. Returns ranked matches with a
    highlighted snippet, plus per-module hit counts."""
    q = (query or "").strip()
    if not q:
        return {"query": "", "count": 0, "matches": [], "by_module": {}}
    ql = q.lower()
    matches: List[Dict[str, Any]] = []
    by_module: Dict[str, int] = {}
    for row in _rows(results):
        fl, vl = row["field"].lower(), row["value"].lower()
        if ql not in fl and ql not in vl:
            continue
        # rank: exact value == query (0) > value startswith (1) > field hit (2)
        if vl == ql:
            rank = 0
        elif vl.startswith(ql) or ql in fl:
            rank = 1
        else:
            rank = 2
        matches.append({
            "module": row["module"], "target": row["target"],
            "field": row["field"],
            "snippet": _snippet(row["value"], q),
            "rank": rank,
        })
        by_module[row["module"]] = by_module.get(row["module"], 0) + 1
    matches.sort(key=lambda m: (m["rank"], m["module"]))
    return {
        "query": q,
        "count": len(matches),
        "matches": matches[:limit],
        "by_module": dict(sorted(by_module.items(),
                                 key=lambda kv: kv[1], reverse=True)),
    }
