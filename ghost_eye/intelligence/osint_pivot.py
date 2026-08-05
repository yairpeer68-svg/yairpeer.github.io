"""Advanced OSINT — automated multi-hop pivoting / deep-dive.

Given a single seed (a domain), this turns the flat OSINT modules into an
*autonomous investigation*: it runs OSINT sources, extracts the entities they
reveal (related domains, e-mails, IPs), then **pivots onto those entities** —
running the right modules for each kind — up to a chosen depth, merging
everything into one enriched Knowledge Graph with **provenance** (which hop, and
from which parent, each entity was discovered) and a **confidence** per hop.

The module runner is injectable (`run_fn`), so the engine is fully testable
without any network, and the real path drives the same scan engine the CLI uses.
Correlation / orchestration only — it runs the same safe modules, just chained.
"""

from __future__ import annotations

from typing import Any, Callable, Dict, List, Optional, Tuple

from ..core import REGISTRY, Result

# which modules to run when we pivot onto an entity of a given kind.
# Only ids that actually exist in the registry are used.
PIVOT_MODULES: Dict[str, List[str]] = {
    "domain": ["whois", "dns", "subs", "emails", "related", "whoispivot",
               "username", "dorks", "emailauth", "waybackcdx", "faviconhash",
               "certpivot", "emailpattern",
               # free/keyless multi-source breadth
               "certspotter", "bufferover", "hackertarget", "subdomaincenter",
               "otxrep", "hudsonrock", "grepapp", "searchcode", "urlhaus",
               "spamhausdbl", "psbdmp", "keybase", "certdetails", "sitedossier",
               "favicmmh3", "anubisjldc", "waybackparams", "wikidata", "commoncrawlmine", "phoneharvest", "extdomains", "otxpulse"],
    "email": ["breachcheck", "hibpbreach", "gravatar", "emailperm", "emailrep", "leakcheck"],
    "username": ["username"],
    "ip": ["geoip", "internetdb", "reputation", "rdap",
           "robtex", "bgpview", "ipapi", "cymruasn",
           "reverseip", "otxip", "iptoasn", "feodo", "peeringdb", "ipwhois", "dnsbl", "stopforumspam", "ipapinet", "blocklistde", "ipinfo"],
}
# confidence decays with each hop away from the seed
_HOP_CONFIDENCE = ["high", "medium", "low", "low"]

RunFn = Callable[[str, List[str], Any], List[Result]]


def annotate_confidence(kg: Dict[str, Any]) -> Dict[str, Any]:
    """Score every entity by **source corroboration**: an entity confirmed by
    several independent modules/sources is more trustworthy than one seen once.
    Writes ``corroboration`` (count) and ``source_confidence`` into each node's
    attrs and returns a summary. This is what makes the OSINT picture defensible
    rather than a pile of unranked hits."""
    bands = {"high": 0, "medium": 0, "low": 0}
    for e in kg.get("entities", []):
        n = len(e.get("sources", []) or [])
        conf = "high" if n >= 3 else "medium" if n == 2 else "low"
        attrs = e.setdefault("attrs", {})
        attrs["corroboration"] = n
        attrs["source_confidence"] = conf
        bands[conf] += 1
    return {"by_confidence": bands,
            "note": "entity confidence from independent-source corroboration "
                    "(>=3 sources = high, 2 = medium, 1 = low)."}


def _default_run_fn(target: str, module_ids: List[str], cfg: Any) -> List[Result]:
    """Run the given modules against one target using the real scan engine."""
    from .. import engine
    from ..core import Context
    try:
        from ..webapp import build_session
        session = build_session(timeout=12)
    except Exception:  # noqa: BLE001
        from ..core import build_session as _bs
        session = _bs(timeout=12)
    ctx = Context(config=cfg, session=session, timeout=12)
    mods = [REGISTRY[m] for m in module_ids if m in REGISTRY]
    return engine.run_scan(mods, target, ctx, parallel=4)


