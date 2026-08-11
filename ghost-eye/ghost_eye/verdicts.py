"""Analyst verdicts — tell the tool it was wrong, once.

Every recon tool produces findings that are not findings: a header flagged as
sensitive that is deliberate, an "exposed" path that is a public API, a CVE that
does not apply to this build. Today the only remedy is remembering, every scan,
which lines to skip — which means the same false positive costs attention
forever, and the noise trains people to skim the list, which is how a real
finding gets missed.

This module lets an analyst rule on a finding once — `false_positive`,
`accepted_risk`, or `confirmed` — and have that ruling applied automatically to
every later scan.

The whole design turns on one hazard: **a suppression that outlives the thing
it was about is worse than the noise it removed.** Someone marks
``server = nginx/1.18`` a false positive; two years later the host runs a
different build with a real problem in the same field, and a naive suppression
list hides it silently and forever. Three rules prevent that:

* **The value is part of the identity.** A verdict fingerprints
  ``scope + module + field + value``, so any change to the value produces a new
  fingerprint and the finding comes back. You ruled on what you saw, not on the
  field for all time.
* **Verdicts expire.** Default `DEFAULT_TTL_DAYS`; an expired verdict stops
  suppressing and is reported as expired rather than silently dropped.
* **Suppression is never invisible.** `apply()` always reports how many
  findings it withheld and under which verdicts, and the withheld findings stay
  available. A count you can see is the difference between a filter and a
  blindfold.

Scope defaults to the target the finding came from, so ruling on one host does
not quietly speak for the rest of your estate; `scope="*"` is available but is
an explicit, recorded choice.

No network. Local judgement store only.
"""

from __future__ import annotations

import hashlib

from .core import open_db
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

FALSE_POSITIVE = "false_positive"
ACCEPTED_RISK = "accepted_risk"
CONFIRMED = "confirmed"
VERDICTS = (FALSE_POSITIVE, ACCEPTED_RISK, CONFIRMED)

# Verdicts that remove a finding from the active list. `confirmed` is recorded
# and displayed but never hides anything — it is the opposite of suppression.
_SUPPRESSING = (FALSE_POSITIVE, ACCEPTED_RISK)

# A ruling is about a moment. Past this it must be re-made rather than inherited.
DEFAULT_TTL_DAYS = 180

# Any scope, chosen explicitly and recorded as such.
ANY_SCOPE = "*"


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _norm(value: Any) -> str:
    return " ".join(str(value or "").split())[:400]


def fingerprint(finding: Dict[str, Any], scope: str = "") -> str:
    """A stable identity for one finding.

    The detail text is part of the identity on purpose: a verdict is a ruling on
    *what was observed*, so when the observation changes the finding is new
    again and has to be re-judged. Leaving the value out is what turns a
    verdict store into a permanent blind spot.
    """
    scope = _norm(scope or finding.get("target", "")).lower() or ANY_SCOPE
    parts = [scope,
             _norm(finding.get("module", "")).lower(),
             _norm(finding.get("field", "")).lower(),
             _norm(finding.get("detail", ""))]
    return hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()


def short_id(fp: str) -> str:
    """The 12-character handle shown in reports and typed on the CLI."""
    return fp[:12]


