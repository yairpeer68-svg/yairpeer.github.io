"""DNS-over-HTTPS resolver (new). Resolves through Cloudflare / Google DoH over
HTTPS instead of the system resolver - handy on mobile networks, captive
portals, or when the local resolver is hijacked/unreliable."""

from __future__ import annotations

from typing import Dict, List

from ..core import Context, Module, Result, clean_host, register

_PROVIDERS = [
    ("Cloudflare", "https://cloudflare-dns.com/dns-query"),
    ("Google", "https://dns.google/resolve"),
]
_TYPES = ["A", "AAAA", "MX", "TXT", "NS", "CNAME"]


def doh_query(session, name: str, rtype: str, timeout: int) -> List[str]:
    """Resolve one name/type via DoH JSON. Returns a list of answer strings."""
    last = None
    for _prov, url in _PROVIDERS:
        try:
            r = session.get(url, params={"name": name, "type": rtype},
                            headers={"accept": "application/dns-json"},
                            timeout=timeout)
            if r.status_code != 200:
                last = f"HTTP {r.status_code}"
                continue
            ans = r.json().get("Answer", []) or []
            out = [a.get("data", "").strip().strip('"') for a in ans if a.get("data")]
            if out or r.json().get("Status") == 0:
                return out
        except Exception as exc:  # noqa: BLE001
            last = str(exc)
            continue
    if last:
        raise RuntimeError(last)
    return []


@register
class DohResolve(Module):
    id = "doh"
    name = "DNS-over-HTTPS resolver"
    category = "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        records: Dict[str, object] = {}
        errors = 0
        for rtype in _TYPES:
            try:
                vals = doh_query(ctx.session, host, rtype, ctx.timeout)
                if vals:
                    records[rtype] = vals
            except Exception as exc:  # noqa: BLE001
                errors += 1
                records[rtype] = f"query failed: {str(exc)[:50]}"
        if errors == len(_TYPES):
            return self.fail(host, "all DoH queries failed (no HTTPS egress?)")
        return self.ok(host, {"records": records,
                              "note": "resolved over HTTPS (Cloudflare/Google), "
                                      "bypassing the local DNS resolver"})
