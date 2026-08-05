"""Export the typed Knowledge Graph to interchange formats and merge several
targets' graphs into one unified view.

* ``to_graphml`` — GraphML (yEd / Gephi / Cytoscape / NetworkX import).
* ``to_gexf``    — GEXF (Gephi native).
* ``unified_graph`` — merge the knowledge graphs of several targets into a
  single graph, adding cross-target edges wherever they *share* an IP, netblock,
  cloud, cert-issuer or name-server (feature 4).

Pure string/XML assembly — no external libraries, no network.
"""

from __future__ import annotations

from typing import Any, Dict, List, Tuple
from xml.sax.saxutils import escape, quoteattr


def _xml_attr(v: Any) -> str:
    return quoteattr(str(v))


def to_graphml(kg: Dict[str, Any]) -> str:
    """Serialise the knowledge graph as GraphML. Node keys: kind, label, risk,
    risk_band, sources. Edge key: relation (the typed relationship)."""
    ents = kg.get("entities", [])
    rels = kg.get("relationships", [])
    out: List[str] = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
        '<key id="kind" for="node" attr.name="kind" attr.type="string"/>',
        '<key id="label" for="node" attr.name="label" attr.type="string"/>',
        '<key id="risk" for="node" attr.name="risk" attr.type="int"/>',
        '<key id="band" for="node" attr.name="risk_band" attr.type="string"/>',
        '<key id="sources" for="node" attr.name="sources" attr.type="string"/>',
        '<key id="relation" for="edge" attr.name="relation" attr.type="string"/>',
        '<key id="confidence" for="edge" attr.name="confidence" attr.type="string"/>',
        '<graph edgedefault="directed">',
    ]
    for e in ents:
        attrs = e.get("attrs", {}) or {}
        out.append(f'<node id={_xml_attr(e["id"])}>')
        out.append(f'  <data key="kind">{escape(str(e.get("kind", "")))}</data>')
        out.append(f'  <data key="label">{escape(str(e.get("label", "")))}</data>')
        if "risk" in attrs:
            out.append(f'  <data key="risk">{int(attrs.get("risk", 0))}</data>')
            out.append(f'  <data key="band">{escape(str(attrs.get("risk_band", "")))}</data>')
        srcs = ", ".join(e.get("sources", []) or [])
        if srcs:
            out.append(f'  <data key="sources">{escape(srcs)}</data>')
        out.append('</node>')
    for i, r in enumerate(rels):
        out.append(f'<edge id="e{i}" source={_xml_attr(r["from"])} '
                   f'target={_xml_attr(r["to"])}>')
        out.append(f'  <data key="relation">{escape(str(r.get("type", "")))}</data>')
        out.append(f'  <data key="confidence">{escape(str(r.get("confidence", "")))}</data>')
        out.append('</edge>')
    out.append('</graph></graphml>')
    return "\n".join(out)


def to_gexf(kg: Dict[str, Any]) -> str:
    """Serialise the knowledge graph as GEXF (Gephi)."""
    ents = kg.get("entities", [])
    rels = kg.get("relationships", [])
    out: List[str] = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<gexf xmlns="http://gexf.net/1.3" version="1.3">',
        '<graph mode="static" defaultedgetype="directed">',
        '<attributes class="node">',
        '  <attribute id="0" title="kind" type="string"/>',
        '  <attribute id="1" title="risk" type="integer"/>',
        '  <attribute id="2" title="risk_band" type="string"/>',
        '</attributes>',
        '<nodes>',
    ]
    for e in ents:
        attrs = e.get("attrs", {}) or {}
        out.append(f'<node id={_xml_attr(e["id"])} label={_xml_attr(e.get("label", ""))}>')
        out.append('  <attvalues>')
        out.append(f'    <attvalue for="0" value={_xml_attr(e.get("kind", ""))}/>')
        if "risk" in attrs:
            out.append(f'    <attvalue for="1" value="{int(attrs.get("risk", 0))}"/>')
            out.append(f'    <attvalue for="2" value={_xml_attr(attrs.get("risk_band", ""))}/>')
        out.append('  </attvalues>')
        out.append('</node>')
    out.append('</nodes>')
    out.append('<edges>')
    for i, r in enumerate(rels):
        out.append(f'<edge id="{i}" source={_xml_attr(r["from"])} '
                   f'target={_xml_attr(r["to"])} label={_xml_attr(r.get("type", ""))}/>')
    out.append('</edges>')
    out.append('</graph></gexf>')
    return "\n".join(out)


