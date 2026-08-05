"""Additional passive-OSINT source modules (feature batch B).

Each pulls from a public/free OSINT source and returns what it finds — passive
DNS, archived URLs, cloud-bucket exposure, favicon pivots, social handles, code
leaks, security.txt, etc. Everything is *detection / discovery only*: no
exploitation, no payloads. All network calls degrade gracefully (a source that
is down, rate-limited or returns nothing simply yields an empty result).

Hosts are always filtered back to the target's own domain so the intelligence
graph stays clean. FOR AUTHORISED SECURITY TESTING ONLY.
"""

from __future__ import annotations

import json
import os
import re
from typing import List
from urllib.parse import quote

from ..core import (Context, Module, Result, clean_host, ensure_scheme,
                    register)

_HOSTRE = re.compile(r"[a-z0-9](?:[a-z0-9\-]{0,61}[a-z0-9])?"
                     r"(?:\.[a-z0-9\-]{1,63})+")


# --------------------------------------------------------------------------- #
#  small, defensive HTTP helpers (never raise)
# --------------------------------------------------------------------------- #
def _get(ctx: Context, url: str, **kw):
    try:
        return ctx.session.get(url, timeout=ctx.timeout, **kw)
    except Exception:  # noqa: BLE001
        return None


def _text(resp) -> str:
    if resp is None:
        return ""
    try:
        return resp.text or ""
    except Exception:  # noqa: BLE001
        return ""


def _json(resp):
    if resp is None:
        return None
    try:
        return resp.json()
    except Exception:  # noqa: BLE001
        return None


def _status(resp):
    return getattr(resp, "status_code", None) if resp is not None else None


def _subs_of(hosts, target: str) -> List[str]:
    """Keep only hosts that are the target or a subdomain of it."""
    t = (target or "").lower().lstrip("*.").rstrip(".")
    out = set()
    for h in hosts or []:
        h = str(h).lower().strip().rstrip(".").lstrip("*.")
        if h and (h == t or h.endswith("." + t)):
            out.add(h)
    return sorted(out)[:500]


# --------------------------------------------------------------------------- #
#  Passive DNS sources
# --------------------------------------------------------------------------- #
@register
class PassiveDNSOTX(Module):
    id, name, category = "pdnsotx", "Passive DNS (AlienVault OTX)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        j = _json(_get(
            ctx, f"https://otx.alienvault.com/api/v1/indicators/domain/"
                 f"{host}/passive_dns")) or {}
        recs = j.get("passive_dns", []) if isinstance(j, dict) else []
        subs, ips = set(), set()
        for r in recs:
            if not isinstance(r, dict):
                continue
            if r.get("hostname"):
                subs.add(str(r["hostname"]))
            if r.get("address"):
                ips.add(str(r["address"]))
        subs = _subs_of(subs, host)
        return self.ok(host, {"source": "otx", "count": len(subs),
                              "subdomains": subs, "ips": sorted(ips)[:60],
                              "note": "historical passive-DNS resolutions"})


@register
class PassiveDNSHackerTarget(Module):
    id, name, category = "pdnsht", "Passive DNS (HackerTarget)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        txt = _text(_get(ctx, f"https://api.hackertarget.com/hostsearch/?q={host}"))
        subs, ips = set(), set()
        for line in txt.splitlines():
            parts = line.split(",")
            if len(parts) >= 2 and "." in parts[0]:
                subs.add(parts[0].strip())
                ips.add(parts[1].strip())
        subs = _subs_of(subs, host)
        return self.ok(host, {"source": "hackertarget", "count": len(subs),
                              "subdomains": subs, "ips": sorted(ips)[:60]})


@register
class PassiveDNSAnubis(Module):
    id, name, category = "pdnsanubis", "Passive subdomains (AnubisDB)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        j = _json(_get(ctx, f"https://jldc.me/anubis/subdomains/{host}"))
        subs = _subs_of(j if isinstance(j, list) else [], host)
        return self.ok(host, {"source": "anubisdb", "count": len(subs),
                              "subdomains": subs})


