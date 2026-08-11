"""Corpus baseline — "which of these findings is actually unusual?"

A 552-module scan returns thousands of fields. Almost all of them are what
*every* host looks like: an nginx `Server` header, a Let's Encrypt issuer, port
443 open, a Cloudflare edge. Reading that output means manually knowing what is
normal, and the tool never tells you — so the one field that is genuinely odd
sits in the same flat list as the four hundred that are not.

This module learns what normal looks like from every host you have ever scanned
and then scores a new scan against it. The finding that matters is the **rare**
one: a header only two hosts in your corpus have ever sent, an exposed path
nobody else exposes, a TLS configuration that is yours alone.

It is the mirror image of `intelligence.attribution`. There, a rare shared
value is strong evidence two hosts have the same operator; here, a rare value
is a reason to look. Both rest on the same measurement — how many hosts in the
observed corpus carry this exact value — so both inherit the same hard-won
correction: **frequency measured over a handful of hosts is not knowledge**.
Below `min_corpus` the engine says so rather than inventing confident numbers.

Two guards keep it from being a firehose:

* **Identifier suppression.** A field whose distinct-value count tracks its
  host count is an *identifier*, not a signal — every host has its own IP,
  certificate serial, and response time, so every host would be "anomalous" in
  those fields, forever. Fields above `_ID_RATIO` distinct-values-per-host are
  dropped automatically, which means no hand-maintained blocklist to keep
  current.
* **Idempotent learning.** Observations are keyed `(host, field, value)`, so
  re-scanning the same host ten times does not make its values look ten times
  more normal. Getting this wrong is how a baseline quietly teaches itself that
  whatever you scan most is what the world looks like.

No network. Correlation over your own scan history only.
"""

from __future__ import annotations

import sqlite3
from datetime import datetime, timezone
from typing import Any, Dict, Iterable, List, Optional

from .reporting import _flatten

# Below this many hosts carrying a field, its frequency says nothing about
# whether a value is rare — the same lesson attribution's IDF had to learn.
MIN_CORPUS = 8

# A value seen on at most this fraction of the hosts that have the field is
# reported as anomalous.
RARE_AT = 0.15

# A field whose distinct values nearly equal its host count is an identifier
# (ip, serial, elapsed, request-id), not a comparable observation.
_ID_RATIO = 0.9

# Values longer than this are prose or blobs, not comparable observations.
_MAX_VALUE = 160

# Fields that are per-run bookkeeping rather than observations about the host.
_SKIP_TAILS = ("note", "notes", "elapsed", "took", "duration", "timestamp",
               "ts", "generated", "scanned_at", "started", "finished")


def _norm(value: Any) -> str:
    """Whitespace-normalise, but never truncate.

    Truncating here would defeat the length guard below *and* manufacture false
    equality: two different 400-character blobs sharing a prefix would collapse
    to the same value and teach the baseline they are the same observation.
    """
    return " ".join(str(value).split())


def _usable(field: str, value: str) -> bool:
    """Whether a flattened (field, value) pair is worth learning."""
    if not field or not value:
        return False
    tail = field.rsplit(".", 1)[-1].lower()
    if tail in _SKIP_TAILS:
        return False
    if value.lower() in ("none", "null", "unknown", "n/a", "-", "0", "false"):
        return False
    return len(value) <= _MAX_VALUE


def observations(results) -> Dict[str, str]:
    """The flattened, learnable (field, value) pairs of one host's results.

    Fields are namespaced by module id so two modules that happen to use the
    same key name are never compared against each other.
    """
    out: Dict[str, str] = {}
    for r in results or []:
        module = getattr(r, "module", "") or ""
        data = getattr(r, "data", None)
        if data is None and isinstance(r, dict):     # accept stored scan rows
            module, data = r.get("module", ""), r.get("data")
        flat: Dict[str, str] = {}
        _flatten("", data or {}, flat)
        for key, raw in flat.items():
            value = _norm(raw)
            field = f"{module}.{key}" if module else key
            if _usable(field, value):
                out[field] = value
    return out


