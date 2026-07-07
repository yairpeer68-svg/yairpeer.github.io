"""AI / LLM API exposure detection. Reconnaissance only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List
from urllib.parse import urljoin

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


# =========================================================================== #
#  AI key leak scanner — scans HTML, JS, env files for leaked AI API keys
# =========================================================================== #
@register
class AiKeyLeakScan(Module):
    id, name, category = "aikeyleak", "AI API key leak scanner (all providers)", "AI/LLM"
    target_kind = "url"

    _PATTERNS = {
        "DeepSeek": [re.compile(r"sk-[a-f0-9]{48}", re.I)],
        "OpenAI": [re.compile(r"sk-(?:proj-)?[A-Za-z0-9_-]{40,}")],
        "Anthropic": [re.compile(r"sk-ant-[A-Za-z0-9_-]{40,}")],
        "Google AI": [re.compile(r"AIzaSy[A-Za-z0-9_-]{33}")],
        "HuggingFace": [re.compile(r"hf_[A-Za-z0-9]{34,}")],
        "Cohere": [re.compile(r"[a-zA-Z0-9]{40}", re.I)],
        "Replicate": [re.compile(r"r8_[A-Za-z0-9]{36,}")],
        "Mistral": [re.compile(r"[a-zA-Z0-9]{32}", re.I)],
    }

    _GENERIC = [
        re.compile(r"""(?:OPENAI|ANTHROPIC|DEEPSEEK|MISTRAL|COHERE|HUGGING|REPLICATE|GEMINI|AI)[_-]?(?:API[_-]?)?KEY\s*[:=]\s*['"]?([A-Za-z0-9_\-]{20,})""", re.I),
        re.compile(r"""(?:LLM|GPT|CLAUDE|CHAT)[_-]?(?:API[_-]?)?(?:KEY|TOKEN|SECRET)\s*[:=]\s*['"]?([A-Za-z0-9_\-]{20,})""", re.I),
    ]

    _SENSITIVE_PATHS = [
        "/.env", "/.env.local", "/.env.production", "/.env.development",
        "/env.js", "/config.js", "/settings.js",
        "/.env.example", "/env.example", "/env.sample",
        "/api/config", "/config.json", "/settings.json",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        leaks = []

        def _scan_text(source, text):
            for provider, patterns in self._PATTERNS.items():
                for pat in patterns:
                    for m in pat.finditer(text):
                        val = m.group(0)
                        leaks.append({
                            "provider": provider,
                            "source": source,
                            "key_preview": val[:10] + "…" + val[-4:],
                            "risk": "CRITICAL",
                        })
            for pat in self._GENERIC:
                for m in pat.finditer(text):
                    val = m.group(1) if m.lastindex else m.group(0)
                    leaks.append({
                        "provider": "generic AI key",
                        "source": source,
                        "key_preview": val[:10] + "…" + val[-4:],
                        "risk": "HIGH",
                    })

        # scan main page + inline scripts
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            _scan_text("homepage HTML", r.text[:100_000])
            scripts = re.findall(r'src=["\']([^"\']+\.js[^"\']*)["\']', r.text)
            for src in scripts[:15]:
                if src.startswith("//"):
                    src = "https:" + src
                elif src.startswith("/"):
                    src = base + src
                elif not src.startswith("http"):
                    src = base + "/" + src
                try:
                    jr = ctx.session.get(src, timeout=ctx.timeout)
                    _scan_text("JS: " + src.split("/")[-1][:40], jr.text[:150_000])
                except Exception:
                    continue
        except Exception:
            pass

        # scan sensitive config paths
        def probe_path(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 5:
                    _scan_text(path, r.text[:50_000])
            except Exception:
                pass

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 5)) as ex:
            list(ex.map(probe_path, self._SENSITIVE_PATHS))

        seen = set()
        unique_leaks = []
        for lk in leaks:
            key = (lk["provider"], lk["key_preview"])
            if key not in seen:
                seen.add(key)
                unique_leaks.append(lk)

        risk = "CRITICAL" if unique_leaks else "informational"
        return self.ok(host, {
            "leaked_keys": unique_leaks or "none found",
            "sources_scanned": ["homepage", "JS files", "env/config files"],
            "risk": risk,
        })


