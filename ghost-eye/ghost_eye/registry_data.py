"""Data-driven source registries.

The scale lever for OSINT: instead of one Python class per source, a *source*
is a row in a JSON file, so a single module can check hundreds or thousands of
sites. This loader normalises three on-disk schemas into one internal shape:

  * **native**       — Ghost Eye's own format (``ghost_eye/data/*.json``)
  * **Sherlock**     — the well-known ``sherlock`` ``data.json``
  * **WhatsMyName**  — the community ``web_accounts_list.json`` standard

so you can drop in the big community datasets and immediately search thousands
of sites:

    GHOSTEYE_USERNAME_SITES=/path/to/sherlock/data.json \
        ghost-eye -t someuser -m usernamescan

Everything degrades gracefully: a missing/broken file yields the built-in
curated registry, never an exception.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

_DATA_DIR = Path(__file__).parent / "data"


# --------------------------------------------------------------------------- #
#  Normalised site record
# --------------------------------------------------------------------------- #
@dataclass
class Site:
    name: str
    url: str                       # template containing "{u}"
    check: str = "status"          # status | message | redirect
    category: str = "other"
    absent_code: int = 404         # status/redirect: this code == "not found"
    present_code: int = 200
    absent_strings: List[str] = field(default_factory=list)   # message: body => absent
    present_strings: List[str] = field(default_factory=list)  # message: body => present
    regex: Optional[str] = None    # username-validity pre-filter
    method: str = "GET"

    def build(self, username: str) -> str:
        # accept every placeholder the supported datasets use:
        # "{u}" (native), "{}" (Sherlock), "{account}" (WhatsMyName)
        for token in ("{u}", "{account}", "{}"):
            if token in self.url:
                return self.url.replace(token, username)
        return self.url

    def username_ok(self, username: str) -> bool:
        if not self.regex:
            return True
        try:
            return bool(re.match(self.regex, username))
        except re.error:
            return True


# --------------------------------------------------------------------------- #
#  Schema detection + normalisation
# --------------------------------------------------------------------------- #
def _as_list(val) -> List[str]:
    if val is None:
        return []
    if isinstance(val, list):
        return [str(v) for v in val]
    return [str(val)]


def _from_native(rows: list) -> List[Site]:
    out: List[Site] = []
    for r in rows:
        if not isinstance(r, dict) or not r.get("url"):
            continue
        out.append(Site(
            name=str(r.get("name") or r.get("url")),
            url=str(r["url"]),
            check=str(r.get("check", "status")),
            category=str(r.get("cat") or r.get("category") or "other"),
            absent_code=int(r.get("absent_code", 404)),
            present_code=int(r.get("present_code", 200)),
            absent_strings=_as_list(r.get("absent_strings")),
            present_strings=_as_list(r.get("present_strings")),
            regex=r.get("regex"),
            method=str(r.get("method", "GET")).upper(),
        ))
    return out


def _from_sherlock(doc: dict) -> List[Site]:
    """Sherlock data.json: {SiteName: {url, errorType, errorMsg, errorCode,...}}."""
    out: List[Site] = []
    for name, meta in doc.items():
        if not isinstance(meta, dict) or not meta.get("url"):
            continue
        etype = meta.get("errorType", "status_code")
        check = {"status_code": "status", "message": "message",
                 "response_url": "redirect"}.get(etype, "status")
        out.append(Site(
            name=str(name),
            url=str(meta["url"]),
            check=check,
            category="sherlock",
            absent_code=int(meta.get("errorCode") or 404),
            absent_strings=_as_list(meta.get("errorMsg")),
            regex=meta.get("regexCheck"),
            method=str(meta.get("request_method", "GET")).upper(),
        ))
    return out


def _from_whatsmyname(doc: dict) -> List[Site]:
    """WhatsMyName web_accounts_list.json: {"sites": [{name, uri_check,
    e_string, e_code, m_string, m_code, cat}]}."""
    out: List[Site] = []
    for s in doc.get("sites", []):
        if not isinstance(s, dict) or not s.get("uri_check"):
            continue
        e_string = s.get("e_string")
        # WMN is "exists when e_code AND e_string present" -> a message check on
        # the presence string is the most portable interpretation
        check = "message" if e_string else "status"
        out.append(Site(
            name=str(s.get("name", s["uri_check"])),
            url=str(s["uri_check"]),
            check=check,
            category=str(s.get("cat", "other")),
            present_code=int(s.get("e_code", 200)),
            absent_code=int(s.get("m_code", 404)),
            present_strings=_as_list(e_string),
            absent_strings=_as_list(s.get("m_string")),
            regex=s.get("strip_bad_char") and None,
        ))
    return out


def normalise(doc) -> List[Site]:
    """Turn any supported on-disk shape into a list of Site records."""
    if isinstance(doc, list):
        return _from_native(doc)
    if isinstance(doc, dict):
        if isinstance(doc.get("sites"), list):
            return _from_whatsmyname(doc)
        if isinstance(doc.get("registry"), list):
            return _from_native(doc["registry"])
        # otherwise assume Sherlock's {SiteName: {...}} map (skip a $schema key)
        return _from_sherlock({k: v for k, v in doc.items()
                               if isinstance(v, dict)})
    return []


# --------------------------------------------------------------------------- #
#  Loading + caching
# --------------------------------------------------------------------------- #
_CACHE: Dict[str, List[Site]] = {}


def _read_json(path: Path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_sites(kind: str = "username",
               env_var: str = "GHOSTEYE_USERNAME_SITES") -> List[Site]:
    """Return the site registry for `kind`.

    Resolution: an explicit path in `env_var` (any supported schema) wins;
    otherwise the shipped ``data/<kind>_sites.json``. Cached per source.
    """
    override = os.environ.get(env_var, "").strip()
    key = f"{kind}:{override}"
    if key in _CACHE:
        return _CACHE[key]
    sites: List[Site] = []
    candidates = []
    if override:
        candidates.append(Path(override))
    candidates.append(_DATA_DIR / f"{kind}_sites.json")
    for path in candidates:
        try:
            if path.exists():
                sites = normalise(_read_json(path))
                if sites:
                    break
        except Exception:  # noqa: BLE001 - a bad file must not crash a scan
            continue
    # de-dupe by (name, url) so a merged/duplicated dataset doesn't double-count
    seen = set()
    deduped: List[Site] = []
    for s in sites:
        sig = (s.name.lower(), s.url)
        if sig not in seen:
            seen.add(sig)
            deduped.append(s)
    _CACHE[key] = deduped
    return deduped


def registry_stats(kind: str = "username") -> Dict[str, int]:
    sites = load_sites(kind)
    by_cat: Dict[str, int] = {}
    for s in sites:
        by_cat[s.category] = by_cat.get(s.category, 0) + 1
    return {"total": len(sites), "categories": len(by_cat)}