class Baseline:
    """A learned picture of what the hosts you scan normally look like."""

    def __init__(self, path: str = "ghosteye.db") -> None:
        self.conn = sqlite3.connect(path)
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS baseline_obs(
                   host TEXT NOT NULL, field TEXT NOT NULL, value TEXT NOT NULL,
                   ts TEXT,
                   PRIMARY KEY(host, field, value))""")
        self.conn.execute(
            "CREATE INDEX IF NOT EXISTS baseline_field ON baseline_obs(field)")
        self.conn.execute(
            "CREATE INDEX IF NOT EXISTS baseline_fv ON baseline_obs(field, value)")
        self.conn.commit()

    # -- learning ---------------------------------------------------------- #
    def learn(self, results, host: str = "") -> int:
        """Record one host's observations. Idempotent per (host, field, value).

        Returns how many *new* observations were stored, so re-learning the
        same scan reports 0 rather than silently reinforcing itself.
        """
        host = (host or _target_of(results)).strip().lower()
        if not host:
            return 0
        now = datetime.now(timezone.utc).isoformat()
        rows = [(host, f, v, now) for f, v in observations(results).items()]
        before = self._rowcount()
        self.conn.executemany(
            "INSERT OR IGNORE INTO baseline_obs(host, field, value, ts) "
            "VALUES(?,?,?,?)", rows)
        self.conn.commit()
        return self._rowcount() - before

    def learn_many(self, scans: Iterable[Dict[str, Any]]) -> int:
        """Learn from stored scan rows (``Store.export_all``/``scans_for``)."""
        total = 0
        for scan in scans or []:
            total += self.learn(scan.get("results") or [],
                                host=scan.get("target", ""))
        return total

    # -- measurement ------------------------------------------------------- #
    def corpus_size(self) -> int:
        return int(self.conn.execute(
            "SELECT COUNT(DISTINCT host) FROM baseline_obs").fetchone()[0])

    def _rowcount(self) -> int:
        return int(self.conn.execute(
            "SELECT COUNT(*) FROM baseline_obs").fetchone()[0])

    def field_stats(self, field: str) -> Dict[str, int]:
        row = self.conn.execute(
            "SELECT COUNT(DISTINCT host), COUNT(DISTINCT value) "
            "FROM baseline_obs WHERE field=?", (field,)).fetchone()
        return {"hosts": int(row[0] or 0), "distinct_values": int(row[1] or 0)}

    def value_hosts(self, field: str, value: str) -> int:
        return int(self.conn.execute(
            "SELECT COUNT(DISTINCT host) FROM baseline_obs "
            "WHERE field=? AND value=?", (field, value)).fetchone()[0])

    def is_identifier(self, field: str) -> bool:
        """A field that is unique-per-host carries no comparable signal."""
        st = self.field_stats(field)
        if st["hosts"] < MIN_CORPUS:
            return False
        return st["distinct_values"] / st["hosts"] > _ID_RATIO

    # -- scoring ----------------------------------------------------------- #
    def anomalies(self, results, host: str = "",
                  min_corpus: int = MIN_CORPUS,
                  rare_at: float = RARE_AT,
                  limit: int = 40) -> Dict[str, Any]:
        """Score a scan against the learned corpus.

        Every value is compared with how many *other* hosts ever carried it.
        The host being scored is excluded from its own prevalence, so a value
        it already taught the baseline is not counted as evidence that the
        value is common.
        """
        host = (host or _target_of(results)).strip().lower()
        corpus = self.corpus_size()
        obs = observations(results)
        if corpus < min_corpus:
            return {
                "host": host, "corpus_hosts": corpus,
                "min_corpus": min_corpus,
                "anomalies": [],
                "anomaly_count": 0,
                "fields_compared": 0,
                "note": (f"the baseline has only {corpus} host(s); at least "
                         f"{min_corpus} are needed before rarity means anything. "
                         "Scan more targets with --baseline-learn, or import "
                         "history with --baseline-rebuild."),
            }

        found: List[Dict[str, Any]] = []
        compared = 0
        for field, value in obs.items():
            stats = self.field_stats(field)
            others = stats["hosts"] - (1 if self._host_has_field(host, field) else 0)
            if others < min_corpus:
                continue                      # field itself is too rarely seen
            if self.is_identifier(field):
                continue                      # unique-per-host: never a signal
            compared += 1
            seen = self.value_hosts(field, value)
            seen_elsewhere = seen - (1 if self._host_has(host, field, value) else 0)
            prevalence = seen_elsewhere / others
            if prevalence > rare_at:
                continue
            module, _, key = field.partition(".")
            found.append({
                "module": module, "field": key or field, "value": value,
                "seen_on_hosts": seen_elsewhere,
                "of_hosts_with_field": others,
                "prevalence": round(prevalence, 4),
                "rarity": round(1.0 - prevalence, 4),
                "unique_to_this_host": seen_elsewhere == 0,
            })

        found.sort(key=lambda a: (-a["rarity"], a["module"], a["field"]))
        unique = [a for a in found if a["unique_to_this_host"]]
        return {
            "host": host,
            "corpus_hosts": corpus,
            "fields_compared": compared,
            "anomalies": found[:limit],
            "anomaly_count": len(found),
            "unique_to_this_host": len(unique),
            "by_module": _count_by(found, "module"),
            "note": ("values carried by at most "
                     f"{int(rare_at * 100)}% of the hosts in your corpus. "
                     "Rare is not the same as bad — it means this host differs "
                     "from everything else you have scanned, which is where an "
                     "analyst's attention is worth spending. Identifier fields "
                     "(unique per host by nature) are excluded automatically."),
        }

    def _host_has_field(self, host: str, field: str) -> bool:
        if not host:
            return False
        return self.conn.execute(
            "SELECT 1 FROM baseline_obs WHERE host=? AND field=? LIMIT 1",
            (host, field)).fetchone() is not None

    def _host_has(self, host: str, field: str, value: str) -> bool:
        if not host:
            return False
        return self.conn.execute(
            "SELECT 1 FROM baseline_obs WHERE host=? AND field=? AND value=? "
            "LIMIT 1", (host, field, value)).fetchone() is not None

    # -- housekeeping ------------------------------------------------------ #
    def summary(self) -> Dict[str, Any]:
        fields = int(self.conn.execute(
            "SELECT COUNT(DISTINCT field) FROM baseline_obs").fetchone()[0])
        return {
            "corpus_hosts": self.corpus_size(),
            "distinct_fields": fields,
            "observations": self._rowcount(),
            "ready": self.corpus_size() >= MIN_CORPUS,
            "min_corpus": MIN_CORPUS,
        }

    def forget(self, host: str) -> int:
        """Drop one host from the corpus (it was mis-scanned, or out of scope)."""
        cur = self.conn.execute("DELETE FROM baseline_obs WHERE host=?",
                                ((host or "").strip().lower(),))
        self.conn.commit()
        return cur.rowcount

    def close(self) -> None:
        self.conn.close()


def _target_of(results) -> str:
    for r in results or []:
        target = getattr(r, "target", "") or (
            r.get("target", "") if isinstance(r, dict) else "")
        if target:
            return str(target)
    return ""


def _count_by(rows: List[Dict[str, Any]], key: str) -> Dict[str, int]:
    out: Dict[str, int] = {}
    for row in rows:
        out[row[key]] = out.get(row[key], 0) + 1
    return dict(sorted(out.items(), key=lambda kv: -kv[1]))


def anomaly_report(results, db: str = "ghosteye.db", target: str = "",
                   learn: bool = False,
                   min_corpus: int = MIN_CORPUS) -> Dict[str, Any]:
    """One-call convenience used by the CLI, dashboard and reports.

    Scoring happens *before* learning, so a host is never compared against a
    corpus that already contains the very scan being scored.
    """
    base: Optional[Baseline] = None
    try:
        base = Baseline(db)
        report = base.anomalies(results, host=target, min_corpus=min_corpus)
        if learn:
            report["learned_observations"] = base.learn(results, host=target)
            report["corpus_hosts"] = base.corpus_size()
        return report
    finally:
        if base is not None:
            base.close()
