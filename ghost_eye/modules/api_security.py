"""API security deep-dive modules (features #1-#10). Detection only."""

from __future__ import annotations

import re
import json as _json
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List
from urllib.parse import urljoin, quote

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class GraphQLAudit(Module):
    id, name, category = "gqlaudit", "GraphQL depth/complexity analysis", "API Security"
    target_kind = "url"

    _PATHS = ["/graphql", "/graphiql", "/api/graphql", "/v1/graphql",
              "/query", "/gql", "/api/gql"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        gql_url = None
        for p in self._PATHS:
            try:
                r = ctx.session.post(base + p, timeout=ctx.timeout,
                                     json={"query": "{__typename}"},
                                     headers={"Content-Type": "application/json"})
                if r.status_code == 200 and "data" in r.text:
                    gql_url = base + p
                    findings["endpoint"] = p
                    break
                if r.status_code in (400, 401, 403) and any(
                    kw in r.text.lower() for kw in ["graphql", "query", "syntax"]):
                    gql_url = base + p
                    findings["endpoint"] = p
                    findings["auth_required"] = r.status_code in (401, 403)
                    break
            except Exception:
                continue

        if not gql_url:
            return self.ok(host, {"graphql": "no endpoint found"})

        # introspection check
        intro_query = '{"query":"{__schema{types{name kind fields{name type{name kind ofType{name}}}}}}"}'
        try:
            r = ctx.session.post(gql_url, timeout=ctx.timeout + 10,
                                 data=intro_query,
                                 headers={"Content-Type": "application/json"})
            if r.status_code == 200:
                j = r.json()
                if "data" in j and j["data"].get("__schema"):
                    types = j["data"]["__schema"].get("types", [])
                    user_types = [t for t in types
                                  if not t["name"].startswith("__")]
                    mutations = [t for t in types if t["name"] == "Mutation"]
                    findings["introspection"] = "ENABLED"
                    findings["type_count"] = len(user_types)
                    findings["types"] = [t["name"] for t in user_types[:20]]
                    if mutations and mutations[0].get("fields"):
                        findings["mutations"] = [
                            f["name"] for f in mutations[0]["fields"][:15]]
                    findings["risk"] = "HIGH"
                else:
                    findings["introspection"] = "disabled or partial"
        except Exception:
            findings["introspection"] = "error"

        # batch query check
        try:
            batch = [{"query": "{__typename}"}, {"query": "{__typename}"}]
            r = ctx.session.post(gql_url, timeout=ctx.timeout,
                                 json=batch,
                                 headers={"Content-Type": "application/json"})
            if r.status_code == 200:
                try:
                    j = r.json()
                    if isinstance(j, list) and len(j) == 2:
                        findings["batching"] = "ENABLED (DoS risk)"
                except Exception:
                    pass
        except Exception:
            pass

        # depth check
        deep = '{"query":"{__schema{types{fields{type{ofType{ofType{ofType{name}}}}}}}}"}'
        try:
            r = ctx.session.post(gql_url, timeout=ctx.timeout,
                                 data=deep,
                                 headers={"Content-Type": "application/json"})
            if r.status_code == 200 and "errors" not in r.text.lower():
                findings["depth_limit"] = "NONE detected (DoS risk)"
            elif "depth" in r.text.lower() or "complexity" in r.text.lower():
                findings["depth_limit"] = "enforced"
        except Exception:
            pass

        if "risk" not in findings:
            findings["risk"] = "MEDIUM" if findings.get("endpoint") else "informational"

        return self.ok(host, findings)


@register
class RestApiFuzzSurface(Module):
    id, name, category = "restfuzz", "REST API fuzzing surface", "API Security"
    target_kind = "url"

    _PATTERNS = [
        "/api/v1/users/1", "/api/v1/users/2", "/api/users/1",
        "/api/v1/items/1", "/api/v1/orders/1",
        "/api/v1/admin", "/api/v1/internal",
        "/api/v1/debug", "/api/v1/test",
        "/api/v1/config", "/api/v1/settings",
        "/api/v1/export", "/api/v1/download",
        "/api/v1/upload", "/api/v1/import",
        "/api/v1/search", "/api/v1/status",
        "/api/v2/users/1", "/api/v2/items/1",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}
        idor_candidates = []

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code < 400:
                    ct = r.headers.get("Content-Type", "")
                    info = {"status": r.status_code, "size": len(r.content),
                            "json": "json" in ct}
                    if any(p in path for p in ["/1", "/2"]):
                        idor_candidates.append(path)
                    return path, info
                if r.status_code in (401, 403):
                    return path, {"status": r.status_code,
                                  "note": "exists, auth required"}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 8)) as ex:
            for path, info in ex.map(probe, self._PATTERNS):
                if info:
                    found[path] = info

        # IDOR: compare /1 vs /2 responses
        idor_findings = []
        if idor_candidates:
            for path in idor_candidates:
                if "/1" in path:
                    path2 = path.replace("/1", "/2")
                    if path2 in found:
                        s1 = found[path].get("size", 0)
                        s2 = found.get(path2, {}).get("size", 0)
                        if s1 > 0 and s2 > 0 and s1 != s2:
                            idor_findings.append({
                                "pattern": path.rsplit("/", 1)[0] + "/<id>",
                                "note": "different response sizes for sequential IDs"
                            })

        risk = "informational"
        if idor_findings:
            risk = "HIGH"
        elif found:
            risk = "MEDIUM"

        return self.ok(host, {
            "endpoints": found or "none found",
            "idor_surface": idor_findings or "none",
            "risk": risk,
        })


