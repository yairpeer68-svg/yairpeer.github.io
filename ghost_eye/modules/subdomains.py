"""Subdomain & asset-discovery modules (features #11-#18)."""

from __future__ import annotations

import re
import socket
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Dict, List, Set

from ..core import (Console, Context, Module, Result, clean_host, ensure_scheme,
                    register)

# Fingerprints for dangling-CNAME subdomain takeover (subset of the
# well-known can-i-take-over-xyz list).
_TAKEOVER_SIGS = {
    "github.io": "There isn't a GitHub Pages site here",
    "herokuapp.com": "No such app",
    "herokudns.com": "No such app",
    "wordpress.com": "Do you want to register",
    "amazonaws.com": "NoSuchBucket",
    "s3.amazonaws.com": "NoSuchBucket",
    "cloudfront.net": "ERROR: The request could not be satisfied",
    "ghost.io": "The thing you were looking for is no longer here",
    "fastly.net": "Fastly error: unknown domain",
    "zendesk.com": "Help Center Closed",
    "surge.sh": "project not found",
    "bitbucket.io": "Repository not found",
    "shopify.com": "Sorry, this shop is currently unavailable",
    "unbounce.com": "The requested URL was not found on this server",
    "pantheonsite.io": "The gods are wise",
    "readme.io": "Project doesnt exist",
}


@register
class SubdomainEnum(Module):
    id, name, category = "subs", "Subdomain enumeration (CT + passive DNS)", "Assets"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        subs: Set[str] = set()
        per_source: Dict[str, object] = {}

        sources = {
            "crt.sh": _src_crtsh,
            "hackertarget": _src_hackertarget,
            "certspotter": _src_certspotter,
            "anubis": _src_anubis,
            "alienvault": _src_alienvault,
            "rapiddns": _src_rapiddns,
            "wayback": _src_wayback,
            "urlscan": _src_urlscan,
            "threatminer": _src_threatminer,
            "threatcrowd": _src_threatcrowd,
        }
        with ThreadPoolExecutor(max_workers=len(sources)) as ex:
            futs = {ex.submit(fn, host, sess, ctx.timeout): nm
                    for nm, fn in sources.items()}
            for fut in as_completed(futs):
                nm = futs[fut]
                try:
                    found = fut.result()
                    per_source[nm] = len(found)
                    subs |= found
                except Exception as exc:  # noqa: BLE001
                    per_source[nm] = f"failed: {str(exc)[:60]}"

        # resolve concurrently (cap to avoid a DNS storm on huge results)
        ordered = sorted(subs)
        capped = ordered[:400]

        def _resolve(name: str):
            try:
                return name, socket.gethostbyname(name)
            except OSError:
                return name, "(unresolved)"
        resolved: Dict[str, str] = {}
        if capped:
            with ThreadPoolExecutor(max_workers=min(ctx.threads * 4, 60)) as ex:
                for name, ip in ex.map(_resolve, capped):
                    resolved[name] = ip

        ok_sources = [s for s, v in per_source.items() if isinstance(v, int) and v > 0]
        if not subs and not ok_sources:
            Console.warn("all subdomain sources failed or returned nothing")
        return self.ok(host, {
            "count": len(subs),
            "sources": per_source,
            "resolved_shown": len(resolved),
            "subdomains": resolved,
            "note": ("only the first 400 names were DNS-resolved"
                     if len(subs) > 400 else ""),
        })


# --- free subdomain sources (no API key); each returns a set of hostnames --- #
def _keep(names, host: str) -> Set[str]:
    out = set()
    for n in names:
        n = str(n).strip().lstrip("*.").lower().rstrip(".")
        if n and n.endswith(host) and "@" not in n and " " not in n:
            out.add(n)
    return out