@register
class PassiveDNSThreatMiner(Module):
    id, name, category = "pdnstm", "Passive DNS (ThreatMiner)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        j = _json(_get(ctx, f"https://api.threatminer.org/v2/domain.php"
                            f"?q={host}&rt=5")) or {}
        res = j.get("results", []) if isinstance(j, dict) else []
        subs = _subs_of([r for r in res if isinstance(r, str)], host)
        return self.ok(host, {"source": "threatminer", "count": len(subs),
                              "subdomains": subs})


@register
class RapidDNS(Module):
    id, name, category = "rapiddns", "Passive subdomains (RapidDNS)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        txt = _text(_get(ctx, f"https://rapiddns.io/subdomain/{host}?full=1"))
        hosts = re.findall(r"[a-z0-9._\-]+\." + re.escape(host), txt.lower())
        subs = _subs_of(hosts, host)
        return self.ok(host, {"source": "rapiddns", "count": len(subs),
                              "subdomains": subs})


# --------------------------------------------------------------------------- #
#  Archived / indexed URLs
# --------------------------------------------------------------------------- #
@register
class WaybackCDX(Module):
    id, name, category = "waybackcdx", "Archived URLs + subdomains (Wayback CDX)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        j = _json(_get(
            ctx, f"http://web.archive.org/cdx/search/cdx?url=*.{host}/*"
                 f"&output=json&fl=original&collapse=urlkey&limit=2000"))
        rows = j if isinstance(j, list) else []
        urls, subs = set(), set()
        for row in rows[1:] if len(rows) > 1 else []:
            u = row[0] if isinstance(row, list) and row else None
            if not u:
                continue
            urls.add(u)
            m = _HOSTRE.search(str(u).lower())
            if m:
                subs.add(m.group(0))
        return self.ok(host, {"source": "wayback-cdx", "urls": sorted(urls)[:200],
                              "url_count": len(urls),
                              "subdomains": _subs_of(subs, host)})


@register
class CommonCrawlIndex(Module):
    id, name, category = "commoncrawl", "Indexed URLs (Common Crawl)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        txt = _text(_get(
            ctx, f"https://index.commoncrawl.org/CC-MAIN-2024-10-index"
                 f"?url=*.{host}&output=json&limit=500"))
        urls = set()
        for line in txt.splitlines():
            try:
                obj = json.loads(line)
                if isinstance(obj, dict) and obj.get("url"):
                    urls.add(obj["url"])
            except Exception:  # noqa: BLE001
                continue
        return self.ok(host, {"source": "commoncrawl", "url_count": len(urls),
                              "urls": sorted(urls)[:200]})


@register
class URLScanSearch(Module):
    id, name, category = "urlscanio", "URLScan.io history", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        j = _json(_get(ctx, f"https://urlscan.io/api/v1/search/"
                            f"?q=domain:{host}&size=100")) or {}
        results = j.get("results", []) if isinstance(j, dict) else []
        urls, subs = set(), set()
        for it in results:
            page = (it or {}).get("page", {}) if isinstance(it, dict) else {}
            if page.get("url"):
                urls.add(str(page["url"]))
            if page.get("domain"):
                subs.add(str(page["domain"]))
        return self.ok(host, {"source": "urlscan", "scans": len(results),
                              "urls": sorted(urls)[:120],
                              "subdomains": _subs_of(subs, host)})