@register
class WebSocketAudit(Module):
    id, name, category = "wsaudit", "WebSocket handshake audit", "API Security"
    target_kind = "url"

    _PATHS = ["/ws", "/websocket", "/socket", "/socket.io/",
              "/ws/v1", "/api/ws", "/realtime", "/live",
              "/cable", "/hub", "/signalr"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        # check homepage for WebSocket indicators
        ws_indicators = []
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:80_000].lower()
            for kw in ["websocket", "ws://", "wss://", "socket.io",
                        "sockjs", "signalr", "actioncable", "phoenix.socket"]:
                if kw in body:
                    ws_indicators.append(kw)
        except Exception:
            pass

        # probe WS upgrade paths
        headers = {
            "Upgrade": "websocket",
            "Connection": "Upgrade",
            "Sec-WebSocket-Key": "dGhlIHNhbXBsZSBub25jZQ==",
            "Sec-WebSocket-Version": "13",
        }

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    headers=headers, allow_redirects=False)
                if r.status_code == 101:
                    return path, {"status": 101, "note": "WebSocket upgrade accepted",
                                  "origin_check": "missing" if "origin" not in
                                  str(r.headers).lower() else "present"}
                if r.status_code == 200 and any(
                    kw in r.text.lower()[:5000]
                    for kw in ["websocket", "socket.io", "sid"]):
                    return path, {"status": 200, "note": "WebSocket endpoint detected"}
                if r.status_code == 400 and "upgrade" in r.text.lower():
                    return path, {"status": 400,
                                  "note": "expects WebSocket upgrade"}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info

        return self.ok(host, {
            "websocket_endpoints": found or "none found",
            "js_indicators": ws_indicators or "none",
            "risk": "MEDIUM" if found else "informational",
        })


@register
class SseDetect(Module):
    id, name, category = "ssedetect", "Server-Sent Events detection", "API Security"
    target_kind = "url"

    _PATHS = ["/events", "/sse", "/stream", "/api/events",
              "/api/stream", "/api/sse", "/api/v1/events",
              "/notifications/stream", "/updates"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=min(ctx.timeout, 5),
                                    stream=True,
                                    headers={"Accept": "text/event-stream"})
                ct = r.headers.get("Content-Type", "")
                if "text/event-stream" in ct:
                    chunk = next(r.iter_content(512), b"")
                    r.close()
                    return path, {"type": "SSE", "status": r.status_code,
                                  "has_data": len(chunk) > 0}
                r.close()
                if r.status_code in (401, 403):
                    return path, {"status": r.status_code,
                                  "note": "stream endpoint, auth required"}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 5)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info

        return self.ok(host, {
            "sse_endpoints": found or "none found",
            "risk": "LOW" if found else "informational",
        })


