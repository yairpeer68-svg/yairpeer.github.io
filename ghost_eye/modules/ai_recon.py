"""AI / LLM API exposure detection. Reconnaissance only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class DeepSeekApiScan(Module):
    id, name, category = "deepseek", "DeepSeek AI API exposure scan", "AI/LLM"
    target_kind = "url"

    _DEEPSEEK_PATHS = [
        "/v1/chat/completions",
        "/v1/completions",
        "/v1/models",
        "/v1/embeddings",
        "/v1/files",
        "/v1/fine_tuning/jobs",
        "/v1/images/generations",
        "/v1/audio/transcriptions",
        "/v1/balance",
        "/v1/dashboard/billing/usage",
        "/v1/dashboard/billing/subscription",
        "/api/v1/chat/completions",
        "/api/v1/models",
        "/deepseek/v1/chat/completions",
        "/deepseek/v1/models",
        "/api/deepseek/chat",
        "/api/deepseek/models",
        "/api/ai/chat",
        "/api/ai/completions",
        "/api/llm/chat",
        "/api/llm/completions",
    ]

    _KEY_PATTERNS = [
        re.compile(r"sk-[a-f0-9]{48}", re.I),
        re.compile(r"sk-[a-zA-Z0-9]{32,}"),
        re.compile(r"deepseek[_-]?(?:api[_-]?)?key\s*[:=]\s*['\"]?([a-zA-Z0-9_\-]{20,})", re.I),
    ]

    _INDICATOR_HEADERS = [
        "x-deepseek", "x-ds-", "x-llm-", "x-ai-model", "x-model-id",
        "x-request-id", "x-ratelimit-limit-requests",
        "x-ratelimit-remaining-requests", "x-ratelimit-limit-tokens",
        "x-ratelimit-remaining-tokens",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings: Dict = {
            "exposed_endpoints": {},
            "api_key_leaks": [],
            "deepseek_indicators": [],
            "proxy_detected": False,
            "model_info": None,
            "risk": "informational",
        }

        # 1 — probe homepage for DeepSeek indicators
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:50_000]
            hdrs = {k.lower(): v for k, v in r.headers.items()}
            for h in self._INDICATOR_HEADERS:
                for hk, hv in hdrs.items():
                    if h in hk:
                        findings["deepseek_indicators"].append(
                            {"header": hk, "value": hv[:120]})
            if "deepseek" in body.lower():
                findings["deepseek_indicators"].append(
                    {"note": "page body references DeepSeek"})
            for pat in self._KEY_PATTERNS:
                for m in pat.finditer(body):
                    leak = m.group(0)
                    masked = leak[:8] + "…" + leak[-4:]
                    findings["api_key_leaks"].append(masked)
        except Exception:
            pass

        # 2 — probe DeepSeek API paths
        def probe(path):
            url = base + path
            try:
                r = ctx.session.get(url, timeout=ctx.timeout,
                                    allow_redirects=False)
                info = {"status": r.status_code, "size": len(r.content)}
                ct = r.headers.get("Content-Type", "")
                if "json" in ct:
                    info["json"] = True
                    try:
                        j = r.json()
                        if isinstance(j, dict):
                            if "error" in j:
                                info["error_type"] = str(j["error"].get("type", ""))[:60] \
                                    if isinstance(j["error"], dict) else str(j["error"])[:60]
                            if "data" in j and isinstance(j["data"], list):
                                info["models"] = [
                                    m.get("id", "") for m in j["data"][:10]
                                    if isinstance(m, dict)]
                            if "model" in j:
                                info["model"] = str(j["model"])[:60]
                    except Exception:
                        pass
                resp_hdrs = {k.lower(): v for k, v in r.headers.items()}
                for h in self._INDICATOR_HEADERS:
                    for hk, hv in resp_hdrs.items():
                        if h in hk:
                            info.setdefault("ai_headers", {})[hk] = hv[:80]
                for pat in self._KEY_PATTERNS:
                    for m in pat.finditer(r.text[:20_000]):
                        leak = m.group(0)
                        info["key_leak"] = leak[:8] + "…" + leak[-4:]
                if r.status_code < 400:
                    return path, info
                if r.status_code in (401, 403) and info.get("json"):
                    info["note"] = "endpoint exists, auth required"
                    return path, info
                if r.status_code == 429:
                    info["note"] = "rate-limited (endpoint exists)"
                    return path, info
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 8)) as ex:
            for path, info in ex.map(probe, self._DEEPSEEK_PATHS):
                if info:
                    findings["exposed_endpoints"][path] = info

        # 3 — check /v1/models for model listing (unauthenticated)
        models_ep = findings["exposed_endpoints"].get("/v1/models") or \
                    findings["exposed_endpoints"].get("/api/v1/models")
        if models_ep and models_ep.get("status") == 200:
            models = models_ep.get("models", [])
            ds_models = [m for m in models if "deepseek" in m.lower()]
            if ds_models:
                findings["model_info"] = ds_models
                findings["proxy_detected"] = True

        # 4 — try a lightweight POST probe on chat endpoint (empty body → error shape)
        for chat_path in ["/v1/chat/completions", "/api/v1/chat/completions"]:
            try:
                r = ctx.session.post(base + chat_path, timeout=ctx.timeout,
                                     json={}, headers={"Content-Type": "application/json"})
                if r.status_code in (400, 401, 403, 422, 429):
                    try:
                        j = r.json()
                        err = j.get("error", {})
                        err_msg = err.get("message", "") if isinstance(err, dict) else str(err)
                        if any(kw in err_msg.lower() for kw in
                               ["deepseek", "model", "api_key", "authentication",
                                "token", "messages", "required"]):
                            findings["exposed_endpoints"][chat_path + " (POST)"] = {
                                "status": r.status_code,
                                "error_message": err_msg[:200],
                                "note": "chat completions endpoint responds to POST"
                            }
                    except Exception:
                        pass
            except Exception:
                continue

        # 5 — check for common reverse-proxy / gateway paths
        proxy_paths = [
            "/deepseek", "/api/deepseek", "/llm/deepseek", "/ai/deepseek",
            "/proxy/deepseek", "/gateway/deepseek",
        ]
        for pp in proxy_paths:
            try:
                r = ctx.session.get(base + pp, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code < 404:
                    findings["exposed_endpoints"][pp] = {
                        "status": r.status_code, "size": len(r.content),
                        "note": "possible DeepSeek proxy/gateway",
                    }
                    findings["proxy_detected"] = True
            except Exception:
                continue

        # 6 — JS source scan for DeepSeek API keys / config
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            scripts = re.findall(r'src=["\']([^"\']+\.js[^"\']*)["\']', r.text)
            for src in scripts[:10]:
                if src.startswith("//"):
                    src = "https:" + src
                elif src.startswith("/"):
                    src = base + src
                elif not src.startswith("http"):
                    src = base + "/" + src
                try:
                    jr = ctx.session.get(src, timeout=ctx.timeout)
                    js = jr.text[:100_000]
                    if "deepseek" in js.lower():
                        findings["deepseek_indicators"].append(
                            {"js_file": src.split("/")[-1][:60],
                             "note": "references DeepSeek"})
                    for pat in self._KEY_PATTERNS:
                        for m in pat.finditer(js):
                            leak = m.group(0)
                            findings["api_key_leaks"].append(
                                leak[:8] + "…" + leak[-4:])
                    if re.search(r"api\.deepseek\.com", js, re.I):
                        findings["deepseek_indicators"].append(
                            {"js_file": src.split("/")[-1][:60],
                             "note": "contains api.deepseek.com URL"})
                except Exception:
                    continue
        except Exception:
            pass

        # deduplicate
        findings["api_key_leaks"] = sorted(set(findings["api_key_leaks"]))

        # risk assessment
        if findings["api_key_leaks"]:
            findings["risk"] = "CRITICAL"
        elif any(ep.get("status") == 200
                 for ep in findings["exposed_endpoints"].values()):
            findings["risk"] = "HIGH"
        elif findings["proxy_detected"]:
            findings["risk"] = "HIGH"
        elif findings["exposed_endpoints"]:
            findings["risk"] = "MEDIUM"
        elif findings["deepseek_indicators"]:
            findings["risk"] = "LOW"

        if not findings["deepseek_indicators"]:
            findings["deepseek_indicators"] = "none"
        if not findings["exposed_endpoints"]:
            findings["exposed_endpoints"] = "none found"
        if not findings["api_key_leaks"]:
            findings["api_key_leaks"] = "none"

        return self.ok(host, findings)


@register
class AiApiExposure(Module):
    id, name, category = "aiapi", "AI/LLM API exposure (multi-provider)", "AI/LLM"
    target_kind = "url"

    _PROVIDERS = {
        "DeepSeek": {
            "paths": ["/v1/models", "/v1/chat/completions"],
            "indicators": ["deepseek", "api.deepseek.com"],
        },
        "OpenAI": {
            "paths": ["/v1/models", "/v1/chat/completions", "/v1/embeddings"],
            "indicators": ["openai", "api.openai.com", "x-openai"],
        },
        "Anthropic": {
            "paths": ["/v1/messages", "/v1/complete"],
            "indicators": ["anthropic", "api.anthropic.com", "x-anthropic"],
        },
        "Google Gemini": {
            "paths": ["/v1beta/models", "/v1/models:generateContent"],
            "indicators": ["generativelanguage.googleapis.com", "gemini"],
        },
        "Ollama": {
            "paths": ["/api/tags", "/api/generate", "/api/chat", "/api/show"],
            "indicators": ["ollama"],
        },
        "LiteLLM": {
            "paths": ["/v1/models", "/v1/chat/completions", "/health"],
            "indicators": ["litellm"],
        },
        "vLLM": {
            "paths": ["/v1/models", "/v1/completions", "/health"],
            "indicators": ["vllm"],
        },
        "LocalAI": {
            "paths": ["/v1/models", "/models/available"],
            "indicators": ["localai"],
        },
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        detected = {}

        # collect all unique paths
        all_paths = {}
        for prov, cfg in self._PROVIDERS.items():
            for p in cfg["paths"]:
                all_paths.setdefault(p, []).append(prov)

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code >= 500:
                    return path, None
                info = {"status": r.status_code, "size": len(r.content)}
                ct = r.headers.get("Content-Type", "")
                body = r.text[:30_000].lower()
                resp_hdrs = " ".join(f"{k} {v}" for k, v in r.headers.items()).lower()
                matched_providers = set()
                for prov in all_paths.get(path, []):
                    for ind in self._PROVIDERS[prov]["indicators"]:
                        if ind in body or ind in resp_hdrs:
                            matched_providers.add(prov)
                if r.status_code < 400 and "json" in ct:
                    info["json"] = True
                    try:
                        j = r.json()
                        if isinstance(j, dict) and "data" in j:
                            models = [m.get("id", "") for m in j["data"][:15]
                                      if isinstance(m, dict)]
                            info["models"] = models
                            for m in models:
                                ml = m.lower()
                                for prov, cfg in self._PROVIDERS.items():
                                    if any(ind in ml for ind in cfg["indicators"]):
                                        matched_providers.add(prov)
                    except Exception:
                        pass
                if matched_providers:
                    info["providers"] = sorted(matched_providers)
                if r.status_code < 400 or r.status_code in (401, 403, 429):
                    return path, info
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, sorted(all_paths)):
                if info:
                    detected[path] = info

        # summarize detected providers
        providers_found = set()
        for info in detected.values():
            if isinstance(info, dict):
                providers_found.update(info.get("providers", []))

        risk = "informational"
        if any(info.get("status") == 200 and info.get("json")
               for info in detected.values() if isinstance(info, dict)):
            risk = "HIGH"
        elif detected:
            risk = "MEDIUM"

        return self.ok(host, {
            "endpoints": detected or "no AI API endpoints found",
            "providers_detected": sorted(providers_found) or "none",
            "risk": risk,
            "note": "unauthenticated AI API access can lead to cost abuse and data leakage"
        })


@register
class DeepSeekApiAuth(Module):
    id, name, category = "dsapi", "DeepSeek API account recon (requires key)", "AI/LLM"
    target_kind = "domain"

    _BASE = "https://api.deepseek.com"

    def run(self, target, ctx):
        try:
            from ..config import Config
            cfg = Config()
            key = cfg.api_key("deepseek")
        except Exception:
            key = None
        if not key:
            return self.fail(target, "requires deepseek API key — set DEEPSEEK_API_KEY "
                             "env var or add to ~/.ghosteye/config.ini under [api_keys]")
        headers = {"Authorization": f"Bearer {key}",
                   "Content-Type": "application/json"}
        results = {}

        # list available models
        try:
            r = ctx.session.get(f"{self._BASE}/v1/models",
                                headers=headers, timeout=ctx.timeout)
            if r.status_code == 200:
                j = r.json()
                models = [m.get("id", "") for m in j.get("data", [])
                          if isinstance(m, dict)]
                results["models"] = models
            else:
                results["models_error"] = f"HTTP {r.status_code}"
        except Exception as e:
            results["models_error"] = str(e)[:100]

        # check balance / usage
        for endpoint, label in [
            ("/v1/dashboard/billing/usage", "usage"),
            ("/v1/dashboard/billing/subscription", "subscription"),
            ("/v1/balance", "balance"),
            ("/user/balance", "balance_alt"),
        ]:
            try:
                r = ctx.session.get(f"{self._BASE}{endpoint}",
                                    headers=headers, timeout=ctx.timeout)
                if r.status_code == 200:
                    try:
                        results[label] = r.json()
                    except Exception:
                        results[label] = r.text[:200]
                elif r.status_code != 404:
                    results[f"{label}_status"] = r.status_code
            except Exception:
                continue

        # test a minimal chat completion (zero-cost probe)
        try:
            r = ctx.session.post(f"{self._BASE}/v1/chat/completions",
                                 headers=headers, timeout=ctx.timeout,
                                 json={"model": "deepseek-chat",
                                       "messages": [{"role": "user", "content": "hi"}],
                                       "max_tokens": 1})
            if r.status_code == 200:
                j = r.json()
                results["chat_test"] = {
                    "status": "working",
                    "model": j.get("model", ""),
                    "usage": j.get("usage", {}),
                }
            else:
                try:
                    results["chat_test"] = {"status": f"HTTP {r.status_code}",
                                            "error": r.json().get("error", {})
                                                     .get("message", "")[:150]}
                except Exception:
                    results["chat_test"] = {"status": f"HTTP {r.status_code}"}
        except Exception as e:
            results["chat_test"] = {"status": "error", "detail": str(e)[:100]}

        # rate limit headers
        try:
            r = ctx.session.get(f"{self._BASE}/v1/models",
                                headers=headers, timeout=ctx.timeout)
            rl = {k: v for k, v in r.headers.items()
                  if "ratelimit" in k.lower() or "x-" in k.lower()[:2]}
            if rl:
                results["rate_limits"] = rl
        except Exception:
            pass

        return self.ok(target, results)
