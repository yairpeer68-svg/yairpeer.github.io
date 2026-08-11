"""Ownership and accountability for findings.

Two things a single-analyst tool can skip and a team cannot.

**Assignment.** A finding nobody owns is a finding nobody fixes. Each one gets
an assignee and a workflow status, so "open" means someone is looking and
"resolved" means someone decided. Status is deliberately a closed set: free
text turns into six spellings of "in progress" and nothing can be counted.

**Audit.** Every state-changing action is recorded — who asked, what changed,
when. This tool can start scans, rewrite scope, delete stored findings and
hold API keys, so "who deleted last quarter's scans?" has to have an answer
that is not a shrug. The log is append-only from the API's point of view:
there is no endpoint that edits or removes an entry, only one that reads.

Neither store holds secrets. The audit log records the *name* of a key that
was set, never its value, and a redaction pass strips anything that looks like
a token out of free-text detail before it is written.
"""

from __future__ import annotations

import json
import os
import re
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

# A closed set. Anything outside it is refused rather than quietly stored,
# because a status you cannot filter on is worse than no status at all.
STATUSES = ("open", "investigating", "remediating", "resolved", "wont_fix")
OPEN_STATUSES = ("open", "investigating", "remediating")

MAX_AUDIT = 5000          # entries kept; older ones roll off the front
MAX_DETAIL = 400          # characters of free text per entry
MAX_NAME = 80             # characters of assignee name

# Things that must never reach disk in a detail string even by accident.
# Everything after the label goes, not just the next word: "Authorization:
# Basic <blob>" would otherwise lose "Basic" and keep the credential. This
# eats any legitimate trailing text on the same line, which is the trade we
# want — a shortened audit entry beats a leaked one.
_LABELLED = re.compile(
    r"(?i)\b(tokens?|api[_-]?keys?|secrets?|passwords?|passwd|authorization)\b"
    r"\s*[:=]\s*.+")
_BEARER = re.compile(r"(?i)\bbearer\s+\S+")
# A bare high-entropy blob: a long run of key-ish alphabet with no spaces.
_BLOB = re.compile(r"\b[A-Za-z0-9_\-]{28,}\b")


def redact(text: str) -> str:
    """Strip anything token-shaped out of free text.

    Deliberately over-eager: a redacted audit entry is still useful, a leaked
    credential in a world-readable log is not.
    """
    s = str(text or "")
    s = _LABELLED.sub(lambda m: m.group(1) + ": ***", s)
    s = _BEARER.sub("bearer ***", s)
    s = _BLOB.sub("***", s)
    return s[:MAX_DETAIL]


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