# =========================================================================== #
#  Exposed ML/AI dashboards (MLflow, TensorBoard, W&B, etc.)
# =========================================================================== #
@register
class AiDashboardExposure(Module):
    id, name, category = "aidash", "Exposed AI/ML dashboards", "AI/LLM"
    target_kind = "url"

    _DASHBOARDS = {
        "MLflow": {
            "paths": ["/api/2.0/mlflow/experiments/list", "/#/experiments",
                      "/ajax-api/2.0/mlflow/experiments/search"],
            "indicators": ["mlflow", "experiment_id", "MLflow"],
        },
        "TensorBoard": {
            "paths": ["/data/runs", "/data/plugins_listing",
                      "/data/environment"],
            "indicators": ["tensorboard", "TensorBoard"],
        },
        "Weights & Biases": {
            "paths": ["/api/v1/reports", "/graphql"],
            "indicators": ["wandb", "weights & biases", "W&B"],
        },
        "Neptune.ai": {
            "paths": ["/api/leaderboard/v1/leaderboard"],
            "indicators": ["neptune"],
        },
        "ClearML": {
            "paths": ["/api/v2.20/projects.get_all", "/api/v2.20/tasks.get_all"],
            "indicators": ["clearml", "allegro"],
        },
        "Label Studio": {
            "paths": ["/api/projects", "/user/login"],
            "indicators": ["label-studio", "Label Studio"],
        },
        "Aim": {
            "paths": ["/api/runs/search/run", "/api/experiments"],
            "indicators": ["aim", "aimstack"],
        },
        "Kubeflow": {
            "paths": ["/pipeline/apis/v2beta1/pipelines",
                      "/api/v1/experiments", "/_/pipeline"],
            "indicators": ["kubeflow", "pipeline"],
        },
        "Airflow": {
            "paths": ["/api/v1/dags", "/home"],
            "indicators": ["airflow", "Apache Airflow"],
        },
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        all_probes = []
        for dash, cfg in self._DASHBOARDS.items():
            for path in cfg["paths"]:
                all_probes.append((dash, path, cfg["indicators"]))

        def probe(item):
            dash, path, indicators = item
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    allow_redirects=True)
                if r.status_code >= 500:
                    return None
                body = r.text[:20_000].lower()
                hdrs_str = " ".join(v for v in r.headers.values()).lower()
                matched = any(ind.lower() in body or ind.lower() in hdrs_str
                              for ind in indicators)
                if r.status_code == 200 and (matched or len(r.text) > 100):
                    return (dash, path, {
                        "status": r.status_code,
                        "confirmed": matched,
                        "size": len(r.content),
                    })
                if r.status_code in (401, 403) and matched:
                    return (dash, path, {
                        "status": r.status_code,
                        "confirmed": True,
                        "note": "exists but requires auth",
                    })
            except Exception:
                pass
            return None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe, all_probes):
                if result:
                    dash, path, info = result
                    found.setdefault(dash, {})[path] = info

        risk = "informational"
        for dash_info in found.values():
            for ep in dash_info.values():
                if ep.get("status") == 200 and ep.get("confirmed"):
                    risk = "HIGH"
                    break

        return self.ok(host, {
            "dashboards": found or "none found",
            "risk": risk,
        })


