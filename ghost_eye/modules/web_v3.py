"""Web advanced modules v3 (features #40-#50). Detection only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class WasmDetect(Module):
    id, name, category = "wasmdetect", "WebAssembly module detection", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"wasm_files": [], "indicators": []}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:100_000]

            wasm_refs = re.findall(r'["\']([^"\']*\.wasm)["\']', body)
            findings["wasm_files"] = list(set(wasm_refs))[:10]

            for kw in ["WebAssembly", "wasm_exec", "Go.run", "emscripten",
                        "Module.asm", "wasmBinary", "instantiateStreaming"]:
                if kw in body:
                    findings["indicators"].append(kw)

            scripts = re.findall(r'src=["\']([^"\']+\.js[^"\']*)["\']', body)
            for src in scripts[:8]:
                if src.startswith("/"):
                    src = base + src
                elif not src.startswith("http"):
                    continue
                try:
                    jr = ctx.session.get(src, timeout=ctx.timeout)
                    js = jr.text[:50_000]
                    wasm = re.findall(r'["\']([^"\']*\.wasm)["\']', js)
                    findings["wasm_files"].extend(wasm)
                except Exception:
                    continue

            findings["wasm_files"] = sorted(set(findings["wasm_files"]))[:10]
        except Exception:
            pass

        if not findings["wasm_files"]:
            findings["wasm_files"] = "none"
        if not findings["indicators"]:
            findings["indicators"] = "none"

        return self.ok(host, findings)


@register
class ServiceWorkerAudit(Module):
    id, name, category = "swaudit", "Service Worker audit", "Web"
    target_kind = "url"

    _PATHS = ["/sw.js", "/service-worker.js", "/serviceworker.js",
              "/firebase-messaging-sw.js", "/ngsw-worker.js",
              "/precache-manifest.js", "/workbox-sw.js"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # check registration in main page
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:50_000]
            reg = re.search(r"(?:navigator\.serviceWorker\.register|registerServiceWorker)\s*\(\s*['\"]([^'\"]+)", body)
            if reg:
                findings["registered"] = reg.group(1)
        except Exception:
            pass

        # probe SW files
        for path in self._PATHS:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 50:
                    body = r.text[:30_000]
                    info = {"path": path, "size": len(r.content)}
                    if "fetch" in body.lower():
                        info["intercepts_fetch"] = True
                    if "cache" in body.lower():
                        info["uses_cache"] = True
                    if "push" in body.lower():
                        info["push_notifications"] = True
                    urls = re.findall(r'["\'](/[^"\']{3,60})["\']', body)
                    info["cached_urls"] = sorted(set(urls))[:15]
                    findings[path] = info
            except Exception:
                continue

        return self.ok(host, {
            "service_workers": findings or "none found",
            "risk": "LOW" if findings else "informational",
        })


@register
class PwaCheck(Module):
    id, name, category = "pwacheck", "Progressive Web App check", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # manifest
        for path in ["/manifest.json", "/site.webmanifest", "/manifest.webmanifest"]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200:
                    try:
                        j = r.json()
                        findings["manifest"] = {
                            "name": j.get("name", ""),
                            "short_name": j.get("short_name", ""),
                            "start_url": j.get("start_url", ""),
                            "display": j.get("display", ""),
                            "theme_color": j.get("theme_color", ""),
                            "icons": len(j.get("icons", [])),
                            "scope": j.get("scope", ""),
                        }
                    except Exception:
                        findings["manifest"] = {"path": path, "parse_error": True}
                    break
            except Exception:
                continue

        # check main page for manifest link and meta tags
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:20_000]
            if 'rel="manifest"' in body:
                findings["manifest_linked"] = True
            if "theme-color" in body:
                findings["theme_color_meta"] = True
            if "apple-touch-icon" in body:
                findings["apple_touch_icon"] = True
        except Exception:
            pass

        return self.ok(host, findings or {"pwa": "not a PWA"})


@register
class Http3Check(Module):
    id, name, category = "http3check", "HTTP/3 QUIC support detection", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            alt_svc = r.headers.get("Alt-Svc", "")
            if alt_svc:
                findings["alt_svc"] = alt_svc[:200]
                if "h3" in alt_svc:
                    findings["http3"] = True
                    findings["protocols"] = re.findall(r'(h3(?:-\d+)?)', alt_svc)
                if "h2" in alt_svc:
                    findings["http2_alt"] = True
        except Exception:
            pass

        return self.ok(host, findings or {"http3": "not advertised"})


@register
class PermissionsPolicy(Module):
    id, name, category = "permspolicy", "Permissions-Policy audit", "Web"
    target_kind = "url"

    _FEATURES = ["camera", "microphone", "geolocation", "payment",
                 "usb", "bluetooth", "midi", "magnetometer",
                 "accelerometer", "gyroscope", "autoplay",
                 "fullscreen", "picture-in-picture"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            pp = r.headers.get("Permissions-Policy", "")
            fp = r.headers.get("Feature-Policy", "")
            policy = pp or fp
        except Exception as e:
            return self.fail(host, str(e)[:80])

        if not policy:
            return self.ok(host, {
                "permissions_policy": "MISSING",
                "risk": "MEDIUM",
                "note": "no Permissions-Policy header — browser features unrestricted"
            })

        parsed = {}
        for directive in policy.split(","):
            directive = directive.strip()
            if "=" in directive:
                feat, val = directive.split("=", 1)
                parsed[feat.strip()] = val.strip()

        unrestricted = [f for f in self._FEATURES if f not in parsed]
        return self.ok(host, {
            "policy": parsed,
            "header": "Permissions-Policy" if pp else "Feature-Policy (deprecated)",
            "unrestricted_features": unrestricted,
            "risk": "LOW" if len(unrestricted) < 5 else "MEDIUM",
        })


@register
class ReferrerPolicy(Module):
    id, name, category = "referrerpol", "Referrer-Policy audit", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            rp = r.headers.get("Referrer-Policy", "")
            # also check meta tag
            meta = re.search(r'<meta\s+name=["\']referrer["\']\s+content=["\']([^"\']+)',
                             r.text[:10_000], re.I)
        except Exception as e:
            return self.fail(host, str(e)[:80])

        policy = rp or (meta.group(1) if meta else "")
        safe = ["no-referrer", "same-origin", "strict-origin",
                "strict-origin-when-cross-origin"]

        risk = "informational"
        if not policy:
            risk = "MEDIUM"
        elif policy.lower() in ["unsafe-url", "no-referrer-when-downgrade"]:
            risk = "HIGH"
        elif policy.lower() not in safe:
            risk = "LOW"

        return self.ok(host, {
            "referrer_policy": policy or "MISSING",
            "source": "header" if rp else "meta" if meta else "none",
            "risk": risk,
        })


@register
class CoopCoep(Module):
    id, name, category = "coopcoep", "COOP/COEP/CORP header audit", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
        except Exception as e:
            return self.fail(host, str(e)[:80])

        coop = r.headers.get("Cross-Origin-Opener-Policy", "")
        coep = r.headers.get("Cross-Origin-Embedder-Policy", "")
        corp = r.headers.get("Cross-Origin-Resource-Policy", "")

        cross_origin_isolated = (
            coop.lower() == "same-origin" and
            coep.lower() in ("require-corp", "credentialless"))

        return self.ok(host, {
            "coop": coop or "not set",
            "coep": coep or "not set",
            "corp": corp or "not set",
            "cross_origin_isolated": cross_origin_isolated,
            "risk": "informational",
            "note": "cross-origin isolation protects against Spectre-class attacks"
        })


@register
class CachePoisonSurface(Module):
    id, name, category = "cachpoison", "Cache poisoning surface", "Web"
    target_kind = "url"

    _UNKEYED_HEADERS = [
        "X-Forwarded-Host", "X-Forwarded-Scheme", "X-Original-URL",
        "X-Rewrite-URL", "X-Forwarded-Port", "X-Host",
        "X-Forwarded-Server", "X-HTTP-Method-Override",
        "X-Custom-IP-Authorization",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # get baseline
        try:
            baseline = ctx.session.get(base, timeout=ctx.timeout)
            baseline_len = len(baseline.content)
            cache_headers = {}
            for h in ["Cache-Control", "Vary", "Age", "X-Cache",
                       "X-Cache-Hits", "CF-Cache-Status", "X-Varnish"]:
                val = baseline.headers.get(h, "")
                if val:
                    cache_headers[h] = val
            findings["cache_headers"] = cache_headers or "none"
        except Exception as e:
            return self.fail(host, str(e)[:80])

        # test unkeyed headers
        reflected = []
        for header in self._UNKEYED_HEADERS:
            try:
                r = ctx.session.get(base, timeout=ctx.timeout,
                                    headers={header: "ghosteye-poison-test.com"})
                if "ghosteye-poison-test.com" in r.text:
                    reflected.append(header)
            except Exception:
                continue

        findings["reflected_headers"] = reflected or "none"
        risk = "informational"
        if reflected:
            risk = "HIGH"
        elif cache_headers:
            risk = "LOW"

        findings["risk"] = risk
        return self.ok(host, findings)


@register
class HostHeaderInjection(Module):
    id, name, category = "hostheader", "Host header injection", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        tests = [
            ("evil.com", "arbitrary host"),
            (f"{host}.evil.com", "host suffix"),
            (f"evil.com/{host}", "host with path"),
        ]

        for test_host, label in tests:
            try:
                r = ctx.session.get(base, timeout=ctx.timeout,
                                    headers={"Host": test_host},
                                    allow_redirects=False)
                if test_host in r.text:
                    findings[label] = {
                        "reflected": True,
                        "status": r.status_code,
                        "risk": "HIGH",
                    }
                loc = r.headers.get("Location", "")
                if test_host in loc:
                    findings[label + " (redirect)"] = {
                        "redirected_to": loc[:200],
                        "risk": "HIGH",
                    }
            except Exception:
                continue

        # X-Forwarded-Host
        try:
            r = ctx.session.get(base, timeout=ctx.timeout,
                                headers={"X-Forwarded-Host": "evil.com"},
                                allow_redirects=False)
            if "evil.com" in r.text or "evil.com" in r.headers.get("Location", ""):
                findings["x-forwarded-host"] = {"reflected": True, "risk": "HIGH"}
        except Exception:
            pass

        risk = "HIGH" if findings else "informational"
        return self.ok(host, {
            "host_injection": findings or "not vulnerable",
            "risk": risk,
        })


@register
class HttpDesync(Module):
    id, name, category = "httpdesync", "HTTP desync/smuggling indicators", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            # check for proxy chain indicators
            via = r.headers.get("Via", "")
            if via:
                findings["via_header"] = via[:100]
                proxies = via.count(",") + 1
                findings["proxy_chain_length"] = proxies

            # check for Transfer-Encoding handling
            server = r.headers.get("Server", "")
            findings["server"] = server[:60]

            # check for connection-specific headers
            for h in ["Keep-Alive", "Connection", "Transfer-Encoding"]:
                val = r.headers.get(h, "")
                if val:
                    findings[h.lower().replace("-", "_")] = val

        except Exception as e:
            return self.fail(host, str(e)[:80])

        # send ambiguous Content-Length
        try:
            r = ctx.session.post(base, timeout=ctx.timeout,
                                  data="0",
                                  headers={
                                      "Content-Type": "application/x-www-form-urlencoded",
                                      "Content-Length": "0",
                                      "Transfer-Encoding": "chunked",
                                  })
            findings["te_cl_response"] = r.status_code
            if r.status_code == 400:
                findings["te_cl_note"] = "server rejects ambiguous request (good)"
        except Exception:
            findings["te_cl_note"] = "connection error (may indicate filtering)"

        risk = "informational"
        if findings.get("proxy_chain_length", 0) > 1:
            risk = "LOW"

        return self.ok(host, {
            "desync_surface": findings,
            "risk": risk,
        })


@register
class MimeSniff(Module):
    id, name, category = "mimesniff", "MIME sniffing audit", "Web"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
        except Exception as e:
            return self.fail(host, str(e)[:80])

        xcto = r.headers.get("X-Content-Type-Options", "")
        ct = r.headers.get("Content-Type", "")

        risk = "informational"
        if not xcto:
            risk = "MEDIUM"
        elif xcto.lower() != "nosniff":
            risk = "LOW"

        findings = {
            "x_content_type_options": xcto or "MISSING",
            "content_type": ct,
            "charset": "charset" in ct.lower() if ct else False,
            "risk": risk,
        }

        if not xcto:
            findings["note"] = "missing X-Content-Type-Options allows MIME sniffing attacks"

        return self.ok(host, findings)
