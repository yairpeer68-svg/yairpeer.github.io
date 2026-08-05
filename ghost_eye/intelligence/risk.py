"""Graph-level risk analytics — the reasoning layer that turns the typed
Knowledge Graph into a *prioritised* picture:

* ``risk_heatmap``  — a per-entity risk score (0-100) + severity band so the
  dashboard can colour every node by how dangerous it is (feature 17).
* ``attack_paths``  — scored chains from an exposure / leak / CVE toward the
  target, i.e. "how an attacker actually gets in" (feature 18).
* ``enrich_tech_cve`` — draws ``tech --affected_by--> cve`` edges by matching a
  CVE's surrounding text to a fingerprinted technology (feature 19).
* ``supply_chain``  — external JS / CDN / dependency hosts pulled in at
  runtime, mapped as ``dependency`` entities (feature 24).

Correlation only: everything here reasons over what the modules already found
and the graph already contains. No LLM, no network, no scanning.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

from ..core import Result
from ..reporting import _flatten

# base danger weight per entity kind (0-100 scale, before neighbourhood boosts)
_KIND_BASE = {
    "leak": 65,
    "cve": 55,
    "exposure": 55,
    "email": 10,
    "cloud": 15,
    "tech": 12,
    "cert_issuer": 5,
    "asn": 8,
    "ip": 18,
    "mailserver": 15,
    "nameserver": 12,
    "subdomain": 20,
    "domain": 18,
    "target": 25,
    "org": 5,
}
# how much a neighbour of a given kind adds to a host's risk
_NEIGHBOUR_WEIGHT = {
    "leak": 22,
    "cve": 16,
    "exposure": 16,
    "cloud": 4,
    "tech": 2,
    "email": 2,
    "ip": 3,
}
_HOST_KINDS = ("target", "subdomain", "domain", "ip", "mailserver",
               "nameserver")

_CVE = re.compile(r"CVE-\d{4}-\d{4,7}", re.I)
# hosts that are pulled in at runtime => supply-chain dependencies
_DEP_HINT = re.compile(
    r"(cdn|cloudflare|jsdelivr|unpkg|googleapis|gstatic|jquery|bootstrap"
    r"|fontawesome|analytics|gtag|segment|hotjar|sentry|stripe|recaptcha"
    r"|akamai|fastly|amazonaws|azureedge)", re.I)


def _band(score: int) -> str:
    if score >= 75:
        return "critical"
    if score >= 50:
        return "high"
    if score >= 25:
        return "medium"
    return "low"


def _adjacency(kg: Dict[str, Any]) -> Tuple[Dict[str, set], Dict[str, dict]]:
    ents = {e["id"]: e for e in kg.get("entities", [])}
    adj: Dict[str, set] = {eid: set() for eid in ents}
    for r in kg.get("relationships", []):
        a, b = r.get("from"), r.get("to")
        if a in adj and b in adj:
            adj[a].add(b)
            adj[b].add(a)
    return adj, ents


def risk_heatmap(kg: Dict[str, Any]) -> Dict[str, Any]:
    """Score every entity 0-100 and write ``risk``/``risk_band`` into its attrs
    (in place, so the graph the dashboard renders is colourable). Returns a
    ranked summary of the hottest entities."""
    adj, ents = _adjacency(kg)
    scores: Dict[str, int] = {}
    for eid, e in ents.items():
        base = _KIND_BASE.get(e.get("kind", ""), 10)
        boost = 0
        for n in adj.get(eid, ()):  # neighbourhood danger
            nk = ents[n].get("kind", "")
            boost += _NEIGHBOUR_WEIGHT.get(nk, 0)
        score = max(0, min(100, base + boost))
        scores[eid] = score
        attrs = e.setdefault("attrs", {})
        attrs["risk"] = score
        attrs["risk_band"] = _band(score)

    ranked = sorted(
        ({"entity": ents[eid]["label"], "kind": ents[eid]["kind"],
          "risk": sc, "band": _band(sc)}
         for eid, sc in scores.items()),
        key=lambda x: x["risk"], reverse=True)
    bands: Dict[str, int] = {"critical": 0, "high": 0, "medium": 0, "low": 0}
    for sc in scores.values():
        bands[_band(sc)] += 1
    hosts = [r for r in ranked if r["kind"] in _HOST_KINDS]
    return {
        "top": ranked[:12],
        "hottest_hosts": hosts[:10],
        "band_counts": bands,
        "max": ranked[0]["risk"] if ranked else 0,
        "note": "per-entity risk 0-100; a host inherits danger from the leaks, "
                "CVEs and exposures attached to it.",
    }


def attack_paths(kg: Dict[str, Any], max_paths: int = 8) -> Dict[str, Any]:
    """Enumerate scored chains from an entry point (leak / CVE / exposure) to
    the target — the shortest such chain per entry, scored by the danger of the
    nodes it traverses. This is a lightweight reasoning aid, not an exploit."""
    adj, ents = _adjacency(kg)
    target_id = next((eid for eid, e in ents.items()
                      if e.get("kind") == "target"), None)
    if not target_id:
        return {"paths": [], "note": "no target node in graph"}

    entry_kinds = ("leak", "cve", "exposure")
    entries = [eid for eid, e in ents.items()
               if e.get("kind") in entry_kinds]
    paths: List[dict] = []
    for start in entries:
        # BFS shortest path start -> target over the undirected graph
        prev: Dict[str, Optional[str]] = {start: None}
        queue = [start]
        while queue:
            cur = queue.pop(0)
            if cur == target_id:
                break
            for nxt in adj.get(cur, ()):
                if nxt not in prev:
                    prev[nxt] = cur
                    queue.append(nxt)
        if target_id not in prev:
            continue
        chain: List[str] = []
        node: Optional[str] = target_id
        while node is not None:
            chain.append(node)
            node = prev[node]
        chain.reverse()
        risk = int(sum(ents[c].get("attrs", {}).get("risk",
                       _KIND_BASE.get(ents[c].get("kind", ""), 10))
                       for c in chain) / max(1, len(chain)))
        paths.append({
            "score": risk,
            "band": _band(risk),
            "length": len(chain),
            "entry": ents[start]["label"],
            "entry_kind": ents[start]["kind"],
            "steps": [{"label": ents[c]["label"], "kind": ents[c]["kind"]}
                      for c in chain],
        })
    paths.sort(key=lambda p: (p["score"], -p["length"]), reverse=True)
    return {
        "paths": paths[:max_paths],
        "count": len(paths),
        "note": "shortest chain from each exposure/leak/CVE to the target, "
                "scored by the average danger of the nodes it crosses.",
    }


def _flat(r: Result) -> Dict[str, str]:
    out: Dict[str, str] = {}
    _flatten("", getattr(r, "data", {}) or {}, out)
    return out


def enrich_tech_cve(kg: Dict[str, Any], results: List[Result]) -> int:
    """Draw ``tech --affected_by--> cve`` edges: for every CVE that appears in a
    module's output alongside a fingerprinted technology name, connect them.
    Returns the number of edges added. Correlation, not a vuln scan."""
    ents = {e["id"]: e for e in kg.get("entities", [])}
    tech = {e["label"].lower(): e["id"] for e in ents.values()
            if e.get("kind") == "tech" and e.get("label")}
    cves = {e["label"].upper(): e["id"] for e in ents.values()
            if e.get("kind") == "cve"}
    if not tech or not cves:
        return 0
    seen = {(r["from"], r["type"], r["to"]) for r in kg.get("relationships", [])}
    added = 0
    for r in results:
        blob = " ".join(f"{k} {v}" for k, v in _flat(r).items()).lower()
        found_cves = {c.upper() for c in _CVE.findall(blob)}
        if not found_cves:
            continue
        for tname, tid in tech.items():
            if len(tname) < 3 or tname not in blob:
                continue
            for cv in found_cves:
                cid = cves.get(cv)
                if not cid:
                    continue
                key = (tid, "affected_by", cid)
                if key in seen:
                    continue
                seen.add(key)
                kg["relationships"].append(
                    {"from": tid, "to": cid, "type": "affected_by",
                     "label": "is affected by", "confidence": "medium"})
                added += 1
    if added:
        kg.setdefault("counts", {})["relationships"] = len(kg["relationships"])
    return added


def supply_chain(kg: Dict[str, Any], results: List[Result],
                 target: str) -> Dict[str, Any]:
    """Map external hosts pulled in at runtime (CDNs, JS libraries, analytics,
    payment/widget providers) as ``dependency`` entities linked to the target —
    the software supply chain the site actually depends on (feature 24)."""
    from .correlation import is_noise_domain
    ents = {e["id"]: e for e in kg.get("entities", [])}
    target_id = next((eid for eid, e in ents.items()
                      if e.get("kind") == "target"), None)
    tgt = (target or "").lower().rstrip(".")
    deps: Dict[str, set] = {}
    host_re = re.compile(r"https?://([a-z0-9.\-]+\.[a-z]{2,})", re.I)
    for r in results:
        for _, v in _flat(r).items():
            for host in host_re.findall(str(v)):
                host = host.lower().rstrip(".")
                if tgt and (host == tgt or host.endswith("." + tgt)):
                    continue  # first-party, not a dependency
                if not _DEP_HINT.search(host):
                    continue
                if is_noise_domain(host, tgt):
                    continue
                provider = _provider(host)
                deps.setdefault(provider, set()).add(host)

    catalog = []
    for provider, hosts in sorted(deps.items()):
        did = kg_add(kg, "dependency", provider)
        if did and target_id:
            kg_link(kg, target_id, "uses", did)
        catalog.append({"provider": provider, "hosts": sorted(hosts)[:8]})
    return {
        "dependencies": catalog[:24],
        "count": len(catalog),
        "note": "external providers the site loads resources from at runtime; "
                "each is a third party in your software supply chain.",
    }


def _provider(host: str) -> str:
    # prefer the most specific brand token (e.g. "jsdelivr" over generic "cdn")
    hits = [m.group(1).lower() for m in _DEP_HINT.finditer(host)]
    generic = {"cdn", "analytics", "gtag"}
    specific = [h for h in hits if h not in generic]
    if specific:
        return max(specific, key=len)
    return hits[0] if hits else host


def kg_add(kg: Dict[str, Any], kind: str, label: str) -> Optional[str]:
    label = (label or "").strip()
    if not label:
        return None
    eid = f"{kind}:{label.lower()}"
    if not any(e["id"] == eid for e in kg.get("entities", [])):
        kg.setdefault("entities", []).append(
            {"id": eid, "kind": kind, "label": label,
             "attrs": {}, "sources": ["supply-chain"]})
        by_kind = kg.setdefault("counts", {}).setdefault("by_kind", {})
        by_kind[kind] = by_kind.get(kind, 0) + 1
        kg["counts"]["entities"] = len(kg["entities"])
    return eid


def kg_link(kg: Dict[str, Any], src: Optional[str], typ: str,
            dst: Optional[str]) -> None:
    if not src or not dst or src == dst:
        return
    seen = {(r["from"], r["type"], r["to"]) for r in kg.get("relationships", [])}
    if (src, typ, dst) in seen:
        return
    kg.setdefault("relationships", []).append(
        {"from": src, "to": dst, "type": typ, "label": "uses",
         "confidence": "medium"})
    kg.setdefault("counts", {})["relationships"] = len(kg["relationships"])
