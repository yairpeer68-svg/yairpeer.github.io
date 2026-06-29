"""Unified asset inventory (new). Correlates every module's findings - hosts,
IPs, open services, emails, URLs and detected tech - into one deduplicated
attack-surface view. Heuristic: flattens each result and mines it with a small
set of regexes, so it works regardless of each module's exact data shape."""

from __future__ import annotations

import re
from typing import Dict, List

from .core import Result
from .reporting import _flatten

_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
_EMAIL = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")
_HOST = re.compile(
    r"\b(?:[a-zA-Z0-9_](?:[a-zA-Z0-9_\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,24}\b")
_URL = re.compile(r"https?://[^\s'\"<>]+")
_PORT_A = re.compile(r"\b([a-z][a-z0-9\-]{1,20}):(\d{2,5})\b")   # redis:6379
_PORT_B = re.compile(r"\b(\d{2,5})/([a-z][a-z0-9\-]{1,20})\b")   # 6379/redis


def _valid_ip(s: str) -> bool:
    parts = s.split(".")
    return len(parts) == 4 and all(p.isdigit() and 0 <= int(p) <= 255 for p in parts)


def build_inventory(results: List[Result], target: str = "") -> dict:
    ips, hosts, emails, urls, services, tech = (set() for _ in range(6))
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        blob = " ".join(f"{k} {v}" for k, v in flat.items())
        for m in _IPV4.findall(blob):
            if _valid_ip(m) and not m.startswith(("0.", "255.")):
                ips.add(m)
        emails.update(e.lower() for e in _EMAIL.findall(blob))
        for m in _HOST.findall(blob):
            ml = m.lower().rstrip(".")
            if not _valid_ip(ml) and "." in ml and len(ml) < 100:
                hosts.add(ml)
        urls.update(_URL.findall(blob))
        for svc, port in _PORT_A.findall(blob):
            services.add(f"{port}/{svc}")
        for port, svc in _PORT_B.findall(blob):
            services.add(f"{port}/{svc}")
        if any(t in r.module.lower() for t in ("tech", "fingerprint", "library", "lib")):
            for v in flat.values():
                if v and len(v) < 60:
                    tech.add(v)
    # an email's domain is also a host; keep hosts inclusive
    for e in emails:
        dom = e.split("@", 1)[-1]
        if dom:
            hosts.add(dom)
    try:
        ips_sorted = sorted(ips, key=lambda x: tuple(int(p) for p in x.split(".")))
    except Exception:
        ips_sorted = sorted(ips)
    return {
        "target": target or (results[0].target if results else ""),
        "counts": {"hosts": len(hosts), "ips": len(ips), "services": len(services),
                   "emails": len(emails), "urls": len(urls)},
        "hosts": sorted(hosts)[:500],
        "ips": ips_sorted[:500],
        "services": sorted(services)[:200],
        "emails": sorted(emails)[:200],
        "urls": sorted(urls)[:300],
        "technologies": sorted(tech)[:80],
    }


def collect_assets(results: List[Result], target: str = "",
                   scope=None, max_hosts: int = 25) -> dict:
    """Pull the additional hosts/IPs discovered during a scan, so a deep scan can
    fan out to them. Excludes the original target and honours an optional scope."""
    inv = build_inventory(results, target)
    tgt = (target or inv.get("target", "")).lower().rstrip(".")
    hosts = [h for h in inv["hosts"] if h and h != tgt]
    ips = [i for i in inv["ips"] if i != tgt]
    if scope is not None and not getattr(scope, "empty", True):
        hosts = [h for h in hosts if scope.allows(h)[0]]
        ips = [i for i in ips if scope.allows(i)[0]]
    return {"hosts": hosts[:max_hosts], "ips": ips[:max_hosts],
            "host_total": len(hosts), "ip_total": len(ips)}


_CVE_RE = re.compile(r"CVE-\d{4}-\d{4,7}", re.I)
_PORTNUM = re.compile(r"\b(\d{1,5})\b")


def _host_ports(flat: Dict[str, str]):
    ports = set()
    for k, v in flat.items():
        kl = k.lower()
        if "port" in kl:
            for m in _PORTNUM.findall(v):
                n = int(m)
                if 0 < n <= 65535:
                    ports.add(n)
        for port, _svc in _PORT_B.findall(f"{k} {v}"):
            ports.add(int(port))
    return sorted(ports)


def build_host_rollup(results: List[Result], target: str = "") -> dict:
    """Group findings by the host/IP each ran on, summarising ports, tech, CVEs
    and a per-host severity. Pairs with --deep to make a sweep actionable."""
    from .reporting_ext import score_findings  # lazy: avoids an import cycle
    by_host: Dict[str, List[Result]] = {}
    for r in results:
        by_host.setdefault(r.target, []).append(r)
    rollup: Dict[str, dict] = {}
    for host, rs in by_host.items():
        ports, tech, cves = set(), set(), set()
        for r in rs:
            flat: Dict[str, str] = {}
            _flatten("", r.data, flat)
            blob = " ".join(f"{k} {v}" for k, v in flat.items())
            for p in _host_ports(flat):
                ports.add(p)
            cves.update(c.upper() for c in _CVE_RE.findall(blob))
            if any(t in r.module.lower()
                   for t in ("tech", "fingerprint", "library", "lib")):
                for v in flat.values():
                    if v and len(v) < 50:
                        tech.add(v)
        try:
            sev = score_findings(rs).get("risk_level")
        except Exception:
            sev = None
        rollup[host] = {
            "findings": len(rs),
            "ports": sorted(ports),
            "tech": sorted(tech)[:20],
            "cves": sorted(cves)[:50],
            "severity": sev,
        }
    # order: highest severity first, then most findings
    order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, None: 4, "INFO": 4}
    return dict(sorted(rollup.items(),
                       key=lambda kv: (order.get(kv[1]["severity"], 4),
                                       -kv[1]["findings"])))
