"""Advanced DNS surface (v3.8 new features). Detection only."""

from __future__ import annotations

import socket
from typing import List

from ..core import Context, Module, Result, clean_host, register


def _resolver(ctx: Context):
    import dns.resolver
    r = dns.resolver.Resolver()
    r.lifetime = ctx.timeout
    r.timeout = ctx.timeout
    return r


def _ns_ips(host: str, ctx) -> List[str]:
    ips = []
    try:
        res = _resolver(ctx)
        for ns in res.resolve(host, "NS"):
            try:
                for a in res.resolve(str(ns).rstrip("."), "A"):
                    ips.append(str(a))
            except Exception:  # noqa: BLE001
                continue
    except Exception:  # noqa: BLE001
        pass
    return ips


# --------------------------------------------------------------------------- #
#  #15 DNS zone-walking (NSEC / NSEC3)
# --------------------------------------------------------------------------- #
@register
class NsecWalk(Module):
    id, name, category = "nsecwalk", "DNS zone-walking (NSEC/NSEC3)", "DNS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            import dns.message
            import dns.query
            import dns.name
            import dns.rdatatype
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"dnspython required: {e}")
        ns_ips = _ns_ips(host, ctx)
        if not ns_ips:
            return self.fail(host, "could not resolve authoritative nameservers")
        apex = dns.name.from_text(host)
        for ns_ip in ns_ips[:3]:
            names: List[str] = []
            cur = apex
            scheme = "NSEC"
            try:
                for _ in range(600):
                    q = dns.message.make_query(cur, dns.rdatatype.NSEC,
                                               want_dnssec=True)
                    resp = dns.query.udp(q, ns_ip, timeout=ctx.timeout)
                    nsec = None
                    nsec3 = False
                    for rr in list(resp.answer) + list(resp.authority):
                        if rr.rdtype == dns.rdatatype.NSEC:
                            nsec = rr
                            break
                        if rr.rdtype == dns.rdatatype.NSEC3:
                            nsec3 = True
                    if nsec3 and nsec is None:
                        scheme = "NSEC3"
                        break
                    if nsec is None:
                        break
                    nxt = nsec[0].next
                    txt = nxt.to_text().rstrip(".")
                    if nxt == apex or txt in names:
                        break
                    names.append(txt)
                    cur = nxt
            except Exception:  # noqa: BLE001
                continue
            if scheme == "NSEC3":
                return self.ok(host, {
                    "dnssec": "NSEC3 (hashed)",
                    "walkable": False,
                    "note": "zone uses NSEC3 — names are hashed and cannot be walked "
                            "online; only offline hash-cracking would enumerate them"})
            if names:
                return self.ok(host, {
                    "dnssec": "NSEC (walkable!)",
                    "records_enumerated": len(names),
                    "names": sorted(set(names))[:300],
                    "risk": "medium",
                    "note": "NSEC lets anyone enumerate every name in the zone — "
                            "switch to NSEC3 to stop full-zone disclosure"})
        return self.ok(host, {"dnssec": "no NSEC records returned",
                              "walkable": False,
                              "note": "zone is likely unsigned, or the NS did not "
                                      "return NSEC over UDP"})


# --------------------------------------------------------------------------- #
#  #16 NS / MX takeover
# --------------------------------------------------------------------------- #
@register
class NsMxTakeover(Module):
    id, name, category = "nsmxtakeover", "NS / MX record takeover", "DNS"
    target_kind = "domain"

    def _dangling(self, hostname: str, ctx) -> str:
        """Return a reason string if the delegated host looks claimable."""
        hostname = hostname.rstrip(".")
        if not hostname:
            return ""
        try:
            import dns.resolver
            res = _resolver(ctx)
            try:
                res.resolve(hostname, "A")
                return ""                       # resolves fine → not dangling
            except dns.resolver.NoAnswer:
                try:
                    res.resolve(hostname, "AAAA")
                    return ""
                except Exception:  # noqa: BLE001
                    return "no A/AAAA for delegated host"
            except dns.resolver.NXDOMAIN:
                return "delegated host is NXDOMAIN (registerable → takeover)"
            except Exception:  # noqa: BLE001
                return ""
        except Exception:  # noqa: BLE001
            # fall back to the system resolver
            try:
                socket.gethostbyname(hostname)
                return ""
            except socket.gaierror:
                return "delegated host does not resolve (possible takeover)"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            res = _resolver(ctx)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"dnspython required: {e}")
        findings = {"ns": {}, "mx": {}}
        try:
            for ns in res.resolve(host, "NS"):
                name = str(ns).rstrip(".")
                reason = self._dangling(name, ctx)
                findings["ns"][name] = reason or "ok"
        except Exception as e:  # noqa: BLE001
            findings["ns"] = f"lookup failed: {str(e)[:60]}"
        try:
            for mx in res.resolve(host, "MX"):
                name = str(mx.exchange).rstrip(".")
                reason = self._dangling(name, ctx)
                findings["mx"][name] = reason or "ok"
        except Exception as e:  # noqa: BLE001
            findings["mx"] = f"lookup failed: {str(e)[:60]}"
        vulnerable = []
        for kind in ("ns", "mx"):
            if isinstance(findings[kind], dict):
                vulnerable += [f"{kind.upper()} {h}: {v}"
                               for h, v in findings[kind].items() if v != "ok"]
        return self.ok(host, {
            "ns_records": findings["ns"],
            "mx_records": findings["mx"],
            "takeover_candidates": vulnerable or "none",
            "risk": "high" if vulnerable else "low",
            "note": "an NS/MX host that no longer resolves may be re-registerable, "
                    "letting an attacker seize DNS or mail for the domain"})
