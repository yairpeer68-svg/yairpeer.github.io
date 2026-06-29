"""Advanced web modules v2 (v3.5 features #23-#40). Detection only."""

from __future__ import annotations

import hashlib
import re
import struct
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Set
from urllib.parse import urljoin

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


def _get(ctx, url, **kw):
    return ctx.session.get(url, timeout=ctx.timeout, **kw)


@register
class OpenRedirectAdv(Module):
    id, name, category = "rediradv", "Open redirect scanner (extended)", "Web"
    target_kind = "url"

    _PARAMS = ["url", "next", "redirect", "return", "returnUrl", "dest",
               "destination", "redir", "r", "u", "target", "goto", "continue",
               "returnTo", "redirect_uri", "callback", "forward", "out", "view",
               "login_url", "image_url", "domain", "checkout_url", "ref"]
    _PAYLOADS = [
        "https://evil.com",
        "//evil.com",
        "/\\evil.com",
        "https://evil.com%00.legitimate.com",
        "https://evil.com?.legitimate.com",
        "https://evil.com#.legitimate.com",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = []
        for param in self._PARAMS:
            for payload in self._PAYLOADS[:3]:
                try:
                    r = ctx.session.get(f"{base}/?{param}={payload}",
                                        timeout=ctx.timeout, allow_redirects=False)
                    loc = r.headers.get("Location", "")
                    if r.status_code in (301, 302, 303, 307, 308) and "evil.com" in loc:
                        findings.append({"param": param, "payload": payload,
                                         "status": r.status_code, "location": loc[:120]})
                        break
                except Exception:
                    continue
        return self.ok(host, {"findings": findings or "no open redirect found",
                              "params_tested": len(self._PARAMS)})


@register
class CorsMisconfig(Module):
    id, name, category = "corsadv", "CORS misconfiguration (extended)", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ensure_scheme(host)
        tests = [
            ("https://evil.com", "arbitrary origin"),
            (f"https://{host}.evil.com", "subdomain prefix"),
            (f"https://evil{host}", "suffix match"),
            ("null", "null origin"),
            (f"https://sub.{host}", "subdomain"),
        ]
        findings = []
        for origin, desc in tests:
            try:
                r = _get(ctx, url, headers={"Origin": origin})
                acao = r.headers.get("Access-Control-Allow-Origin", "")
                acac = r.headers.get("Access-Control-Allow-Credentials", "")
                if acao == origin or (acao == "*"):
                    risk = "HIGH" if acac.lower() == "true" else "MEDIUM" if acao != "*" else "LOW"
                    findings.append({"test": desc, "origin_sent": origin,
                                     "acao": acao, "acac": acac, "risk": risk})
            except Exception:
                continue
        return self.ok(host, {"findings": findings or "CORS properly configured"})


@register
class CookieAudit(Module):
    id, name, category = "cookieaudit", "Cookie security audit (extended)", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _get(ctx, ensure_scheme(host))
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        cookies = []
        issues_total = 0
        for c in r.cookies:
            issues = []
            if not c.secure:
                issues.append("missing Secure flag")
            httponly = bool(c._rest.get("HttpOnly") or c._rest.get("httponly"))
            if not httponly:
                issues.append("missing HttpOnly flag")
            samesite = c._rest.get("SameSite") or c._rest.get("samesite")
            if not samesite:
                issues.append("missing SameSite attribute")
            elif samesite.lower() == "none" and not c.secure:
                issues.append("SameSite=None without Secure flag")
            has_prefix = c.name.startswith("__Host-") or c.name.startswith("__Secure-")
            if c.name.startswith("__Host-") and (not c.secure or c.path != "/"):
                issues.append("__Host- prefix requires Secure + Path=/")
            if c.name.startswith("__Secure-") and not c.secure:
                issues.append("__Secure- prefix requires Secure flag")
            if "session" in c.name.lower() or "token" in c.name.lower() or "auth" in c.name.lower():
                if not c.secure or not httponly:
                    issues.append("sensitive cookie name without full protection")
            issues_total += len(issues)
            cookies.append({"name": c.name, "secure": c.secure, "httponly": httponly,
                            "samesite": samesite or "unset", "prefix": has_prefix,
                            "issues": issues or ["ok"]})
        grade = "A" if issues_total == 0 else "B" if issues_total <= 2 else "C" if issues_total <= 5 else "F"
        return self.ok(host, {"cookies": cookies or "no cookies set",
                              "total_issues": issues_total, "grade": grade})


@register
class ClickjackTest(Module):
    id, name, category = "clickjack", "Clickjacking test", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _get(ctx, ensure_scheme(host))
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        h = {k.lower(): v for k, v in r.headers.items()}
        xfo = h.get("x-frame-options", "")
        csp = h.get("content-security-policy", "")
        fa_match = re.search(r"frame-ancestors\s+([^;]+)", csp)
        protected = False
        method = None
        if xfo:
            protected = True
            method = f"X-Frame-Options: {xfo}"
        if fa_match:
            protected = True
            method = f"CSP frame-ancestors: {fa_match.group(1).strip()}"
        issues = []
        if not protected:
            issues.append("no clickjacking protection (missing X-Frame-Options and frame-ancestors)")
        if xfo.upper() == "ALLOWALL":
            issues.append("X-Frame-Options: ALLOWALL is not protective")
            protected = False
        if fa_match and "*" in fa_match.group(1):
            issues.append("frame-ancestors contains wildcard")
        return self.ok(host, {"protected": protected, "method": method or "none",
                              "issues": issues or ["properly protected"],
                              "risk": "HIGH" if not protected else "ok"})


@register
class HttpMethodEnum(Module):
    id, name, category = "methodenum", "HTTP method enumeration (full)", "Web"
    target_kind = "url"

    _METHODS = ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD",
                "TRACE", "CONNECT"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ensure_scheme(host)
        results = {}
        risky = []
        for method in self._METHODS:
            try:
                r = ctx.session.request(method, url, timeout=ctx.timeout)
                results[method] = r.status_code
                if method in ("PUT", "DELETE", "TRACE", "CONNECT") and r.status_code < 400:
                    risky.append(method)
            except Exception:
                results[method] = "error"
        # TRACE specifically
        trace_vuln = False
        if results.get("TRACE") and results["TRACE"] == 200:
            trace_vuln = True
        return self.ok(host, {"methods": results, "risky_allowed": risky or "none",
                              "trace_enabled": trace_vuln,
                              "note": "TRACE enables XST attacks" if trace_vuln else ""})


@register
class ApiDiscovery(Module):
    id, name, category = "apidisco", "API endpoint discovery", "Web"
    target_kind = "url"

    _PATHS = ["/api", "/api/v1", "/api/v2", "/api/v3", "/v1", "/v2",
              "/swagger", "/swagger.json", "/swagger.yaml",
              "/swagger-ui.html", "/swagger-ui/",
              "/openapi.json", "/openapi.yaml", "/api-docs",
              "/docs", "/redoc", "/graphql", "/graphiql",
              "/api/docs", "/api/swagger", "/api/openapi",
              "/_api", "/rest", "/rest/api", "/jsonapi"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code < 400 and len(r.text) > 20:
                    is_json = "application/json" in r.headers.get("Content-Type", "")
                    return path, {"status": r.status_code, "json": is_json,
                                  "size": len(r.content)}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info
        return self.ok(host, {"endpoints": found or "no API endpoints found"})


@register
class RateLimitDetect(Module):
    id, name, category = "ratelimit", "Rate limiting detection", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ensure_scheme(host)
        statuses = []
        headers_seen = {}
        for i in range(15):
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                statuses.append(r.status_code)
                for h in ("X-RateLimit-Limit", "X-RateLimit-Remaining",
                          "X-RateLimit-Reset", "Retry-After",
                          "RateLimit-Limit", "RateLimit-Remaining"):
                    val = r.headers.get(h)
                    if val:
                        headers_seen[h] = val
                if r.status_code == 429:
                    break
            except Exception:
                break
        has_429 = 429 in statuses
        has_headers = bool(headers_seen)
        return self.ok(host, {
            "rate_limited": has_429,
            "rate_limit_headers": headers_seen or "none",
            "requests_before_limit": statuses.index(429) + 1 if has_429 else f">{len(statuses)}",
            "status_codes": statuses,
            "note": "rate limiting active" if has_429 or has_headers
            else "no rate limiting detected (risk: brute-force/DDoS)"
        })


@register
class WafFingerprint(Module):
    id, name, category = "waffp", "WAF fingerprinting (extended)", "Web"
    target_kind = "url"

    _SIGS = {
        "Cloudflare": {"headers": ["cf-ray", "cf-cache-status"], "cookies": ["__cfduid", "__cf_bm"],
                       "body": ["cloudflare"]},
        "AWS WAF": {"headers": ["x-amzn-requestid", "x-amz-cf-id"], "cookies": ["awsalb"],
                    "body": ["aws"]},
        "Akamai": {"headers": ["x-akamai-transformed", "akamai-grn"], "cookies": [],
                   "body": ["akamai"]},
        "Imperva/Incapsula": {"headers": ["x-iinfo", "x-cdn"], "cookies": ["incap_ses", "visid_incap"],
                              "body": ["incapsula"]},
        "ModSecurity": {"headers": [], "cookies": [],
                        "body": ["mod_security", "modsecurity", "NOYB"]},
        "F5 BIG-IP": {"headers": ["x-waf-status"], "cookies": ["BIGipServer"],
                      "body": ["the requested url was rejected"]},
        "Sucuri": {"headers": ["x-sucuri-id", "x-sucuri-cache"], "cookies": [],
                   "body": ["sucuri"]},
        "Fastly": {"headers": ["x-served-by", "x-fastly-request-id"], "cookies": [],
                   "body": []},
        "Barracuda": {"headers": ["barra_counter_session"], "cookies": ["barra_counter_session"],
                      "body": ["barracuda"]},
        "Citrix NetScaler": {"headers": ["cneonction", "nncoection"],
                             "cookies": ["ns_af", "citrix_ns_id"], "body": []},
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ensure_scheme(host)
        detected = []
        try:
            # normal request
            r1 = _get(ctx, url)
            # malicious-looking request to trigger WAF
            r2 = _get(ctx, url + "?id=1' OR '1'='1", headers={"X-Forwarded-For": "127.0.0.1"})
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        for waf_name, sigs in self._SIGS.items():
            h_lower = {k.lower(): v for k, v in r1.headers.items()}
            h_lower.update({k.lower(): v for k, v in r2.headers.items()})
            cookies_str = " ".join(r1.cookies.keys()).lower() + " " + " ".join(r2.cookies.keys()).lower()
            body = (r1.text + r2.text).lower()
            if any(s in h_lower for s in sigs["headers"]):
                detected.append(waf_name)
            elif any(s in cookies_str for s in sigs["cookies"]):
                detected.append(waf_name)
            elif any(s in body for s in sigs["body"]):
                detected.append(waf_name)
        blocked = r2.status_code in (403, 406, 429, 503)
        return self.ok(host, {"waf_detected": detected or "none identified",
                              "attack_blocked": blocked,
                              "normal_status": r1.status_code,
                              "attack_status": r2.status_code})


@register
class CmsDetect(Module):
    id, name, category = "cmsdetect", "CMS detection (extended)", "Web"
    target_kind = "url"

    _CMS_SIGS = {
        "WordPress": {
            "paths": ["/wp-login.php", "/wp-admin/", "/wp-content/", "/xmlrpc.php"],
            "html": [r"/wp-content/", r"/wp-includes/", r'name="generator" content="WordPress'],
        },
        "Drupal": {
            "paths": ["/core/misc/drupal.js", "/misc/drupal.js"],
            "html": [r"Drupal\.settings", r"/sites/default/files"],
        },
        "Joomla": {
            "paths": ["/administrator/", "/media/jui/"],
            "html": [r"/components/com_", r"Joomla!"],
        },
        "Ghost": {
            "paths": ["/ghost/api/"],
            "html": [r"ghost-url", r'content="Ghost"'],
        },
        "Hugo": {
            "paths": [],
            "html": [r'name="generator" content="Hugo'],
        },
        "Wix": {
            "paths": [],
            "html": [r"wix\.com", r"X-Wix-"],
        },
        "Squarespace": {
            "paths": [],
            "html": [r"squarespace\.com", r"Static\.SQUARESPACE"],
        },
        "Shopify": {
            "paths": [],
            "html": [r"cdn\.shopify\.com", r"Shopify\.theme"],
        },
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        try:
            r = _get(ctx, base)
            html = r.text
            server = r.headers.get("Server", "")
            powered = r.headers.get("X-Powered-By", "")
            generator = re.search(r'name=["\']generator["\']\s+content=["\']([^"\']+)', html, re.I)
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        detected = {}
        for cms, sigs in self._CMS_SIGS.items():
            for pattern in sigs["html"]:
                if re.search(pattern, html, re.I):
                    detected[cms] = "detected (HTML signature)"
                    break
        # path probing for undetected CMS
        for cms, sigs in self._CMS_SIGS.items():
            if cms in detected:
                continue
            for path in sigs["paths"][:2]:
                try:
                    pr = ctx.session.get(base + path, timeout=ctx.timeout, allow_redirects=False)
                    if pr.status_code < 400:
                        detected[cms] = f"detected (path {path})"
                        break
                except Exception:
                    continue
        return self.ok(host, {"cms": detected or "no CMS detected",
                              "server": server, "x_powered_by": powered,
                              "generator": generator.group(1) if generator else "none"})


@register
class WpVulnScan(Module):
    id, name, category = "wpscan", "WordPress vulnerability scan", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}
        # xmlrpc
        try:
            r = ctx.session.post(base + "/xmlrpc.php",
                                 data='<?xml version="1.0"?><methodCall><methodName>system.listMethods</methodName></methodCall>',
                                 headers={"Content-Type": "text/xml"},
                                 timeout=ctx.timeout)
            if r.status_code == 200 and "methodResponse" in r.text:
                findings["xmlrpc"] = "ENABLED (brute-force / DDoS amplification risk)"
        except Exception:
            pass
        # wp-json
        try:
            r = _get(ctx, base + "/wp-json/wp/v2/users")
            if r.status_code == 200 and "slug" in r.text:
                users = [u.get("slug") for u in r.json()[:10]]
                findings["user_enum"] = f"EXPOSED: {users}"
        except Exception:
            pass
        # readme.html (version disclosure)
        try:
            r = _get(ctx, base + "/readme.html")
            if r.status_code == 200 and "wordpress" in r.text.lower():
                ver = re.search(r"Version\s+([\d.]+)", r.text)
                findings["version_disclosure"] = ver.group(1) if ver else "readme.html exposed"
        except Exception:
            pass
        # debug.log
        try:
            r = _get(ctx, base + "/wp-content/debug.log")
            if r.status_code == 200 and len(r.text) > 50:
                findings["debug_log"] = "EXPOSED (may contain sensitive errors)"
        except Exception:
            pass
        # plugin enumeration
        try:
            r = _get(ctx, base)
            plugins = set(re.findall(r"/wp-content/plugins/([^/\"']+)", r.text))
            if plugins:
                findings["plugins"] = sorted(plugins)[:20]
        except Exception:
            pass
        # uploads listing
        try:
            r = _get(ctx, base + "/wp-content/uploads/")
            if r.status_code == 200 and "Index of" in r.text:
                findings["uploads_listing"] = "DIRECTORY LISTING ENABLED"
        except Exception:
            pass
        return self.ok(host, {"wordpress": findings or "no WordPress issues found or not WordPress"})


@register
class SourceMapDetect(Module):
    id, name, category = "sourcemap", "Source map file detection", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _get(ctx, ensure_scheme(host))
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(r.text, "html.parser")
        js_files = [tag["src"] for tag in soup.find_all("script", src=True)]
        exposed = []
        for js_url in js_files[:20]:
            full_url = urljoin(ensure_scheme(host), js_url)
            # check sourceMappingURL comment in JS
            try:
                js_r = ctx.session.get(full_url, timeout=ctx.timeout)
                if js_r.status_code == 200:
                    m = re.search(r"//[#@]\s*sourceMappingURL=(\S+)", js_r.text)
                    if m:
                        map_url = urljoin(full_url, m.group(1))
                        try:
                            mr = ctx.session.get(map_url, timeout=ctx.timeout)
                            if mr.status_code == 200 and '"sources"' in mr.text:
                                exposed.append({"js": js_url[:80], "map": map_url[:100],
                                                "status": "EXPOSED (contains original source)"})
                        except Exception:
                            pass
            except Exception:
                continue
        # Also check .map suffix
        for js_url in js_files[:10]:
            map_url = urljoin(ensure_scheme(host), js_url + ".map")
            try:
                r = ctx.session.get(map_url, timeout=ctx.timeout)
                if r.status_code == 200 and '"sources"' in r.text:
                    if not any(e["map"] == map_url for e in exposed):
                        exposed.append({"js": js_url[:80], "map": map_url[:100],
                                        "status": "EXPOSED"})
            except Exception:
                continue
        return self.ok(host, {"source_maps": exposed or "none found",
                              "risk": "original source code exposed" if exposed else "ok"})


@register
class AdminPanelFinder(Module):
    id, name, category = "adminfinder", "Admin panel finder (extended)", "Web"
    target_kind = "url"

    _PATHS = [
        "admin", "administrator", "admin/login", "admin.php", "admin.html",
        "wp-admin", "wp-login.php", "user/login", "login", "signin",
        "cpanel", "webmail", "phpmyadmin", "adminer.php", "adminer",
        "manager/html", "console", "portal", "auth/login",
        "panel", "dashboard", "controlpanel", "siteadmin",
        "admin/dashboard", "backend", "manage", "management",
        "moderator", "webadmin", "adminpanel", "admin_area",
        "admin/cp", "admin/controlpanel", "admin/index",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(path):
            try:
                r = ctx.session.get(f"{base}/{path}", timeout=ctx.timeout,
                                    allow_redirects=True)
                if r.status_code in (200, 401, 403):
                    has_login = bool(re.search(
                        r'type=["\']password["\']|login|sign.?in|username|authenticate',
                        r.text, re.I))
                    if has_login or r.status_code in (401, 403):
                        return path, {"status": r.status_code, "has_login_form": has_login}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info
        return self.ok(host, {"panels": found or "none found"})


@register
class RequestSmuggling(Module):
    id, name, category = "smuggle", "HTTP request smuggling indicators", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ensure_scheme(host)
        findings = {}
        # check for CL.TE indicators
        try:
            r = ctx.session.get(url, timeout=ctx.timeout)
            server = r.headers.get("Server", "").lower()
            te = r.headers.get("Transfer-Encoding", "")
            findings["server"] = r.headers.get("Server", "unknown")
            findings["transfer_encoding"] = te or "not present"
            # proxy chain detection (multiple servers = smuggling surface)
            via = r.headers.get("Via", "")
            if via:
                findings["via_header"] = via
                findings["proxy_chain"] = "detected (increases smuggling risk)"
            # check if TE header variations are accepted
            r2 = ctx.session.get(url, timeout=ctx.timeout,
                                 headers={"Transfer-Encoding": "chunked"})
            findings["te_chunked_status"] = r2.status_code
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        surface = bool(via) or "nginx" in server or "apache" in server
        return self.ok(host, {**findings,
                              "smuggling_surface": "present" if surface else "low",
                              "note": "definitive testing requires active probes (e.g. smuggler tool)"})


@register
class FaviconFingerprint(Module):
    id, name, category = "favhash", "Favicon hash fingerprint", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        for path in ("/favicon.ico", "/assets/favicon.ico"):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.content) > 0:
                    import codecs
                    b64 = codecs.encode(r.content, "base64")
                    # mmh3 hash (MurmurHash3) for Shodan
                    try:
                        import mmh3
                        favicon_hash = mmh3.hash(b64)
                    except ImportError:
                        # fallback: simple hash
                        favicon_hash = hash(b64) & 0xFFFFFFFF
                    return self.ok(host, {
                        "path": path,
                        "size": len(r.content),
                        "mmh3_hash": favicon_hash,
                        "md5": hashlib.md5(r.content).hexdigest(),
                        "shodan_query": f"http.favicon.hash:{favicon_hash}",
                        "note": "search this hash on Shodan to find related hosts"
                    })
            except Exception:
                continue
        return self.ok(host, {"favicon": "not found"})


@register
class MetaTagIntel(Module):
    id, name, category = "metatags", "Meta tag intelligence", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _get(ctx, ensure_scheme(host))
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(r.text, "html.parser")
        metas = {}
        interesting = ["generator", "author", "description", "keywords", "robots",
                       "theme-color", "viewport", "application-name",
                       "msapplication-TileColor", "apple-mobile-web-app-title"]
        for tag in soup.find_all("meta"):
            name = tag.get("name") or tag.get("property") or ""
            content = tag.get("content", "")
            if name.lower() in interesting:
                metas[name] = content[:200]
            elif name.startswith(("og:", "twitter:", "fb:")):
                metas[name] = content[:200]
        title = soup.title.string.strip() if soup.title and soup.title.string else None
        return self.ok(host, {"title": title, "meta_tags": metas or "none found"})


@register
class FormActionAnalysis(Module):
    id, name, category = "formaction", "Form action analysis", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _get(ctx, ensure_scheme(host))
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(r.text, "html.parser")
        forms = []
        external_actions = []
        for form in soup.find_all("form"):
            action = form.get("action", "")
            method = (form.get("method") or "GET").upper()
            inputs = [{"name": i.get("name"), "type": i.get("type", "text")}
                      for i in form.find_all(("input", "textarea", "select"))]
            has_csrf = any("csrf" in str(i.get("name", "")).lower() or
                          "token" in str(i.get("name", "")).lower() for i in inputs)
            password_fields = [i for i in inputs if i.get("type") == "password"]
            is_external = action.startswith("http") and host not in action
            if is_external:
                external_actions.append(action[:120])
            forms.append({"action": action[:120] or "(self)", "method": method,
                          "input_count": len(inputs), "has_csrf": has_csrf,
                          "has_password": bool(password_fields),
                          "external": is_external})
        issues = []
        if external_actions:
            issues.append(f"forms POST to external domains: {external_actions}")
        no_csrf = [f for f in forms if f["method"] == "POST" and not f["has_csrf"]]
        if no_csrf:
            issues.append(f"{len(no_csrf)} POST form(s) without CSRF token")
        return self.ok(host, {"forms": forms or "no forms found",
                              "issues": issues or "none"})


@register
class ExposedFiles(Module):
    id, name, category = "exposedfiles", "Exposed .env/.git/config detection", "Web"
    target_kind = "url"

    _FILES = {
        ".env": ["DB_PASSWORD", "SECRET_KEY", "API_KEY", "APP_KEY"],
        ".env.local": ["DB_PASSWORD", "SECRET_KEY"],
        ".env.production": ["DB_PASSWORD", "SECRET_KEY"],
        ".git/config": ["[core]", "[remote"],
        ".git/HEAD": ["ref:"],
        "config.php.bak": ["<?php"],
        "wp-config.php.bak": ["DB_NAME"],
        ".DS_Store": ["\x00\x00\x00\x01Bud1"],
        ".htpasswd": [":$apr1$", ":{SHA}"],
        "web.config": ["<configuration"],
        ".npmrc": ["registry", "//npm"],
        ".dockerenv": [],
        "Dockerfile": ["FROM "],
        "docker-compose.yml": ["version:", "services:"],
        ".gitlab-ci.yml": ["stages:", "script:"],
        "Makefile": ["all:", "install:"],
        "composer.json": ['"require"'],
        "package.json": ['"dependencies"', '"name"'],
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(item):
            path, sigs = item
            try:
                r = ctx.session.get(f"{base}/{path}", timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code == 200:
                    if not sigs or any(s in r.text[:500] for s in sigs):
                        return path, {"status": 200, "size": len(r.content),
                                      "risk": "HIGH" if path.startswith(".env") or
                                      "password" in r.text.lower()[:500] else "MEDIUM"}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for path, info in ex.map(probe, self._FILES.items()):
                if info:
                    found[path] = info
        return self.ok(host, {"exposed": found or "none found"})