# --------------------------------------------------------------------------- #
class Assignments:
    """Who owns which finding, and where it stands.

    Keyed by the same fingerprint the verdict store uses, so an assignment and
    a verdict on one finding refer to the same thing and survive a re-scan.
    """

    def __init__(self, path: Optional[Path] = None) -> None:
        self.path = Path(path) if path else None
        self._data: Dict[str, Dict[str, Any]] = {}
        self._load()

    def _load(self) -> None:
        if not self.path or not self.path.exists():
            return
        try:
            raw = json.loads(self.path.read_text(encoding="utf-8"))
            if isinstance(raw, dict):
                self._data = {k: v for k, v in raw.items() if isinstance(v, dict)}
        except Exception:  # noqa: BLE001 - a corrupt file must not brick startup
            self._data = {}

    def _save(self) -> None:
        if not self.path:
            return
        # Atomic: write_text truncates first, so a crash between truncate and
        # write loses every assignment rather than losing the newest one.
        self.path.parent.mkdir(parents=True, exist_ok=True)
        blob = json.dumps(self._data, indent=1, ensure_ascii=False)
        tmp = self.path.with_name(self.path.name + ".tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write(blob)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, self.path)

    # ---- operations ------------------------------------------------------ #
    def assign(self, key: str, assignee: str = "", status: str = "open",
               target: str = "", note: str = "") -> Dict[str, Any]:
        """Set (or update) ownership of one finding.

        Raises ValueError on an unknown status — silently storing a typo means
        the finding vanishes from every filtered view without saying so.
        """
        key = str(key or "").strip()
        if not key:
            raise ValueError("key required")
        status = (status or "open").strip().lower()
        if status not in STATUSES:
            raise ValueError(f"unknown status {status!r}; "
                             f"expected one of {', '.join(STATUSES)}")
        prev = self._data.get(key, {})
        entry = {
            "assignee": str(assignee or "").strip()[:MAX_NAME],
            "status": status,
            "target": str(target or prev.get("target", ""))[:200],
            "note": redact(note)[:MAX_DETAIL],
            "updated": _now(),
            "created": prev.get("created") or _now(),
            # a short history so "it was resolved, then reopened" is visible
            "history": (prev.get("history") or [])[-19:] + [
                {"status": status, "assignee": str(assignee or "").strip()[:MAX_NAME],
                 "at": _now()}],
        }
        self._data[key] = entry
        self._save()
        return dict(entry, key=key)

    def get(self, key: str) -> Dict[str, Any]:
        return dict(self._data.get(str(key), {}))

    def unassign(self, key: str) -> bool:
        gone = self._data.pop(str(key), None) is not None
        if gone:
            self._save()
        return gone

    def all(self) -> Dict[str, Dict[str, Any]]:
        return {k: dict(v) for k, v in self._data.items()}

    def apply(self, findings: List[Dict[str, Any]],
              key_of=lambda f: f.get("id") or f.get("fingerprint") or "",
              ) -> List[Dict[str, Any]]:
        """Attach assignment metadata to a list of findings, in place-ish.

        Findings with no assignment are left alone rather than stamped with a
        fake "unassigned" owner, so a caller can tell the difference between
        "nobody owns this" and "someone owns it and did nothing".
        """
        for f in findings:
            entry = self._data.get(str(key_of(f)))
            if entry:
                f["assignee"] = entry.get("assignee", "")
                f["workflow_status"] = entry.get("status", "open")
        return findings

    def summary(self) -> Dict[str, Any]:
        """Counts by status and by owner — the numbers a standup asks for."""
        by_status: Dict[str, int] = {s: 0 for s in STATUSES}
        by_owner: Dict[str, int] = {}
        for entry in self._data.values():
            st = entry.get("status", "open")
            by_status[st] = by_status.get(st, 0) + 1
            who = entry.get("assignee") or "(unassigned)"
            if st in OPEN_STATUSES:
                by_owner[who] = by_owner.get(who, 0) + 1
        return {
            "total": len(self._data),
            "open": sum(by_status.get(s, 0) for s in OPEN_STATUSES),
            "by_status": by_status,
            "by_owner": dict(sorted(by_owner.items(),
                                    key=lambda kv: kv[1], reverse=True)),
            "statuses": list(STATUSES),
        }


# --------------------------------------------------------------------------- #
class AuditLog:
    """Append-only record of every state-changing action.

    Stored as JSON Lines so a partial write costs one entry rather than the
    whole file, and so `tail -f` works on it without any tooling.
    """

    def __init__(self, path: Optional[Path] = None, cap: int = MAX_AUDIT) -> None:
        self.path = Path(path) if path else None
        self.cap = max(10, int(cap))
        self._mem: List[Dict[str, Any]] = []

    def record(self, action: str, detail: str = "", actor: str = "",
               ok: bool = True, target: str = "") -> Dict[str, Any]:
        entry = {
            "at": _now(),
            "actor": str(actor or "local")[:MAX_NAME],
            "action": str(action or "")[:80],
            "target": str(target or "")[:200],
            "detail": redact(detail),
            "ok": bool(ok),
        }
        self._mem.append(entry)
        if len(self._mem) > self.cap:
            self._mem = self._mem[-self.cap:]
        if self.path:
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with self.path.open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps(entry, ensure_ascii=False) + "\n")
                self._trim()
            except Exception:  # noqa: BLE001 - logging must never break the API
                pass
        return entry

    def _trim(self) -> None:
        """Keep the file bounded. Only rewrites when it is well over cap, so
        the common path is a single append."""
        if not self.path:
            return
        try:
            lines = self.path.read_text(encoding="utf-8").splitlines()
        except Exception:  # noqa: BLE001
            return
        if len(lines) <= self.cap * 2:
            return
        # Trimming rewrites the whole log. In place, a crash here destroys the
        # audit trail — the one file whose loss is least acceptable.
        tmp = self.path.with_name(self.path.name + ".tmp")
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write("\n".join(lines[-self.cap:]) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, self.path)

    def tail(self, n: int = 100, action: str = "") -> List[Dict[str, Any]]:
        """Most recent entries first."""
        entries = self._read()
        if action:
            entries = [e for e in entries if e.get("action") == action]
        return list(reversed(entries[-max(1, int(n)):]))

    def _read(self) -> List[Dict[str, Any]]:
        if not self.path or not self.path.exists():
            return list(self._mem)
        out: List[Dict[str, Any]] = []
        try:
            for line in self.path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    out.append(json.loads(line))
                except Exception:  # noqa: BLE001 - skip one bad line, keep the rest
                    continue
        except Exception:  # noqa: BLE001
            return list(self._mem)
        return out

    def active(self, minutes: int = 30) -> List[Dict[str, Any]]:
        """Who else has done something recently.

        Derived from the log rather than from a presence protocol, because
        there is no user identity here to build one on: the console is reached
        with a shared token, so an "online users" list would be an invention.
        What can be said honestly is *who acted, and when* — an actor whose
        last action was four seconds ago is someone you are sharing the console
        with, and that is the fact worth surfacing before you delete a scan.
        """
        cutoff = time.gmtime(time.time() - max(1, int(minutes)) * 60)
        since = time.strftime("%Y-%m-%dT%H:%M:%SZ", cutoff)
        seen: Dict[str, Dict[str, Any]] = {}
        for entry in self._read():
            at = str(entry.get("at", ""))
            if at < since:
                continue
            who = str(entry.get("actor") or "?")
            row = seen.setdefault(who, {"actor": who, "actions": 0,
                                        "last": at, "last_action": ""})
            row["actions"] += 1
            if at >= row["last"]:
                row["last"] = at
                row["last_action"] = entry.get("action", "")
        return sorted(seen.values(), key=lambda r: r["last"], reverse=True)

    def summary(self) -> Dict[str, Any]:
        entries = self._read()
        by_action: Dict[str, int] = {}
        by_actor: Dict[str, int] = {}
        failures = 0
        for e in entries:
            by_action[e.get("action", "?")] = by_action.get(e.get("action", "?"), 0) + 1
            by_actor[e.get("actor", "?")] = by_actor.get(e.get("actor", "?"), 0) + 1
            if not e.get("ok", True):
                failures += 1
        return {
            "total": len(entries),
            "failures": failures,
            "by_action": dict(sorted(by_action.items(),
                                     key=lambda kv: kv[1], reverse=True)),
            "by_actor": dict(sorted(by_actor.items(),
                                    key=lambda kv: kv[1], reverse=True)),
            "first": entries[0]["at"] if entries else None,
            "last": entries[-1]["at"] if entries else None,
        }