# =========================================================================== #
#  Exposed vector databases (Qdrant, Weaviate, ChromaDB, Milvus, etc.)
# =========================================================================== #
@register
class VectorDbExposure(Module):
    id, name, category = "vectordb", "Exposed vector databases", "AI/LLM"
    target_kind = "url"

    _DATABASES = {
        "Qdrant": {
            "paths": [":6333/collections", ":6333/dashboard",
                      "/collections", "/dashboard"],
            "indicators": ["qdrant", "collection_name", "vectors_count"],
        },
        "Weaviate": {
            "paths": [":8080/v1/schema", ":8080/v1/meta",
                      "/v1/schema", "/v1/meta"],
            "indicators": ["weaviate", "vectorizer"],
        },
        "ChromaDB": {
            "paths": [":8000/api/v1/collections", ":8000/api/v1/heartbeat",
                      "/api/v1/collections", "/api/v1/heartbeat"],
            "indicators": ["chroma", "heartbeat"],
        },
        "Milvus": {
            "paths": [":9091/api/v1/health", ":19530/api/v1/collections",
                      "/api/v1/health"],
            "indicators": ["milvus"],
        },
        "Pinecone": {
            "paths": ["/describe_index_stats"],
            "indicators": ["pinecone", "namespaces", "dimension"],
        },
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base_url = ensure_scheme(host).rstrip("/")
        found = {}

        probes = []
        for db, cfg in self._DATABASES.items():
            for path in cfg["paths"]:
                if path.startswith(":"):
                    port, rest = path[1:].split("/", 1)
                    url = f"{ensure_scheme(host, 'http').split(':')[0]}:{ensure_scheme(host, 'http').split(':')[1]}:{port}/{rest}"
                    scheme = ensure_scheme(host).split("://")[0]
                    url = f"{scheme}://{host.split(':')[0]}:{port}/{rest}"
                else:
                    url = base_url + path
                probes.append((db, path, url, cfg["indicators"]))

        def probe(item):
            db, path, url, indicators = item
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                body = r.text[:20_000].lower()
                matched = any(ind.lower() in body for ind in indicators)
                if r.status_code == 200 and matched:
                    info = {"url": url, "status": 200, "confirmed": True,
                            "size": len(r.content)}
                    try:
                        j = r.json()
                        if isinstance(j, dict):
                            if "collections" in str(j.keys()):
                                colls = j.get("collections", j.get("result", {}).get("collections", []))
                                if isinstance(colls, list):
                                    info["collection_count"] = len(colls)
                                    info["collections"] = [
                                        c.get("name", str(c))[:40]
                                        for c in colls[:10]
                                        if isinstance(c, (dict, str))
                                    ]
                    except Exception:
                        pass
                    return (db, info)
                if r.status_code in (401, 403):
                    return (db, {"url": url, "status": r.status_code,
                                 "note": "exists, auth required"})
            except Exception:
                pass
            return None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe, probes):
                if result:
                    db, info = result
                    found.setdefault(db, []).append(info)

        risk = "informational"
        for db_hits in found.values():
            for hit in db_hits:
                if hit.get("confirmed"):
                    risk = "CRITICAL"
                    break

        return self.ok(host, {
            "vector_databases": found or "none found",
            "risk": risk,
            "note": "exposed vector DBs leak embeddings, RAG data, and proprietary knowledge bases"
        })


