"""Custom alert rules (feature 49).

The change-monitor produces a *surface diff* (new_subdomains, new_ips,
new_ports, new_cves, new_leaks, …). Instead of alerting on every change, an
alert-rule set decides **whether** to fire and at **what severity**, so you can
say "only alert me on new CVEs or leaks", "ignore new subdomains", or "only fire
when at least 3 things changed".

Rules are a small, JSON-serialisable dict — safe to store and edit from the
dashboard. Pure evaluation; no I/O.
"""

from __future__ import annotations

import json
import os
from typing import Any, Dict

# every diff key the monitor can emit, mapped to a default severity weight
_EVENT_SEVERITY = {
    "new_cves": "critical",
    "new_leaks": "critical",
    "new_ports": "high",
    "new_ips": "medium",
    "new_subdomains": "medium",
    "new_services": "medium",
    "new_tech": "low",
    "new_certs": "low",
}
_SEV_ORDER = {"info": 0, "low": 1, "medium": 2, "high": 3, "critical": 4}

DEFAULT_RULES: Dict[str, Any] = {
    "enabled": True,
    "min_events": 1,            # need at least this many new items to fire
    "min_severity": "low",     # drop events below this severity
    "watch": list(_EVENT_SEVERITY),   # which event types to consider
    "ignore": [],              # event types to always ignore
    "only_targets": [],        # if set, only these targets alert
}


def load_rules(path: str = "") -> Dict[str, Any]:
    """Load alert rules from a JSON file (or GHOSTEYE_ALERT_RULES), merged over
    the defaults. Missing / invalid file -> defaults."""
    candidate = path or os.environ.get("GHOSTEYE_ALERT_RULES", "")
    rules = dict(DEFAULT_RULES)
    if candidate and os.path.exists(candidate):
        try:
            with open(candidate, encoding="utf-8") as fh:
                user = json.load(fh)
            if isinstance(user, dict):
                rules.update({k: v for k, v in user.items() if k in DEFAULT_RULES})
        except Exception:  # noqa: BLE001
            pass
    return rules


def evaluate(diff: Dict[str, Any], rules: Dict[str, Any] | None = None,
             target: str = "") -> Dict[str, Any]:
    """Decide whether a surface diff should raise an alert under ``rules``.

    Returns {fire, severity, matched_events, total, reasons}."""
    rules = {**DEFAULT_RULES, **(rules or {})}
    if not rules.get("enabled", True):
        return {"fire": False, "severity": "info", "matched_events": {},
                "total": 0, "reasons": ["alerting disabled"]}
    only = rules.get("only_targets") or []
    if only and target and target not in only:
        return {"fire": False, "severity": "info", "matched_events": {},
                "total": 0, "reasons": [f"{target} not in only_targets"]}

    watch = set(rules.get("watch", list(_EVENT_SEVERITY)))
    ignore = set(rules.get("ignore", []))
    min_sev = _SEV_ORDER.get(rules.get("min_severity", "low"), 1)

    matched: Dict[str, int] = {}
    top_sev = 0
    for key, items in diff.items():
        if not key.startswith("new_") or not isinstance(items, list) or not items:
            continue
        if key in ignore or key not in watch:
            continue
        sev = _EVENT_SEVERITY.get(key, "low")
        if _SEV_ORDER.get(sev, 1) < min_sev:
            continue
        matched[key] = len(items)
        top_sev = max(top_sev, _SEV_ORDER.get(sev, 1))

    total = sum(matched.values())
    fire = total >= int(rules.get("min_events", 1)) and total > 0
    severity = next((s for s, n in _SEV_ORDER.items() if n == top_sev), "info")
    reasons = ([f"{k}: {n}" for k, n in matched.items()] if fire
               else ["no watched event met the threshold"])
    return {"fire": fire, "severity": severity, "matched_events": matched,
            "total": total, "reasons": reasons}
