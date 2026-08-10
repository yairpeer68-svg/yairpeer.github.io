"""IP classification — separate CDN/WAF edge addresses from real origins.

When a scan comes back, most of the IPs are usually not the target's servers at
all: they are Cloudflare/Akamai/Fastly edge nodes that thousands of unrelated
sites also answer from. Treating them as the target's infrastructure is the
single most common way an OSINT picture goes wrong — it inflates the asset
count, poisons attribution, and hides the handful of addresses that actually
matter.

This module classifies every IP a scan produced into:

  * ``cdn``     — a published CDN / WAF edge range (Cloudflare, Akamai, …)
  * ``cloud``   — a known cloud provider range (not necessarily fronted)
  * ``private`` — RFC1918 / loopback / link-local / reserved
  * ``origin``  — none of the above: a candidate *real* server

Ranges are bundled so classification works offline, and can be refreshed from
the providers' own published endpoints with ``refresh_ranges()`` when you want
them current.
"""

from __future__ import annotations

import ipaddress
from typing import Any, Dict, Iterable, List, Optional, Tuple

# --------------------------------------------------------------------------- #
#  Published edge ranges (snapshot; see refresh_ranges() to update live)
# --------------------------------------------------------------------------- #
CDN_RANGES: Dict[str, List[str]] = {
    "Cloudflare": [
        "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22",
        "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
        "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22",
        "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
        "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32",
        "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29",
        "2c0f:f248::/32",
    ],
    "Fastly": ["151.101.0.0/16", "199.232.0.0/16", "23.235.32.0/20",
               "43.249.72.0/22", "103.244.50.0/24", "146.75.0.0/16",
               "2a04:4e40::/32"],
    "CloudFront": ["13.32.0.0/15", "13.224.0.0/14", "52.84.0.0/15",
                   "54.192.0.0/16", "54.230.0.0/16", "54.239.128.0/18",
                   "99.84.0.0/16", "205.251.192.0/19", "143.204.0.0/16",
                   "2600:9000::/28"],
    "Akamai": ["23.32.0.0/11", "23.192.0.0/11", "104.64.0.0/10",
               "184.24.0.0/13", "2.16.0.0/13", "95.100.0.0/15",
               "96.16.0.0/15", "88.221.0.0/16"],
    "Imperva/Incapsula": ["199.83.128.0/21", "198.143.32.0/19",
                          "149.126.72.0/21", "103.28.248.0/22",
                          "45.64.64.0/22", "185.11.124.0/22",
                          "192.230.64.0/18", "107.154.0.0/16",
                          "45.223.0.0/16", "131.125.128.0/17"],
    "Sucuri": ["192.88.134.0/23", "185.93.228.0/22", "66.248.200.0/22",
               "208.109.0.0/22"],
    "StackPath/Highwinds": ["151.139.0.0/16", "205.185.216.0/21",
                            "69.16.175.0/24", "192.230.64.0/18"],
    "BunnyCDN": ["169.150.196.0/22", "89.187.160.0/19", "143.244.48.0/20"],
    "DDoS-Guard": ["186.2.160.0/20", "190.115.16.0/20", "45.132.192.0/22",
                   "185.178.208.0/22"],
    "Qrator": ["87.245.197.0/24", "185.51.116.0/22", "178.248.232.0/21"],
    "CDN77": ["185.59.220.0/22", "212.102.32.0/19"],
    "Gcore": ["92.223.64.0/18", "5.188.120.0/22", "185.229.224.0/22"],
    "Azure Front Door": ["13.107.0.0/16", "150.171.0.0/16", "204.79.197.0/24"],
    "Google Cloud LB": ["34.96.0.0/20", "34.107.0.0/16", "35.190.0.0/17",
                        "130.211.0.0/16"],
}

