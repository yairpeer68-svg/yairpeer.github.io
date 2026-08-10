"""OPSEC leak-awareness.

An OSINT tool has an awkward property: to investigate a target it hands the
target's name to lots of *third parties* — Gravatar, ip-api, GitHub, threat
feeds, certificate-transparency mirrors. Each of those now knows what (and,
via your IP, who) you are looking into. For sensitive investigations that is a
real exposure the operator should see and be able to control.

This module:

  * records every outbound host a scan contacted (via a session recorder wired
    into ``workflow.wrap_session``),
  * classifies each as the *target itself* vs a *third party*,
  * produces a report of exactly which third parties saw the target, and
  * powers a blocklist mode so a run can refuse to touch anything but the
    target (``GHOSTEYE_OPSEC=strict`` / CLI ``--opsec-strict``).

It records hosts, never request bodies or responses.
"""

from __future__ import annotations

import threading
from typing import Dict
from urllib.parse import urlparse

# hosts that are infrastructure noise rather than a meaningful "who saw my
# target" disclosure (they're contacted regardless of target).
_INFRA = {
    "127.0.0.1", "localhost", "0.0.0.0",
}


class LeakRecorder:
    """Thread-safe record of outbound hosts contacted during a scan."""

    def __init__(self, target: str = "", strict: bool = False) -> None:
        self.target = _host(target)
        self.strict = strict
        self._hosts: Dict[str, int] = {}
        self._blocked: Dict[str, int] = {}
        self._lock = threading.Lock()

    def is_target(self, host: str) -> bool:
        h = _host(host)
        if not self.target or not h:
            return False
        return h == self.target or h.endswith("." + self.target)

    def record(self, url: str) -> None:
        h = _host(url)
        if not h:
            return
        with self._lock:
            self._hosts[h] = self._hosts.get(h, 0) + 1

    def note_blocked(self, url: str) -> None:
        h = _host(url)
        if not h:
            return
        with self._lock:
            self._blocked[h] = self._blocked.get(h, 0) + 1

    def should_block(self, url: str) -> bool:
        """In strict mode, block any request that isn't to the target."""
        if not self.strict:
            return False
        h = _host(url)
        if not h or h in _INFRA:
            return False
        return not self.is_target(h)

    def report(self) -> Dict[str, object]:
        with self._lock:
            hosts = dict(self._hosts)
            blocked = dict(self._blocked)
        third = {h: n for h, n in hosts.items()
                 if not self.is_target(h) and h not in _INFRA}
        target_hosts = {h: n for h, n in hosts.items() if self.is_target(h)}
        ranked = sorted(third.items(), key=lambda kv: (-kv[1], kv[0]))
        return {
            "target": self.target or "(none)",
            "strict_mode": self.strict,
            "third_parties_contacted": [
                {"host": h, "requests": n} for h, n in ranked],
            "third_party_count": len(third),
            "target_hosts_contacted": sorted(target_hosts),
            "blocked_in_strict_mode": sorted(blocked) if self.strict else [],
            "exposure": _exposure_level(len(third)),
            "note": ("in an OSINT investigation each of these services now knows "
                     "what you searched for; use --opsec-strict to contact only "
                     "the target, or --tor to hide your source IP"),
        }


def _host(url_or_host: str) -> str:
    if not url_or_host:
        return ""
    s = str(url_or_host).strip()
    if "://" in s:
        s = urlparse(s).hostname or ""
    else:
        s = s.split("/")[0]
    return s.split(":")[0].strip().lower().rstrip(".")


def _exposure_level(n: int) -> str:
    if n == 0:
        return "none — nothing but the target was contacted"
    if n <= 3:
        return "low"
    if n <= 12:
        return "moderate"
    return "high — the investigation was disclosed to many third parties"


# --------------------------------------------------------------------------- #
#  Reporting from finished results (no recorder needed)
# --------------------------------------------------------------------------- #
def report_from_results(results, target: str = "") -> Dict[str, object]:
    """Best-effort OPSEC picture reconstructed from result data when no live
    recorder was attached: harvest any URLs the modules reported touching."""
    import re
    rec = LeakRecorder(target)
    url_re = re.compile(r"https?://[^\s\"'<>]+")
    for r in results or []:
        blob = str(getattr(r, "data", "") or "")
        for m in url_re.findall(blob):
            rec.record(m)
    out = rec.report()
    out["note"] = ("reconstructed from reported URLs only — attach a live "
                   "recorder (a normal scan does) for the complete picture")
    return out
