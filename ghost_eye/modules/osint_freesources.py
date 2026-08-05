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


# =========================================================================== #
#  Wave 5 — organisation intelligence (Wikidata) + network owner (PeeringDB)
# =========================================================================== #
from urllib.parse import quote as _quote


@register
class Wikidata(Module):
    id = "wikidata"
    name = "Wikidata organisation intelligence"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # match the org whose official website (P856) contains this domain,
        # then pull parent company (P749), country (P17), industry (P452),
        # inception (P571) and official name (P1448).
        sparql = (
            "SELECT ?item ?itemLabel ?parentLabel ?countryLabel ?industryLabel "
            "?inception WHERE { ?item wdt:P856 ?website. "
            f'FILTER(CONTAINS(LCASE(STR(?website)), "{host.lower()}")). '
            "OPTIONAL { ?item wdt:P749 ?parent. } "
            "OPTIONAL { ?item wdt:P17 ?country. } "
            "OPTIONAL { ?item wdt:P452 ?industry. } "
            "OPTIONAL { ?item wdt:P571 ?inception. } "
            'SERVICE wikibase:label { bd:serviceParam wikibase:language "en". } } '
            "LIMIT 3")
        url = f"https://query.wikidata.org/sparql?format=json&query={_quote(sparql)}"
        d = _json(_get(ctx, url, headers={"User-Agent": "GhostEye-OSINT/1.0",
                                          "Accept": "application/sparql-results+json"}))
        rows = (((d or {}).get("results") or {}).get("bindings")) or []
        if not rows:
            return self.ok(host, {"note": "no Wikidata organisation match",
                                  "source": "wikidata"})
        r0 = rows[0]

        def _v(key):
            return (r0.get(key) or {}).get("value", "")
        entity = _v("item").rsplit("/", 1)[-1] if _v("item") else ""
        return self.ok(host, {
            "organisation": _v("itemLabel"),
            "parent_company": _v("parentLabel"),
            "country": _v("countryLabel"),
            "industry": _v("industryLabel"),
            "inception": _v("inception")[:10],
            "wikidata_id": entity,
            "wikidata_url": f"https://www.wikidata.org/wiki/{entity}" if entity else "",
            "source": "wikidata",
        })


def _ip_to_asn(ip: str) -> str:
    """IPv4 -> origin ASN via Team Cymru DNS (keyless). '' on failure."""
    if ":" in ip:
        return ""
    try:
        import dns.resolver
        rev = ".".join(reversed(ip.split(".")))
        ans = dns.resolver.resolve(f"{rev}.origin.asn.cymru.com", "TXT")
        return str(ans[0]).strip('"').split("|")[0].strip()
    except Exception:  # noqa: BLE001
        return ""


@register
class PeeringDb(Module):
    id = "peeringdb"
    name = "PeeringDB network owner (IP → org)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "peeringdb expects an IP"})
        asn = _ip_to_asn(ip)
        if not asn:
            return self.ok(ip, {"note": "could not resolve IP to ASN",
                                "source": "peeringdb"})
        d = _json(_get(ctx, f"https://www.peeringdb.com/api/net?asn={asn}")) or {}
        rows = d.get("data") if isinstance(d.get("data"), list) else []
        if not rows:
            return self.ok(ip, {"asn": asn, "note": "ASN not in PeeringDB",
                                "source": "peeringdb"})
        n = rows[0]
        return self.ok(ip, {
            "asn": f"AS{asn}",
            "network_name": n.get("name"),
            "aka": n.get("aka"),
            "website": n.get("website"),
            "info_type": n.get("info_type"),
            "notes": (n.get("notes") or "")[:200],
            "source": "peeringdb",
        })


# =========================================================================== #
#  Wave 6 — deep archive mining (CommonCrawl endpoints/params, Wayback secrets)
# =========================================================================== #
import json as _jsonmod
from urllib.parse import parse_qs as _parse_qs, urlparse as _urlparse

_INTERESTING_EXT = re.compile(
    r"\.(?:json|xml|sql|bak|old|env|config|conf|yml|yaml|ini|zip|tar|gz|db|"
    r"sqlite|pem|key|p12|pfx|log|swp)(?:$|\?)", re.I)


