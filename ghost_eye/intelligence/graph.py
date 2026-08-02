"""Build and render the attack-surface graph from correlated intelligence."""

from __future__ import annotations

import html as _html
import math
from typing import Any, Dict, List

_KIND_COLORS = {
    "target": "#f85149", "subdomain": "#58a6ff", "ip": "#3fb950",
    "cloud": "#d29922", "tech": "#a371f7", "service": "#e3b341",
    "domain": "#79c0ff",
}


def build_graph(intel: Dict[str, Any]) -> Dict[str, List[dict]]:
    """A node/edge graph: target at the centre, connected to its subdomains,
    IPs, cloud providers and detected technologies."""
    target = intel.get("target", "target")
    nodes: List[dict] = [{"id": target, "label": target, "kind": "target"}]
    edges: List[dict] = []
    seen = {target}

    def add(items, kind, cap):
        for it in list(items)[:cap]:
            nid = f"{kind}:{it}"
            if nid in seen:
                continue
            seen.add(nid)
            nodes.append({"id": nid, "label": str(it), "kind": kind})
            edges.append({"from": target, "to": nid})

    add(intel.get("subdomains", []), "subdomain", 24)
    add(intel.get("ips", []), "ip", 16)
    add([c for c in intel.get("cloud", []) if "unknown" not in c], "cloud", 8)
    techs: List[str] = []
    for group in intel.get("technologies", {}).values():
        techs.extend(group)
    add(techs, "tech", 12)
    return {"nodes": nodes, "edges": edges}


def render_svg(graph: Dict[str, List[dict]], width: int = 860,
               height: int = 600) -> str:
    """Render the graph as a self-contained radial SVG (no JS/libs)."""
    cx, cy = width // 2, height // 2
    nodes = graph["nodes"]
    by_kind: Dict[str, List[dict]] = {}
    center = None
    for n in nodes:
        if n["kind"] == "target":
            center = n
        else:
            by_kind.setdefault(n["kind"], []).append(n)

    # each kind gets a ring radius
    radii = {"tech": 90, "cloud": 150, "ip": 215, "subdomain": 275,
             "service": 190, "domain": 240}
    pos: Dict[str, tuple] = {}
    edges_svg, nodes_svg, labels_svg = [], [], []

    for kind, group in by_kind.items():
        r = radii.get(kind, 200)
        color = _KIND_COLORS.get(kind, "#8b949e")
        n = len(group)
        for i, node in enumerate(group):
            ang = (2 * math.pi * i / n) - math.pi / 2 if n else 0
            x = cx + r * math.cos(ang)
            y = cy + r * math.sin(ang)
            pos[node["id"]] = (x, y)
            edges_svg.append(
                f'<line x1="{cx}" y1="{cy}" x2="{x:.0f}" y2="{y:.0f}" '
                f'stroke="{color}" stroke-opacity="0.22" stroke-width="1"/>')
            nodes_svg.append(
                f'<circle cx="{x:.0f}" cy="{y:.0f}" r="4.5" fill="{color}"/>')
            lab = _html.escape(node["label"][:26])
            anchor = "start" if x >= cx else "end"
            dx = 7 if x >= cx else -7
            labels_svg.append(
                f'<text x="{x + dx:.0f}" y="{y + 3:.0f}" font-size="9.5" '
                f'fill="#8b949e" text-anchor="{anchor}">{lab}</text>')

    clabel = _html.escape((center or {}).get("label", "target")[:40])
    center_svg = (f'<circle cx="{cx}" cy="{cy}" r="9" fill="#f85149"/>'
                  f'<text x="{cx}" y="{cy - 16}" font-size="14" font-weight="700" '
                  f'fill="#e6edf3" text-anchor="middle">{clabel}</text>')

    legend_items = [("subdomain", "subdomains"), ("ip", "IPs"),
                    ("cloud", "cloud"), ("tech", "tech")]
    legend = ['<g font-size="11" fill="#8b949e">']
    for i, (kind, label) in enumerate(legend_items):
        y = 18 + i * 20
        legend.append(f'<circle cx="16" cy="{y}" r="5" '
                      f'fill="{_KIND_COLORS[kind]}"/>'
                      f'<text x="28" y="{y + 4}">{label}</text>')
    legend.append("</g>")

    return (f'<svg viewBox="0 0 {width} {height}" width="100%" role="img" '
            f'aria-label="attack surface graph">'
            + "".join(edges_svg) + "".join(nodes_svg) + center_svg
            + "".join(labels_svg) + "".join(legend) + "</svg>")
