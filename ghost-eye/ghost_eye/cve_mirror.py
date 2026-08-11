"""Offline CVE mirror (feature 78).

A local SQLite cache of CVE intelligence so lookups keep working without a
network connection:

  * every live ``check_cve`` result is **written back** to the mirror, so a
    second run (or an offline run) is instant and self-contained;
  * the whole **CISA KEV** catalogue can be seeded in one call;
  * an **NVD-style JSON feed** can be imported for bulk offline coverage;
  * set ``GHOSTEYE_OFFLINE=1`` to serve *only* from the mirror (no network).

Mirror path: ``$GHOSTEYE_CVE_MIRROR`` or ``~/.ghosteye/cve_mirror.db``.
Pure local storage — reconnaissance/detection tooling only.
"""

from __future__ import annotations

import json
import os
import sqlite3

from .core import open_db
import time
from pathlib import Path
from typing import Any, Dict, Optional


def mirror_path() -> Path:
    p = os.environ.get("GHOSTEYE_CVE_MIRROR", "")
    return Path(p) if p else Path.home() / ".ghosteye" / "cve_mirror.db"


def offline() -> bool:
    return os.environ.get("GHOSTEYE_OFFLINE", "") not in ("", "0", "false")


def _conn(path: Optional[str] = None) -> sqlite3.Connection:
    p = str(path) if path else str(mirror_path())
    if p != ":memory:":
        Path(p).parent.mkdir(parents=True, exist_ok=True)
    conn = open_db(p)
    conn.execute(
        """CREATE TABLE IF NOT EXISTS cve(
               id TEXT PRIMARY KEY, ts REAL, data TEXT)""")
    conn.commit()
    return conn


class CveMirror:
    def __init__(self, path: Optional[str] = None) -> None:
        self.conn = _conn(path)

    def get(self, cve: str) -> Optional[Dict[str, Any]]:
        cur = self.conn.execute("SELECT data FROM cve WHERE id=?",
                                (cve.upper(),))
        row = cur.fetchone()
        if not row:
            return None
        try:
            return json.loads(row[0])
        except Exception:  # noqa: BLE001
            return None

    def put(self, cve: str, data: Dict[str, Any]) -> None:
        try:
            self.conn.execute(
                "INSERT OR REPLACE INTO cve(id,ts,data) VALUES(?,?,?)",
                (cve.upper(), time.time(),
                 json.dumps(data, ensure_ascii=False)))
            self.conn.commit()
        except Exception:  # noqa: BLE001
            pass

    def seed_kev(self, session, timeout: int = 30) -> int:
        """Import the whole CISA KEV catalogue into the mirror. Returns count."""
        url = ("https://www.cisa.gov/sites/default/files/feeds/"
               "known_exploited_vulnerabilities.json")
        try:
            r = session.get(url, timeout=timeout)
            if r.status_code != 200:
                return 0
            data = r.json() or {}
        except Exception:  # noqa: BLE001
            return 0
        n = 0
        for v in data.get("vulnerabilities", []) or []:
            cid = str(v.get("cveID", "")).upper()
            if not cid:
                continue
            cur = self.get(cid) or {"cve": cid}
            cur.update({
                "known_exploited": True,
                "kev_due_date": v.get("dueDate", ""),
                "kev_ransomware": v.get("knownRansomwareCampaignUse", "") == "Known",
                "source": "cisa-kev",
            })
            self.put(cid, cur)
            n += 1
        return n

    def import_feed(self, path: str) -> int:
        """Import an NVD-style JSON feed: either {"CVE_Items":[…]} or a plain
        list of {cve, cvss, severity, …} records. Returns imported count."""
        try:
            with open(path, encoding="utf-8") as fh:
                blob = json.load(fh)
        except Exception:  # noqa: BLE001
            return 0
        items = []
        if isinstance(blob, dict):
            items = blob.get("CVE_Items") or blob.get("cves") or blob.get("vulnerabilities") or []
        elif isinstance(blob, list):
            items = blob
        n = 0
        for it in items:
            rec = _normalise_feed_item(it)
            if rec.get("cve"):
                cur = self.get(rec["cve"]) or {}
                cur.update(rec)
                self.put(rec["cve"], cur)
                n += 1
        return n

    def stats(self) -> Dict[str, Any]:
        cur = self.conn.execute("SELECT COUNT(*) FROM cve")
        total = cur.fetchone()[0]
        kev = self.conn.execute(
            "SELECT COUNT(*) FROM cve WHERE data LIKE '%\"known_exploited\": true%'"
        ).fetchone()[0]
        return {"path": str(mirror_path()), "cves": total, "kev": kev,
                "offline_mode": offline()}

    def close(self) -> None:
        try:
            self.conn.close()
        except Exception:  # noqa: BLE001
            pass


def _normalise_feed_item(it: Any) -> Dict[str, Any]:
    """Best-effort flatten of a feed record into our compact CVE shape."""
    if not isinstance(it, dict):
        return {}
    # plain shape
    if it.get("cve") and isinstance(it.get("cve"), str):
        return {k: it[k] for k in it if k in
                ("cve", "cvss", "severity", "known_exploited", "epss",
                 "exploit_available")}
    # NVD 1.1 CVE_Items shape
    meta = (it.get("cve", {}) or {}).get("CVE_data_meta", {}) or {}
    cid = meta.get("ID")
    if not cid:
        return {}
    impact = it.get("impact", {}) or {}
    cvss = None
    for key in ("baseMetricV3", "baseMetricV2"):
        m = impact.get(key, {})
        cvss = ((m.get("cvssV3") or m.get("cvssV2") or {}).get("baseScore")
                if m else None)
        if cvss is not None:
            break
    return {"cve": cid.upper(), "cvss": cvss, "source": "nvd-feed"}


# module-level convenience (read-through / write-back used by exploit_intel)
_SHARED: Optional[CveMirror] = None


def shared() -> CveMirror:
    global _SHARED
    if _SHARED is None:
        _SHARED = CveMirror()
    return _SHARED