# =========================================================================== #
#  Exposed AI app frameworks (Gradio, Streamlit, Chainlit, etc.)
# =========================================================================== #
@register
class AiAppFramework(Module):
    id, name, category = "aiapp", "Exposed AI app frameworks (Gradio/Streamlit/Chainlit)", "AI/LLM"
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
            body = r.text[:80_000]
            hdrs = {k.lower(): v for k, v in r.headers.items()}
        except Exception as e:
            return self.fail(host, str(e)[:80])

        # Gradio
        gradio_signs = ["gradio", "__gradio", "gradio-app", "gr.Interface",
                        "/api/predict", "/run/predict"]
        if any(s.lower() in body.lower() for s in gradio_signs):
            info = {"detected": True, "framework": "Gradio"}
            try:
                ar = ctx.session.get(base + "/info", timeout=ctx.timeout)
                if ar.status_code == 200:
                    info["info_endpoint"] = ar.json()
            except Exception:
                pass
            try:
                ar = ctx.session.get(base + "/config", timeout=ctx.timeout)
                if ar.status_code == 200:
                    cfg = ar.json()
                    info["mode"] = cfg.get("mode", "")
                    info["show_api"] = cfg.get("show_api", "")
                    info["auth_required"] = cfg.get("auth_required", False)
                    comps = cfg.get("components", [])
                    info["components"] = len(comps)
            except Exception:
                pass
            try:
                ar = ctx.session.get(base + "/api", timeout=ctx.timeout)
                if ar.status_code == 200:
                    api_info = ar.json()
                    endpoints = api_info.get("named_endpoints", {})
                    info["api_endpoints"] = list(endpoints.keys())[:10]
            except Exception:
                pass
            findings["Gradio"] = info

        # Streamlit
        streamlit_signs = ["streamlit", "stApp", "_stcore", "st.session_state",
                           "streamlitapp"]
        if any(s.lower() in body.lower() for s in streamlit_signs):
            info = {"detected": True, "framework": "Streamlit"}
            try:
                ar = ctx.session.get(base + "/_stcore/health", timeout=ctx.timeout)
                if ar.status_code == 200:
                    info["health"] = ar.text[:100]
            except Exception:
                pass
            try:
                ar = ctx.session.get(base + "/_stcore/host-config", timeout=ctx.timeout)
                if ar.status_code == 200:
                    info["host_config"] = ar.json()
            except Exception:
                pass
            findings["Streamlit"] = info

        # Chainlit
        if "chainlit" in body.lower() or any("chainlit" in v.lower()
                                              for v in hdrs.values()):
            info = {"detected": True, "framework": "Chainlit"}
            try:
                ar = ctx.session.get(base + "/project/settings",
                                     timeout=ctx.timeout)
                if ar.status_code == 200:
                    info["settings"] = ar.json()
            except Exception:
                pass
            findings["Chainlit"] = info

        # Open WebUI (formerly Ollama WebUI)
        if any(s in body.lower() for s in ["open webui", "ollama webui",
                                            "open-webui"]):
            info = {"detected": True, "framework": "Open WebUI"}
            try:
                ar = ctx.session.get(base + "/api/config", timeout=ctx.timeout)
                if ar.status_code == 200:
                    info["config"] = ar.json()
            except Exception:
                pass
            findings["Open WebUI"] = info

        # LangServe
        langserve_paths = ["/docs", "/openapi.json"]
        for lp in langserve_paths:
            try:
                lr = ctx.session.get(base + lp, timeout=ctx.timeout)
                if lr.status_code == 200 and "langserve" in lr.text.lower():
                    findings.setdefault("LangServe", {"detected": True,
                                                       "framework": "LangServe"})
                    findings["LangServe"][lp] = {"status": 200}
            except Exception:
                continue

        # Flowise
        if "flowise" in body.lower():
            info = {"detected": True, "framework": "Flowise"}
            try:
                ar = ctx.session.get(base + "/api/v1/chatflows",
                                     timeout=ctx.timeout)
                if ar.status_code == 200:
                    flows = ar.json()
                    if isinstance(flows, list):
                        info["chatflow_count"] = len(flows)
                        info["chatflows"] = [f.get("name", "")[:40]
                                             for f in flows[:5]]
                    info["risk"] = "CRITICAL"
            except Exception:
                pass
            findings["Flowise"] = info

        # Dify
        if "dify" in body.lower():
            info = {"detected": True, "framework": "Dify"}
            for ep in ["/console/api/apps", "/v1/parameters"]:
                try:
                    ar = ctx.session.get(base + ep, timeout=ctx.timeout)
                    if ar.status_code == 200:
                        info[ep] = {"status": 200, "size": len(ar.content)}
                except Exception:
                    continue
            findings["Dify"] = info

        risk = "informational"
        if findings:
            risk = "MEDIUM"
            for fw in findings.values():
                if isinstance(fw, dict):
                    if fw.get("api_endpoints") or fw.get("chatflows"):
                        risk = "HIGH"
                    if fw.get("risk") == "CRITICAL":
                        risk = "CRITICAL"

        return self.ok(host, {
            "frameworks": findings or "none detected",
            "risk": risk,
        })