def _mine_urls(urls, host):
    """Shared: extract endpoints / params / interesting files from a URL list."""
    params: Set[str] = set()
    endpoints: Set[str] = set()
    interesting: Set[str] = set()
    for u in urls:
        try:
            p = _urlparse(u)
        except Exception:  # noqa: BLE001
            continue
        if p.path and p.path != "/":
            endpoints.add(p.path[:120])
        for k in _parse_qs(p.query or ""):
            params.add(k[:60])
        if _INTERESTING_EXT.search(u):
            interesting.add(u[:180])
    return params, endpoints, interesting


@register
class CommonCrawlMine(Module):
    id = "commoncrawlmine"
    name = "CommonCrawl deep mining (endpoints + params)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # pick the latest CommonCrawl index, then query its CDX for the domain
        idx = _json(_get(ctx, "https://index.commoncrawl.org/collinfo.json"))
        api = None
        if isinstance(idx, list) and idx:
            api = (idx[0] or {}).get("cdx-api")
        if not api:
            return self.ok(host, {"note": "CommonCrawl index unavailable",
                                  "source": "commoncrawl"})
        resp = _get(ctx, f"{api}?url={host}/*&output=json&fl=url&limit=3000")
        txt = _text(resp)
        urls: List[str] = []
        for line in txt.splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                rec = _jsonmod.loads(line)
                if rec.get("url"):
                    urls.append(rec["url"])
            except Exception:  # noqa: BLE001
                continue
        params, endpoints, interesting = _mine_urls(urls, host)
        data: Dict[str, Any] = {
            "indexed_urls": len(urls),
            "unique_endpoints": len(endpoints),
            "unique_parameters": len(params),
            "parameters": sorted(params)[:80],
            "endpoints_sample": sorted(endpoints)[:60],
            "source": "commoncrawl",
        }
        if interesting:
            data["interesting_files"] = sorted(interesting)[:30]
            data["severity"] = "medium"
        return self.ok(host, data)


@register
class WaybackSecrets(Module):
    id = "waybacksecrets"
    name = "Wayback historical secret scan (archived JS/config)"
    category = "OSINT"
    target_kind = "domain"

    _WANT = re.compile(r"\.(?:js|json|env|config|conf|yml|yaml|txt|map)(?:$|\?)", re.I)
    _MAX_FETCH = 8

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        cdx = _json(_get(ctx,
                    "http://web.archive.org/cdx/search/cdx?"
                    f"url={host}/*&output=json&fl=original,timestamp"
                    "&collapse=urlkey&limit=4000"))
        rows = cdx if isinstance(cdx, list) else []
        # rows[0] is the header; pick archived JS/config snapshots
        candidates: List[tuple] = []
        for r in rows[1:] if rows else []:
            if isinstance(r, list) and len(r) >= 2 and self._WANT.search(r[0]):
                candidates.append((r[1], r[0]))       # (timestamp, original)
        # import the high-signal secret patterns from the JS-secret scanner
        try:
            from .newscan_wave import _SECRET_PATTERNS, _redact
        except Exception:  # noqa: BLE001
            return self.ok(host, {"note": "secret patterns unavailable"})

        findings: List[Dict[str, Any]] = []
        seen: Set[tuple] = set()
        scanned = 0
        for ts, original in candidates[:self._MAX_FETCH]:
            snap = f"https://web.archive.org/web/{ts}id_/{original}"
            body = _text(_get(ctx, snap))
            if not body:
                continue
            scanned += 1
            for kind, rx in _SECRET_PATTERNS.items():
                for m in rx.findall(body)[:5]:
                    val = m if isinstance(m, str) else (m[0] if m else "")
                    key = (kind, val)
                    if not val or key in seen:
                        continue
                    seen.add(key)
                    findings.append({"type": kind, "match": _redact(val),
                                     "archived_url": original[:150],
                                     "timestamp": ts})
        data: Dict[str, Any] = {"archived_assets": len(candidates),
                                "scanned": scanned,
                                "secrets_found": len(findings),
                                "source": "wayback"}
        if findings:
            data["findings"] = findings[:30]
            data["severity"] = "high"
            data["note"] = ("secrets found in ARCHIVED content — they may have "
                            "been removed from the live site but are still leaked "
                            "in the Wayback Machine. Rotate them.")
        return self.ok(host, data)


# =========================================================================== #
#  Wave 8 — phone-number OSINT + a second free IP org source (corroboration)
# =========================================================================== #
_PHONE_RE = re.compile(r"\+?\d[\d\s().\-]{7,17}\d")