def _extract_entities(results: List[Result], seed: str) -> Dict[str, List[str]]:
    """Pull pivotable entities out of a batch of results via the correlator."""
    from .correlation import correlate, is_noise_domain
    intel = correlate(results, seed)
    out: Dict[str, List[str]] = {"domain": [], "email": [], "ip": []}
    for d in intel.get("related_domains", []):
        if d and not is_noise_domain(d, seed):
            out["domain"].append(d.lower())
    for e in intel.get("emails", []):
        if e and "@" in e:
            out["email"].append(e.lower())
    for ip in intel.get("ips", []):
        if ip:
            out["ip"].append(ip)
    # de-dup preserving order
    for k in out:
        seen: set = set()
        out[k] = [x for x in out[k] if not (x in seen or seen.add(x))]
    return out


def deep_dive(seed: str, run_fn: Optional[RunFn] = None, cfg: Any = None,
              depth: int = 1, max_per_hop: int = 12,
              max_total: int = 60) -> Dict[str, Any]:
    """Run an automated OSINT investigation starting from ``seed``.

    depth 0 = just the seed; depth 1 = seed + its directly-discovered entities;
    depth 2 = one more pivot, and so on. Returns the merged Knowledge Graph plus
    a per-hop trail with provenance."""
    run_fn = run_fn or _default_run_fn
    seed = (seed or "").strip().lower()
    if not seed:
        return {"error": "no seed"}

    all_results: List[Result] = []
    provenance: List[Dict[str, Any]] = []
    visited: set = set()
    # frontier items: (entity, kind, parent)
    frontier: List[Tuple[str, str, str]] = [(seed, "domain", "")]
    processed = 0
    hops: List[Dict[str, Any]] = []

    for hop in range(depth + 1):
        if not frontier:
            break
        conf = _HOP_CONFIDENCE[min(hop, len(_HOP_CONFIDENCE) - 1)]
        next_frontier: List[Tuple[str, str, str]] = []
        hop_discovered: Dict[str, List[str]] = {"domain": [], "email": [], "ip": []}
        for entity, kind, parent in frontier:
            key = f"{kind}:{entity}"
            if key in visited or processed >= max_total:
                continue
            visited.add(entity)
            visited.add(key)
            processed += 1
            module_ids = [m for m in PIVOT_MODULES.get(kind, []) if m in REGISTRY]
            if not module_ids:
                continue
            try:
                results = run_fn(entity, module_ids, cfg)
            except Exception:  # noqa: BLE001
                results = []
            all_results.extend(results)
            provenance.append({"entity": entity, "kind": kind, "hop": hop,
                               "parent": parent, "confidence": conf,
                               "modules": len(results)})
            # discover the next hop's entities (only from a domain/ip pivot)
            if hop < depth and kind in ("domain", "ip"):
                found = _extract_entities(results, seed)
                for fk, items in found.items():
                    for it in items:
                        if it in visited or f"{fk}:{it}" in visited:
                            continue
                        hop_discovered[fk].append(it)
                        next_frontier.append((it, fk, entity))
        hops.append({"hop": hop, "confidence": conf,
                     "processed": len([p for p in provenance if p["hop"] == hop]),
                     "discovered": {k: v[:30] for k, v in hop_discovered.items()},
                     "discovered_counts": {k: len(v) for k, v in hop_discovered.items()}})
        # cap the breadth of the next hop
        frontier = next_frontier[:max_per_hop]

    # merge everything into one enriched knowledge graph
    from .entities import knowledge_graph
    from .correlation import correlate
    intel = correlate(all_results, seed)
    kg = knowledge_graph(all_results, seed, intel)
    confidence = annotate_confidence(kg)   # source-corroboration scoring
    from .identity import identity_graph
    identity = identity_graph(all_results, seed)
    return {
        "seed": seed,
        "depth": depth,
        "entities_processed": processed,
        "hops": hops,
        "provenance": provenance,
        "knowledge_graph": kg,
        "confidence": confidence,
        "identity_graph": identity,
        "counts": kg.get("counts", {}),
        "note": "automated multi-hop OSINT: each entity discovered by one hop is "
                "pivoted on in the next. Confidence decays with distance from the "
                "seed. Reconnaissance/detection only.",
    }