# =========================================================================== #
#  Model serving infrastructure (TorchServe, TF Serving, Triton, BentoML)
# =========================================================================== #
@register
class ModelServingExposure(Module):
    id, name, category = "modelserve", "Exposed model serving infra", "AI/LLM"
    target_kind = "url"

    _SERVERS = {
        "TorchServe": {
            "paths": [":8081/models", ":8082/metrics",
                      "/models", "/api-description"],
            "indicators": ["torchserve", "modelName", "modelVersion"],
        },
        "TF Serving": {
            "paths": [":8501/v1/models", "/v1/models"],
            "indicators": ["model_version_status", "tensorflow"],
        },
        "Triton": {
            "paths": [":8000/v2/health/ready", ":8000/v2/models",
                      "/v2/health/ready", "/v2/models"],
            "indicators": ["triton", "nvidia"],
        },
        "BentoML": {
            "paths": ["/docs", "/healthz", "/metrics", "/readyz"],
            "indicators": ["bentoml", "bento"],
        },
        "Seldon Core": {
            "paths": ["/api/v1.0/predictions", "/seldon"],
            "indicators": ["seldon"],
        },
        "KServe": {
            "paths": ["/v1/models", "/v2/models"],
            "indicators": ["kserve", "inferenceservice"],
        },
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        probes = []
        for srv, cfg in self._SERVERS.items():
            for path in cfg["paths"]:
                if path.startswith(":"):
                    port, rest = path[1:].split("/", 1)
                    scheme = ensure_scheme(host).split("://")[0]
                    url = f"{scheme}://{host.split(':')[0]}:{port}/{rest}"
                else:
                    url = base + path
                probes.append((srv, path, url, cfg["indicators"]))

        def probe(item):
            srv, path, url, indicators = item
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                body = r.text[:15_000].lower()
                matched = any(ind.lower() in body for ind in indicators)
                if r.status_code == 200 and (matched or len(r.text) > 50):
                    info = {"url": url, "status": 200, "confirmed": matched}
                    if matched:
                        try:
                            j = r.json()
                            if isinstance(j, dict) and "models" in str(j):
                                info["response_preview"] = str(j)[:300]
                        except Exception:
                            pass
                    return (srv, info)
            except Exception:
                pass
            return None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe, probes):
                if result:
                    srv, info = result
                    found.setdefault(srv, []).append(info)

        risk = "informational"
        for hits in found.values():
            if any(h.get("confirmed") for h in hits):
                risk = "HIGH"

        return self.ok(host, {
            "model_servers": found or "none found",
            "risk": risk,
            "note": "exposed model servers allow model theft, inference abuse, and poisoning"
        })


# =========================================================================== #
#  AI observability / orchestration platforms (LangSmith, LangFuse, Helicone)
# =========================================================================== #
@register
class AiOrchExposure(Module):
    id, name, category = "aiorch", "Exposed AI orchestration platforms", "AI/LLM"
    target_kind = "url"

    _PLATFORMS = {
        "LangSmith": {
            "paths": ["/api/v1/runs", "/api/v1/sessions", "/api/v1/datasets"],
            "indicators": ["langsmith", "langchain"],
        },
        "LangFuse": {
            "paths": ["/api/public/traces", "/api/public/generations",
                      "/api/public/scores"],
            "indicators": ["langfuse"],
        },
        "Helicone": {
            "paths": ["/v1/request/query"],
            "indicators": ["helicone"],
        },
        "PromptLayer": {
            "paths": ["/api/v1/prompt-templates"],
            "indicators": ["promptlayer"],
        },
        "Portkey": {
            "paths": ["/v1/logs"],
            "indicators": ["portkey"],
        },
        "Phoenix (Arize)": {
            "paths": ["/v1/traces", "/graphql"],
            "indicators": ["phoenix", "arize"],
        },
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(item):
            platform, path = item
            cfg = self._PLATFORMS[platform]
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                body = r.text[:15_000].lower()
                matched = any(ind in body for ind in cfg["indicators"])
                if r.status_code == 200 and matched:
                    return (platform, path, {"status": 200, "confirmed": True,
                                             "size": len(r.content),
                                             "risk": "HIGH"})
                if r.status_code in (401, 403):
                    return (platform, path, {"status": r.status_code,
                                             "note": "endpoint exists"})
            except Exception:
                pass
            return None

        items = [(p, path) for p, cfg in self._PLATFORMS.items()
                 for path in cfg["paths"]]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe, items):
                if result:
                    platform, path, info = result
                    found.setdefault(platform, {})[path] = info

        risk = "informational"
        for plat in found.values():
            for ep in plat.values():
                if ep.get("confirmed"):
                    risk = "HIGH"

        return self.ok(host, {
            "platforms": found or "none found",
            "risk": risk,
            "note": "exposed traces/logs leak prompts, completions, user data, and cost info"
        })


