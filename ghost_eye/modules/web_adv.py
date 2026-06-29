"""Advanced web/HTTP modules (new features #18-#28). Detection only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

_JS_LIB_RE = [
    (re.compile(r"jquery[/-]?(\d+\.\d+\.\d+)", re.I), "jQuery"),
    (re.compile(r"angular[.-]?(\d+\.\d+\.\d+)", re.I), "AngularJS"),
    (re.compile(r"react(?:-dom)?[@/-]?(\d+\.\d+\.\d+)", re.I), "React"),
    (re.compile(r"vue[@/.-]?(\d+\.\d+\.\d+)", re.I), "Vue"),
    (re.compile(r"bootstrap[/-]?(\d+\.\d+\.\d+)", re.I), "Bootstrap"),
    (re.compile(r"lodash[/-]?(\d+\.\d+\.\d+)", re.I), "Lodash"),
    (re.compile(r"moment[/-]?(\d+\.\d+\.\d+)", re.I), "Moment.js"),
]
# very small "is this ancient" heuristic (major version cutoffs)
_OLD_MAJOR = {"jQuery": 3, "Bootstrap": 4, "AngularJS": 1}


@register
class HttpVersions(Module):
    id, name, category = "httpversions", "HTTP/2 + HTTP/3 support", "Web"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = {}
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
            alt = r.headers.get("alt-svc", "")
            data["http3_advertised"] = "h3" in alt
            data["alt_svc"] = alt or "(none)"
            data["http_version_seen"] = f"HTTP/{getattr(r.raw, 'version', 11) / 10:.1f}" \
                if isinstance(getattr(r.raw, 'version', None), int) else "HTTP/1.x"
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        # ALPN check for h2
        try:
            import socket, ssl
            c = ssl.create_default_context()
            c.check_hostname = False
            c.verify_mode = ssl.CERT_NONE
            c.set_alpn_protocols(["h2", "http/1.1"])
            with socket.create_connection((host, 443), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host) as ss:
                    data["alpn_negotiated"] = ss.selected_alpn_protocol()
                    data["http2"] = ss.selected_alpn_protocol() == "h2"
        except Exception:
            data["http2"] = "unknown"
        return self.ok(host, data)


@register
class SecurityTxt(Module):
    id, name, category = "securitytxt", "security.txt (RFC 9116)", "Web"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        for path in ("/.well-known/security.txt", "/security.txt"):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and ("Contact" in r.text or "contact" in r.text):
                    fields = dict(re.findall(r"(?im)^([A-Za-z\-]+):\s*(.+)$", r.text))
                    return self.ok(host, {"path": path, "fields": fields})
            except Exception:
                continue
        return self.ok(host, {"security_txt": "not found"})


@register
class TxtFiles(Module):
    id, name, category = "txtfiles", "humans/ads/app-ads.txt", "Web"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        out = {}
        for f in ("humans.txt", "ads.txt", "app-ads.txt"):
            try:
                r = ctx.session.get(f"{base}/{f}", timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) < 100000:
                    out[f] = r.text.strip().splitlines()[:30]
            except Exception:
                continue
        return self.ok(host, {"files": out or "none found"})


@register
class WellKnown(Module):
    id, name, category = "wellknown", ".well-known enumeration", "Web"
    target_kind = "domain"

    _PATHS = ["security.txt", "openid-configuration", "assetlinks.json",
              "apple-app-site-association", "change-password", "mta-sts.txt",
              "host-meta", "nodeinfo", "webfinger", "carddav", "caldav",
              "dnt-policy.txt", "gpc.json", "ai.txt", "matrix/server"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/") + "/.well-known/"
        found = {}

        def probe(p):
            try:
                r = ctx.session.get(base + p, timeout=ctx.timeout, allow_redirects=False)
                if r.status_code < 400:
                    return p, r.status_code
            except Exception:
                return None
            return None
        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(probe, self._PATHS):
                if res:
                    found[res[0]] = res[1]
        return self.ok(host, {"well_known": found or "none found"})


@register
class GraphqlIntrospect(Module):
    id, name, category = "graphql", "GraphQL introspection", "Web"
    target_kind = "url"

    _ENDPOINTS = ["/graphql", "/api/graphql", "/v1/graphql", "/graphql/v1",
                  "/query", "/gql", "/api/gql"]
    _Q = {"query": "{__schema{queryType{name}}}"}

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        out = {}
        for ep in self._ENDPOINTS:
            try:
                r = ctx.session.post(base + ep, json=self._Q, timeout=ctx.timeout)
                if r.status_code == 200 and "__schema" in r.text:
                    out[ep] = "introspection ENABLED (schema exposed)"
                elif r.status_code in (200, 400) and "graphql" in r.text.lower():
                    out[ep] = "GraphQL present (introspection blocked)"
            except Exception:
                continue
        return self.ok(host, {"graphql": out or "no GraphQL endpoint found"})


@register
class WebsocketFind(Module):
    id, name, category = "websocket", "WebSocket endpoints", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host)
        try:
            html = ctx.session.get(base, timeout=ctx.timeout).text
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        ws = set(re.findall(r"""['"](wss?://[^'"]+)['"]""", html))
        ws |= set(re.findall(r"""new\s+WebSocket\(\s*['"]([^'"]+)""", html))
        sockio = "socket.io" in html.lower()
        return self.ok(host, {"websocket_urls": sorted(ws) or "none in HTML",
                              "socket_io_detected": sockio})