@register
class PhoneHarvest(Module):
    id = "phoneharvest"
    name = "Phone-number harvest + parse (keyless)"
    category = "OSINT"
    target_kind = "domain"

    _PAGES = ("", "/contact", "/contact-us", "/about", "/about-us", "/support",
              "/impressum", "/legal")

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        from urllib.parse import urljoin
        base = f"https://{host}"
        raw: Set[str] = set()
        for path in self._PAGES:
            txt = _text(_get(ctx, urljoin(base, path)))
            if not txt:
                continue
            for m in _PHONE_RE.findall(txt):
                digits = re.sub(r"[^\d+]", "", m)
                if 8 <= len(re.sub(r"\D", "", digits)) <= 15:
                    raw.add(digits)
            if len(raw) >= 40:
                break

        numbers: List[Dict[str, Any]] = []
        try:
            import phonenumbers
            from phonenumbers import geocoder, number_type, PhoneNumberType
            type_name = {getattr(PhoneNumberType, n): n.lower()
                         for n in ("MOBILE", "FIXED_LINE", "FIXED_LINE_OR_MOBILE",
                                   "TOLL_FREE", "VOIP") if hasattr(PhoneNumberType, n)}
            for r in sorted(raw):
                try:
                    pn = phonenumbers.parse(r, None)  # needs +country
                    if phonenumbers.is_valid_number(pn):
                        numbers.append({
                            "e164": phonenumbers.format_number(
                                pn, phonenumbers.PhoneNumberFormat.E164),
                            "region": geocoder.description_for_number(pn, "en"),
                            "type": type_name.get(number_type(pn), "unknown")})
                        continue
                except Exception:  # noqa: BLE001
                    pass
                numbers.append({"raw": r})
        except Exception:  # noqa: BLE001 - phonenumbers optional
            numbers = [{"raw": r} for r in sorted(raw)]

        return self.ok(host, {"phone_numbers": numbers[:40],
                              "count": len(raw),
                              "parsed": sum(1 for n in numbers if n.get("e164")),
                              "source": "phone-harvest"})


@register
class IpWhois(Module):
    id = "ipwhois"
    name = "ipwho.is geo / org (IP, keyless)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "ipwho.is expects an IP"})
        d = _json(_get(ctx, f"https://ipwho.is/{ip}")) or {}
        if not d.get("success", False) and "connection" not in d:
            return self.ok(ip, {"note": "no data", "source": "ipwho.is"})
        conn = d.get("connection", {}) or {}
        return self.ok(ip, {"country": d.get("country"), "city": d.get("city"),
                            "org": conn.get("org"), "isp": conn.get("isp"),
                            "asn": conn.get("asn"), "domain": conn.get("domain"),
                            "source": "ipwho.is"})


# =========================================================================== #
#  Wave 10 — third-party domains (from the page) + multi-DNSBL IP reputation
# =========================================================================== #
_SRC_ATTR = re.compile(
    r"(?:src|href|action|data-src)\s*=\s*['\"]([^'\"]+)['\"]", re.I)
_VENDOR_HINT = {
    "cdn": r"cdn|jsdelivr|unpkg|cloudflare|akamai|fastly|cloudfront|gstatic|jquery|bootstrap",
    "analytics": r"google-analytics|googletagmanager|gtag|segment|hotjar|mixpanel|amplitude|matomo|plausible",
    "advertising": r"doubleclick|adservice|adsystem|adnxs|criteo|taboola|outbrain",
    "payment": r"stripe|paypal|braintree|adyen|checkout|squareup",
    "social": r"facebook|connect\.facebook|twitter|x\.com|linkedin|instagram|youtube",
    "font": r"fonts\.googleapis|fonts\.gstatic|typekit|fontawesome",
    "support": r"zendesk|intercom|drift|freshdesk|livechat|tawk",
    "error": r"sentry|bugsnag|rollbar|newrelic|datadog",
}