# --------------------------------------------------------------------------- #
#  Cloud / infra exposure & pivots
# --------------------------------------------------------------------------- #
@register
class CloudBuckets(Module):
    id, name, category = "bucketscan", "Cloud bucket exposure (S3/GCS/Azure)", "OSINT"
    target_kind = "domain"
    note = "generates candidate bucket names from the domain and checks public access"

    _SUFFIXES = ("", "-backup", "-backups", "-assets", "-static", "-media",
                 "-prod", "-dev", "-staging", "-data", "-uploads", "-public")

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = host.split(".")[0]
        cands = []
        for s in self._SUFFIXES:
            cands.append(base + s)
        cands.append(host.replace(".", "-"))
        cands = list(dict.fromkeys(cands))[:12]
        findings = []
        for c in cands:
            for prov, url in (
                    ("s3", f"https://{c}.s3.amazonaws.com"),
                    ("gcs", f"https://storage.googleapis.com/{c}"),
                    ("azure", f"https://{c}.blob.core.windows.net/?comp=list")):
                code = _status(_get(ctx, url))
                if code == 200:
                    findings.append({"provider": prov, "bucket": c,
                                     "url": url, "state": "PUBLIC / listable"})
                elif code == 403:
                    findings.append({"provider": prov, "bucket": c,
                                     "url": url, "state": "exists (private)"})
        public = [f for f in findings if "PUBLIC" in f["state"]]
        return self.ok(host, {"candidates_tested": len(cands) * 3,
                              "found": findings[:40],
                              "public_count": len(public),
                              "note": "PUBLIC buckets may expose data — verify"})


@register
class FaviconPivot(Module):
    id, name, category = "faviconhash", "Favicon hash (Shodan/Censys pivot)", "OSINT"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        resp = _get(ctx, ensure_scheme(host).rstrip("/") + "/favicon.ico")
        content = b""
        try:
            content = getattr(resp, "content", b"") or b""
        except Exception:  # noqa: BLE001
            content = b""
        if not content:
            return self.fail(host, "no favicon found")
        import base64
        import hashlib
        b64 = base64.encodebytes(content)
        mmh = None
        try:
            import mmh3
            mmh = mmh3.hash(b64)
        except Exception:  # noqa: BLE001
            mmh = None
        md5 = hashlib.md5(content).hexdigest()
        out = {"md5": md5, "bytes": len(content)}
        if mmh is not None:
            out["mmh3"] = mmh
            out["shodan_pivot"] = ("https://www.shodan.io/search?query="
                                   f"http.favicon.hash:{mmh}")
        else:
            out["note"] = ("install `mmh3` for the Shodan favicon-hash pivot; "
                           "md5 provided for reference")
        return self.ok(host, out)


# --------------------------------------------------------------------------- #
#  People / accounts / leaks
# --------------------------------------------------------------------------- #
@register
class SocialFinder(Module):
    id, name, category = "socialrecon", "Social handle discovery", "OSINT"
    target_kind = "domain"

    _PLATFORMS = {
        "GitHub": "https://github.com/{u}",
        "GitLab": "https://gitlab.com/{u}",
        "Twitter/X": "https://twitter.com/{u}",
        "Instagram": "https://www.instagram.com/{u}/",
        "Facebook": "https://www.facebook.com/{u}",
        "LinkedIn": "https://www.linkedin.com/company/{u}",
        "YouTube": "https://www.youtube.com/@{u}",
        "Reddit": "https://www.reddit.com/user/{u}",
        "Telegram": "https://t.me/{u}",
        "Medium": "https://medium.com/@{u}",
        "Keybase": "https://keybase.io/{u}",
        "Docker Hub": "https://hub.docker.com/u/{u}",
    }

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        user = host.split(".")[0]
        found = []
        for name, tmpl in self._PLATFORMS.items():
            url = tmpl.format(u=user)
            if _status(_get(ctx, url)) == 200:
                found.append({"platform": name, "url": url})
        return self.ok(host, {"username": user, "found": found,
                              "count": len(found),
                              "note": "handles matching the org name — verify "
                                      "they belong to the target"})


