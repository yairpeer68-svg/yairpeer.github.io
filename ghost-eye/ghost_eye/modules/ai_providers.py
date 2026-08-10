"""AI/LLM provider account recon.

Authenticated recon of a *commercial* LLM provider account using your own API
key — the counterpart to the existing ``dsapi`` (DeepSeek) module, extended to
the major providers. Given a key (found in a leak, or one you own), it answers
the questions a responder actually asks:

  * is this key still live, or was it revoked?
  * which models — and how much spend/quota — does it unlock?
  * what rate-limit / org context does the provider report back?

Every module here is **read-only**: it lists models and reads account metadata.
Where a provider has no read-only endpoint, liveness is inferred from the auth
status of a single minimal request (401 = dead/invalid, anything else = the key
authenticated) — no generation is billed.

Keys come from the config/keyring/env exactly like every other key
(``OPENAI_API_KEY`` … or the dashboard Keys panel). With no key set, a module
returns a graceful ``fail()`` naming the env var it needs.

FOR AUTHORISED USE ONLY — audit keys you own or are authorised to assess.
"""

from __future__ import annotations

from typing import Dict, List, Optional

from ..core import Context, Module, register


def _key(ctx: Context, name: str) -> Optional[str]:
    """Resolve an API key via the shared Config resolution order, tolerating a
    Context whose config is a bare stub (as in some tests)."""
    cfg = getattr(ctx, "config", None)
    getter = getattr(cfg, "api_key", None)
    if not callable(getter):
        return None
    try:
        return getter(name)
    except Exception:  # noqa: BLE001
        return None


def _interpret(status: int) -> str:
    return {
        200: "key is VALID",
        401: "key is INVALID or revoked",
        403: "key authenticated but forbidden (scope/permission)",
        429: "key is VALID but rate-limited",
    }.get(status, f"HTTP {status}")


def _rate_headers(resp) -> Dict[str, str]:
    return {k: v for k, v in resp.headers.items()
            if "ratelimit" in k.lower() or k.lower().startswith("x-request")}


class _ProviderAccount(Module):
    """Base class for a 'validate my key + list what it unlocks' module.

    Subclasses set the provider metadata; the flow (missing-key guard, models
    listing, optional account endpoint, liveness verdict, rate-limit headers)
    is shared so every provider behaves identically.
    """

    category = "AI/LLM"
    target_kind = "domain"          # account recon: the target is informational

    key_name: str = ""              # config key name (also the env var source)
    env_var: str = ""               # shown in the missing-key message
    base: str = ""                  # API base URL, no trailing slash
    models_path: str = "/v1/models"
    auth: str = "bearer"            # bearer | x-api-key | query
    extra_headers: Dict[str, str] = {}
    account_paths: List = []        # [(path, label)] read-only account endpoints

    # ---- request helpers ------------------------------------------------- #
    def _headers(self, key: str) -> Dict[str, str]:
        h = {"Accept": "application/json"}
        h.update(self.extra_headers)
        if self.auth == "bearer":
            h["Authorization"] = f"Bearer {key}"
        elif self.auth == "x-api-key":
            h["x-api-key"] = key
        return h

    def _url(self, path: str, key: str) -> str:
        url = self.base + path
        if self.auth == "query":
            url += ("&" if "?" in url else "?") + "key=" + key
        return url

    def _model_ids(self, payload) -> List[str]:
        """Pull model ids out of the provider's (varying) response shape."""
        if isinstance(payload, dict):
            for field in ("data", "models"):
                items = payload.get(field)
                if isinstance(items, list):
                    out = []
                    for it in items:
                        if isinstance(it, dict):
                            out.append(it.get("id") or it.get("name")
                                       or it.get("slug") or "")
                        elif isinstance(it, str):
                            out.append(it)
                    return [m for m in out if m]
        if isinstance(payload, list):
            return [it.get("id", "") if isinstance(it, dict) else str(it)
                    for it in payload]
        return []

    # ---- run ------------------------------------------------------------- #
    def run(self, target, ctx: Context):
        key = _key(ctx, self.key_name)
        if not key:
            return self.fail(target,
                             f"requires the {self.name.split(' account')[0]} "
                             f"API key — set the {self.env_var} env var, add it "
                             f"under [api_keys] in ~/.ghosteye/config.ini, or "
                             f"paste it in the dashboard Keys panel")
        out: Dict[str, object] = {"provider": self.name.split(" account")[0]}
        # 1) list models — also our primary liveness probe
        try:
            r = ctx.session.get(self._url(self.models_path, key),
                                headers=self._headers(key), timeout=ctx.timeout)
        except Exception as e:  # noqa: BLE001
            return self.fail(target, f"request failed: {e}")
        out["key_status"] = _interpret(r.status_code)
        if r.status_code == 200:
            try:
                models = self._model_ids(r.json())
            except Exception:  # noqa: BLE001
                models = []
            out["model_count"] = len(models)
            out["models"] = sorted(models)[:60] or "none listed"
        else:
            # surface the provider's own error text — it often says why
            snippet = (r.text or "")[:200].strip()
            if snippet:
                out["error"] = snippet
        rl = _rate_headers(r)
        if rl:
            out["rate_limits"] = rl
        # 2) optional read-only account endpoints (quota / identity)
        for path, label in self.account_paths:
            try:
                ar = ctx.session.get(self._url(path, key),
                                     headers=self._headers(key),
                                     timeout=ctx.timeout)
            except Exception:  # noqa: BLE001
                continue
            if ar.status_code == 200:
                try:
                    out[label] = ar.json()
                except Exception:  # noqa: BLE001
                    out[label] = (ar.text or "")[:200]
            elif ar.status_code not in (404, 405):
                out[f"{label}_status"] = ar.status_code
        out["note"] = ("read-only account recon — no generation was billed. "
                       "Audit keys you own or are authorised to assess.")
        return self.ok(target, out)


