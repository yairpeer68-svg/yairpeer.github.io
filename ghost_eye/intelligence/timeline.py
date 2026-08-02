"""Intelligence Timeline — extract dated events from the scan output and order
them chronologically, so an analyst sees the *history* of an attack surface:
when the domain was registered, when certificates were issued and when they
expire, when leaks/breaches were dated, when infrastructure last changed.

Correlation only. No LLM. Dates are parsed heuristically from whatever the
modules already reported; anything undatable is skipped rather than guessed.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from ..core import Result
from ..reporting import _flatten

# a few common date shapes seen in WHOIS / cert / HTTP output
_DATE_PATTERNS = [
    ("%Y-%m-%dT%H:%M:%S", re.compile(r"\b(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})")),
    ("%Y-%m-%d %H:%M:%S", re.compile(r"\b(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})")),
    ("%Y-%m-%d", re.compile(r"\b(\d{4}-\d{2}-\d{2})\b")),
    ("%Y/%m/%d", re.compile(r"\b(\d{4}/\d{2}/\d{2})\b")),
    ("%d-%b-%Y", re.compile(r"\b(\d{2}-[A-Za-z]{3}-\d{4})\b")),
    ("%b %d %H:%M:%S %Y", re.compile(
        r"\b([A-Za-z]{3} +\d{1,2} \d{2}:\d{2}:\d{2} \d{4})")),  # openssl
    ("%d %b %Y", re.compile(r"\b(\d{1,2} [A-Za-z]{3} \d{4})\b")),
]

# key hints → (event kind, human label, severity)
_KEY_EVENTS = {
    "creat": ("registration", "Domain registered", "info"),
    "regist": ("registration", "Domain registered", "info"),
    "updat": ("change", "Registration/record last updated", "info"),
    "chang": ("change", "Record changed", "info"),
    "expir": ("expiry", "Registration/cert expiry", "medium"),
    "not_after": ("expiry", "Certificate expires", "medium"),
    "notafter": ("expiry", "Certificate expires", "medium"),
    "valid_to": ("expiry", "Certificate expires", "medium"),
    "not_before": ("issuance", "Certificate issued", "info"),
    "notbefore": ("issuance", "Certificate issued", "info"),
    "valid_from": ("issuance", "Certificate issued", "info"),
    "issued": ("issuance", "Certificate issued", "info"),
    "breach": ("breach", "Breach / leak dated", "high"),
    "leak": ("breach", "Leak dated", "high"),
    "pwned": ("breach", "Credential breach dated", "high"),
    "last-modified": ("change", "Content last modified", "info"),
    "last_seen": ("sighting", "Last seen", "info"),
    "first_seen": ("sighting", "First seen", "info"),
    "seen": ("sighting", "Sighting", "info"),
}


def _parse_date(s: str) -> Optional[datetime]:
    for fmt, pat in _DATE_PATTERNS:
        m = pat.search(s)
        if not m:
            continue
        try:
            dt = datetime.strptime(m.group(1), fmt)
            return dt.replace(tzinfo=timezone.utc)
        except ValueError:
            continue
    return None


def _classify(key: str) -> Optional[tuple]:
    kl = key.lower()
    for hint, ev in _KEY_EVENTS.items():
        if hint in kl:
            return ev
    return None


def build_timeline(results: List[Result], target: str = "") -> Dict[str, Any]:
    """Return {events, span, insights}. Events are chronological dicts with
    date/kind/label/detail/module/host/severity."""
    events: List[dict] = []
    seen: set = set()
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        for k, v in flat.items():
            sval = str(v)
            ev = _classify(k)
            dt = _parse_date(sval)
            if dt is None:
                continue
            # only keep a dated value if the key looks event-like, OR the value
            # is essentially just a date (avoids matching random numeric noise)
            if ev is None:
                if not re.fullmatch(r"[\sA-Za-z0-9:/\-]+", sval.strip()):
                    continue
                ev = ("event", k.split(".")[-1].replace("_", " ").title(),
                      "info")
            kind, label, sev = ev
            key = (dt.date().isoformat(), kind, r.module)
            if key in seen:
                continue
            seen.add(key)
            events.append({
                "date": dt.date().isoformat(),
                "ts": dt.isoformat(),
                "kind": kind,
                "label": label,
                "detail": sval[:100],
                "module": r.module,
                "host": r.target,
                "severity": sev,
            })
    events.sort(key=lambda e: e["ts"])

    insights: List[str] = []
    now = datetime.now(timezone.utc)
    expiries = [e for e in events if e["kind"] == "expiry"]
    for e in expiries:
        try:
            when = datetime.fromisoformat(e["ts"])
        except ValueError:
            continue
        days = (when - now).days
        if -3650 < days <= 30:
            insights.append(
                f"⏰ {e['label']} for {e['host']} "
                + (f"in {days} day(s) ({e['date']})" if days >= 0
                   else f"EXPIRED {abs(days)} day(s) ago ({e['date']})"))
    regs = [e for e in events if e["kind"] == "registration"]
    if regs:
        yrs = (now - datetime.fromisoformat(regs[0]["ts"])).days // 365
        insights.append(f"🗓 Domain first registered {regs[0]['date']} "
                        f"(~{yrs} year(s) old)")
    breaches = [e for e in events if e["kind"] == "breach"]
    if breaches:
        insights.append(f"🩸 {len(breaches)} dated breach/leak event(s), "
                        f"most recent {breaches[-1]['date']}")
    recent_change = [e for e in events if e["kind"] == "change"]
    if recent_change:
        insights.append(f"✏️ Infrastructure/records last changed "
                        f"{recent_change[-1]['date']}")

    span = None
    if events:
        span = {"from": events[0]["date"], "to": events[-1]["date"],
                "events": len(events)}
    return {
        "target": target,
        "events": events[:120],
        "span": span,
        "insights": insights[:8] or ["no dated intelligence available "
                                     "in this scan"],
        "note": "dates parsed from module output (WHOIS, certificates, breach "
                "data, HTTP). Undatable findings are omitted.",
    }