@register
class GithubLeakDorks(Module):
    id, name, category = "ghleak", "GitHub code-leak dorks (leads)", "OSINT"
    target_kind = "domain"
    note = "builds GitHub code-search links for secrets mentioning the domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        terms = [f'"{host}"', f'"{host}" password', f'"{host}" api_key',
                 f'"{host}" secret', f'"{host}" token', f'"{host}" BEGIN RSA',
                 f'"{host}" aws_access_key_id', f'"@{host}" password']
        leads = [{"dork": t,
                  "github": "https://github.com/search?type=code&q=" + quote(t),
                  "gitlab": "https://gitlab.com/search?scope=blobs&search=" + quote(t)}
                 for t in terms]
        return self.ok(host, {"leads": leads, "count": len(leads),
                              "note": "open each link to review potential code "
                                      "leaks (manual, detection-only)"})


@register
class EmailBreach(Module):
    id, name, category = "hibpbreach", "Email breach lookup (HIBP, opt-in)", "OSINT"
    target_kind = "domain"
    needs = ["HIBP_API_KEY env var"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        key = os.environ.get("HIBP_API_KEY", "")
        if not key:
            return self.ok(host, {
                "configured": False,
                "note": "set HIBP_API_KEY to check the domain's breaches via "
                        "Have I Been Pwned (paid key). Detection-only."})
        j = _json(_get(ctx, "https://haveibeenpwned.com/api/v3/breaches"
                            f"?domain={host}", headers={"hibp-api-key": key})) or []
        breaches = [b.get("Name") for b in j if isinstance(b, dict)] \
            if isinstance(j, list) else []
        return self.ok(host, {"configured": True, "count": len(breaches),
                              "breaches": breaches})


# --------------------------------------------------------------------------- #
#  Site metadata discovery
# --------------------------------------------------------------------------- #
@register
class SecurityTxt(Module):
    id, name, category = "sectxt", "security.txt discovery", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        for path in ("/.well-known/security.txt", "/security.txt"):
            txt = _text(_get(ctx, base + path))
            if txt and ("Contact" in txt or "contact" in txt.lower()):
                contacts = re.findall(r"Contact:\s*(\S+)", txt, re.I)
                exp = re.findall(r"Expires:\s*(\S+)", txt, re.I)
                return self.ok(host, {"found_at": path,
                                      "contacts": contacts[:10],
                                      "expires": exp[0] if exp else None})
        return self.fail(host, "no security.txt")


@register
class RobotsSitemap(Module):
    id, name, category = "robotsmap", "robots.txt / sitemap discovery", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        robots = _text(_get(ctx, base + "/robots.txt"))
        paths = re.findall(r"(?:Dis)?[Aa]llow:\s*(\S+)", robots)
        sitemaps = re.findall(r"[Ss]itemap:\s*(\S+)", robots)
        if not sitemaps and _status(_get(ctx, base + "/sitemap.xml")) == 200:
            sitemaps = [base + "/sitemap.xml"]
        if not (paths or sitemaps):
            return self.fail(host, "no robots.txt / sitemap")
        return self.ok(host, {"disallowed_paths": sorted(set(paths))[:60],
                              "sitemaps": sorted(set(sitemaps))[:20],
                              "note": "Disallow entries often reveal hidden "
                                      "paths worth reviewing"})


@register
class DNSDumpsterPassive(Module):
    id, name, category = "riddler", "Passive subdomains (aggregate)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        subs = set()
        # certspotter issuances (structured JSON, not crt.sh)
        j = _json(_get(ctx, "https://api.certspotter.com/v1/issuances"
                           f"?domain={host}&include_subdomains=true&expand=dns_names"))
        for cert in j if isinstance(j, list) else []:
            for n in (cert or {}).get("dns_names", []) if isinstance(cert, dict) else []:
                subs.add(str(n))
        subs = _subs_of(subs, host)
        return self.ok(host, {"source": "certspotter", "count": len(subs),
                              "subdomains": subs,
                              "note": "certificate-issuance subdomains"})
