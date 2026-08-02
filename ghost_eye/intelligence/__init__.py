"""Intelligence layer — correlates the raw output of the 307 scan modules into
a single, unified attack-surface picture: assets, technologies (classified),
cloud footprint, email posture, certificate relationships, leak indicators, an
organization profile and a node/edge graph.

This is the layer that turns "a list of module results" into an ASM-style
intelligence report. Correlation only — it never scans anything itself, it
reasons over what the modules already found.
"""

from .correlation import correlate, organization_profile  # noqa: F401
from .graph import build_graph, render_svg  # noqa: F401

__all__ = ["correlate", "organization_profile", "build_graph", "render_svg"]