# =========================================================================== #
#  Jupyter / notebook exposure
# =========================================================================== #
@register
class JupyterExposure(Module):
    id, name, category = "jupyter", "Exposed Jupyter/notebook servers", "AI/LLM"
    target_kind = "url"

    _PATHS = {
        "Jupyter": ["/api", "/api/kernels", "/api/sessions",
                    "/api/contents", "/tree", "/lab"],
        "JupyterHub": ["/hub/api", "/hub/login", "/hub/api/users"],
        "Google Colab (proxy)": ["/api/colab"],
        "Zeppelin": ["/api/notebook", "/api/interpreter"],
        "Databricks": ["/api/2.0/workspace/list"],
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        # also try common notebook ports
        hosts_to_try = [base]
        bare = host.split(":")[0]
        for port in [8888, 8889, 8890, 8080]:
            scheme = ensure_scheme(host).split("://")[0]
            hosts_to_try.append(f"{scheme}://{bare}:{port}")

        def probe(item):
            platform, path, b = item
            try:
                r = ctx.session.get(b + path, timeout=ctx.timeout,
                                    allow_redirects=True)
                body = r.text[:15_000].lower()
                if r.status_code == 200:
                    is_jupyter = any(kw in body for kw in
                                     ["jupyter", "notebook", "kernelspec",
                                      "ipython", "nbformat", "zeppelin",
                                      "databricks"])
                    if is_jupyter or path in ("/api", "/api/kernels"):
                        info = {"url": b + path, "status": 200,
                                "confirmed": is_jupyter}
                        try:
                            j = r.json()
                            if isinstance(j, list):
                                info["items"] = len(j)
                            if isinstance(j, dict):
                                info["keys"] = list(j.keys())[:8]
                        except Exception:
                            pass
                        return (platform, info)
                if r.status_code in (401, 403) and any(
                    kw in body for kw in ["jupyter", "login", "token"]):
                    return (platform, {"url": b + path,
                                       "status": r.status_code,
                                       "note": "exists, auth required"})
            except Exception:
                pass
            return None

        items = [(p, path, b)
                 for b in hosts_to_try
                 for p, paths in self._PATHS.items()
                 for path in paths]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe, items[:40]):
                if result:
                    platform, info = result
                    found.setdefault(platform, []).append(info)

        risk = "informational"
        for plat_hits in found.values():
            for h in plat_hits:
                if h.get("confirmed") and h.get("status") == 200:
                    risk = "CRITICAL"

        return self.ok(host, {
            "notebooks": found or "none found",
            "risk": risk,
            "note": "exposed notebooks allow code execution, data access, and credential theft"
        })


# =========================================================================== #
#  Hugging Face integration detection
# =========================================================================== #
@register
class HuggingFaceRecon(Module):
    id, name, category = "hfrecon", "Hugging Face token/model exposure", "AI/LLM"
    target_kind = "url"

    _HF_KEY = re.compile(r"hf_[A-Za-z0-9]{34,}")

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"token_leaks": [], "hf_indicators": [], "spaces_detected": False}

        # scan main page + JS
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:80_000]

            for m in self._HF_KEY.finditer(body):
                val = m.group(0)
                findings["token_leaks"].append(val[:8] + "…" + val[-4:])

            if "huggingface" in body.lower() or "hugging face" in body.lower():
                findings["hf_indicators"].append("page references Hugging Face")

            hf_urls = re.findall(r"https?://huggingface\.co/[^\s\"'<>]{5,60}", body)
            if hf_urls:
                findings["hf_indicators"].append({
                    "hf_urls_found": list(set(hf_urls))[:10]})

            hf_spaces = re.findall(r"https?://[a-z0-9\-]+\.hf\.space[^\s\"'<>]*", body)
            if hf_spaces:
                findings["spaces_detected"] = True
                findings["hf_indicators"].append({
                    "hf_spaces": list(set(hf_spaces))[:10]})

            scripts = re.findall(r'src=["\']([^"\']+\.js[^"\']*)["\']', body)
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
                    for m in self._HF_KEY.finditer(js):
                        val = m.group(0)
                        findings["token_leaks"].append(val[:8] + "…" + val[-4:])
                    if "huggingface" in js.lower():
                        findings["hf_indicators"].append({
                            "js_file": src.split("/")[-1][:40],
                            "note": "references Hugging Face"})
                except Exception:
                    continue
        except Exception:
            pass

        # check sensitive paths
        for path in ["/.env", "/config.json", "/env.js"]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200:
                    for m in self._HF_KEY.finditer(r.text[:30_000]):
                        val = m.group(0)
                        findings["token_leaks"].append(val[:8] + "…" + val[-4:])
            except Exception:
                continue

        findings["token_leaks"] = sorted(set(findings["token_leaks"]))

        risk = "informational"
        if findings["token_leaks"]:
            risk = "CRITICAL"
        elif findings["hf_indicators"]:
            risk = "LOW"

        if not findings["hf_indicators"]:
            findings["hf_indicators"] = "none"
        if not findings["token_leaks"]:
            findings["token_leaks"] = "none"

        return self.ok(host, findings)