@register
class ApiVersionDetect(Module):
    id, name, category = "apiver", "API versioning & deprecation detection", "API Security"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        versions = {}

        paths = [f"/api/v{i}" for i in range(1, 8)] + \
                [f"/v{i}" for i in range(1, 8)] + \
                [f"/api/v{i}/status" for i in range(1, 5)] + \
                [f"/api/v{i}/health" for i in range(1, 5)]

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code < 404:
                    return path, {"status": r.status_code,
                                  "size": len(r.content)}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 8)) as ex:
            for path, info in ex.map(probe, paths):
                if info:
                    versions[path] = info

        # detect deprecated headers
        deprecated = []
        for path in list(versions.keys())[:3]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                for h in ["Deprecation", "Sunset", "X-API-Deprecated",
                           "X-API-Warn", "Warning"]:
                    if h.lower() in {k.lower() for k in r.headers}:
                        deprecated.append({"path": path, "header": h,
                                           "value": r.headers.get(h, "")[:100]})
            except Exception:
                continue

        active_versions = sorted(set(
            re.findall(r'/v(\d+)', " ".join(versions.keys()))))

        risk = "informational"
        if len(active_versions) > 2:
            risk = "MEDIUM"
        if deprecated:
            risk = "HIGH"

        return self.ok(host, {
            "active_versions": active_versions or "none",
            "endpoints": versions or "none",
            "deprecated_indicators": deprecated or "none",
            "risk": risk,
            "note": "old API versions may lack security fixes"
        })


@register
class PreflightCheck(Module):
    id, name, category = "preflightcheck", "CORS preflight deep analysis", "API Security"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        results = {}

        origins = [
            "https://evil.com",
            "https://null",
            f"https://{host}.evil.com",
            f"https://evil.{host}",
            "https://localhost",
            "https://127.0.0.1",
        ]

        methods = ["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"]

        for origin in origins:
            try:
                r = ctx.session.options(base, timeout=ctx.timeout, headers={
                    "Origin": origin,
                    "Access-Control-Request-Method": "POST",
                    "Access-Control-Request-Headers": "Authorization, Content-Type",
                })
                acao = r.headers.get("Access-Control-Allow-Origin", "")
                acam = r.headers.get("Access-Control-Allow-Methods", "")
                acah = r.headers.get("Access-Control-Allow-Headers", "")
                acac = r.headers.get("Access-Control-Allow-Credentials", "")
                if acao:
                    results[origin] = {
                        "allow_origin": acao,
                        "allow_methods": acam,
                        "allow_headers": acah[:100],
                        "allow_credentials": acac,
                    }
                    if acao == "*" and acac.lower() == "true":
                        results[origin]["vulnerability"] = "wildcard + credentials"
                    if origin == "https://evil.com" and acao == origin:
                        results[origin]["vulnerability"] = "reflects arbitrary origin"
            except Exception:
                continue

        risk = "informational"
        for info in results.values():
            if info.get("vulnerability"):
                risk = "HIGH"
                break
            if info.get("allow_origin"):
                risk = "MEDIUM"

        return self.ok(host, {
            "preflight_results": results or "no CORS headers",
            "risk": risk,
        })


@register
class ContentNegotiation(Module):
    id, name, category = "contentneg", "Content negotiation audit", "API Security"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        results = {}

        accept_types = [
            ("application/json", "JSON"),
            ("application/xml", "XML"),
            ("text/xml", "XML (text)"),
            ("application/x-yaml", "YAML"),
            ("text/csv", "CSV"),
            ("application/ld+json", "JSON-LD"),
            ("application/msgpack", "MessagePack"),
            ("application/protobuf", "Protobuf"),
        ]

        for accept, label in accept_types:
            try:
                r = ctx.session.get(base, timeout=ctx.timeout,
                                    headers={"Accept": accept})
                ct = r.headers.get("Content-Type", "")
                if accept.split("/")[1] in ct.lower():
                    results[label] = {
                        "accepted": True,
                        "content_type": ct[:80],
                        "status": r.status_code,
                    }
            except Exception:
                continue

        return self.ok(host, {
            "supported_formats": results or "only default format",
            "risk": "LOW" if len(results) > 3 else "informational",
            "note": "multiple formats may expose serialization vulnerabilities"
        })