@register
class JsLibCve(Module):
    id, name, category = "jslibs", "JS library versions + age flag", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host)
        try:
            html = ctx.session.get(base, timeout=ctx.timeout).text
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(html, "html.parser")
        srcs = [t.get("src", "") for t in soup.find_all("script", src=True)]
        blob = " ".join(srcs) + " " + html
        libs = {}
        for rx, name in _JS_LIB_RE:
            m = rx.search(blob)
            if m:
                ver = m.group(1)
                old = name in _OLD_MAJOR and int(ver.split(".")[0]) < _OLD_MAJOR[name]
                libs[name] = {"version": ver, "outdated_major": bool(old)}
        return self.ok(host, {"libraries": libs or "none fingerprinted",
                              "note": "cross-check versions against retire.js / CVE DBs"})


@register
class CspGrade(Module):
    id, name, category = "cspgrade", "CSP policy grading", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        csp = r.headers.get("content-security-policy", "")
        if not csp:
            return self.ok(host, {"csp": "absent", "grade": "F",
                                  "issues": ["no Content-Security-Policy header"]})
        issues = []
        if "unsafe-inline" in csp:
            issues.append("uses 'unsafe-inline'")
        if "unsafe-eval" in csp:
            issues.append("uses 'unsafe-eval'")
        if re.search(r"(default|script)-src[^;]*\*", csp):
            issues.append("wildcard * in script/default-src")
        if "default-src" not in csp:
            issues.append("no default-src fallback")
        if "object-src" not in csp:
            issues.append("no object-src (plugin sink)")
        score = max(0, 100 - 22 * len(issues))
        grade = ("A" if score >= 90 else "B" if score >= 70 else "C" if score >= 50
                 else "D" if score >= 30 else "F")
        return self.ok(host, {"grade": grade, "score": score,
                              "issues": issues or ["solid policy"],
                              "policy": csp[:300]})


@register
class SriCheck(Module):
    id, name, category = "sri", "Subresource Integrity coverage", "Web"
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
            html = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout).text
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(html, "html.parser")
        ext = []
        for tag in soup.find_all(["script", "link"]):
            url = tag.get("src") or tag.get("href") or ""
            if url.startswith("http") and host not in url:
                ext.append({"url": url[:90], "has_sri": bool(tag.get("integrity"))})
        missing = [e["url"] for e in ext if not e["has_sri"]]
        return self.ok(host, {"external_resources": len(ext),
                              "missing_sri": missing[:25] or "all covered or none external"})


@register
class VhostEnum(Module):
    id, name, category = "vhost", "Virtual-host enumeration", "Web"
    target_kind = "host"

    _NAMES = ["www", "dev", "staging", "test", "admin", "internal", "intranet",
              "api", "old", "beta", "portal", "secure", "vpn", "mail"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import socket
            ip = socket.gethostbyname(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        base_len = None
        try:
            base_len = len(ctx.session.get(f"http://{ip}", timeout=ctx.timeout,
                                           headers={"Host": "nonexistent.invalid"}).text)
        except Exception:
            pass
        hits = {}
        root = ".".join(host.split(".")[-2:])
        for n in self._NAMES:
            vh = f"{n}.{root}"
            try:
                r = ctx.session.get(f"http://{ip}", timeout=ctx.timeout,
                                    headers={"Host": vh}, allow_redirects=False)
                if r.status_code < 400 and (base_len is None or abs(len(r.text) - base_len) > 50):
                    hits[vh] = r.status_code
            except Exception:
                continue
        return self.ok(host, {"ip": ip, "responding_vhosts": hits or "none differed from baseline"})


@register
class ErrorFingerprint(Module):
    id, name, category = "errorpage", "Error-page fingerprint", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        try:
            r = ctx.session.get(f"{base}/ghosteye-404-{hash(host) & 0xffff}",
                                timeout=ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        body = r.text.lower()
        stack = {
            "Apache": "apache", "nginx": "nginx", "IIS": "iis",
            "Tomcat": "apache tomcat", "Express": "cannot get",
            "Flask/Werkzeug": "werkzeug", "Django": "django",
            "Laravel": "laravel", "Spring": "whitelabel error",
            "ASP.NET": "asp.net",
        }
        found = [t for t, sig in stack.items() if sig in body or sig in str(r.headers).lower()]
        return self.ok(host, {"status_for_missing": r.status_code,
                              "custom_404": r.status_code == 404,
                              "stack_hints": found or "none leaked"})
