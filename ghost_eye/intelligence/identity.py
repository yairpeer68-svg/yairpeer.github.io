"""Identity graph — correlate people, e-mails, usernames and social profiles
into one linked picture (advanced OSINT).

Reasons over everything the modules already found and links:

  * ``person``   ── has_email ──▶ ``email``
  * ``email``    ── at_domain ──▶ ``domain``
  * ``email``    ── same_as   ──▶ ``username``   (local-part == handle)
  * ``profile``  ── profile_of ─▶ ``person``/``username``

and clusters likely-same-person nodes (a name whose tokens appear in an e-mail
local part, or a handle that matches a name). Correlation only — no scanning,
no network. This is what turns "a pile of e-mails and handles" into "these three
belong to the same human".
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Set

from ..core import Result
from ..reporting import _flatten

_EMAIL = re.compile(r"\b([A-Za-z0-9._%+\-]+)@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})\b")
_NAME = re.compile(r"\b([A-Z][a-z]{1,15})\s+([A-Z][a-z]{1,15})\b")
_PROFILE = re.compile(
    r"https?://(?:www\.)?(github|gitlab|twitter|x|linkedin|facebook|instagram"
    r"|keybase|mastodon|reddit|medium|gitea|bitbucket|t\.me|telegram)\.com?/"
    r"([A-Za-z0-9_\-./]{2,40})", re.I)
_NAME_STOP = {"The", "This", "Our", "About", "Contact", "Home", "Privacy",
              "Terms", "All", "Read", "More", "Learn", "Cookie", "Policy",
              "Team", "Company", "Services", "Products", "Support", "Careers"}


def _flat_text(results: List[Result]) -> str:
    parts: List[str] = []
    for r in results:
        flat: Dict[str, Any] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        for k, v in flat.items():
            parts.append(f"{k} {v}")
    return "\n".join(parts)


def _norm_name(n: str) -> str:
    return re.sub(r"\s+", " ", n).strip().lower()


def identity_graph(results: List[Result], target: str = "") -> Dict[str, Any]:
    """Build the identity graph from a batch of results."""
    blob = _flat_text(results)
    ent: Dict[str, dict] = {}
    rels: List[dict] = []
    seen_rel: Set[tuple] = set()

    def add(kind: str, label: str, **attrs) -> str:
        label = (label or "").strip()
        eid = f"{kind}:{label.lower()}"
        e = ent.get(eid)
        if not e:
            e = {"id": eid, "kind": kind, "label": label, "attrs": {}}
            ent[eid] = e
        for k, v in attrs.items():
            if v not in (None, "", []):
                e["attrs"][k] = v
        return eid

    def link(a: str, typ: str, b: str) -> None:
        if not a or not b or a == b or (a, typ, b) in seen_rel:
            return
        seen_rel.add((a, typ, b))
        rels.append({"from": a, "to": b, "type": typ, "label": typ.replace("_", " ")})

    # e-mails → domain, and remember local parts for same-person matching
    emails: Dict[str, str] = {}       # email -> local part
    for m in _EMAIL.finditer(blob):
        local, dom = m.group(1).lower(), m.group(2).lower()
        addr = f"{local}@{dom}"
        emails[addr] = local
        eid = add("email", addr)
        link(eid, "at_domain", add("domain", dom))

    # social profiles → username
    handles: Set[str] = set()
    for m in _PROFILE.finditer(blob):
        platform, handle = m.group(1).lower(), m.group(2).strip("/").split("/")[0]
        if not handle or handle.lower() in ("in", "company", "search", "explore"):
            continue
        handles.add(handle.lower())
        pid = add("profile", f"{platform}/{handle}", platform=platform)
        uid = add("username", handle, platform=platform)
        link(pid, "profile_of", uid)

    # person names (title-case pairs, filtered)
    people: Set[str] = set()
    for a, b in _NAME.findall(blob):
        if a in _NAME_STOP or b in _NAME_STOP:
            continue
        people.add(f"{a} {b}")

    # link people to e-mails / usernames when tokens match
    for name in people:
        first, _, last = _norm_name(name).partition(" ")
        pid = add("person", name)
        for addr, local in emails.items():
            ll = local.lower()
            if (first and last and (
                    ll == f"{first}.{last}" or ll == f"{first}{last}"
                    or ll == f"{first[:1]}{last}" or ll == f"{first}_{last}"
                    or (first in ll and last in ll))):
                link(pid, "has_email", f"email:{addr}")
        for h in handles:
            if first and last and (h == f"{first}{last}" or h == f"{first}.{last}"
                                   or h == f"{first}{last[:1]}"):
                link(pid, "has_username", f"username:{h}")

    # e-mail local-part == a known handle → same person
    for addr, local in emails.items():
        if local in handles:
            link(f"email:{addr}", "same_as", f"username:{local}")

    entities = list(ent.values())
    by_kind: Dict[str, int] = {}
    for e in entities:
        by_kind[e["kind"]] = by_kind.get(e["kind"], 0) + 1
    # a "resolved identity" = a person linked to at least one email or username
    linked_people = {r["from"] for r in rels
                     if r["type"] in ("has_email", "has_username")}
    return {
        "entities": entities,
        "relationships": rels,
        "people": sorted(people)[:60],
        "emails": sorted(emails)[:80],
        "usernames": sorted(handles)[:60],
        "resolved_identities": len(linked_people),
        "counts": {"entities": len(entities), "relationships": len(rels),
                   "by_kind": by_kind},
        "note": "identity correlation from names/e-mails/handles found by the "
                "scan; links are heuristic (name↔local-part / handle) — verify "
                "before acting.",
    }
