"""Web / HTTP analysis modules (features #36-#47 + original robots/crawler/headers)."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Set
from urllib.parse import urljoin, urlsplit

from ..core import (Console, Context, Module, Result, clean_host, ensure_scheme,
                    register)

_SECURITY_HEADERS = {
    "strict-transport-security": "HSTS - forces HTTPS",
    "content-security-policy": "CSP - mitigates XSS/injection",
    "x-frame-options": "clickjacking protection",
    "x-content-type-options": "blocks MIME sniffing",
    "referrer-policy": "controls referrer leakage",
    "permissions-policy": "restricts browser features",
    "cross-origin-opener-policy": "process isolation",
}

_WAF_SIGS = {
    "cloudflare": ["cf-ray", "cloudflare", "__cfduid", "cf-cache-status"],
    "akamai": ["akamai", "x-akamai", "akamaighost"],
    "aws-waf / cloudfront": ["x-amz-cf-id", "x-amzn-requestid", "awselb"],
    "imperva/incapsula": ["incap_ses", "x-iinfo", "x-cdn"],
    "sucuri": ["x-sucuri-id", "sucuri"],
    "f5 big-ip": ["bigipserver", "x-waf"],
    "fastly": ["x-served-by", "fastly"],
}


def _get(ctx: Context, url: str, **kw):
    return ctx.session.get(url, timeout=ctx.timeout, **kw)


@register
class SecurityHeaders(Module):
    id, name, category = "headers", "Security headers + clickjacking", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            resp = _get(ctx, ensure_scheme(host))
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        h = {k.lower(): v for k, v in resp.headers.items()}
        present, missing = {}, []
        for name, why in _SECURITY_HEADERS.items():
            if name in h:
                present[name] = h[name]
            else:
                missing.append(f"{name} ({why})")
        return self.ok(host, {
            "status_code": resp.status_code,
            "server": h.get("server", "(hidden)"),
            "present": present,
            "missing": missing,
            "clickjacking": ("protected" if "x-frame-options" in h
                             or "frame-ancestors" in h.get("content-security-policy", "")
                             else "VULNERABLE - no X-Frame-Options / frame-ancestors"),
        })


@register
class CorsCheck(Module):
    id, name, category = "cors", "CORS misconfiguration", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        evil = "https://evil-ghosteye-test.example"
        try:
            resp = _get(ctx, ensure_scheme(host), headers={"Origin": evil})
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        h = {k.lower(): v for k, v in resp.headers.items()}
        acao = h.get("access-control-allow-origin", "")
        acac = h.get("access-control-allow-credentials", "")
        verdict = "ok"
        if acao == "*":
            verdict = "wildcard origin (no creds allowed - low risk)"
        if acao == evil:
            verdict = "reflects arbitrary origin - MISCONFIGURED"
            if acac.lower() == "true":
                verdict = "reflects origin WITH credentials - HIGH RISK"
        return self.ok(host, {"allow_origin": acao or "(none)",
                              "allow_credentials": acac or "(none)",
                              "verdict": verdict})


@register
class CookieFlags(Module):
    id, name, category = "cookies", "Cookie security flags", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            resp = _get(ctx, ensure_scheme(host))
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        cookies = []
        for c in resp.cookies:
            flags = {
                "secure": c.secure,
                "httponly": bool(c._rest.get("HttpOnly") or c._rest.get("httponly")),
                "samesite": c._rest.get("SameSite") or c._rest.get("samesite") or "(unset)",
            }
            issues = []
            if not flags["secure"]:
                issues.append("missing Secure")
            if not flags["httponly"]:
                issues.append("missing HttpOnly")
            if flags["samesite"] == "(unset)":
                issues.append("missing SameSite")
            cookies.append({"name": c.name, **flags, "issues": issues or ["ok"]})
        return self.ok(host, {"cookies": cookies or "(no cookies set)"})


@register
class HttpMethods(Module):
    id, name, category = "methods", "HTTP methods (OPTIONS)", "Web"
    target_kind = "url"

    _RISKY = {"PUT", "DELETE", "TRACE", "CONNECT", "PATCH"}

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        url = ensure_scheme(host)
        try:
            resp = ctx.session.request("OPTIONS", url, timeout=ctx.timeout)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        allow = resp.headers.get("Allow") or resp.headers.get("allow") or ""
        methods = [m.strip().upper() for m in allow.split(",") if m.strip()]
        risky = [m for m in methods if m in self._RISKY]
        return self.ok(host, {"allowed": methods or "(server did not advertise)",
                              "risky": risky or ["none"]})


@register
class WafDetect(Module):
    id, name, category = "waf", "WAF detection", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            resp = _get(ctx, ensure_scheme(host))
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        blob = " ".join(f"{k}:{v}" for k, v in resp.headers.items()).lower()
        blob += " " + " ".join(resp.cookies.keys()).lower()
        detected = [waf for waf, sigs in _WAF_SIGS.items()
                    if any(sig in blob for sig in sigs)]
        return self.ok(host, {"waf": detected or ["none detected"]})


@register
class CdnDetect(Module):
    id, name, category = "cdn", "CDN detection", "Web"
    target_kind = "url"

    _CDN = {
        "cloudflare": "cf-ray", "fastly": "x-served-by",
        "akamai": "x-akamai-transformed", "cloudfront": "x-amz-cf-id",
        "google": "x-goog", "azure": "x-azure-ref", "vercel": "x-vercel-id",
        "netlify": "x-nf-request-id",
    }

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            resp = _get(ctx, ensure_scheme(host))
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        hl = {k.lower() for k in resp.headers}
        found = [cdn for cdn, hdr in self._CDN.items() if hdr in hl]
        srv = resp.headers.get("Server", "")
        return self.ok(host, {"cdn": found or ["none detected"], "server": srv})


@register
class TechFingerprint(Module):
    id, name, category = "tech", "Technology fingerprint / CMS", "Web"
    target_kind = "url"

    _SIGS = {
        "WordPress": [r"/wp-content/", r"/wp-includes/", r'name="generator" content="WordPress'],
        "Joomla": [r"/components/com_", r"Joomla!"],
        "Drupal": [r"/sites/default/files", r"Drupal.settings"],
        "Magento": [r"/skin/frontend/", r"Mage.Cookies"],
        "Shopify": [r"cdn.shopify.com", r"Shopify.theme"],
        "React": [r"data-reactroot", r"__REACT_DEVTOOLS"],
        "Vue.js": [r"data-v-", r"__vue__"],
        "Angular": [r"ng-version", r"ng-app"],
        "Next.js": [r"/_next/static", r"__NEXT_DATA__"],
        "Laravel": [r"laravel_session", r"XSRF-TOKEN"],
        "Django": [r"csrftoken", r"__admin_media_prefix__"],
        "jQuery": [r"jquery(?:\.min)?\.js"],
        "Bootstrap": [r"bootstrap(?:\.min)?\.css"],
        "Nginx": [], "Apache": [], "Cloudflare": [],
    }

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        # prefer webtech if available for richer output
        try:
            import webtech
            wt = webtech.WebTech(options={"json": True})
            report = wt.start_from_url(ensure_scheme(host), timeout=ctx.timeout)
            return self.ok(host, {"webtech": report})
        except Exception:
            pass

        try:
            resp = _get(ctx, ensure_scheme(host))
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        body = resp.text
        detected = []
        for tech, patterns in self._SIGS.items():
            if any(re.search(p, body, re.I) for p in patterns):
                detected.append(tech)
        server = resp.headers.get("Server", "")
        powered = resp.headers.get("X-Powered-By", "")
        for hint, tech in (("nginx", "Nginx"), ("apache", "Apache"),
                           ("cloudflare", "Cloudflare")):
            if hint in (server + powered).lower():
                detected.append(tech)
        return self.ok(host, {
            "technologies": sorted(set(detected)) or ["unknown"],
            "server": server, "x_powered_by": powered,
        })


@register
class DirBuster(Module):
    id, name, category = "dirs", "Directory / file discovery", "Web"
    target_kind = "url"

    _PATHS = [
        "admin", "administrator", "login", "wp-admin", "wp-login.php",
        "phpmyadmin", "dashboard", "config", "config.php", ".env", ".git/HEAD",
        "backup", "backup.zip", "backup.sql", "db.sql", "dump.sql",
        "robots.txt", "sitemap.xml", "server-status", ".htaccess",
        "api", "uploads", "images", "css", "js", "test", "dev", "old",
        "info.php", "phpinfo.php", "readme.html", "license.txt",
        "web.config", ".svn/entries", ".DS_Store", "crossdomain.xml",
    ]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        wl = ctx.config.get("wordlist")
        paths = self._PATHS
        if wl:
            try:
                paths = [p.strip() for p in open(wl, encoding="utf-8")
                         if p.strip() and not p.startswith("#")]
            except OSError as exc:
                Console.warn(f"wordlist unreadable ({exc}); using builtin list")

        found: Dict[str, int] = {}

        def probe(path: str):
            url = f"{base}/{path.lstrip('/')}"
            try:
                r = ctx.session.get(url, timeout=ctx.timeout, allow_redirects=False)
                if r.status_code not in (404,):
                    return path, r.status_code
            except Exception:
                return path, None
            return path, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for path, code in ex.map(probe, paths):
                if code:
                    found[path] = code
        return self.ok(host, {"found": dict(sorted(found.items(), key=lambda x: x[1]))})


@register
class SitemapParse(Module):
    id, name, category = "sitemap", "Sitemap.xml parser", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        urls: Set[str] = set()
        for path in ("/sitemap.xml", "/sitemap_index.xml"):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200:
                    urls.update(re.findall(r"<loc>\s*(.*?)\s*</loc>", r.text, re.I))
            except Exception:
                continue
        return self.ok(host, {"count": len(urls), "urls": sorted(urls)[:300]})


@register
class FormFinder(Module):
    id, name, category = "forms", "Forms & input fields", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            resp = _get(ctx, ensure_scheme(host))
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(resp.text, "html.parser")
        forms = []
        for f in soup.find_all("form"):
            inputs = [{"name": i.get("name"), "type": i.get("type", "text")}
                      for i in f.find_all(("input", "textarea", "select"))]
            forms.append({
                "action": f.get("action", "(self)"),
                "method": (f.get("method") or "GET").upper(),
                "inputs": inputs,
                "has_csrf_token": any("csrf" in str(i.get("name", "")).lower()
                                      or "token" in str(i.get("name", "")).lower()
                                      for i in inputs),
            })
        return self.ok(host, {"form_count": len(forms), "forms": forms})


@register
class OpenRedirect(Module):
    id, name, category = "redirect", "Open redirect detection", "Web"
    target_kind = "url"

    _PARAMS = ["url", "next", "redirect", "return", "returnUrl", "dest",
               "destination", "redir", "r", "u", "target", "goto"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        canary = "https://example.com/ghosteye-canary"
        findings = []
        for param in self._PARAMS:
            url = f"{base}/?{param}={canary}"
            try:
                r = ctx.session.get(url, timeout=ctx.timeout, allow_redirects=False)
            except Exception:
                continue
            loc = r.headers.get("Location", "")
            if r.status_code in (301, 302, 303, 307, 308) and "example.com" in loc:
                findings.append({"param": param, "status": r.status_code,
                                 "location": loc, "verdict": "OPEN REDIRECT"})
        return self.ok(host, {"findings": findings or "no open redirect on common params"})


@register
class HttpsCheck(Module):
    id, name, category = "httpscheck", "HTTPS redirect + mixed content", "Web"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        data: Dict[str, object] = {}
        try:
            r = ctx.session.get(f"http://{host}", timeout=ctx.timeout,
                                allow_redirects=False)
            loc = r.headers.get("Location", "")
            data["http_redirects_to_https"] = (
                r.status_code in (301, 302, 307, 308) and loc.startswith("https"))
            data["redirect_location"] = loc or "(none)"
        except Exception as exc:  # noqa: BLE001
            data["http_check"] = f"failed: {exc}"
        # mixed content on the https page
        try:
            rs = ctx.session.get(f"https://{host}", timeout=ctx.timeout)
            mixed = re.findall(r'(?:src|href)=["\'](http://[^"\']+)', rs.text)
            data["mixed_content_count"] = len(mixed)
            data["mixed_content_samples"] = list(dict.fromkeys(mixed))[:10]
        except Exception as exc:  # noqa: BLE001
            data["https_check"] = f"failed: {exc}"
        return self.ok(host, data)


@register
class RobotsScan(Module):
    id, name, category = "robots", "Robots.txt scanner", "Web"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            r = ctx.session.get(ensure_scheme(host).rstrip("/") + "/robots.txt",
                                timeout=ctx.timeout)
            if r.status_code != 200:
                return self.ok(host, {"robots": f"HTTP {r.status_code} - none"})
            disallowed = re.findall(r"(?im)^\s*Disallow:\s*(\S+)", r.text)
            sitemaps = re.findall(r"(?im)^\s*Sitemap:\s*(\S+)", r.text)
            return self.ok(host, {
                "disallowed_paths": disallowed,
                "sitemaps": sitemaps,
                "raw": r.text.splitlines()[:60],
            })
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"request failed: {exc}")


@register
class Crawler(Module):
    id, name, category = "crawl", "Link crawler (same-host)", "Web"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as exc:
            return self.fail(target, str(exc))
        from collections import deque
        start = ensure_scheme(host)
        netloc = urlsplit(start).netloc
        max_pages = ctx.config.get_int("crawl_max", 60) if hasattr(
            ctx.config, "get_int") else 60

        queue = deque([start])
        seen: Set[str] = set()
        internal: Set[str] = set()
        external: Set[str] = set()

        while queue and len(seen) < max_pages:
            url = queue.popleft()
            if url in seen:
                continue
            seen.add(url)
            try:
                resp = ctx.session.get(url, timeout=ctx.timeout)
            except Exception:
                continue
            soup = BeautifulSoup(resp.text, "html.parser")
            for a in soup.find_all("a", href=True):
                link = urljoin(url, a["href"])
                parts = urlsplit(link)
                if parts.scheme not in ("http", "https"):
                    continue
                if parts.netloc == netloc:
                    internal.add(link)
                    if link not in seen:
                        queue.append(link)
                else:
                    external.add(link)
        return self.ok(host, {
            "pages_crawled": len(seen),
            "internal_links": sorted(internal)[:200],
            "external_links": sorted(external)[:100],
        })