# Broad cloud-provider space. Being here does not mean "fronted" — it means the
# address belongs to a hosting provider, which is useful context but is NOT a
# reason to discard it as an origin candidate.
CLOUD_RANGES: Dict[str, List[str]] = {
    "AWS": ["3.0.0.0/8", "18.128.0.0/9", "52.0.0.0/8", "54.0.0.0/8",
            "35.152.0.0/13", "16.0.0.0/9"],
    "Google": ["34.64.0.0/10", "35.184.0.0/13", "35.192.0.0/11",
               "104.196.0.0/14", "146.148.0.0/17"],
    "Azure": ["20.0.0.0/8", "40.64.0.0/10", "52.224.0.0/11", "104.40.0.0/13"],
    "DigitalOcean": ["104.131.0.0/16", "159.65.0.0/16", "167.71.0.0/16",
                     "134.209.0.0/16", "165.227.0.0/16", "138.68.0.0/16"],
    "Hetzner": ["5.9.0.0/16", "78.46.0.0/15", "88.99.0.0/16", "116.202.0.0/16",
                "159.69.0.0/16", "168.119.0.0/16"],
    "OVH": ["51.68.0.0/14", "51.75.0.0/16", "137.74.0.0/16", "145.239.0.0/16",
            "51.178.0.0/16"],
    "Linode": ["45.33.0.0/17", "139.162.0.0/16", "172.104.0.0/15",
               "192.46.208.0/20"],
    "Vultr": ["45.32.0.0/16", "108.61.0.0/16", "149.28.0.0/16"],
}

# CDN/WAF ranges published as a plain newline-delimited list of CIDRs.
_LIVE_SOURCES: Dict[str, Tuple[str, ...]] = {
    "Cloudflare": ("https://www.cloudflare.com/ips-v4",
                   "https://www.cloudflare.com/ips-v6"),
}

# Providers that publish JSON instead. Each entry maps a URL to the top-level
# keys holding CIDR lists, so one code path covers both shapes and the refresh
# is not Cloudflare-only (which is what the docstring has always claimed).
_LIVE_JSON_SOURCES: Dict[str, Tuple[Tuple[str, Tuple[str, ...]], ...]] = {
    "Fastly": ((
        "https://api.fastly.com/public-ip-list",
        ("addresses", "ipv6_addresses"),
    ),),
    "CloudFront": ((
        "https://d7uri8nf7uskq.cloudfront.net/tools/list-cloudfront-ips",
        ("CLOUDFRONT_GLOBAL_IP_LIST", "CLOUDFRONT_REGIONAL_EDGE_IP_LIST"),
    ),),
}


def _compile(ranges: Dict[str, List[str]]):
    out: List[Tuple[str, Any]] = []
    for provider, cidrs in ranges.items():
        for cidr in cidrs:
            try:
                out.append((provider, ipaddress.ip_network(cidr, strict=False)))
            except ValueError:
                continue
    return out


_CDN_NETS = _compile(CDN_RANGES)
_CLOUD_NETS = _compile(CLOUD_RANGES)


def refresh_ranges(session=None, timeout: int = 10) -> Dict[str, int]:
    """Refresh the bundled edge ranges from the providers' published lists.

    Optional and opt-in: the bundled snapshot already works offline. Returns
    {provider: cidr_count} for whatever refreshed successfully; a provider that
    fails simply keeps its bundled ranges."""
    global _CDN_NETS
    updated: Dict[str, int] = {}
    if session is None:
        try:
            from .core import build_session
            session = build_session(timeout=timeout)
        except Exception:  # noqa: BLE001
            return updated
    for provider, urls in _LIVE_SOURCES.items():
        cidrs: List[str] = []
        for url in urls:
            try:
                resp = session.get(url, timeout=timeout)
                if getattr(resp, "status_code", 0) != 200:
                    continue
                cidrs += [ln.strip() for ln in (resp.text or "").splitlines()
                          if ln.strip() and "/" in ln]
            except Exception:  # noqa: BLE001
                continue
        # only replace when the fetch looks sane, never on a partial/empty read
        if len(cidrs) >= 5:
            CDN_RANGES[provider] = cidrs
            updated[provider] = len(cidrs)

    for provider, sources in _LIVE_JSON_SOURCES.items():
        cidrs = []
        for url, keys in sources:
            try:
                resp = session.get(url, timeout=timeout)
                if getattr(resp, "status_code", 0) != 200:
                    continue
                doc = resp.json() or {}
                for key in keys:
                    for item in doc.get(key, []) or []:
                        item = str(item).strip()
                        if item and "/" in item:
                            cidrs.append(item)
            except Exception:  # noqa: BLE001
                continue
        if len(cidrs) >= 5:
            CDN_RANGES[provider] = cidrs
            updated[provider] = len(cidrs)

    _CDN_NETS = _compile(CDN_RANGES)
    return updated


