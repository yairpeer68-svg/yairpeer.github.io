"""Intelligence layer — correlates the raw output of the 307 scan modules into
a single, unified attack-surface picture: assets, technologies (classified),
cloud footprint, email posture, certificate relationships, leak indicators, an
organization profile, a full typed Knowledge Graph with smart entity
correlation, an Intelligence Timeline and a rule-based AI-analyst write-up.

This is the layer that turns "a list of module results" into a Personal Cyber
Intelligence Platform. Correlation only — it never scans anything itself, it
reasons over what the modules already found (no LLM, no external calls).
"""

from .analyst import analyze  # noqa: F401
from .correlation import correlate, organization_profile  # noqa: F401
from .entities import entity_correlation, knowledge_graph  # noqa: F401
from .graph import (  # noqa: F401
    build_graph,
    render_knowledge_svg,
    render_svg,
)
from .graph_export import to_gexf, to_graphml, unified_graph  # noqa: F401
from .risk import (  # noqa: F401
    attack_paths,
    enrich_tech_cve,
    risk_heatmap,
    supply_chain,
)
from .timeline import build_timeline  # noqa: F401

__all__ = [
    "correlate", "organization_profile",
    "build_graph", "render_svg", "render_knowledge_svg",
    "knowledge_graph", "entity_correlation",
    "risk_heatmap", "attack_paths", "enrich_tech_cve", "supply_chain",
    "to_graphml", "to_gexf", "unified_graph",
    "build_timeline", "analyze",
]