# =========================================================================== #
#  LLM prompt/config exposure — leaked system prompts, RAG config
# =========================================================================== #
@register
class LlmPromptLeak(Module):
    id, name, category = "promptleak", "LLM prompt/config leak detection", "AI/LLM"
    target_kind = "url"

    _PROMPT_INDICATORS = [
        "system_prompt", "system prompt", "systemPrompt",
        "system_message", "systemMessage", "system_instruction",
        "pre_prompt", "preprompt", "initial_prompt",
        "persona", "you are a", "you are an",
        "assistant_instructions", "instructions",
        "rag_config", "ragConfig", "knowledge_base",
        "vector_store", "embedding_model", "chunk_size",
        "retrieval", "context_window",
    ]

    _CONFIG_PATHS = [
        "/api/config", "/api/settings", "/api/v1/config",
        "/config.json", "/settings.json",
        "/api/prompt", "/api/system-prompt",
        "/api/v1/parameters", "/.well-known/ai-plugin.json",
        "/openapi.json", "/ai-plugin.json",
        "/api/chatbot/config", "/chatbot/config",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"prompt_leaks": [], "config_endpoints": {}, "ai_plugin": None}

        # scan config endpoints
        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200 or len(r.text) < 10:
                    return path, None
                body = r.text[:30_000]
                body_lower = body.lower()
                matched = [ind for ind in self._PROMPT_INDICATORS
                           if ind.lower() in body_lower]
                if matched:
                    info = {"status": 200, "indicators": matched[:8],
                            "size": len(r.content)}
                    try:
                        j = r.json()
                        for key in ["system_prompt", "systemPrompt",
                                    "system_message", "pre_prompt",
                                    "instructions", "persona"]:
                            if key in str(j):
                                prompt_val = self._extract_key(j, key)
                                if prompt_val and len(prompt_val) > 10:
                                    info["leaked_prompt_preview"] = prompt_val[:200] + "…"
                    except Exception:
                        pass
                    return path, info
                if path == "/.well-known/ai-plugin.json":
                    try:
                        j = r.json()
                        if "name_for_model" in str(j) or "api" in str(j):
                            return path, {"status": 200, "type": "OpenAI plugin manifest",
                                          "data": {k: str(v)[:100] for k, v in
                                                   (j if isinstance(j, dict) else {}).items()}}
                    except Exception:
                        pass
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, self._CONFIG_PATHS):
                if info:
                    findings["config_endpoints"][path] = info
                    if info.get("type") == "OpenAI plugin manifest":
                        findings["ai_plugin"] = info.get("data")

        # scan homepage for inline prompts
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:80_000]
            for ind in self._PROMPT_INDICATORS:
                idx = body.lower().find(ind.lower())
                if idx >= 0:
                    snippet = body[max(0, idx - 20):idx + len(ind) + 80]
                    findings["prompt_leaks"].append({
                        "indicator": ind,
                        "context": snippet.strip()[:120]
                    })
        except Exception:
            pass

        if not findings["prompt_leaks"]:
            findings["prompt_leaks"] = "none"
        if not findings["config_endpoints"]:
            findings["config_endpoints"] = "none"

        risk = "informational"
        for ep in (findings["config_endpoints"]
                   if isinstance(findings["config_endpoints"], dict) else {}).values():
            if ep.get("leaked_prompt_preview"):
                risk = "HIGH"
                break
            if ep.get("indicators"):
                risk = "MEDIUM"

        findings["risk"] = risk
        return self.ok(host, findings)

    @staticmethod
    def _extract_key(obj, key):
        if isinstance(obj, dict):
            if key in obj:
                return str(obj[key])
            for v in obj.values():
                result = LlmPromptLeak._extract_key(v, key)
                if result:
                    return result
        if isinstance(obj, list):
            for item in obj[:5]:
                result = LlmPromptLeak._extract_key(item, key)
                if result:
                    return result
        return None