# --------------------------------------------------------------------------- #
#  OpenAI-compatible providers (Bearer + GET /v1/models)
# --------------------------------------------------------------------------- #
@register
class OpenAIAccount(_ProviderAccount):
    id, name = "openaiacct", "OpenAI account recon (key)"
    key_name, env_var = "openai", "OPENAI_API_KEY"
    base = "https://api.openai.com"
    # these 402/403 for restricted keys but 200 for full ones — useful signal
    account_paths = [("/v1/organizations", "organizations"),
                     ("/dashboard/billing/subscription", "billing")]


@register
class GroqAccount(_ProviderAccount):
    id, name = "groqacct", "Groq account recon (key)"
    key_name, env_var = "groq", "GROQ_API_KEY"
    base = "https://api.groq.com/openai"


@register
class MistralAccount(_ProviderAccount):
    id, name = "mistralacct", "Mistral AI account recon (key)"
    key_name, env_var = "mistral", "MISTRAL_API_KEY"
    base = "https://api.mistral.ai"


@register
class TogetherAccount(_ProviderAccount):
    id, name = "togetheracct", "Together AI account recon (key)"
    key_name, env_var = "together", "TOGETHER_API_KEY"
    base = "https://api.together.xyz"


@register
class XaiAccount(_ProviderAccount):
    id, name = "xaiacct", "xAI (Grok) account recon (key)"
    key_name, env_var = "xai", "XAI_API_KEY"
    base = "https://api.x.ai"
    # xAI exposes a key-metadata endpoint: which ACLs/models the key may use
    account_paths = [("/v1/api-key", "api_key_info")]


@register
class OpenRouterAccount(_ProviderAccount):
    id, name = "openrouteracct", "OpenRouter account recon (key)"
    key_name, env_var = "openrouter", "OPENROUTER_API_KEY"
    base = "https://openrouter.ai/api"
    # /v1/auth/key returns this key's usage + remaining credit limit
    account_paths = [("/v1/auth/key", "key_usage"),
                     ("/v1/credits", "credits")]


# --------------------------------------------------------------------------- #
#  Providers with a different auth or model-list shape
# --------------------------------------------------------------------------- #
@register
class AnthropicAccount(_ProviderAccount):
    id, name = "anthropicacct", "Anthropic account recon (key)"
    key_name, env_var = "anthropic", "ANTHROPIC_API_KEY"
    base = "https://api.anthropic.com"
    auth = "x-api-key"
    extra_headers = {"anthropic-version": "2023-06-01"}


@register
class GeminiAccount(_ProviderAccount):
    id, name = "geminiacct", "Google Gemini account recon (key)"
    key_name, env_var = "gemini", "GEMINI_API_KEY"
    base = "https://generativelanguage.googleapis.com"
    models_path = "/v1beta/models"
    auth = "query"                  # Gemini takes ?key=<key>


@register
class CohereAccount(_ProviderAccount):
    id, name = "cohereacct", "Cohere account recon (key)"
    key_name, env_var = "cohere", "COHERE_API_KEY"
    base = "https://api.cohere.com"


@register
class ReplicateAccount(_ProviderAccount):
    id, name = "replicateacct", "Replicate account recon (key)"
    key_name, env_var = "replicate", "REPLICATE_API_TOKEN"
    base = "https://api.replicate.com"
    # Replicate's model list is paginated at /v1/models; /v1/account is the
    # identity/quota read
    account_paths = [("/v1/account", "account")]


@register
class PerplexityAccount(_ProviderAccount):
    id, name = "pplxacct", "Perplexity account recon (key)"
    key_name, env_var = "perplexity", "PERPLEXITY_API_KEY"
    base = "https://api.perplexity.ai"
    # Perplexity has no read-only model list; validate via the models route and
    # let the shared verdict logic classify the auth status (401 vs otherwise).
    models_path = "/models"