@register
class ExtDomains(Module):
    id = "extdomains"
    name = "Third-party domains referenced by the site"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        html = _text(_get(ctx, f"https://{host}")) or _text(_get(ctx, f"http://{host}"))
        if not html:
            return self.ok(host, {"note": "could not fetch homepage",
                                  "source": "extdomains"})
        domains: Set[str] = set()
        for ref in _SRC_ATTR.findall(html):
            m = re.match(r"(?:https?:)?//([a-z0-9.\-]+\.[a-z]{2,})", ref, re.I)
            if m:
                d = m.group(1).lower().rstrip(".")
                if d != host and not d.endswith("." + host):
                    domains.add(d)
        # also pull hosts out of a CSP header if the server sent one
        # (best-effort: some responses expose it in a <meta http-equiv>)
        for m in re.finditer(r"content-security-policy[^>]*content=['\"]([^'\"]+)",
                             html, re.I):
            for d in _HOSTRE.findall(m.group(1).lower()):
                if d != host and not d.endswith("." + host):
                    domains.add(d)

        by_type: Dict[str, List[str]] = {}
        for d in domains:
            label = "other"
            for typ, pat in _VENDOR_HINT.items():
                if re.search(pat, d):
                    label = typ
                    break
            by_type.setdefault(label, []).append(d)
        return self.ok(host, {
            "third_party_domains": sorted(domains)[:80],
            "related_domains": sorted(domains)[:60],   # feeds correlator/pivot
            "count": len(domains),
            "by_type": {k: sorted(v)[:20] for k, v in by_type.items()},
            "source": "page-references",
        })


# public DNS block-lists (A-record lookup of the reversed IP under each zone)
_DNSBL_ZONES = {
    "Spamhaus ZEN": "zen.spamhaus.org",
    "SpamCop": "bl.spamcop.net",
    "Barracuda": "b.barracudacentral.org",
    "SORBS": "dnsbl.sorbs.net",
    "s5h": "all.s5h.net",
    "UCEPROTECT-1": "dnsbl-1.uceprotect.net",
}


@register
class DnsBl(Module):
    id = "dnsbl"
    name = "Multi-blocklist IP reputation (DNS, keyless)"
    category = "Threat Intel"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip) or ":" in ip:
            return self.ok(ip, {"note": "DNSBL check expects an IPv4"})
        rev = ".".join(reversed(ip.split(".")))
        listed: List[str] = []
        try:
            import dns.resolver
            resolver = dns.resolver.Resolver()
            resolver.lifetime = min(getattr(ctx, "timeout", 8), 8)
            for name, zone in _DNSBL_ZONES.items():
                try:
                    resolver.resolve(f"{rev}.{zone}", "A")
                    listed.append(name)
                except Exception:  # noqa: BLE001 - NXDOMAIN = not listed
                    continue
        except Exception:  # noqa: BLE001
            return self.ok(ip, {"note": "dnspython unavailable",
                                "source": "dnsbl"})
        data: Dict[str, Any] = {
            "checked": len(_DNSBL_ZONES),
            "listed_on": listed,
            "listed_count": len(listed),
            "source": "dnsbl",
        }
        if listed:
            data["severity"] = "high" if len(listed) >= 2 else "medium"
            data["note"] = (f"IP is on {len(listed)} block-list(s) — poor "
                            "reputation, possibly spam/abuse infrastructure.")
        return self.ok(ip, data)


# =========================================================================== #
#  Wave 11 — more corroborating free sources (spam reputation + IP intel)
# =========================================================================== #
@register
class StopForumSpam(Module):
    id = "stopforumspam"
    name = "StopForumSpam abuse reputation (IP, keyless)"
    category = "Threat Intel"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "stopforumspam expects an IP"})
        d = _json(_get(ctx, f"https://api.stopforumspam.org/api?ip={ip}&json")) or {}
        rec = d.get("ip", {}) or {}
        appears = bool(rec.get("appears"))
        data: Dict[str, Any] = {
            "listed": appears,
            "frequency": rec.get("frequency", 0),
            "last_seen": rec.get("lastseen", ""),
            "source": "stopforumspam",
        }
        if appears:
            data["severity"] = "medium"
            data["note"] = ("IP appears in the StopForumSpam abuse database "
                            f"({rec.get('frequency', 0)} reports).")
        return self.ok(ip, data)


@register
class IpApiNet(Module):
    id = "ipapinet"
    name = "ipapi.co geo / org (IP, keyless)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "ipapi.co expects an IP"})
        d = _json(_get(ctx, f"https://ipapi.co/{ip}/json/")) or {}
        if d.get("error"):
            return self.ok(ip, {"note": "no data", "source": "ipapi.co"})
        return self.ok(ip, {"country": d.get("country_name"), "city": d.get("city"),
                            "org": d.get("org"), "asn": d.get("asn"),
                            "network": d.get("network"), "source": "ipapi.co"})


