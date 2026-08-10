"""CDN/WAF edge filtering — tell the target's real addresses from edge nodes.

Resolves the target (and, when present, the addresses other modules reported)
and classifies every address against published CDN/WAF ranges, so the answer to
"which of these IPs is actually theirs?" is explicit instead of guesswork.

Detection only — DNS resolution and range arithmetic, nothing sent to the
addresses themselves. FOR AUTHORISED SECURITY TESTING ONLY.
"""

from __future__ import annotations

import socket
from typing import List

from ..core import Context, Module, Result, clean_host, register
from ..netclass import classify_ips, CDN_RANGES


def _resolve(name: str) -> List[str]:
    out = set()
    for family in (socket.AF_INET, socket.AF_INET6):
        try:
            for info in socket.getaddrinfo(name, None, family):
                out.add(str(info[4][0]).split("%")[0])
        except Exception:  # noqa: BLE001
            continue
    return sorted(out)


@register
class CdnFilter(Module):
    id, name, category = "cdnfilter", "CDN/WAF edge filter (origin candidates)", "Network"
    expect = ["resolved_ips", "verdict"]
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        ips = _resolve(str(host))
        if not ips:
            return self.fail(host, "no addresses resolved")
        classified = classify_ips(ips)
        cdn = [c for c in classified if c["kind"] == "cdn"]
        origins = [c["ip"] for c in classified if c["kind"] == "origin"]
        cloud = [c for c in classified if c["kind"] == "cloud"]
        providers = sorted({c["provider"] for c in cdn if c["provider"]})
        fronted = bool(cdn) and not origins
        return self.ok(host, {
            "resolved_ips": ips,
            "behind_cdn": bool(cdn),
            "cdn_providers": providers or "none",
            "cdn_edge_ips": [c["ip"] for c in cdn],
            "cloud_ips": {c["ip"]: c["provider"] for c in cloud},
            "origin_candidates": origins or "none exposed",
            "verdict": ("FULLY FRONTED — every address is a CDN/WAF edge; the "
                        "real origin is not exposed in DNS"
                        if fronted else
                        "origin exposed — at least one address sits outside "
                        "every known CDN/WAF range" if origins else
                        "no CDN detected on these addresses"),
            "known_providers": len(CDN_RANGES),
            "note": "classification uses published edge ranges (bundled "
                    "snapshot). An address outside them is a *candidate* "
                    "origin, not proof — verify before acting.",
        })
