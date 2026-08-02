"""Entity extraction, smart correlation and the full Knowledge Graph.

This is the layer that turns the flat correlation picture into a *graph of
typed entities and typed relationships* — the difference between "a list of
subdomains/IPs/tech" and "this subdomain resolves_to this IP, which sits in
this netblock, hosted_on this cloud, whose cert was issued_for the apex".

Correlation only: it reasons over what the modules already found and never
scans anything itself. No LLM, no external calls.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional, Tuple

from ..core import Result, clean_host, is_ip
from ..reporting import _flatten

_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
_CVE = re.compile(r"CVE-\d{4}-\d{4,7}", re.I)
_ASN = re.compile(r"\bAS(\d{2,7})\b")
_HOSTRE = re.compile(r"[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?"
                     r"(?:\.[a-z0-9\-]{1,63})+")

# typed relationships between entities
REL = {
    "subdomain_of": "is a subdomain of",
    "resolves_to": "resolves to",
    "in_netblock": "is announced by",
    "hosted_on": "is hosted on",
    "uses": "uses",
    "issued_for": "issued a certificate for",
    "mx_for": "is a mail server for",
    "ns_for": "is a name server for",
    "affected_by": "is affected by",
    "exposes": "exposes",
    "registered_to": "is registered to",
    "related_to": "is related to",
}


def _valid_ip(s: str) -> bool:
    parts = s.split(".")
    return (len(parts) == 4
            and all(p.isdigit() and 0 <= int(p) <= 255 for p in parts)
            and not s.startswith(("0.", "255.", "127.")))


class _Graph:
    """Accumulates typed entities and typed edges, de-duplicated."""

    def __init__(self) -> None:
        self.ent: Dict[str, dict] = {}
        self.rel: List[dict] = []
        self._seen_rel: set = set()

    def add(self, kind: str, label: str, source: str = "",
            **attrs) -> Optional[str]:
        label = (label or "").strip()
        if not label:
            return None
        eid = f"{kind}:{label.lower()}"
        e = self.ent.get(eid)
        if not e:
            e = {"id": eid, "kind": kind, "label": label,
                 "attrs": {}, "sources": []}
            self.ent[eid] = e
        if source and source not in e["sources"]:
            e["sources"].append(source)
        for k, v in attrs.items():
            if v not in (None, "", []):
                e["attrs"][k] = v
        return eid

    def link(self, src: Optional[str], typ: str, dst: Optional[str],
             confidence: str = "medium") -> None:
        if not src or not dst or src == dst:
            return
        key = (src, typ, dst)
        if key in self._seen_rel:
            return
        self._seen_rel.add(key)
        self.rel.append({"from": src, "to": dst, "type": typ,
                         "label": REL.get(typ, typ), "confidence": confidence})


def _flat(r: Result) -> Dict[str, str]:
    out: Dict[str, str] = {}
    _flatten("", getattr(r, "data", {}) or {}, out)
    return out


def _dns_map(results: List[Result]) -> Dict[str, List[str]]:
    """host -> [A/AAAA IPs] mined from DNS-ish modules, so we can draw real
    resolves_to edges instead of guessing."""
    out: Dict[str, set] = {}
    for r in results:
        mod = getattr(r, "module", "").lower()
        if not any(m in mod for m in ("dns", "record", "resolve", "a record",
                                      "host", "ip")):
            continue
        host = str(getattr(r, "target", "")).lower().rstrip(".")
        flat = _flat(r)
        for k, v in flat.items():
            kl = k.lower()
            if any(t in kl for t in ("a", "aaaa", "ip", "address", "resolve")):
                for m in _IPV4.findall(str(v)):
                    if _valid_ip(m):
                        out.setdefault(host, set()).add(m)
    return {h: sorted(v) for h, v in out.items() if h}


def _mx_ns(results: List[Result]) -> Tuple[List[str], List[str], List[str]]:
    """(mx hosts, ns hosts, org names) from DNS/WHOIS output."""
    mx, ns, orgs = set(), set(), set()
    for r in results:
        flat = _flat(r)
        for k, v in flat.items():
            kl, sv = k.lower(), str(v)
            if "mx" in kl or "mail exchang" in kl:
                for h in _HOSTRE.findall(sv.lower()):
                    mx.add(h)
            if kl.endswith("ns") or "nameserver" in kl or "name server" in kl:
                for h in _HOSTRE.findall(sv.lower()):
                    ns.add(h)
            if any(t in kl for t in ("org", "registrant", "organization",
                                     "owner", "netname")) and 2 < len(sv) < 60:
                if not _HOSTRE.fullmatch(sv.lower()) and not _valid_ip(sv):
                    orgs.add(sv.strip())
    return sorted(mx)[:10], sorted(ns)[:10], sorted(orgs)[:6]


def knowledge_graph(results: List[Result], target: str,
                    intel: Dict[str, Any]) -> Dict[str, Any]:
    """Build the full typed knowledge graph from correlated intelligence.

    Returns {entities, relationships, counts} where every edge is a typed,
    directed relationship (resolves_to, hosted_on, issued_for, ...)."""
    g = _Graph()
    tgt = (target or intel.get("target", "")).lower().rstrip(".")
    try:
        tgt = clean_host(tgt) if tgt else tgt
    except ValueError:
        pass
    tid = g.add("target", tgt or "target", "correlation")

    # subdomains --subdomain_of--> target
    for s in intel.get("subdomains", []):
        if s == tgt:
            continue
        sid = g.add("subdomain", s, "subdomain enumeration")
        g.link(sid, "subdomain_of", tid, "high")

    for d in intel.get("related_domains", []):
        did = g.add("domain", d, "correlation")
        g.link(did, "related_to", tid, "low")

    # IPs, and host --resolves_to--> ip from DNS output
    for ip in intel.get("ips", []):
        g.add("ip", ip, "dns/network")
    dns = _dns_map(results)
    for host, ips in dns.items():
        hid = (f"subdomain:{host}" if f"subdomain:{host}" in g.ent
               else g.add("subdomain", host, "dns")
               if host != tgt else tid)
        for ip in ips:
            iid = g.add("ip", ip, "dns")
            g.link(hid, "resolves_to", iid, "high")

    # ASN / netblock
    blob = " ".join(f"{k} {v}" for r in results for k, v in _flat(r).items())
    for asn in sorted(set(_ASN.findall(blob)))[:6]:
        aid = g.add("asn", f"AS{asn}", "network")
        for iid in [e for e in g.ent if e.startswith("ip:")]:
            g.link(iid, "in_netblock", aid, "medium")

    # cloud --hosted_on
    for c in intel.get("cloud", []):
        if "unknown" in c.lower():
            continue
        cid = g.add("cloud", c, "correlation")
        g.link(tid, "hosted_on", cid, "medium")

    # technologies --uses
    for kind, items in intel.get("technologies", {}).items():
        for t in items:
            tech_id = g.add("tech", t, "fingerprint", category=kind)
            g.link(tid, "uses", tech_id, "medium")

    # certificate issuers --issued_for--> target
    for issuer in intel.get("certificates", {}).get("issuers", []):
        short = issuer.split(",")[0][:40]
        cid = g.add("cert_issuer", short, "certificate")
        g.link(cid, "issued_for", tid, "high")

    # emails --registered_to / mx / ns / org
    for em in intel.get("emails", [])[:20]:
        eid = g.add("email", em, "osint")
        g.link(eid, "registered_to", tid, "low")
    mx, ns, orgs = _mx_ns(results)
    for h in mx:
        g.link(g.add("mailserver", h, "dns"), "mx_for", tid, "high")
    for h in ns:
        g.link(g.add("nameserver", h, "dns"), "ns_for", tid, "high")
    for o in orgs:
        g.link(tid, "registered_to", g.add("org", o, "whois"), "medium")

    # CVEs --affected_by (attach to the target and to matching tech)
    for cve in sorted(set(c.upper() for c in _CVE.findall(blob)))[:30]:
        vid = g.add("cve", cve, "vuln intel")
        g.link(tid, "affected_by", vid, "medium")

    # leak indicators --exposes
    for i, leak in enumerate(intel.get("leak_indicators", [])[:12]):
        lid = g.add("leak", f"leak#{i + 1}", "leak intel", detail=leak[:120])
        g.link(lid, "exposes", tid, "medium")

    entities = list(g.ent.values())
    by_kind: Dict[str, int] = {}
    for e in entities:
        by_kind[e["kind"]] = by_kind.get(e["kind"], 0) + 1
    return {
        "entities": entities,
        "relationships": g.rel,
        "counts": {"entities": len(entities),
                   "relationships": len(g.rel),
                   "by_kind": by_kind},
    }


def entity_correlation(kg: Dict[str, Any]) -> Dict[str, Any]:
    """Smart entity correlation over the knowledge graph:

    * degree — the most connected entities ("pivot points" an attacker or an
      analyst would move through);
    * clusters — connected components (assets that provably belong together);
    * shared_infrastructure — IPs/netblocks/cloud that tie many hosts together.
    """
    ents = {e["id"]: e for e in kg["entities"]}
    adj: Dict[str, set] = {eid: set() for eid in ents}
    degree: Dict[str, int] = {eid: 0 for eid in ents}
    for r in kg["relationships"]:
        a, b = r["from"], r["to"]
        if a in adj and b in adj:
            adj[a].add(b)
            adj[b].add(a)
            degree[a] += 1
            degree[b] += 1

    # connected components
    seen: set = set()
    clusters: List[dict] = []
    for eid in ents:
        if eid in seen:
            continue
        stack, comp = [eid], []
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            comp.append(cur)
            stack.extend(adj[cur] - seen)
        if len(comp) >= 2:
            kinds: Dict[str, int] = {}
            for m in comp:
                k = ents[m]["kind"]
                kinds[k] = kinds.get(k, 0) + 1
            anchor = max(comp, key=lambda m: degree[m])
            clusters.append({
                "size": len(comp),
                "anchor": ents[anchor]["label"],
                "anchor_kind": ents[anchor]["kind"],
                "kinds": kinds,
                "members": [ents[m]["label"] for m in comp[:40]],
            })
    clusters.sort(key=lambda c: c["size"], reverse=True)

    pivots = sorted(
        ({"entity": ents[eid]["label"], "kind": ents[eid]["kind"],
          "degree": deg} for eid, deg in degree.items() if deg >= 2),
        key=lambda p: p["degree"], reverse=True)[:10]

    # shared infrastructure — an IP/netblock/cloud that many hosts point at
    shared: List[dict] = []
    for eid, e in ents.items():
        if e["kind"] in ("ip", "asn", "cloud", "cert_issuer", "nameserver"):
            neighbours = [ents[n]["label"] for n in adj[eid]
                          if ents[n]["kind"] in ("subdomain", "domain",
                                                 "target")]
            if len(neighbours) >= 2:
                shared.append({"hub": e["label"], "kind": e["kind"],
                               "connects": len(neighbours),
                               "hosts": neighbours[:12]})
    shared.sort(key=lambda s: s["connects"], reverse=True)

    return {
        "pivot_points": pivots,
        "clusters": clusters[:8],
        "shared_infrastructure": shared[:8],
        "note": "correlated from the knowledge graph; a pivot point is a highly "
                "connected entity, shared infrastructure ties multiple hosts "
                "to one hub.",
    }