# =========================================================================== #
#  Wave 12 — more IP-reputation + e-mail breach corroboration
# =========================================================================== #
@register
class BlocklistDe(Module):
    id = "blocklistde"
    name = "blocklist.de attack reputation (IP, keyless)"
    category = "Threat Intel"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "blocklist.de expects an IP"})
        txt = _text(_get(ctx, f"https://api.blocklist.de/api.php?ip={ip}"))
        attacks = reports = 0
        for line in (txt or "").splitlines():
            m = re.match(r"\s*attacks\s*:?\s*(\d+)", line, re.I)
            if m:
                attacks = int(m.group(1))
            m = re.match(r"\s*reports\s*:?\s*(\d+)", line, re.I)
            if m:
                reports = int(m.group(1))
        data: Dict[str, Any] = {"attacks": attacks, "reports": reports,
                                "listed": attacks > 0 or reports > 0,
                                "source": "blocklist.de"}
        if attacks or reports:
            data["severity"] = "high" if attacks >= 5 else "medium"
            data["note"] = (f"IP reported for {attacks} attack(s) / {reports} "
                            "report(s) on blocklist.de.")
        return self.ok(ip, data)


@register
class LeakCheck(Module):
    id = "leakcheck"
    name = "LeakCheck public breach lookup (email, keyless)"
    category = "OSINT"
    target_kind = "email"

    def run(self, target, ctx):
        email = str(target).strip().lower()
        if "@" not in email:
            return self.ok(email, {"note": "leakcheck expects an e-mail address"})
        d = _json(_get(ctx, f"https://leakcheck.io/api/public?check={email}")) or {}
        found = int(d.get("found", 0) or 0)
        sources = []
        for s in d.get("sources", []) or []:
            if isinstance(s, dict) and s.get("name"):
                sources.append({"name": s.get("name"), "date": s.get("date", "")})
        data: Dict[str, Any] = {
            "breached": bool(d.get("success") and found),
            "breach_count": found,
            "fields": d.get("fields", [])[:15],
            "sources": sources[:20],
            "source": "leakcheck",
        }
        if found:
            data["severity"] = "high"
            data["note"] = (f"e-mail found in {found} public breach(es) — "
                            "credentials may be compromised.")
        return self.ok(email, data)


# =========================================================================== #
#  Wave 13 — OTX threat pulses (domain) + ipinfo.io org (IP corroboration)
# =========================================================================== #
@register
class OtxPulse(Module):
    id = "otxpulse"
    name = "AlienVault OTX threat pulses (domain)"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, "https://otx.alienvault.com/api/v1/indicators/domain/"
                            f"{host}/general")) or {}
        pinfo = d.get("pulse_info", {}) or {}
        pulses = []
        for p in (pinfo.get("pulses", []) or [])[:15]:
            if isinstance(p, dict) and p.get("name"):
                pulses.append({"name": p.get("name")[:100],
                               "created": (p.get("created") or "")[:10],
                               "tags": (p.get("tags") or [])[:6]})
        count = int(pinfo.get("count", 0) or 0)
        data: Dict[str, Any] = {"threat_pulses": count, "pulses": pulses,
                                "flagged": count > 0, "source": "alienvault-otx"}
        if count:
            data["severity"] = "medium"
            data["note"] = (f"domain referenced in {count} OTX threat pulse(s) — "
                            "review the associated campaigns.")
        return self.ok(host, data)


@register
class IpInfo(Module):
    id = "ipinfo"
    name = "ipinfo.io org / geo (IP, keyless basic)"
    category = "OSINT"
    target_kind = "ip"

    def run(self, target, ctx):
        ip = str(target).strip()
        if not is_ip(ip):
            return self.ok(ip, {"note": "ipinfo expects an IP"})
        d = _json(_get(ctx, f"https://ipinfo.io/{ip}/json")) or {}
        if d.get("error") or not d:
            return self.ok(ip, {"note": "no data", "source": "ipinfo.io"})
        return self.ok(ip, {"hostname": d.get("hostname"), "city": d.get("city"),
                            "region": d.get("region"), "country": d.get("country"),
                            "org": d.get("org"), "source": "ipinfo.io"})


