"""DNS reconnaissance modules (features #1-#10)."""

from __future__ import annotations

import socket
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List

from ..core import (Console, Context, Module, Result, clean_host, have_binary,
                    is_ip, register, run_cmd)

_RECORD_TYPES = ["A", "AAAA", "MX", "NS", "TXT", "SOA", "CNAME", "CAA", "SRV", "PTR"]


def _resolver(ctx: Context):
    import dns.resolver
    r = dns.resolver.Resolver()
    r.lifetime = ctx.timeout
    r.timeout = ctx.timeout
    return r


@register
class DnsRecords(Module):
    id, name, category = "dns", "DNS records (all types)", "DNS"
    target_kind = "domain"
    # dnspython preferred; transparently falls back to DNS-over-HTTPS (no extra
    # dependency, works when port 53 / resolv.conf is unavailable e.g. on Termux)

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        data: Dict[str, List[str]] = {}
        try:
            import dns.resolver  # noqa: F401
            r = _resolver(ctx)
            for rtype in _RECORD_TYPES:
                try:
                    ans = r.resolve(host, rtype)
                    vals = sorted(str(rr.to_text()) for rr in ans)
                    if vals:
                        data[rtype] = vals
                except Exception:
                    continue
        except Exception:
            pass  # dnspython missing or no resolver config -> DoH fallback below
        if not data:
            try:
                from .doh import doh_query
                for rtype in ["A", "AAAA", "MX", "NS", "TXT", "SOA", "CNAME", "CAA", "SRV"]:
                    try:
                        vals = doh_query(ctx.session, host, rtype, ctx.timeout)
                        if vals:
                            data[rtype] = vals
                    except Exception:
                        continue
                if data:
                    data["_resolver"] = ["DNS-over-HTTPS fallback (system resolver unavailable)"]
            except Exception:
                pass
        if not data:
            return self.fail(host, "no DNS records resolved (system resolver and "
                                   "DoH both failed - check connectivity)")
        return self.ok(host, data)


@register
class ReverseDns(Module):
    id, name, category = "rdns", "Reverse DNS (PTR)", "DNS"
    target_kind = "ip"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        if not is_ip(host):
            try:
                host = socket.gethostbyname(host)
            except OSError as exc:
                return self.fail(target, f"cannot resolve to IP: {exc}")
        try:
            name, aliases, _ = socket.gethostbyaddr(host)
            return self.ok(host, {"ip": host, "ptr": name, "aliases": aliases})
        except OSError as exc:
            return self.fail(host, f"no PTR record: {exc}")


@register
class ZoneTransfer(Module):
    id, name, category = "axfr", "DNS zone transfer (AXFR)", "DNS"
    target_kind = "domain"
    needs = ["dnspython"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            import dns.query
            import dns.resolver
            import dns.zone
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as exc:
            return self.fail(target, str(exc))

        out: Dict[str, List[str]] = {}
        ns_names: List[str] = []
        try:
            ns_names = [str(ns.target).rstrip(".")
                        for ns in dns.resolver.resolve(host, "NS")]
        except Exception:
            try:
                from .doh import doh_query
                ns_names = [v.rstrip(".")
                            for v in doh_query(ctx.session, host, "NS", ctx.timeout)]
            except Exception:
                ns_names = []
        if not ns_names:
            return self.ok(host, {"note": "could not determine nameservers"})

        vulnerable = []
        for ns_name in ns_names:
            try:
                z = dns.zone.from_xfr(dns.query.xfr(ns_name, host, lifetime=ctx.timeout))
                names = sorted(z.nodes.keys())
                out[ns_name] = [f"{n}.{host}" for n in map(str, names)]
                vulnerable.append(ns_name)
            except Exception:
                out[ns_name] = ["refused / not vulnerable"]
        if vulnerable:
            out["VULNERABLE_nameservers"] = vulnerable
        return self.ok(host, out)


@register
class Dnssec(Module):
    id, name, category = "dnssec", "DNSSEC validation", "DNS"
    target_kind = "domain"
    needs = ["dnspython"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as exc:
            return self.fail(target, str(exc))
        data: Dict[str, object] = {}
        for rtype in ("DNSKEY", "DS", "RRSIG"):
            try:
                ans = dns.resolver.resolve(host, rtype)
                data[rtype] = [str(rr.to_text())[:120] for rr in ans]
            except Exception:
                data[rtype] = []
        data["dnssec_enabled"] = bool(data.get("DNSKEY"))
        return self.ok(host, data)


@register
class CaaRecords(Module):
    id, name, category = "caa", "CAA record analysis", "DNS"
    target_kind = "domain"
    needs = ["dnspython"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            ans = dns.resolver.resolve(host, "CAA")
            issuers = [str(rr.to_text()) for rr in ans]
            return self.ok(host, {
                "caa_records": issuers,
                "note": "no CAA = any CA may issue certs for this domain"
                        if not issuers else "issuance restricted to listed CAs",
            })
        except Exception:
            return self.ok(host, {"caa_records": [],
                                  "note": "no CAA record - any CA may issue certs"})


@register
class WildcardDetect(Module):
    id, name, category = "wildcard", "Wildcard DNS detection", "DNS"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        probes = ["thisdoesnotexist-zx91", "random-nope-77231", "ghosteye-wild-test"]
        hits = {}
        for p in probes:
            try:
                ip = socket.gethostbyname(f"{p}.{host}")
                hits[f"{p}.{host}"] = ip
            except OSError:
                continue
        return self.ok(host, {
            "wildcard_detected": len(hits) > 0,
            "resolved_probes": hits,
            "note": "wildcard present - subdomain brute-force results will be noisy"
                    if hits else "no wildcard detected",
        })


@register
class DnsBrute(Module):
    id, name, category = "dnsbrute", "DNS brute-force (wordlist)", "DNS"
    target_kind = "domain"

    _BUILTIN = [
        "www", "mail", "ftp", "webmail", "smtp", "pop", "ns1", "ns2", "dns",
        "admin", "api", "dev", "staging", "test", "vpn", "portal", "remote",
        "blog", "shop", "app", "secure", "m", "mobile", "cdn", "static",
        "git", "gitlab", "jenkins", "jira", "confluence", "db", "sql",
        "backup", "old", "new", "beta", "demo", "cloud", "mx", "imap",
        "intranet", "internal", "owa", "exchange", "autodiscover", "sso",
        "auth", "login", "dashboard", "monitor", "grafana", "kibana",
    ]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        words = list(self._BUILTIN)
        wl = ctx.config.get("wordlist")
        if wl:
            try:
                words = [w.strip() for w in open(wl, encoding="utf-8")
                         if w.strip() and not w.startswith("#")]
            except OSError as exc:
                Console.warn(f"wordlist unreadable ({exc}); using builtin list")

        found: Dict[str, str] = {}

        def probe(sub: str):
            fqdn = f"{sub}.{host}"
            try:
                return fqdn, socket.gethostbyname(fqdn)
            except OSError:
                return fqdn, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for fqdn, ip in ex.map(probe, words):
                if ip:
                    found[fqdn] = ip
        return self.ok(host, {"count": len(found), "subdomains": found})


# NOTE: the paid SecurityTrails passive-DNS lookup was removed in v3.3. Free
# passive DNS / historical data is gathered by the 'subs' module (crt.sh,
# AlienVault OTX, wayback, rapiddns, etc.) and the new 'internetdb' module.
