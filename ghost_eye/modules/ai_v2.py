"""AI/LLM attack-surface detection (v3.8 new features). Reconnaissance only —
no prompts are actually injected."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Module, clean_host, ensure_scheme, register


@register
class PromptInjectSurface(Module):
    id, name, category = "promptinject", "LLM prompt-injection surface", "AI/LLM"
    target_kind = "url"

    _PATHS = [
        "/chat", "/api/chat", "/ask", "/api/ask", "/query", "/api/query",
        "/completion", "/completions", "/api/completion", "/generate",
        "/api/generate", "/v1/chat/completions", "/api/v1/chat",
        "/assistant", "/api/assistant", "/bot", "/api/bot", "/message",
        "/api/message", "/prompt", "/api/prompt", "/llm", "/api/llm",
        "/copilot", "/api/copilot", "/agent", "/api/agent", "/rag", "/search/ai",
    ]
    _BODY_HINTS = [
        (re.compile(r"\b(chatbot|ai assistant|ask me anything|powered by (?:gpt|openai|"
                    r"claude|gemini|llama))\b", re.I), "chat/LLM UI text"),
        (re.compile(r"(gpt-4|gpt-3\.5|claude-|gemini-|llama-?\d|mistral|text-embedding)",
                    re.I), "model name referenced"),
        (re.compile(r"(system\s*prompt|you are a helpful|as an ai language model)",
                    re.I), "system-prompt style text leaked"),
    ]

    def _probe(self, base, path, ctx):
        url = base + path
        try:
            r = ctx.session.get(url, timeout=ctx.timeout, allow_redirects=False)
        except Exception:  # noqa: BLE001
            return path, None
        ct = r.headers.get("Content-Type", "")
        info: Dict[str, object] = {"status": r.status_code, "ctype": ct[:40]}
        streaming = "text/event-stream" in ct
        looks_llm = (streaming or "json" in ct
                     or r.status_code in (200, 400, 401, 405, 422))
        if streaming:
            info["streaming_sse"] = True
        # a POST-only chat endpoint typically 405s on GET, or 400/422 asking for a body
        if r.status_code in (405, 400, 422) and looks_llm:
            info["note"] = "endpoint exists (expects POST body — likely a prompt sink)"
            return path, info
        if r.status_code == 200 and ("json" in ct or streaming):
            return path, info
        return path, None

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"endpoints": {}, "ui_signals": [], "input_forms": 0}
        # 1) landing page signals
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:120_000]
            for rx, label in self._BODY_HINTS:
                if rx.search(body):
                    findings["ui_signals"].append(label)
            findings["input_forms"] = len(re.findall(
                r'<(?:textarea|input)[^>]*(?:chat|message|prompt|ask|query)',
                body, re.I))
        except Exception:  # noqa: BLE001
            pass
        # 2) probe likely prompt-sink endpoints
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 8)) as ex:
            for path, info in ex.map(lambda p: self._probe(base, p, ctx), self._PATHS):
                if info:
                    findings["endpoints"][path] = info
        surface = (len(findings["endpoints"]) + len(findings["ui_signals"])
                   + (1 if findings["input_forms"] else 0))
        risk = "high" if surface >= 3 else "medium" if surface else "informational"
        return self.ok(host, {
            "llm_endpoints": findings["endpoints"] or "none found",
            "ui_signals": findings["ui_signals"] or "none",
            "prompt_input_fields": findings["input_forms"],
            "surface_score": surface,
            "risk": risk,
            "note": "these endpoints/inputs likely forward user text to an LLM and "
                    "are candidates for prompt injection / jailbreak testing. "
                    "Detection only — no prompts were sent."})