# =========================================================================== #
#  Wave 14 — MerkleMap CT subdomains + multi-list domain URI reputation
# =========================================================================== #
@register
class MerkleMap(Module):
    id = "merklemap"
    name = "MerkleMap certificate-transparency (subdomains)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, f"https://api.merklemap.com/search?query=*.{host}"))
        names: Set[str] = set()
        rows = []
        if isinstance(d, dict):
            rows = d.get("results") or d.get("data") or []
        elif isinstance(d, list):
            rows = d
        for r in rows or []:
            if isinstance(r, dict):
                for k in ("domain", "hostname", "name", "common_name"):
                    if r.get(k):
                        names.add(str(r[k]))
            elif isinstance(r, str):
                names.add(r)
        subs = _subs_of(names, host)
        return self.ok(host, {"subdomains": subs, "count": len(subs),
                              "source": "merklemap"})


# public domain URI block-lists (query the domain directly under each zone)
_URIBL_ZONES = {
    "SURBL": "multi.surbl.org",
    "URIBL": "black.uribl.com",
    "Spamhaus DBL": "dbl.spamhaus.org",
    "SORBS RHSBL": "rhsbl.sorbs.net",
}


@register
class UriBlock(Module):
    id = "uriblock"
    name = "Multi-list domain URI reputation (DNS, keyless)"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        listed: List[str] = []
        try:
            import dns.resolver
            resolver = dns.resolver.Resolver()
            resolver.lifetime = min(getattr(ctx, "timeout", 8), 8)
            for name, zone in _URIBL_ZONES.items():
                try:
                    ans = resolver.resolve(f"{host}.{zone}", "A")
                    if any(str(a).startswith("127.") for a in ans):
                        listed.append(name)
                except Exception:  # noqa: BLE001 - NXDOMAIN = not listed
                    continue
        except Exception:  # noqa: BLE001
            return self.ok(host, {"note": "dnspython unavailable",
                                  "source": "uriblock"})
        data: Dict[str, Any] = {"checked": len(_URIBL_ZONES),
                                "listed_on": listed,
                                "listed_count": len(listed),
                                "source": "uriblock"}
        if listed:
            data["severity"] = "high" if len(listed) >= 2 else "medium"
            data["note"] = (f"domain is on {len(listed)} URI block-list(s) — "
                            "associated with spam/abuse/malware.")
        return self.ok(host, data)


# =========================================================================== #
#  Wave 15 — published software artifacts by the org (npm + Docker Hub)
# =========================================================================== #
@register
class NpmSearch(Module):
    id = "npmsearch"
    name = "npm registry search (org packages, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, "https://registry.npmjs.org/-/v1/search"
                            f"?text={host}&size=25")) or {}
        pkgs = []
        for obj in d.get("objects", []) or []:
            p = (obj or {}).get("package", {}) or {}
            if not p.get("name"):
                continue
            pkgs.append({"name": p.get("name"),
                         "description": (p.get("description") or "")[:100],
                         "publisher": (p.get("publisher") or {}).get("username", ""),
                         "date": (p.get("date") or "")[:10]})
        return self.ok(host, {"npm_packages": pkgs[:25], "count": len(pkgs),
                              "source": "npm-registry"})


@register
class DockerHub(Module):
    id = "dockerhub"
    name = "Docker Hub public images (org, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # Docker Hub repos are named after the org, not the FQDN — use the
        # second-level label (acme.com -> "acme") as the search term.
        org = host.split(".")[-2] if host.count(".") >= 1 else host
        d = _json(_get(ctx, "https://hub.docker.com/v2/search/repositories/"
                            f"?query={org}&page_size=25")) or {}
        repos = []
        for r in d.get("results", []) or []:
            if r.get("repo_name"):
                repos.append({"repo": r.get("repo_name"),
                              "description": (r.get("short_description") or "")[:100],
                              "stars": r.get("star_count", 0),
                              "official": bool(r.get("is_official"))})
        return self.ok(host, {"query": org, "docker_images": repos[:25],
                              "count": len(repos), "source": "docker-hub"})


# =========================================================================== #
#  Wave 16 — more org package registries (Rust / Ruby / PHP), keyless
# =========================================================================== #
def _org_label(host: str) -> str:
    return host.split(".")[-2] if host.count(".") >= 1 else host


