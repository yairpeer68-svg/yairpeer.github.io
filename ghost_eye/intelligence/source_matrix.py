"""Asset source-attribution matrix.

The breadth of free OSINT sources only pays off if you can see *which* sources
agree. This maps every discovered asset (subdomain, IP, e-mail) to the exact set
of modules that reported it, so an analyst can tell a high-confidence asset
(seen by many independent sources) from a one-off:

    api.example.com   ← certspotter, hackertarget, otxrep, anubisjldc  (4)
    weird.example.com ← sitedossier                                     (1)

Correlation only — reasons over what the modules already returned. No network.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Set

from ..core import Result
from ..reporting import _flatten

_HOST = re.compile(r"\b([a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?"
                   r"(?:\.[a-z0-9\-]{1,63})+)\b")
_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
_EMAIL = re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b")


def _valid_ip(s: str) -> bool:
    p = s.split(".")
    return (len(p) == 4 and all(x.isdigit() and 0 <= int(x) <= 255 for x in p)
            and not s.startswith(("0.", "127.", "255.")))


def source_matrix(results: List[Result], target: str = "") -> Dict[str, Any]:
    """Build the per-asset source-attribution matrix."""
    tgt = (target or "").lower().lstrip(".")
    hosts: Dict[str, Set[str]] = {}
    ips: Dict[str, Set[str]] = {}
    emails: Dict[str, Set[str]] = {}

    for r in results:
        module = getattr(r, "module", "") or "?"
        flat: Dict[str, Any] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        blob = " ".join(f"{k} {v}" for k, v in flat.items()).lower()
        for h in set(_HOST.findall(blob)):
            if tgt and (h == tgt or h.endswith("." + tgt)):
                hosts.setdefault(h, set()).add(module)
        for ip in set(_IPV4.findall(blob)):
            if _valid_ip(ip):
                ips.setdefault(ip, set()).add(module)
        for em in set(_EMAIL.findall(blob)):
            if not tgt or em.endswith("@" + tgt) or ("@" + tgt) in em:
                emails.setdefault(em, set()).add(module)

    def _rank(d: Dict[str, Set[str]]) -> List[dict]:
        rows = [{"asset": a, "sources": sorted(s), "corroboration": len(s),
                 "confidence": "high" if len(s) >= 3 else
                 "medium" if len(s) == 2 else "low"}
                for a, s in d.items()]
        rows.sort(key=lambda x: (-x["corroboration"], x["asset"]))
        return rows

    host_rows, ip_rows, email_rows = _rank(hosts), _rank(ips), _rank(emails)
    multi = [r for r in host_rows if r["corroboration"] >= 2]
    return {
        "subdomains": host_rows[:200],
        "ips": ip_rows[:100],
        "emails": email_rows[:100],
        "summary": {
            "subdomains": len(host_rows),
            "ips": len(ip_rows),
            "emails": len(email_rows),
            "multi_source_subdomains": len(multi),
            "distinct_sources": len({s for v in hosts.values() for s in v}),
        },
        "note": "each asset is attributed to the modules that reported it; more "
                "independent sources = higher confidence.",
    }
