"""Confidence & provenance model.

The value of a recon tool is trust in its output, and 553 modules that report
flatly ("here is a finding") give the reader no way to tell a *directly
observed* fact (a TLS certificate we fetched) from a *third-party claim* (a
reputation feed) from a *heuristic guess* (a keyword matched in HTML). This
module attaches, to every finding, a **confidence** and a **provenance** — with
no change required to the 553 modules, because it infers them from how the
finding was produced, while letting a module override when it knows better.

A module overrides by putting hints in its result data:

    data["_confidence"] = "high"          # confirmed | high | medium | low
    data["_provenance"] = "direct"        # direct | third_party | heuristic

or per-hit, as the data-driven OSINT modules already do (each match carries its
own ``confidence``). Everything else is inferred from the module's nature.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

# ordered strongest -> weakest
LEVELS = ("confirmed", "high", "medium", "low")
_ORDER = {lvl: i for i, lvl in enumerate(LEVELS)}

# how a module obtains its data -> how much we should trust it by default.
# matched as stems against each word of the module name (so "headers" matches
# the "header" stem, "certificate" matches "cert", etc.).
_DIRECT = (
    "header", "cert", "tls", "ssl", "cipher", "dns", "axfr", "caa", "dnssec",
    "port", "banner", "whois", "rdap", "http", "crawl", "robot", "sitemap",
    "cookie", "redirect", "traceroute", "ping", "smtp", "starttls", "spf",
    "dkim", "dmarc", "favicon", "screenshot", "scan", "hsts", "cors", "csp")
_HEURISTIC = (
    "indicator", "surface", "likely", "possible", "heuristic", "guess",
    "maybe", "candidate", "pattern", "infer", "suspect", "prediction")
_THIRD_PARTY = (
    "osint", "wayback", "github", "gitlab", "pastebin", "paste", "threat",
    "reputation", "feed", "breach", "leak", "shodan", "censys", "virustotal",
    "abuseipdb", "otx", "greynoise", "passive", "pdns", "geoip", "ipapi",
    "social", "username", "gravatar", "search", "recon", "intel")


def _has_stem(name: str, stems) -> bool:
    """True if any word in `name` starts with any of `stems`."""
    words = re.split(r"[^a-z0-9]+", (name or "").lower())
    return any(w.startswith(stem) for w in words if w for stem in stems)

_PROV_LABEL = {
    "direct": "direct observation (we contacted the asset)",
    "third_party": "third-party source (a service reported this)",
    "heuristic": "heuristic / pattern match (not verified)",
}
_PROV_CONF = {"direct": "high", "third_party": "medium", "heuristic": "low"}


def provenance_of(module: str, data: Optional[Dict[str, Any]] = None) -> str:
    """Classify how a finding was obtained: direct | third_party | heuristic."""
    if data and isinstance(data, dict):
        override = str(data.get("_provenance", "")).lower()
        if override in _PROV_LABEL:
            return override
    m = module or ""
    # heuristic wins over direct when a module is explicitly indicator-based;
    # third-party wins over direct so an OSINT source that also probes http
    # (e.g. "OSINT http mirror") is still counted as a third-party claim
    if _has_stem(m, _HEURISTIC):
        return "heuristic"
    if _has_stem(m, _THIRD_PARTY):
        return "third_party"
    if _has_stem(m, _DIRECT):
        return "direct"
    return "third_party"          # unknown OSINT source -> treat as a claim


def _from_text(detail: str) -> Optional[str]:
    """A finding that literally says 'possible/likely/indicator' is low
    confidence no matter which module produced it."""
    d = (detail or "").lower()
    if any(w in d for w in ("possibl", "likel", "may be", "might be", "maybe",
                            "indicator", "candidate", "unverif", "heuristic",
                            "suspect", "could be")):
        return "low"
    if any(w in d for w in ("confirmed", "verified", "public exploit")):
        return "confirmed"
    return None


def confidence_of(module: str, detail: str = "",
                  data: Optional[Dict[str, Any]] = None,
                  explicit: Optional[str] = None) -> str:
    """Best confidence for a finding: explicit override > per-finding text cue
    > provenance-derived default."""
    if explicit and str(explicit).lower() in _ORDER:
        return str(explicit).lower()
    if data and isinstance(data, dict):
        ov = str(data.get("_confidence", "")).lower()
        if ov in _ORDER:
            return ov
    cue = _from_text(detail)
    if cue:
        return cue
    return _PROV_CONF[provenance_of(module, data)]


def annotate_finding(finding: Dict[str, Any],
                     data: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Attach confidence + provenance to a finding dict in place, and return it.

    `finding` is expected to have at least `module`; `detail` is used as a text
    cue when present. `data` is the owning module's result data (for overrides).
    """
    module = finding.get("module", "")
    prov = provenance_of(module, data)
    conf = confidence_of(module, finding.get("detail", ""), data,
                         explicit=finding.get("confidence"))
    finding["confidence"] = conf
    finding["provenance"] = prov
    finding["provenance_label"] = _PROV_LABEL[prov]
    return finding


def summarize(findings: List[Dict[str, Any]]) -> Dict[str, Any]:
    """Roll up confidence across findings for a report header."""
    counts = {lvl: 0 for lvl in LEVELS}
    prov_counts: Dict[str, int] = {}
    for f in findings:
        c = str(f.get("confidence", "")).lower()
        if c in counts:
            counts[c] += 1
        p = f.get("provenance", "")
        if p:
            prov_counts[p] = prov_counts.get(p, 0) + 1
    verified = counts["confirmed"] + counts["high"]
    total = sum(counts.values())
    return {
        "by_confidence": counts,
        "by_provenance": prov_counts,
        "verified_fraction": round(verified / total, 2) if total else 0.0,
        "note": "confidence is inferred from how each finding was obtained — "
                "direct observations rank above third-party claims above "
                "heuristic/pattern matches; modules may override.",
    }


def rank(level: str) -> int:
    """Sort key: 0 = strongest. Unknown levels sort last."""
    return _ORDER.get(str(level).lower(), len(LEVELS))