def _src_crtsh(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get(f"https://crt.sh/?q=%25.{host}&output=json",
                 timeout=timeout + 20,
                 headers={"User-Agent": "Mozilla/5.0 GhostEye"})
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    try:
        data = r.json()
    except ValueError:
        raise RuntimeError("non-JSON (rate-limited or error page)")
    names = []
    for entry in data:
        names.extend(str(entry.get("name_value", "")).splitlines())
        cn = entry.get("common_name")
        if cn:
            names.append(cn)
    return _keep(names, host)


def _src_hackertarget(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get(f"https://api.hackertarget.com/hostsearch/?q={host}", timeout=timeout)
    if r.status_code != 200 or "error" in r.text.lower() or "api count" in r.text.lower():
        raise RuntimeError("unavailable / quota")
    return _keep((ln.split(",")[0] for ln in r.text.splitlines()), host)


def _src_certspotter(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get("https://api.certspotter.com/v1/issuances",
                 params={"domain": host, "include_subdomains": "true",
                         "expand": "dns_names"}, timeout=timeout + 10)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    names = []
    for cert in r.json():
        names.extend(cert.get("dns_names", []))
    return _keep(names, host)


def _src_anubis(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get(f"https://jldc.me/anubis/subdomains/{host}", timeout=timeout)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    return _keep(r.json(), host)


def _src_alienvault(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get(f"https://otx.alienvault.com/api/v1/indicators/domain/{host}/passive_dns",
                 timeout=timeout + 5)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    return _keep((rec.get("hostname", "") for rec in r.json().get("passive_dns", [])), host)


def _src_rapiddns(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get(f"https://rapiddns.io/subdomain/{host}?full=1",
                 timeout=timeout + 5, headers={"User-Agent": "Mozilla/5.0 GhostEye"})
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    found = re.findall(r"[A-Za-z0-9._-]+\." + re.escape(host), r.text)
    return _keep(found, host)


def _src_wayback(host: str, sess, timeout: int) -> Set[str]:
    from urllib.parse import urlparse
    r = sess.get("http://web.archive.org/cdx/search/cdx",
                 params={"url": f"*.{host}/*", "output": "json",
                         "fl": "original", "collapse": "urlkey", "limit": "20000"},
                 timeout=timeout + 20)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    rows = r.json()
    names = (urlparse(row[0]).hostname or "" for row in rows[1:])
    return _keep(names, host)


def _src_urlscan(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get("https://urlscan.io/api/v1/search/",
                 params={"q": f"domain:{host}", "size": 1000}, timeout=timeout + 10)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    names = []
    for res in r.json().get("results", []):
        page = res.get("page", {}) or {}
        names.append(page.get("domain", ""))
        task = res.get("task", {}) or {}
        names.append(task.get("domain", ""))
    return _keep(names, host)


def _src_threatminer(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get("https://api.threatminer.org/v2/domain.php",
                 params={"q": host, "rt": "5"}, timeout=timeout + 10)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    return _keep(r.json().get("results", []), host)


def _src_threatcrowd(host: str, sess, timeout: int) -> Set[str]:
    r = sess.get("https://www.threatcrowd.org/searchApi/v2/domain/report/",
                 params={"domain": host}, timeout=timeout + 10)
    if r.status_code != 200:
        raise RuntimeError(f"HTTP {r.status_code}")
    return _keep(r.json().get("subdomains", []), host)


@register
class SubdomainTakeover(Module):
    id, name, category = "takeover", "Subdomain takeover detection", "Assets"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as exc:
            return self.fail(target, str(exc))

        sess = ctx.session
        findings: List[Dict[str, str]] = []
        # Re-use enumerated subs if available, else probe the host itself.
        candidates = [host, f"www.{host}"]
        sub_result = SubdomainEnum().run(host, ctx)
        candidates += list(sub_result.data.get("subdomains", {}).keys())

        for sub in dict.fromkeys(candidates):  # de-dupe, keep order
            try:
                cname = str(dns.resolver.resolve(sub, "CNAME")[0].target).rstrip(".")
            except Exception:
                continue
            service = next((svc for svc in _TAKEOVER_SIGS if svc in cname), None)
            if not service:
                continue
            try:
                body = sess.get(ensure_scheme(sub), timeout=ctx.timeout).text
            except Exception:
                body = ""
            if _TAKEOVER_SIGS[service] in body:
                findings.append({"subdomain": sub, "cname": cname,
                                 "service": service, "status": "VULNERABLE"})
            else:
                findings.append({"subdomain": sub, "cname": cname,
                                 "service": service, "status": "dangling CNAME - review"})
        return self.ok(host, {"candidates": findings,
                              "note": "no obvious takeover" if not findings else ""})


@register
class ReverseIp(Module):
    id, name, category = "revip", "Reverse IP (shared hosts)", "Assets"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        try:
            resp = sess.get(f"https://api.hackertarget.com/reverseiplookup/?q={host}",
                            timeout=ctx.timeout)
            if resp.status_code != 200 or "error" in resp.text.lower():
                return self.fail(host, "reverse-IP lookup unavailable (rate limit?)")
            hosts = [h.strip() for h in resp.text.splitlines() if h.strip()]
            return self.ok(host, {"count": len(hosts), "hosts": hosts})
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"reverse-IP failed: {exc}")


@register
class AsnLookup(Module):
    id, name, category = "asn", "ASN -> IP ranges", "Assets"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        from ..core import is_ip
        ip = host if is_ip(host) else None
        if not ip:
            try:
                ip = socket.gethostbyname(host)
            except OSError as exc:
                return self.fail(host, f"cannot resolve: {exc}")
        sess = ctx.session
        try:
            # Team Cymru style lookup via free ip-api
            resp = sess.get(f"http://ip-api.com/json/{ip}?fields=as,asname,isp,org,query",
                            timeout=ctx.timeout)
            j = resp.json()
            return self.ok(host, {
                "ip": j.get("query"), "as": j.get("as"),
                "as_name": j.get("asname"), "isp": j.get("isp"),
                "org": j.get("org"),
            })
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"ASN lookup failed: {exc}")


@register
class FaviconHash(Module):
    id, name, category = "favicon", "Favicon hash (Shodan pivot)", "Assets"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        import base64
        try:
            import mmh3
        except ImportError:
            return self.fail(target, "requires mmh3  (pip install mmh3)")
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        url = ensure_scheme(host).rstrip("/") + "/favicon.ico"
        try:
            resp = sess.get(url, timeout=ctx.timeout)
            if resp.status_code != 200 or not resp.content:
                return self.fail(host, "no favicon found")
            b64 = base64.encodebytes(resp.content)
            fh = mmh3.hash(b64)
            return self.ok(host, {
                "favicon_url": url,
                "mmh3_hash": fh,
                "shodan_pivot": f'http.favicon.hash:{fh}',
                "note": "search that hash on Shodan to find hosts sharing this favicon",
            })
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"favicon fetch failed: {exc}")


@register
class WaybackUrls(Module):
    id, name, category = "wayback", "Wayback Machine URLs", "Assets"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        url = (f"http://web.archive.org/cdx/search/cdx?url={host}/*"
               f"&output=text&fl=original&collapse=urlkey&limit=2000")
        try:
            resp = sess.get(url, timeout=ctx.timeout + 20)
            urls = sorted(set(u.strip() for u in resp.text.splitlines() if u.strip()))
            interesting = [u for u in urls if re.search(
                r"\.(json|xml|sql|bak|config|env|log|zip|tar|gz)(\?|$)", u, re.I)]
            return self.ok(host, {
                "total": len(urls),
                "interesting": interesting[:100],
                "sample": urls[:100],
            })
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"wayback fetch failed: {exc}")


@register
class JsEndpoints(Module):
    id, name, category = "jsendpoints", "Endpoints from JS files", "Assets"
    target_kind = "url"

    _PATH_RE = re.compile(r"""['"](/[a-zA-Z0-9_\-/.]+?(?:\?[^'"]*)?)['"]""")
    _URL_RE = re.compile(r"""https?://[a-zA-Z0-9_.\-]+(?:/[a-zA-Z0-9_\-/.?=&%]*)?""")

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        base = ensure_scheme(host)
        try:
            html = sess.get(base, timeout=ctx.timeout).text
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"cannot fetch page: {exc}")
        soup = BeautifulSoup(html, "html.parser")
        scripts = []
        for tag in soup.find_all("script", src=True):
            src = tag["src"]
            if src.startswith("//"):
                src = "https:" + src
            elif src.startswith("/"):
                src = base.rstrip("/") + src
            elif not src.startswith("http"):
                src = base.rstrip("/") + "/" + src
            scripts.append(src)

        paths: Set[str] = set()
        urls: Set[str] = set()
        for js in scripts[:30]:
            try:
                body = sess.get(js, timeout=ctx.timeout).text
            except Exception:
                continue
            paths.update(self._PATH_RE.findall(body))
            urls.update(self._URL_RE.findall(body))
        return self.ok(host, {
            "js_files": scripts,
            "paths": sorted(p for p in paths if len(p) > 1)[:200],
            "absolute_urls": sorted(urls)[:100],
        })


@register
class ApiDiscovery(Module):
    id, name, category = "apidisc", "API endpoint discovery", "Assets"
    target_kind = "url"

    _COMMON = [
        "/api", "/api/v1", "/api/v2", "/v1", "/v2", "/graphql",
        "/swagger.json", "/swagger/v1/swagger.json", "/openapi.json",
        "/api-docs", "/api/swagger.json", "/.well-known/openapi.json",
        "/rest", "/wp-json", "/api/health", "/health", "/status", "/metrics",
        "/actuator", "/actuator/health", "/api/docs",
    ]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        sess = ctx.session
        base = ensure_scheme(host).rstrip("/")
        found: Dict[str, int] = {}
        for path in self._COMMON:
            try:
                r = sess.get(base + path, timeout=ctx.timeout, allow_redirects=False)
                if r.status_code < 400 or r.status_code in (401, 403):
                    found[path] = r.status_code
            except Exception:
                continue
        return self.ok(host, {"endpoints": found,
                              "note": "401/403 means it exists but is protected"})