class VerdictStore:
    """Persisted analyst rulings, applied to later scans."""

    def __init__(self, path: str = "ghosteye.db") -> None:
        self.conn = open_db(path)
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS verdicts(
                   fingerprint TEXT PRIMARY KEY,
                   short TEXT,
                   verdict TEXT NOT NULL,
                   scope TEXT, module TEXT, field TEXT, detail TEXT,
                   severity TEXT, reason TEXT, author TEXT,
                   created TEXT, expires TEXT)""")
        self.conn.execute(
            "CREATE INDEX IF NOT EXISTS verdict_short ON verdicts(short)")
        # The ids printed beside findings have to survive until the analyst
        # gets round to ruling on them, which is usually a later invocation.
        # Without this, `--mark <id>` could only ever work inside the same run.
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS seen_findings(
                   short TEXT PRIMARY KEY, target TEXT, module TEXT,
                   field TEXT, detail TEXT, severity TEXT, last_seen TEXT)""")
        self.conn.commit()

    def remember(self, findings: List[Dict[str, Any]]) -> int:
        """Record the id -> finding mapping so a later `--mark <id>` resolves."""
        now = _now().isoformat()
        rows = []
        for f in findings or []:
            short = short_id(fingerprint(f, f.get("target", "")))
            rows.append((short, _norm(f.get("target", "")),
                         _norm(f.get("module", "")), _norm(f.get("field", "")),
                         _norm(f.get("detail", "")), _norm(f.get("severity", "")),
                         now))
        self.conn.executemany(
            "INSERT OR REPLACE INTO seen_findings(short,target,module,field,"
            "detail,severity,last_seen) VALUES(?,?,?,?,?,?,?)", rows)
        self.conn.commit()
        return len(rows)

    def recall(self, short: str) -> Optional[Dict[str, Any]]:
        """The finding a printed id refers to, from any earlier scan."""
        cur = self.conn.execute(
            "SELECT target,module,field,detail,severity FROM seen_findings "
            "WHERE short=?", ((short or "").strip(),))
        row = cur.fetchone()
        if not row:
            return None
        return {"target": row[0], "module": row[1], "field": row[2],
                "detail": row[3], "severity": row[4]}

    # -- recording --------------------------------------------------------- #
    def record(self, finding: Dict[str, Any], verdict: str,
               scope: str = "", reason: str = "", author: str = "",
               ttl_days: int = DEFAULT_TTL_DAYS) -> Dict[str, Any]:
        """Rule on one finding. Re-recording the same fingerprint updates it."""
        if verdict not in VERDICTS:
            raise ValueError(f"verdict must be one of {', '.join(VERDICTS)}")
        scope = _norm(scope or finding.get("target", "")).lower() or ANY_SCOPE
        fp = fingerprint(finding, scope)
        created = _now()
        expires = created + timedelta(days=max(1, int(ttl_days)))
        row = (fp, short_id(fp), verdict, scope,
               _norm(finding.get("module", "")), _norm(finding.get("field", "")),
               _norm(finding.get("detail", "")), _norm(finding.get("severity", "")),
               _norm(reason), _norm(author),
               created.isoformat(), expires.isoformat())
        self.conn.execute(
            "INSERT OR REPLACE INTO verdicts(fingerprint,short,verdict,scope,"
            "module,field,detail,severity,reason,author,created,expires) "
            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", row)
        self.conn.commit()
        return {"fingerprint": fp, "id": short_id(fp), "verdict": verdict,
                "scope": scope, "expires": expires.isoformat()}

    def record_by_id(self, short: str, verdict: str, findings: List[Dict[str, Any]],
                     **kw) -> Optional[Dict[str, Any]]:
        """Rule on a finding by the short id a report printed next to it."""
        for f in findings or []:
            if short_id(fingerprint(f, f.get("target", ""))) == short:
                return self.record(f, verdict, scope=f.get("target", ""), **kw)
        return None

    def clear(self, short: str) -> int:
        cur = self.conn.execute("DELETE FROM verdicts WHERE short=?", (short,))
        self.conn.commit()
        return cur.rowcount

    # -- lookup ------------------------------------------------------------ #
    def _row(self, fp: str) -> Optional[Dict[str, Any]]:
        cur = self.conn.execute(
            "SELECT fingerprint,short,verdict,scope,module,field,detail,"
            "severity,reason,author,created,expires FROM verdicts "
            "WHERE fingerprint=?", (fp,))
        row = cur.fetchone()
        if not row:
            return None
        keys = ("fingerprint", "id", "verdict", "scope", "module", "field",
                "detail", "severity", "reason", "author", "created", "expires")
        return dict(zip(keys, row))

    def lookup(self, finding: Dict[str, Any]) -> Optional[Dict[str, Any]]:
        """The verdict covering this finding, host-scoped first then any-scope."""
        for scope in (finding.get("target", ""), ANY_SCOPE):
            row = self._row(fingerprint(finding, scope))
            if row:
                row["expired"] = _is_expired(row.get("expires"))
                return row
        return None

    def all(self, include_expired: bool = True) -> List[Dict[str, Any]]:
        cur = self.conn.execute(
            "SELECT fingerprint,short,verdict,scope,module,field,detail,"
            "severity,reason,author,created,expires FROM verdicts "
            "ORDER BY created DESC")
        keys = ("fingerprint", "id", "verdict", "scope", "module", "field",
                "detail", "severity", "reason", "author", "created", "expires")
        out = []
        for row in cur.fetchall():
            entry = dict(zip(keys, row))
            entry["expired"] = _is_expired(entry.get("expires"))
            if include_expired or not entry["expired"]:
                out.append(entry)
        return out

    def purge_expired(self) -> int:
        cur = self.conn.execute("SELECT fingerprint,expires FROM verdicts")
        dead = [fp for fp, exp in cur.fetchall() if _is_expired(exp)]
        for fp in dead:
            self.conn.execute("DELETE FROM verdicts WHERE fingerprint=?", (fp,))
        self.conn.commit()
        return len(dead)

    # -- application ------------------------------------------------------- #
    def apply(self, findings: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Split findings into what still stands and what a verdict withheld.

        Every finding is stamped with its short id so the next ruling can be
        made by typing it, and suppression is always reported — a filter you
        cannot see is a blindfold.
        """
        self.remember(findings)
        active: List[Dict[str, Any]] = []
        suppressed: List[Dict[str, Any]] = []
        expired_hits: List[Dict[str, Any]] = []
        counts: Dict[str, int] = {}
        for finding in findings or []:
            f = dict(finding)
            f["id"] = short_id(fingerprint(f, f.get("target", "")))
            row = self.lookup(f)
            if row:
                f["verdict"] = row["verdict"]
                f["verdict_reason"] = row.get("reason", "")
                f["verdict_scope"] = row.get("scope", "")
                if row["expired"]:
                    f["verdict_expired"] = True
                    expired_hits.append(f)
                    active.append(f)      # an expired ruling suppresses nothing
                    continue
                if row["verdict"] in _SUPPRESSING:
                    counts[row["verdict"]] = counts.get(row["verdict"], 0) + 1
                    suppressed.append(f)
                    continue
            active.append(f)
        return {
            "findings": active,
            "active_count": len(active),
            "suppressed": suppressed,
            "suppressed_count": len(suppressed),
            "suppressed_by_verdict": counts,
            "expired_verdicts_ignored": expired_hits,
            "expired_count": len(expired_hits),
            "note": ("findings you previously ruled on are withheld, never "
                     "deleted — and a verdict older than its TTL stops "
                     "suppressing so a stale ruling cannot hide a live problem."),
        }

    def close(self) -> None:
        self.conn.close()


def _is_expired(expires: Optional[str]) -> bool:
    if not expires:
        return False
    try:
        when = datetime.fromisoformat(str(expires).replace("Z", "+00:00"))
        if when.tzinfo is None:
            when = when.replace(tzinfo=timezone.utc)
        return when < _now()
    except Exception:  # noqa: BLE001 - an unparseable date is not an excuse to hide
        return False


def apply_verdicts(findings: List[Dict[str, Any]],
                   db: str = "ghosteye.db") -> Dict[str, Any]:
    """One-call convenience for the CLI, reports and dashboard."""
    store = VerdictStore(db)
    try:
        return store.apply(findings)
    finally:
        store.close()