@register
class CratesIo(Module):
    id = "cratesio"
    name = "crates.io Rust packages (org, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, f"https://crates.io/api/v1/crates?q={org}&per_page=20",
                       headers={"User-Agent": "GhostEye-OSINT (ghost-eye)"})) or {}
        crates = []
        for c in d.get("crates", []) or []:
            if c.get("name"):
                crates.append({"name": c.get("name"),
                               "description": (c.get("description") or "")[:100],
                               "downloads": c.get("downloads", 0),
                               "repository": c.get("repository") or ""})
        return self.ok(host, {"query": org, "crates": crates[:20],
                              "count": len(crates), "source": "crates.io"})


@register
class RubyGems(Module):
    id = "rubygems"
    name = "RubyGems packages (org, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, f"https://rubygems.org/api/v1/search.json?query={org}"))
        rows = d if isinstance(d, list) else []
        gems = []
        for g in rows:
            if isinstance(g, dict) and g.get("name"):
                gems.append({"name": g.get("name"),
                             "info": (g.get("info") or "")[:100],
                             "downloads": g.get("downloads", 0),
                             "homepage": g.get("homepage_uri") or g.get("project_uri") or ""})
        return self.ok(host, {"query": org, "gems": gems[:20],
                              "count": len(gems), "source": "rubygems"})


@register
class Packagist(Module):
    id = "packagist"
    name = "Packagist PHP packages (org, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, f"https://packagist.org/search.json?q={org}&per_page=20")) or {}
        pkgs = []
        for p in d.get("results", []) or []:
            if p.get("name"):
                pkgs.append({"name": p.get("name"),
                             "description": (p.get("description") or "")[:100],
                             "downloads": p.get("downloads", 0),
                             "url": p.get("url") or p.get("repository") or ""})
        return self.ok(host, {"query": org, "packages": pkgs[:20],
                              "count": len(pkgs), "source": "packagist"})


# =========================================================================== #
#  Wave 17 — .NET + cloud-native registries + public GitLab projects (keyless)
# =========================================================================== #
@register
class NuGet(Module):
    id = "nuget"
    name = "NuGet .NET packages (org, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, f"https://azuresearch-usnc.nuget.org/query?q={org}&take=20")) or {}
        pkgs = []
        for p in d.get("data", []) or []:
            if p.get("id"):
                pkgs.append({"id": p.get("id"),
                             "description": (p.get("description") or "")[:100],
                             "downloads": p.get("totalDownloads", 0),
                             "authors": p.get("authors")})
        return self.ok(host, {"query": org, "packages": pkgs[:20],
                              "count": len(pkgs), "source": "nuget"})


@register
class ArtifactHub(Module):
    id = "artifacthub"
    name = "Artifact Hub (Helm / cloud-native, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, "https://artifacthub.io/api/v1/packages/search"
                            f"?ts_query_web={org}&limit=20&facets=false")) or {}
        pkgs = []
        for p in d.get("packages", []) or []:
            if p.get("name"):
                repo = p.get("repository", {}) or {}
                pkgs.append({"name": p.get("name"),
                             "kind": repo.get("kind"),
                             "repository": repo.get("name"),
                             "description": (p.get("description") or "")[:100],
                             "stars": p.get("stars", 0)})
        return self.ok(host, {"query": org, "artifacts": pkgs[:20],
                              "count": len(pkgs), "source": "artifacthub"})


@register
class GitLabSearch(Module):
    id = "gitlabsearch"
    name = "GitLab public projects (org, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, "https://gitlab.com/api/v4/projects"
                            f"?search={org}&per_page=20&order_by=star_count"))
        rows = d if isinstance(d, list) else []
        projects = []
        for p in rows:
            if isinstance(p, dict) and p.get("path_with_namespace"):
                projects.append({"path": p.get("path_with_namespace"),
                                 "description": (p.get("description") or "")[:100],
                                 "stars": p.get("star_count", 0),
                                 "url": p.get("web_url") or ""})
        return self.ok(host, {"query": org, "projects": projects[:20],
                              "count": len(projects), "source": "gitlab"})


