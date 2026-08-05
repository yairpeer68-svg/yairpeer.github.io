"""Free / keyless OSINT sources — breadth over depth.

The goal here is *many independent, no-API-key sources for every kind of data*,
so the correlator's source-corroboration confidence has lots to work with and
the deep-dive has many places to pivot. Nothing here needs a paid account or an
API key (no Shodan/Censys key required).

  Subdomains / CT   : certspotter, bufferover, hackertarget, subdomaincenter
  Passive DNS / rep : otxrep (AlienVault OTX), robtex
  ASN / netblock    : bgpview, ipapi, cymruasn
  Malware / abuse   : threatfox, urlhaus (abuse.ch)
  Breach / stealer  : hudsonrock (infostealer infections), emailrep
  Public code       : grepapp, searchcode

Every module goes through ``ctx.session``, wraps all I/O in try/except, parses
defensively (empty/garbage in → empty result out) and filters discovered hosts
to the target domain. Passive/read-only. FOR AUTHORISED SECURITY TESTING ONLY.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Set

from ..core import Context, Module, Result, clean_host, is_ip, register

_HOSTRE = re.compile(r"[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?(?:\.[a-z0-9\-]{1,63})+")
_IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")


def _get(ctx, url, **kw):
    try:
        return ctx.session.get(url, timeout=getattr(ctx, "timeout", 15), **kw)
    except Exception:  # noqa: BLE001
        return None


def _post(ctx, url, **kw):
    try:
        return ctx.session.post(url, timeout=getattr(ctx, "timeout", 15), **kw)
    except Exception:  # noqa: BLE001
        return None


def _json(resp):
    if resp is None or getattr(resp, "status_code", 0) != 200:
        return None
    try:
        return resp.json()
    except Exception:  # noqa: BLE001
        return None


def _text(resp):
    if resp is None or getattr(resp, "status_code", 0) != 200:
        return ""
    try:
        return resp.text or ""
    except Exception:  # noqa: BLE001
        return ""


def _subs_of(hosts, domain: str) -> List[str]:
    out: Set[str] = set()
    d = domain.lower().lstrip(".")
    for h in hosts:
        h = str(h).lower().strip().lstrip("*.").rstrip(".")
        if h and (h == d or h.endswith("." + d)) and _HOSTRE.fullmatch(h):
            out.add(h)
    return sorted(out)


# --------------------------------------------------------------------------- #
#  Subdomain / certificate-transparency sources
# --------------------------------------------------------------------------- #
@register
class CertSpotter(Module):
    id = "certspotter"
    name = "CertSpotter CT log (subdomains)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = (f"https://api.certspotter.com/v1/issuances?domain={host}"
               "&include_subdomains=true&expand=dns_names")
        data = _json(_get(ctx, url))
        names: Set[str] = set()
        if isinstance(data, list):
            for cert in data:
                for n in (cert or {}).get("dns_names", []) or []:
                    names.add(n)
        subs = _subs_of(names, host)
        return self.ok(host, {"subdomains": subs, "count": len(subs),
                              "source": "certspotter"})


@register
class BufferOver(Module):
    id = "bufferover"
    name = "BufferOver DNS dataset (subdomains/IPs)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = _json(_get(ctx, f"https://dns.bufferover.run/dns?q=.{host}"))
        hosts: Set[str] = set()
        ips: Set[str] = set()
        for key in ("FDNS_A", "RDNS", "FDNS_AAAA"):
            for row in (data or {}).get(key, []) or []:
                parts = str(row).split(",")
                for p in parts:
                    if _IPV4.fullmatch(p.strip()):
                        ips.add(p.strip())
                    else:
                        hosts.add(p.strip())
        subs = _subs_of(hosts, host)
        return self.ok(host, {"subdomains": subs, "ips": sorted(ips)[:60],
                              "count": len(subs), "source": "bufferover"})


@register
class HackerTarget(Module):
    id = "hackertarget"
    name = "HackerTarget hostsearch (subdomains)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        txt = _text(_get(ctx, f"https://api.hackertarget.com/hostsearch/?q={host}"))
        hosts: Set[str] = set()
        ips: Set[str] = set()
        if txt and "API count exceeded" not in txt and "error" not in txt.lower():
            for line in txt.splitlines():
                if "," in line:
                    h, _, ip = line.partition(",")
                    hosts.add(h.strip())
                    if _IPV4.fullmatch(ip.strip()):
                        ips.add(ip.strip())
        subs = _subs_of(hosts, host)
        return self.ok(host, {"subdomains": subs, "ips": sorted(ips)[:60],
                              "count": len(subs), "source": "hackertarget"})


@register
class SubdomainCenter(Module):
    id = "subdomaincenter"
    name = "Subdomain Center (subdomains)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = _json(_get(ctx, f"https://api.subdomain.center/?domain={host}"))
        subs = _subs_of(data if isinstance(data, list) else [], host)
        return self.ok(host, {"subdomains": subs, "count": len(subs),
                              "source": "subdomain.center"})


# --------------------------------------------------------------------------- #
#  Passive DNS / reputation
# --------------------------------------------------------------------------- #
@register
class OtxRep(Module):
    id = "otxrep"
    name = "AlienVault OTX (passive DNS + URLs)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = f"https://otx.alienvault.com/api/v1/indicators/domain/{host}"
        pdns = _json(_get(ctx, base + "/passive_dns")) or {}
        urls = _json(_get(ctx, base + "/url_list?limit=100")) or {}
        hosts: Set[str] = set()
        ips: Set[str] = set()
        for rec in pdns.get("passive_dns", []) or []:
            if rec.get("hostname"):
                hosts.add(rec["hostname"])
            if rec.get("address") and _IPV4.fullmatch(str(rec["address"])):
                ips.add(rec["address"])
        for u in urls.get("url_list", []) or []:
            if u.get("hostname"):
                hosts.add(u["hostname"])
        subs = _subs_of(hosts, host)
        return self.ok(host, {"subdomains": subs, "ips": sorted(ips)[:60],
                              "url_records": len(urls.get("url_list", []) or []),
                              "source": "alienvault-otx"})


@register
class Robtex(Module):
    id = "robtex"
    name = "Robtex passive DNS (IP → domains)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "robtex expects an IP"})
        data = _json(_get(ctx, f"https://freeapi.robtex.com/ipquery/{ip}")) or {}
        domains: Set[str] = set()
        for key in ("pas", "act", "pah", "ac"):
            for rec in data.get(key, []) or []:
                if isinstance(rec, dict) and rec.get("o"):
                    domains.add(str(rec["o"]).lower())
        return self.ok(ip, {"related_domains": sorted(domains)[:60],
                            "count": len(domains), "source": "robtex"})


# --------------------------------------------------------------------------- #
#  ASN / netblock enrichment (IP kind — used when pivoting on discovered IPs)
# --------------------------------------------------------------------------- #
@register
class BgpView(Module):
    id = "bgpview"
    name = "BGPView ASN / prefix (IP)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "bgpview expects an IP"})
        data = (_json(_get(ctx, f"https://api.bgpview.io/ip/{ip}")) or {}).get("data", {})
        prefixes = []
        for p in (data or {}).get("prefixes", []) or []:
            asn = (p.get("asn") or {})
            prefixes.append({"prefix": p.get("prefix"),
                             "asn": asn.get("asn"),
                             "name": asn.get("name") or asn.get("description")})
        return self.ok(ip, {"prefixes": prefixes[:10],
                            "ptr": (data or {}).get("ptr_record"),
                            "source": "bgpview"})


@register
class IpApi(Module):
    id = "ipapi"
    name = "ip-api.com geo / org (IP)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "ip-api expects an IP"})
        d = _json(_get(ctx, f"http://ip-api.com/json/{ip}"
                            "?fields=status,country,city,isp,org,as,reverse")) or {}
        if d.get("status") != "success":
            return self.ok(ip, {"note": "no geo data", "source": "ip-api"})
        return self.ok(ip, {"country": d.get("country"), "city": d.get("city"),
                            "isp": d.get("isp"), "org": d.get("org"),
                            "asn": d.get("as"), "ptr": d.get("reverse"),
                            "source": "ip-api"})


@register
class CymruAsn(Module):
    id = "cymruasn"
    name = "Team Cymru IP → ASN (DNS, keyless)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip) or ":" in ip:
            return self.ok(ip, {"note": "cymru lookup expects an IPv4"})
        rev = ".".join(reversed(ip.split(".")))
        try:
            import dns.resolver
            ans = dns.resolver.resolve(f"{rev}.origin.asn.cymru.com", "TXT")
            txt = str(ans[0]).strip('"')
            parts = [p.strip() for p in txt.split("|")]
            asn = parts[0] if parts else ""
            name = ""
            if asn:
                try:
                    a2 = dns.resolver.resolve(f"AS{asn}.asn.cymru.com", "TXT")
                    name = str(a2[0]).strip('"').split("|")[-1].strip()
                except Exception:  # noqa: BLE001
                    pass
            return self.ok(ip, {"asn": asn, "prefix": parts[1] if len(parts) > 1 else "",
                                "country": parts[2] if len(parts) > 2 else "",
                                "asn_name": name, "source": "team-cymru"})
        except Exception:  # noqa: BLE001
            return self.ok(ip, {"note": "cymru lookup failed", "source": "team-cymru"})


# --------------------------------------------------------------------------- #
#  Malware / abuse (abuse.ch — free, keyless)
# --------------------------------------------------------------------------- #
@register
class ThreatFox(Module):
    id = "threatfox"
    name = "ThreatFox IOC lookup (abuse.ch)"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        term = str(target).strip()
        data = _json(_post(ctx, "https://threatfox-api.abuse.ch/api/v1/",
                           json={"query": "search_ioc", "search_term": term})) or {}
        rows = data.get("data") if isinstance(data.get("data"), list) else []
        iocs = [{"ioc": r.get("ioc"), "threat": r.get("threat_type"),
                 "malware": r.get("malware_printable")} for r in (rows or [])[:20]]
        return self.ok(term, {"iocs": iocs, "count": len(iocs),
                              "listed": bool(iocs), "source": "threatfox"})


@register
class UrlHaus(Module):
    id = "urlhaus"
    name = "URLhaus malware URLs on host (abuse.ch)"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = _json(_post(ctx, "https://urlhaus-api.abuse.ch/v1/host/",
                           data={"host": host})) or {}
        urls = data.get("urls") if isinstance(data.get("urls"), list) else []
        listed = data.get("query_status") == "ok" and bool(urls)
        return self.ok(host, {"malware_urls": len(urls or []),
                              "listed": listed,
                              "sample": [u.get("url") for u in (urls or [])[:5]],
                              "source": "urlhaus"})


# --------------------------------------------------------------------------- #
#  Breach / infostealer / e-mail reputation
# --------------------------------------------------------------------------- #
@register
class HudsonRock(Module):
    id = "hudsonrock"
    name = "Hudson Rock infostealer exposure (free)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ("https://cavalier.hudsonrock.com/api/json/v2/osint-tools/"
               f"search-by-domain?domain={host}")
        d = _json(_get(ctx, url)) or {}
        total = d.get("total") or 0
        data: Dict[str, Any] = {
            "employees_infected": (d.get("data", {}) or {}).get("employees", total)
            if isinstance(d.get("data"), dict) else total,
            "total": total,
            "source": "hudsonrock",
        }
        if total:
            data["severity"] = "high"
            data["note"] = ("machines with this domain in stealer logs — "
                            "credentials may be compromised.")
        return self.ok(host, data)


@register
class EmailRep(Module):
    id = "emailrep"
    name = "EmailRep.io reputation / breach (email)"
    category = "OSINT"
    target_kind = "email"

    def run(self, target, ctx):
        email = str(target).strip().lower()
        if "@" not in email:
            return self.ok(email, {"note": "emailrep expects an e-mail address"})
        d = _json(_get(ctx, f"https://emailrep.io/{email}",
                       headers={"User-Agent": "GhostEye-OSINT"})) or {}
        det = d.get("details", {}) or {}
        return self.ok(email, {
            "reputation": d.get("reputation"),
            "suspicious": d.get("suspicious"),
            "credentials_leaked": det.get("credentials_leaked"),
            "data_breach": det.get("data_breach"),
            "profiles": det.get("profiles", [])[:10],
            "source": "emailrep",
        })


# --------------------------------------------------------------------------- #
#  Public code mentions
# --------------------------------------------------------------------------- #
@register
class GrepApp(Module):
    id = "grepapp"
    name = "grep.app public-code mentions"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, f"https://grep.app/api/search?q={host}")) or {}
        hits = ((d.get("hits") or {}).get("hits")) or []
        repos: Set[str] = set()
        for h in hits[:40]:
            repo = ((h.get("repo") or {}).get("raw"))
            if repo:
                repos.add(repo)
        total = ((d.get("hits") or {}).get("total")) or 0
        return self.ok(host, {"code_hits": total, "repos": sorted(repos)[:25],
                              "source": "grep.app"})


@register
class SearchCode(Module):
    id = "searchcode"
    name = "searchcode.com public-code search"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, f"https://searchcode.com/api/codesearch_I/?q={host}&per_page=20")) or {}
        results = d.get("results") if isinstance(d.get("results"), list) else []
        repos: Set[str] = set()
        for r in (results or [])[:30]:
            if r.get("repo"):
                repos.add(str(r["repo"]))
        return self.ok(host, {"code_results": len(results or []),
                              "repos": sorted(repos)[:25], "source": "searchcode"})


# =========================================================================== #
#  Wave 2 — more free / keyless sources (co-hosting, ASN, C2, dumps, identity)
# =========================================================================== #
@register
class ReverseIp(Module):
    id = "reverseip"
    name = "Reverse IP — co-hosted domains (HackerTarget)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "reverse-IP expects an IP"})
        txt = _text(_get(ctx, f"https://api.hackertarget.com/reverseiplookup/?q={ip}"))
        domains: Set[str] = set()
        if txt and "error" not in txt.lower() and "API count" not in txt:
            for line in txt.splitlines():
                d = line.strip().lower()
                if d and _HOSTRE.fullmatch(d):
                    domains.add(d)
        return self.ok(ip, {"related_domains": sorted(domains)[:80],
                            "count": len(domains), "source": "reverseip"})


@register
class OtxIp(Module):
    id = "otxip"
    name = "AlienVault OTX (IP passive DNS + reputation)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "OTX IP lookup expects an IP"})
        base = f"https://otx.alienvault.com/api/v1/indicators/IPv4/{ip}"
        pdns = _json(_get(ctx, base + "/passive_dns")) or {}
        gen = _json(_get(ctx, base + "/general")) or {}
        domains: Set[str] = set()
        for rec in pdns.get("passive_dns", []) or []:
            if rec.get("hostname"):
                domains.add(str(rec["hostname"]).lower())
        pulses = ((gen.get("pulse_info") or {}).get("count")) or 0
        return self.ok(ip, {"related_domains": sorted(domains)[:60],
                            "threat_pulses": pulses,
                            "flagged": pulses > 0, "source": "alienvault-otx"})


@register
class IpToAsn(Module):
    id = "iptoasn"
    name = "iptoasn.com ASN / org (IP)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "iptoasn expects an IP"})
        d = _json(_get(ctx, f"https://api.iptoasn.com/v1/as/ip/{ip}")) or {}
        return self.ok(ip, {"asn": d.get("as_number"),
                            "asn_org": d.get("as_description"),
                            "country": d.get("as_country_code"),
                            "announced": d.get("announced"),
                            "source": "iptoasn"})


@register
class Feodo(Module):
    id = "feodo"
    name = "Feodo Tracker botnet C2 (abuse.ch)"
    category = "Threat Intel"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "feodo expects an IP"})
        d = _json(_get(ctx, "https://feodotracker.abuse.ch/downloads/ipblocklist.json"))
        listed = False
        info: Dict[str, Any] = {}
        if isinstance(d, list):
            for row in d:
                if isinstance(row, dict) and row.get("ip_address") == ip:
                    listed = True
                    info = {"malware": row.get("malware"),
                            "first_seen": row.get("first_seen"),
                            "status": row.get("status")}
                    break
        data = {"c2_listed": listed, "source": "feodotracker"}
        if listed:
            data.update(info)
            data["severity"] = "critical"
            data["note"] = "IP is a known botnet command-and-control server."
        return self.ok(ip, data)


@register
class SpamhausDbl(Module):
    id = "spamhausdbl"
    name = "Spamhaus DBL domain block-list (DNS)"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        listed = False
        code = ""
        try:
            import dns.resolver
            ans = dns.resolver.resolve(f"{host}.dbl.spamhaus.org", "A")
            code = str(ans[0])
            listed = code.startswith("127.0.1.")
        except Exception:  # noqa: BLE001
            listed = False
        data = {"dbl_listed": listed, "source": "spamhaus-dbl"}
        if listed:
            data["return_code"] = code
            data["severity"] = "high"
            data["note"] = "domain is on the Spamhaus Domain Block List."
        return self.ok(host, data)


@register
class Psbdmp(Module):
    id = "psbdmp"
    name = "Pastebin-dump search (psbdmp.cc)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, f"https://psbdmp.cc/api/search/{host}")) or {}
        rows = d.get("data") if isinstance(d.get("data"), list) else []
        dumps = [{"id": r.get("id"), "date": r.get("time") or r.get("date")}
                 for r in (rows or [])[:20]]
        data = {"paste_dumps": len(dumps), "dumps": dumps, "source": "psbdmp"}
        if dumps:
            data["severity"] = "medium"
            data["note"] = "domain appeared in public paste dumps — review for leaks."
        return self.ok(host, data)


@register
class Keybase(Module):
    id = "keybase"
    name = "Keybase identities by domain"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, "https://keybase.io/_/api/1.0/user/lookup.json"
                           f"?domain={host}")) or {}
        users = []
        for u in d.get("them", []) or []:
            basics = (u or {}).get("basics", {}) or {}
            if basics.get("username"):
                users.append(basics["username"])
        return self.ok(host, {"keybase_users": users[:40], "count": len(users),
                              "source": "keybase"})


@register
class CertDetails(Module):
    id = "certdetails"
    name = "Certificate transparency (certificatedetails.com)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, f"https://certificatedetails.com/api/list/{host}")) or {}
        names: Set[str] = set()
        rows = d if isinstance(d, list) else d.get("certificates", []) if isinstance(d, dict) else []
        for cert in rows or []:
            if isinstance(cert, dict):
                for n in (cert.get("DNSNames") or cert.get("dns_names") or []):
                    names.add(n)
                if cert.get("CommonName"):
                    names.add(cert["CommonName"])
        subs = _subs_of(names, host)
        return self.ok(host, {"subdomains": subs, "count": len(subs),
                              "source": "certificatedetails"})


@register
class SiteDossier(Module):
    id = "sitedossier"
    name = "SiteDossier subdomain listing"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        txt = _text(_get(ctx, f"http://www.sitedossier.com/parentdomain/{host}"))
        names = set(m.lower() for m in _HOSTRE.findall(txt or ""))
        subs = _subs_of(names, host)
        return self.ok(host, {"subdomains": subs, "count": len(subs),
                              "source": "sitedossier"})


# =========================================================================== #
#  Wave 3 — free favicon-hash pivot (Shodan-compatible, but NO Shodan needed)
# =========================================================================== #
import base64 as _b64
import hashlib as _hashlib


@register
class FaviconMmh3(Module):
    id = "favicmmh3"
    name = "Favicon hash pivot (mmh3, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        from urllib.parse import urljoin
        base = f"https://{host}"
        content = None
        for path in ("/favicon.ico", "/favicon.png"):
            resp = _get(ctx, urljoin(base, path))
            if resp is not None and getattr(resp, "status_code", 0) == 200:
                try:
                    content = resp.content
                except Exception:  # noqa: BLE001
                    content = None
                if content:
                    break
        if not content:
            return self.ok(host, {"note": "no favicon found", "source": "favicon"})
        # Shodan-compatible favicon hash: mmh3.hash(base64.encodebytes(icon))
        mmh3_hash = None
        try:
            import mmh3  # optional
            b64 = _b64.encodebytes(content)
            mmh3_hash = mmh3.hash(b64)
        except Exception:  # noqa: BLE001 - mmh3 optional
            mmh3_hash = None
        md5 = _hashlib.md5(content).hexdigest()
        data: Dict[str, Any] = {
            "favicon_md5": md5,
            "favicon_bytes": len(content),
            "source": "favicon",
        }
        if mmh3_hash is not None:
            data["favicon_mmh3"] = mmh3_hash
            # FREE search leads — no paid API. Same hash elsewhere = same app/org.
            data["pivot_leads"] = {
                "fofa": f'icon_hash="{mmh3_hash}"',
                "zoomeye": f"iconhash:\"{mmh3_hash}\"",
                "note": "search this hash on FOFA/ZoomEye (free web UI) to find "
                        "other hosts serving the identical favicon — same "
                        "application or organisation.",
            }
        else:
            data["note"] = ("install mmh3 for the Shodan-compatible hash; md5 is "
                            "still usable to correlate identical favicons.")
        return self.ok(host, data)


# =========================================================================== #
#  Wave 4 — subdomains (jldc), phishing reputation, deep Wayback param mining
# =========================================================================== #
@register
class AnubisJldc(Module):
    id = "anubisjldc"
    name = "Anubis subdomain DB (jldc.me, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = _json(_get(ctx, f"https://jldc.me/anubis/subdomains/{host}"))
        subs = _subs_of(data if isinstance(data, list) else [], host)
        return self.ok(host, {"subdomains": subs, "count": len(subs),
                              "source": "anubis-jldc"})


@register
class PhishStats(Module):
    id = "phishstats"
    name = "PhishStats phishing reports (free)"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ("https://phishstats.info:2096/api/phishing?_where=(url,like,~"
               f"{host}~)&_size=50")
        data = _json(_get(ctx, url))
        rows = data if isinstance(data, list) else []
        samples = [{"url": r.get("url"), "ip": r.get("ip"),
                    "score": r.get("score")} for r in rows[:15]]
        out = {"phishing_reports": len(rows), "samples": samples,
               "listed": bool(rows), "source": "phishstats"}
        if rows:
            out["severity"] = "high"
            out["note"] = ("this domain appears in phishing reports — it may be "
                           "abused, impersonated, or hosting phishing.")
        return self.ok(host, out)


@register
class WaybackParams(Module):
    id = "waybackparams"
    name = "Wayback deep mining (endpoints + parameters)"
    category = "OSINT"
    target_kind = "domain"

    _INTERESTING = re.compile(
        r"\.(?:json|xml|sql|bak|old|env|log|config|conf|yml|yaml|ini|zip|tar|"
        r"gz|db|sqlite|pem|key|p12|pfx|swp)(?:$|\?)", re.I)

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ("http://web.archive.org/cdx/search/cdx?"
               f"url={host}/*&output=json&collapse=urlkey&fl=original&limit=3000")
        data = _json(_get(ctx, url))
        rows = data if isinstance(data, list) else []
        # first row is the header ["original"] when present
        urls = [r[0] for r in rows[1:] if isinstance(r, list) and r] if rows else []
        params: Set[str] = set()
        endpoints: Set[str] = set()
        interesting: Set[str] = set()
        from urllib.parse import urlparse, parse_qs
        for u in urls:
            try:
                p = urlparse(u)
            except Exception:  # noqa: BLE001
                continue
            if p.path and p.path != "/":
                endpoints.add(p.path[:120])
            for k in parse_qs(p.query or ""):
                params.add(k[:60])
            if self._INTERESTING.search(u):
                interesting.add(u[:180])
        data_out: Dict[str, Any] = {
            "archived_urls": len(urls),
            "unique_endpoints": len(endpoints),
            "unique_parameters": len(params),
            "parameters": sorted(params)[:80],
            "endpoints_sample": sorted(endpoints)[:60],
            "source": "wayback-cdx",
        }
        if interesting:
            data_out["interesting_files"] = sorted(interesting)[:30]
            data_out["severity"] = "medium"
            data_out["note"] = ("historical URLs point at sensitive file types — "
                                "check whether any are still reachable.")
        return self.ok(host, data_out)
