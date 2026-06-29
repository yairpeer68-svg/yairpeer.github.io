"""Passive intel & reputation modules (new features #57-#63).
Passive = no active scanning of the target; queries third-party datasets."""

from __future__ import annotations

import socket
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, is_ip, register


def _to_ip(host: str) -> str:
    return host if is_ip(host) else socket.gethostbyname(host)


@register
class InternetDb(Module):
    id, name, category = "internetdb", "Shodan InternetDB (free)", "Passive Intel"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = _to_ip(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(f"https://internetdb.shodan.io/{ip}", timeout=ctx.timeout)
            if r.status_code == 404:
                return self.ok(ip, {"note": "no InternetDB data for this IP"})
            if r.status_code != 200:
                return self.fail(ip, f"InternetDB HTTP {r.status_code}")
            j = r.json()
            return self.ok(ip, {"ports": j.get("ports"), "hostnames": j.get("hostnames"),
                                "cpes": j.get("cpes"), "tags": j.get("tags"),
                                "vulns": j.get("vulns")})
        except Exception as exc:
            return self.fail(ip, f"InternetDB failed: {exc}")


@register
class TorExit(Module):
    id, name, category = "torexit", "Tor exit-node check", "Passive Intel"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = _to_ip(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get("https://check.torproject.org/torbulkexitlist",
                                timeout=ctx.timeout + 10)
            exits = set(r.text.split()) if r.status_code == 200 else set()
            return self.ok(ip, {"is_tor_exit": ip in exits,
                                "total_known_exits": len(exits)})
        except Exception as exc:
            return self.fail(ip, f"failed: {exc}")


@register
class ProxyHosting(Module):
    id, name, category = "proxytype", "VPN/proxy/hosting detection", "Passive Intel"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = _to_ip(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            j = ctx.session.get(
                f"http://ip-api.com/json/{ip}?fields=proxy,hosting,mobile,org,isp,as",
                timeout=ctx.timeout).json()
            return self.ok(ip, {"proxy_or_vpn": j.get("proxy"),
                                "hosting_datacenter": j.get("hosting"),
                                "mobile": j.get("mobile"),
                                "org": j.get("org"), "isp": j.get("isp")})
        except Exception as exc:
            return self.fail(ip, f"failed: {exc}")


@register
class ThreatFeed(Module):
    id, name, category = "threatfeed", "Threat-feed cross-check", "Passive Intel"
    target_kind = "host"

    _FEEDS = {
        "URLhaus (abuse.ch)": "https://urlhaus.abuse.ch/downloads/text_online/",
        "Feodo C2 (abuse.ch)": "https://feodotracker.abuse.ch/downloads/ipblocklist.txt",
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            ip = _to_ip(host)
        except OSError:
            ip = None
        hits = {}
        for name, url in self._FEEDS.items():
            try:
                txt = ctx.session.get(url, timeout=ctx.timeout + 10).text
                if host in txt or (ip and ip in txt):
                    hits[name] = "LISTED"
            except Exception:
                hits[name] = "feed unreachable"
        return self.ok(host, {"feed_hits": {k: v for k, v in hits.items() if v == "LISTED"}
                              or "not found on checked feeds"})


@register
class GeoEnrich(Module):
    id, name, category = "geoip", "GeoIP + ASN enrichment", "Passive Intel"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = _to_ip(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            j = ctx.session.get(
                f"http://ip-api.com/json/{ip}?fields=country,regionName,city,"
                "lat,lon,timezone,isp,org,as,asname,reverse", timeout=ctx.timeout).json()
            return self.ok(ip, {k: v for k, v in j.items() if v not in (None, "")})
        except Exception as exc:
            return self.fail(ip, f"failed: {exc}")


@register
class UrlScan(Module):
    id, name, category = "urlscan", "URLScan.io public results", "Passive Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get("https://urlscan.io/api/v1/search/",
                                params={"q": f"domain:{host}", "size": 10},
                                timeout=ctx.timeout)
            if r.status_code != 200:
                return self.fail(host, f"urlscan HTTP {r.status_code}")
            results = r.json().get("results", [])
            out = [{"url": x.get("page", {}).get("url"),
                    "time": x.get("task", {}).get("time"),
                    "result": x.get("result")} for x in results]
            return self.ok(host, {"scans": out or "no public scans"})
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")


@register
class DomainReputation(Module):
    id, name, category = "reputation", "Domain age + popularity rank", "Passive Intel"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = {}
        # age via whois (reuse module)
        try:
            from .whois_recon import DomainAge
            age = DomainAge().run(host, ctx)
            data["age_days"] = age.data.get("age_days")
            data["created"] = age.data.get("created")
        except Exception:
            pass
        # popularity via Tranco list presence (best-effort through their API)
        try:
            r = ctx.session.get(f"https://tranco-list.eu/api/ranks/domain/{host}",
                                timeout=ctx.timeout)
            if r.status_code == 200:
                ranks = r.json().get("ranks", [])
                if ranks:
                    data["tranco_rank"] = ranks[-1].get("rank")
        except Exception:
            pass
        age_days = data.get("age_days")
        data["reputation_flag"] = ("newly registered (<30d) - higher risk"
                                   if isinstance(age_days, int) and age_days < 30
                                   else "established" if isinstance(age_days, int)
                                   else "unknown")
        return self.ok(host, data)