# =========================================================================== #
#  Wave 18 — public-mention / brand monitoring (Hacker News + Reddit), keyless
# =========================================================================== #
@register
class HackerNews(Module):
    id = "hackernews"
    name = "Hacker News mentions (Algolia, keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, "https://hn.algolia.com/api/v1/search"
                            f"?query={host}&hitsPerPage=25")) or {}
        hits = []
        for h in d.get("hits", []) or []:
            title = h.get("title") or h.get("story_title") or ""
            if not (title or h.get("url")):
                continue
            oid = h.get("objectID", "")
            hits.append({"title": title[:120], "author": h.get("author"),
                         "points": h.get("points", 0),
                         "comments": h.get("num_comments", 0),
                         "url": h.get("url") or "",
                         "hn": f"https://news.ycombinator.com/item?id={oid}" if oid else "",
                         "date": (h.get("created_at") or "")[:10]})
        return self.ok(host, {"mentions": hits[:25], "count": len(hits),
                              "source": "hacker-news"})


@register
class Reddit(Module):
    id = "reddit"
    name = "Reddit mentions (keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, f"https://www.reddit.com/search.json?q={host}&limit=25",
                       headers={"User-Agent": "GhostEye-OSINT/1.0 (research)"})) or {}
        posts = []
        for c in ((d.get("data", {}) or {}).get("children", []) or []):
            p = (c or {}).get("data", {}) or {}
            if not p.get("title"):
                continue
            posts.append({"title": p.get("title", "")[:120],
                          "subreddit": p.get("subreddit"),
                          "score": p.get("score", 0),
                          "comments": p.get("num_comments", 0),
                          "permalink": ("https://reddit.com" + p.get("permalink", ""))
                          if p.get("permalink") else "",
                          "date": p.get("created_utc")})
        return self.ok(host, {"mentions": posts[:25], "count": len(posts),
                              "source": "reddit"})


# =========================================================================== #
#  Wave 19 — news (GDELT) + dev Q&A (StackExchange) + filings (SEC EDGAR)
# =========================================================================== #
@register
class Gdelt(Module):
    id = "gdelt"
    name = "GDELT global news mentions (keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, "https://api.gdeltproject.org/api/v2/doc/doc"
                            f"?query={host}&mode=artlist&format=json&maxrecords=20")) or {}
        arts = []
        for a in d.get("articles", []) or []:
            if a.get("url"):
                arts.append({"title": (a.get("title") or "")[:120],
                             "url": a.get("url"),
                             "domain": a.get("domain"),
                             "country": a.get("sourcecountry"),
                             "date": (a.get("seendate") or "")[:8]})
        return self.ok(host, {"news": arts[:20], "count": len(arts),
                              "source": "gdelt"})


@register
class StackExchange(Module):
    id = "stackexchange"
    name = "Stack Overflow / Exchange mentions (keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        d = _json(_get(ctx, "https://api.stackexchange.com/2.3/search/excerpts"
                            f"?q={host}&site=stackoverflow&pagesize=20"
                            "&order=desc&sort=relevance")) or {}
        items = []
        for it in d.get("items", []) or []:
            if it.get("title"):
                items.append({"title": it.get("title", "")[:120],
                              "tags": (it.get("tags") or [])[:6],
                              "score": it.get("score", 0),
                              "question_id": it.get("question_id"),
                              "answered": it.get("is_answered")})
        return self.ok(host, {"posts": items[:20], "count": len(items),
                              "source": "stackexchange"})


@register
class SecEdgar(Module):
    id = "secedgar"
    name = "SEC EDGAR filings full-text search (keyless)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = _org_label(host)
        d = _json(_get(ctx, f'https://efts.sec.gov/LATEST/search-index?q="{org}"',
                       headers={"User-Agent": "GhostEye OSINT research@example.com"})) or {}
        hits = ((d.get("hits") or {}).get("hits")) or []
        filings = []
        for h in hits[:20]:
            src = (h or {}).get("_source", {}) or {}
            names = src.get("display_names") or []
            filings.append({"company": names[0] if names else "",
                            "form": src.get("root_form") or src.get("file_type"),
                            "date": src.get("file_date"),
                            "id": h.get("_id", "")})
        total = ((d.get("hits") or {}).get("total") or {})
        return self.ok(host, {"filings": filings[:20],
                              "total": total.get("value", len(filings))
                              if isinstance(total, dict) else len(filings),
                              "count": len(filings), "source": "sec-edgar"})