# --------------------------------------------------------------------------- #
#  Classification
# --------------------------------------------------------------------------- #
def classify_ip(ip: str) -> Dict[str, Optional[str]]:
    """Classify one address. Returns {ip, kind, provider}."""
    raw = str(ip).strip()
    try:
        addr = ipaddress.ip_address(raw)
    except ValueError:
        return {"ip": raw, "kind": "invalid", "provider": None}
    if (addr.is_private or addr.is_loopback or addr.is_link_local
            or addr.is_reserved or addr.is_multicast or addr.is_unspecified):
        return {"ip": raw, "kind": "private", "provider": None}
    for provider, net in _CDN_NETS:
        if addr in net:
            return {"ip": raw, "kind": "cdn", "provider": provider}
    for provider, net in _CLOUD_NETS:
        if addr in net:
            return {"ip": raw, "kind": "cloud", "provider": provider}
    return {"ip": raw, "kind": "origin", "provider": None}


def is_cdn(ip: str) -> bool:
    """True when the address belongs to a published CDN/WAF edge range."""
    return classify_ip(ip)["kind"] == "cdn"


def classify_ips(ips: Iterable[str]) -> List[Dict[str, Optional[str]]]:
    seen, out = set(), []
    for ip in ips or []:
        key = str(ip).strip()
        if key and key not in seen:
            seen.add(key)
            out.append(classify_ip(key))
    return out


def filter_ips(ips: Iterable[str], keep: str = "origin") -> List[str]:
    """Return only the addresses of a given kind — the actual 'filter'.

    ``keep='origin'`` is the useful default: everything that is *not* a known
    CDN edge or a private address, i.e. the addresses worth investigating.
    """
    return [c["ip"] for c in classify_ips(ips) if c["kind"] == keep]


# --------------------------------------------------------------------------- #
#  Scan-level report
# --------------------------------------------------------------------------- #
_IPV4 = r"\b(?:\d{1,3}\.){3}\d{1,3}\b"


def harvest_ips(results) -> List[str]:
    """Every IPv4 address any module reported, de-duplicated."""
    import re
    from .reporting import _flatten
    found: List[str] = []
    seen = set()
    for r in results or []:
        flat: Dict[str, str] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        for val in flat.values():
            for ip in re.findall(_IPV4, str(val)):
                if ip not in seen:
                    seen.add(ip)
                    found.append(ip)
    return found


def ip_report(results, target: str = "") -> Dict[str, Any]:
    """Classify every IP a scan produced, and say which ones actually matter.

    The headline is ``origin_candidates``: addresses that are neither published
    CDN/WAF edge nor private space, i.e. the ones plausibly belonging to the
    target rather than to a shared edge network.
    """
    ips = harvest_ips(results)
    classified = classify_ips(ips)
    by_kind: Dict[str, List[str]] = {}
    by_provider: Dict[str, List[str]] = {}
    for c in classified:
        by_kind.setdefault(c["kind"], []).append(c["ip"])
        if c["provider"]:
            by_provider.setdefault(c["provider"], []).append(c["ip"])
    cdn = by_kind.get("cdn", [])
    origins = by_kind.get("origin", [])
    fronted = bool(cdn) and not origins
    return {
        "target": target,
        "total_ips": len(classified),
        "origin_candidates": sorted(origins),
        "origin_count": len(origins),
        "cdn_edge_ips": sorted(cdn),
        "cdn_count": len(cdn),
        "cdn_providers": {p: sorted(v) for p, v in sorted(by_provider.items())},
        "cloud_ips": sorted(by_kind.get("cloud", [])),
        "private_ips": sorted(by_kind.get("private", [])),
        "behind_cdn": bool(cdn),
        "fully_fronted": fronted,
        "classified": classified,
        "note": ("every address resolves inside a CDN/WAF edge range — the real "
                 "origin was not exposed by this scan"
                 if fronted else
                 "origin_candidates are addresses outside every known CDN/WAF "
                 "edge range and outside private space; they are the ones worth "
                 "investigating. Ranges are a bundled snapshot — call "
                 "refresh_ranges() to update them from the providers."),
    }
