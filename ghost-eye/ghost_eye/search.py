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


def _rows_from_dicts(results: List[Dict[str, Any]],
                     target: str = "") -> List[Dict[str, str]]:
    """Same flattening, for results loaded back out of storage.

    Stored scans are plain dicts, not ``Result`` objects, and there is no point
    rehydrating a class just to read two attributes off it.
    """
    rows: List[Dict[str, str]] = []
    for r in results or []:
        if not isinstance(r, dict):
            continue
        flat: Dict[str, Any] = {}
        _flatten("", r.get("data") or {}, flat)
        module = str(r.get("module") or "")
        tgt = str(r.get("target") or target)
        for field, value in flat.items():
            rows.append({"module": module, "target": tgt,
                         "field": field, "value": str(value)})
    return rows


def search_scans(scans: List[Dict[str, Any]], query: str,
                 limit: int = 200) -> Dict[str, Any]:
    """Search across *stored* scans, not just the one on screen.

    This is the question you actually have six months in: "where have I ever
    seen this IP / this header / this CVE?" — which no per-scan search can
    answer. Each match carries the scan it came from so you can open it.

    ``scans`` is a list of ``{id, target, ts, results}`` as ``Store`` returns.
    Results are ranked exactly like :func:`full_text_search`, then newest scan
    first within a rank, because a stale hit is usually the less interesting one.
    """
    q = (query or "").strip()
    if not q:
        return {"query": "", "count": 0, "matches": [], "scanned": 0,
                "by_target": {}}
    ql = q.lower()
    matches: List[Dict[str, Any]] = []
    by_target: Dict[str, int] = {}
    # Scans whose *target* matches but whose findings do not. Searching for a
    # hostname you have scanned and being told "nothing matches" is a lie by
    # omission: the answer is "yes, three times — just not in any value".
    target_matches: List[Dict[str, str]] = []
    for scan in scans or []:
        sid = str(scan.get("id") or "")
        tgt = str(scan.get("target") or "")
        ts = str(scan.get("ts") or "")
        before = len(matches)
        for row in _rows_from_dicts(scan.get("results") or [], tgt):
            fl, vl = row["field"].lower(), row["value"].lower()
            if ql not in fl and ql not in vl:
                continue
            if vl == ql:
                rank = 0
            elif vl.startswith(ql) or ql in fl:
                rank = 1
            else:
                rank = 2
            matches.append({"scan_id": sid, "target": row["target"] or tgt,
                            "ts": ts, "module": row["module"],
                            "field": row["field"],
                            "snippet": _snippet(row["value"], q),
                            "rank": rank})
            by_target[tgt] = by_target.get(tgt, 0) + 1
        if len(matches) == before and ql in tgt.lower():
            target_matches.append({"scan_id": sid, "target": tgt, "ts": ts})
    # newest first, then stable-sorted by rank: Python's sort is stable, so the
    # recency ordering survives inside each rank band.
    matches.sort(key=lambda m: m["ts"], reverse=True)
    matches.sort(key=lambda m: m["rank"])
    target_matches.sort(key=lambda m: m["ts"], reverse=True)
    return {
        "query": q,
        "count": len(matches),
        "scanned": len(scans or []),
        "matches": matches[:limit],
        "target_matches": target_matches[:limit],
        "by_target": dict(sorted(by_target.items(),
                                 key=lambda kv: kv[1], reverse=True)),
    }


def dedup_findings(results: List[Result]) -> Dict[str, Any]:
    """Collapse duplicate findings across modules (feature 76): the same
    field=value seen in several modules is reported once, with the list of
    modules that produced it. Returns {unique, duplicates_removed, findings}."""
    buckets: Dict[tuple, Dict[str, Any]] = {}
    total = 0
    for row in _rows(results):
        total += 1
        key = (row["field"].lower(), row["value"].lower())
        b = buckets.get(key)
        if not b:
            buckets[key] = {"field": row["field"], "value": row["value"][:200],
                            "modules": [row["module"]], "count": 1}
        else:
            b["count"] += 1
            if row["module"] not in b["modules"]:
                b["modules"].append(row["module"])
    unique = list(buckets.values())
    return {
        "total_findings": total,
        "unique": len(unique),
        "duplicates_removed": total - len(unique),
        "findings": sorted(unique, key=lambda x: x["count"], reverse=True)[:200],
    }


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
