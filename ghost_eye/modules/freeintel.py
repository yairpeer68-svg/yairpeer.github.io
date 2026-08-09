"""Free, no-API-key threat-intel (new in v3.3). Replaces the paid Shodan/Censys
lookups that were removed. The free Shodan InternetDB lookup already lives in
the 'passive' module ('internetdb'); this file adds RIPEstat enrichment.

  * ripestat - RIPE NCC's RIPEstat Data API (https://stat.ripe.net): the
               announcing ASN, the covering BGP prefix, the AS holder name and
               the network's abuse-contact address. Completely free, no key and
               no signup, backed by the RIRs' own data.

Read-only enrichment (detection only)."""

from __future__ import annotations

import socket
from typing import Dict, List, Tuple

from ..core import Context, Module, Result, clean_host, is_ip, register

_BASE = "https://stat.ripe.net/data"


def _resolve(host: str) -> str:
    return host if is_ip(host) else socket.gethostbyname(host)


def _parse_netinfo(j: dict) -> Tuple[List[str], str]:
    d = j.get("data", {}) or {}
    asns = [str(a) for a in (d.get("asns") or [])]
    return asns, d.get("prefix", "")


def _parse_asoverview(j: dict) -> str:
    return (j.get("data", {}) or {}).get("holder", "")


def _parse_abuse(j: dict) -> List[str]:
    d = j.get("data", {}) or {}
    return [c for c in (d.get("abuse_contacts") or []) if c]


@register
class RipeStat(Module):
    id, name, category = "ripestat", "RIPEstat ASN/prefix/abuse (free, no key)", "Threat Intel"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            ip = _resolve(host)
        except ValueError as exc:
            return self.fail(target, str(exc))
        except OSError as exc:
            return self.fail(target, f"cannot resolve: {exc}")
        out: Dict[str, object] = {"ip": ip}
        try:
            ni = ctx.session.get(f"{_BASE}/network-info/data.json",
                                 params={"resource": ip}, timeout=ctx.timeout)
            if ni.status_code != 200:
                return self.fail(ip, f"RIPEstat HTTP {ni.status_code}")
            asns, prefix = _parse_netinfo(ni.json())
            out["prefix"] = prefix
            out["asns"] = asns
        except Exception as exc:  # noqa: BLE001
            return self.fail(ip, f"RIPEstat failed: {exc}")
        # AS holder name for the first ASN
        if out.get("asns"):
            try:
                ao = ctx.session.get(f"{_BASE}/as-overview/data.json",
                                     params={"resource": f"AS{out['asns'][0]}"},
                                     timeout=ctx.timeout)
                if ao.status_code == 200:
                    out["asn_holder"] = _parse_asoverview(ao.json())
            except Exception:  # noqa: BLE001
                pass
        # abuse contact for the IP
        try:
            ab = ctx.session.get(f"{_BASE}/abuse-contact-finder/data.json",
                                 params={"resource": ip}, timeout=ctx.timeout)
            if ab.status_code == 200:
                contacts = _parse_abuse(ab.json())
                if contacts:
                    out["abuse_contacts"] = contacts
        except Exception:  # noqa: BLE001
            pass
        return self.ok(ip, out)