# entity kinds that, when shared between two targets, imply a real link
_SHARED_KINDS = ("ip", "asn", "cloud", "cert_issuer", "nameserver",
                 "mailserver", "dependency")


def unified_graph(graphs: List[Tuple[str, Dict[str, Any]]]) -> Dict[str, Any]:
    """Merge several ``(target, knowledge_graph)`` pairs into one graph.

    Entities are de-duplicated by id (a shared IP/cloud/issuer appears once and
    connects to every target that uses it). For each shared-infrastructure
    entity touched by more than one target we surface a ``shared_between`` edge
    so the cross-target pivot is explicit. Returns the same
    ``{entities, relationships, counts, targets}`` shape the dashboard renders."""
    ent: Dict[str, dict] = {}
    rels: List[dict] = []
    seen_rel: set = set()
    # which targets each entity id is reachable from (for shared detection)
    touched: Dict[str, set] = {}
    targets: List[str] = []

    for target, kg in graphs:
        targets.append(target)
        target_ids = {e["id"] for e in kg.get("entities", [])
                      if e.get("kind") == "target"}
        for e in kg.get("entities", []):
            eid = e["id"]
            cur = ent.get(eid)
            if not cur:
                # deep-ish copy so we don't mutate the source graph
                ent[eid] = {"id": eid, "kind": e.get("kind", ""),
                            "label": e.get("label", ""),
                            "attrs": dict(e.get("attrs", {}) or {}),
                            "sources": list(e.get("sources", []) or [])}
            else:
                for s in e.get("sources", []) or []:
                    if s not in cur["sources"]:
                        cur["sources"].append(s)
            touched.setdefault(eid, set()).update(target_ids or {target})
        for r in kg.get("relationships", []):
            key = (r["from"], r.get("type"), r["to"])
            if key in seen_rel:
                continue
            seen_rel.add(key)
            rels.append(dict(r))

    # cross-target links via shared infrastructure
    shared_hits: List[dict] = []
    for eid, tset in touched.items():
        e = ent.get(eid)
        if not e or e["kind"] not in _SHARED_KINDS or len(tset) < 2:
            continue
        e["attrs"]["shared_between"] = len(tset)
        tlist = sorted(tset)
        shared_hits.append({"hub": e["label"], "kind": e["kind"],
                            "targets": [t.split(":", 1)[-1] for t in tlist]})
        for i in range(len(tlist)):
            for j in range(i + 1, len(tlist)):
                a, b = tlist[i], tlist[j]
                if a in ent and b in ent:
                    key = (a, "shared_between", b)
                    if key not in seen_rel:
                        seen_rel.add(key)
                        rels.append({"from": a, "to": b,
                                     "type": "shared_between",
                                     "label": f"shares {e['kind']} {e['label']}",
                                     "confidence": "high"})

    entities = list(ent.values())
    by_kind: Dict[str, int] = {}
    for e in entities:
        by_kind[e["kind"]] = by_kind.get(e["kind"], 0) + 1
    return {
        "entities": entities,
        "relationships": rels,
        "targets": [t for t in targets],
        "shared_infrastructure": sorted(
            shared_hits, key=lambda s: len(s["targets"]), reverse=True)[:20],
        "counts": {"entities": len(entities), "relationships": len(rels),
                   "targets": len(targets), "by_kind": by_kind},
    }