@register
class HateoasDiscovery(Module):
    id, name, category = "hateoas", "HATEOAS/hypermedia link discovery", "API Security"
    target_kind = "url"

    _API_PATHS = ["/api", "/api/v1", "/api/v2", "/v1", "/v2",
                  "/api/v1/", "/api/v2/"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        links_found = {}

        def extract_links(body, source):
            links = set()
            try:
                j = _json.loads(body)
                self._walk_links(j, links)
            except Exception:
                pass
            link_header = []
            return links

        def _walk(obj, links, depth=0):
            if depth > 5:
                return
            if isinstance(obj, dict):
                for k, v in obj.items():
                    if k in ("href", "url", "link", "uri", "self", "next",
                             "prev", "first", "last"):
                        if isinstance(v, str) and ("/" in v or "http" in v):
                            links.add(v[:200])
                    if isinstance(v, (dict, list)):
                        _walk(v, links, depth + 1)
            if isinstance(obj, list):
                for item in obj[:20]:
                    _walk(item, links, depth + 1)

        self._walk_links = _walk

        for path in self._API_PATHS:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    headers={"Accept": "application/json"})
                if r.status_code != 200:
                    continue
                links = set()
                _walk(r.json() if "json" in r.headers.get("Content-Type", "")
                      else {}, links)
                # also check Link header
                link_header = r.headers.get("Link", "")
                if link_header:
                    for m in re.finditer(r'<([^>]+)>', link_header):
                        links.add(m.group(1)[:200])
                if links:
                    links_found[path] = sorted(links)[:20]
            except Exception:
                continue

        return self.ok(host, {
            "hypermedia_links": links_found or "no HATEOAS links found",
            "risk": "LOW" if links_found else "informational",
        })


@register
class WebhookFinder(Module):
    id, name, category = "webhookfind", "Webhook endpoint discovery", "API Security"
    target_kind = "url"

    _PATHS = [
        "/webhook", "/webhooks", "/api/webhook", "/api/webhooks",
        "/callback", "/callbacks", "/api/callback",
        "/notify", "/api/notify", "/api/notifications",
        "/hook", "/hooks", "/api/hooks",
        "/events/webhook", "/integrations/webhook",
        "/api/v1/webhook", "/api/v1/webhooks",
        "/api/v1/hooks", "/incoming-webhook",
        "/stripe/webhook", "/payment/webhook",
        "/github/webhook", "/gitlab/webhook",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(path):
            for method in [ctx.session.get, ctx.session.post]:
                try:
                    kw = {"timeout": ctx.timeout, "allow_redirects": False}
                    if method == ctx.session.post:
                        kw["json"] = {}
                        kw["headers"] = {"Content-Type": "application/json"}
                    r = method(base + path, **kw)
                    if r.status_code < 405:
                        mname = "POST" if method == ctx.session.post else "GET"
                        return path, {"status": r.status_code, "method": mname,
                                      "size": len(r.content)}
                except Exception:
                    continue
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 8)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info

        return self.ok(host, {
            "webhook_endpoints": found or "none found",
            "risk": "MEDIUM" if found else "informational",
            "note": "exposed webhooks may allow event injection or SSRF"
        })


@register
class IdorSurface(Module):
    id, name, category = "idorsurface", "IDOR surface detection", "API Security"
    target_kind = "url"

    _PATTERNS = [
        "/api/v1/users/{id}", "/api/v1/orders/{id}",
        "/api/v1/invoices/{id}", "/api/v1/documents/{id}",
        "/api/v1/accounts/{id}", "/api/v1/files/{id}",
        "/api/users/{id}", "/api/orders/{id}",
        "/api/files/{id}", "/api/data/{id}",
        "/user/{id}", "/profile/{id}", "/account/{id}",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        idor_risks = []

        def probe(pattern):
            results = {}
            for test_id in ["1", "2", "100", "999"]:
                path = pattern.replace("{id}", test_id)
                try:
                    r = ctx.session.get(base + path, timeout=ctx.timeout,
                                        allow_redirects=False)
                    results[test_id] = {
                        "status": r.status_code,
                        "size": len(r.content),
                    }
                except Exception:
                    continue
            statuses = [v["status"] for v in results.values()]
            if any(s == 200 for s in statuses):
                sizes = [v["size"] for v in results.values() if v["status"] == 200]
                return pattern, {
                    "accessible": True,
                    "responses": results,
                    "varying_size": len(set(sizes)) > 1 if sizes else False,
                }
            if any(s in (401, 403) for s in statuses):
                return pattern, {"accessible": False, "auth_required": True}
            return pattern, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 5)) as ex:
            for pattern, info in ex.map(probe, self._PATTERNS):
                if info and info.get("accessible"):
                    idor_risks.append({"pattern": pattern, **info})

        risk = "informational"
        if any(r.get("varying_size") for r in idor_risks):
            risk = "HIGH"
        elif idor_risks:
            risk = "MEDIUM"

        return self.ok(host, {
            "idor_candidates": idor_risks or "none found",
            "risk": risk,
            "note": "sequential ID access with varying responses indicates IDOR"
        })
