"""Wave batch — three new detection modules:

* ``jssecrets``  — pull the site's JavaScript and scan it for leaked secrets
  (API keys, tokens, private keys) via a curated high-signal regex set (21).
* ``sigscan``    — a lightweight nuclei-style signature engine: load YAML/JSON
  rules (path / regex / status) and test them against the target (24).
* ``iamexpose``  — probe for exposed cloud-credential / IAM artifacts
  (`.aws/credentials`, service-account JSON, over-permissive policy files) (25).

All three go through ``ctx.session``, wrap every network call in try/except,
parse defensively and only report high-signal matches (no generic-page false
positives). Reconnaissance / detection only — nothing is ever exploited.
FOR AUTHORISED SECURITY TESTING ONLY.
"""

from __future__ import annotations

import os
import re
from typing import Any, Dict, List
from urllib.parse import urljoin, urlparse

from ..core import Context, Module, Result, ensure_scheme, record_error, register


def _get(ctx: Context, url: str):
    try:
        return ctx.session.get(url, timeout=getattr(ctx, "timeout", 15))
    except Exception:  # noqa: BLE001
        return None


def _text(resp) -> str:
    try:
        return resp.text or ""
    except Exception:  # noqa: BLE001
        return ""


def _status(resp) -> int:
    try:
        return int(getattr(resp, "status_code", 0) or 0)
    except Exception:  # noqa: BLE001
        return 0


# =========================================================================== #
#  21  jssecrets — leaked secrets inside the site's JavaScript
# =========================================================================== #
# high-signal patterns only: each has a distinctive prefix/shape so a generic
# HTML page can't trip them.
_SECRET_PATTERNS = {
    "aws_access_key_id": re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    "aws_secret_hint": re.compile(r"aws_secret_access_key\s*[=:]\s*['\"][0-9a-zA-Z/+]{40}['\"]"),
    "google_api_key": re.compile(r"\bAIza[0-9A-Za-z\-_]{35}\b"),
    "slack_token": re.compile(r"\bxox[baprs]-[0-9A-Za-z\-]{10,48}\b"),
    "stripe_secret": re.compile(r"\bsk_live_[0-9a-zA-Z]{24,}\b"),
    "github_token": re.compile(r"\bghp_[0-9A-Za-z]{36}\b"),
    "slack_webhook": re.compile(r"https://hooks\.slack\.com/services/[A-Za-z0-9/]{20,}"),
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
    "jwt": re.compile(r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}"),
    "generic_secret": re.compile(
        r"(?:api[_-]?key|secret|passwd|password|token)\s*[:=]\s*"
        r"['\"][0-9a-zA-Z\-_]{16,45}['\"]", re.I),
}
_SCRIPT_SRC = re.compile(r"<script[^>]+src=['\"]([^'\"]+)['\"]", re.I)


def _redact(s: str) -> str:
    s = s.strip()
    return (s[:6] + "…" + s[-4:]) if len(s) > 14 else (s[:3] + "…")


@register
class JsSecrets(Module):
    id = "jssecrets"
    name = "JavaScript secret leak scanner"
    category = "Exposure"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        base = ensure_scheme(target)
        host = urlparse(base).netloc
        resp = _get(ctx, base)
        if resp is None:
            return self.fail(target, "could not fetch target")
        html = _text(resp)
        # same-host script URLs + the inline HTML itself
        blobs: List[tuple] = [("(inline)", html)]
        srcs = _SCRIPT_SRC.findall(html)[:20]
        for src in srcs:
            u = urljoin(base, src)
            if urlparse(u).netloc and urlparse(u).netloc != host:
                continue                      # first-party JS only
            r = _get(ctx, u)
            if r is not None and _status(r) == 200:
                blobs.append((src, _text(r)[:400000]))

        findings: List[Dict[str, Any]] = []
        seen: set = set()
        for where, body in blobs:
            for kind, rx in _SECRET_PATTERNS.items():
                for m in rx.findall(body)[:5]:
                    val = m if isinstance(m, str) else (m[0] if m else "")
                    key = (kind, val)
                    if not val or key in seen:
                        continue
                    seen.add(key)
                    findings.append({"type": kind, "source": where,
                                     "match": _redact(val)})
        data: Dict[str, Any] = {"scripts_scanned": len(blobs),
                                "secrets_found": len(findings)}
        if findings:
            data["findings"] = findings[:40]
            data["severity"] = "high"
        return self.ok(target, data)


# =========================================================================== #
#  24  sigscan — nuclei-style signature engine (YAML/JSON rules)
# =========================================================================== #
# Built-in starter rules; a user file (GHOSTEYE_SIGNATURES or
# ~/.ghosteye/signatures.yaml|json) is merged/overrides on top.
_BUILTIN_SIGS = [
    {"id": "git-config", "name": "Exposed .git/config", "path": "/.git/config",
     "match": r"\[core\]|\[remote", "severity": "high"},
    {"id": "env-file", "name": "Exposed .env", "path": "/.env",
     "match": r"(?i)^[A-Z0-9_]+=", "severity": "high"},
    {"id": "ds-store", "name": "Exposed .DS_Store", "path": "/.DS_Store",
     "match": r"Bud1|\x00\x00\x00\x01Bud1", "severity": "low"},
    {"id": "phpinfo", "name": "phpinfo() page", "path": "/phpinfo.php",
     "match": r"phpinfo\(\)|PHP Version", "severity": "medium"},
    {"id": "wp-config-bak", "name": "WordPress config backup",
     "path": "/wp-config.php.bak", "match": r"DB_PASSWORD|DB_NAME",
     "severity": "high"},
]


def _load_sig_rules() -> List[dict]:
    path = os.environ.get("GHOSTEYE_SIGNATURES", "")
    candidates = [path] if path else []
    candidates += [os.path.expanduser("~/.ghosteye/signatures.yaml"),
                   os.path.expanduser("~/.ghosteye/signatures.json")]
    rules = list(_BUILTIN_SIGS)
    for cand in candidates:
        if not cand or not os.path.exists(cand):
            continue
        try:
            with open(cand, encoding="utf-8") as fh:
                raw = fh.read()
            data = None
            if cand.endswith(".json"):
                import json
                data = json.loads(raw)
            else:
                try:
                    import yaml
                    data = yaml.safe_load(raw)
                except Exception:  # noqa: BLE001 - yaml optional
                    import json
                    data = json.loads(raw)
            items = data.get("rules", data) if isinstance(data, dict) else data
            if isinstance(items, list):
                rules += [r for r in items if isinstance(r, dict) and r.get("path")]
        except Exception as exc:  # noqa: BLE001
            record_error("sigscan rules", cand, exc)
        break
    return rules


@register
class SigScan(Module):
    id = "sigscan"
    name = "Signature engine (nuclei-style rules)"
    category = "Exposure"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        base = ensure_scheme(target).rstrip("/")
        rules = _load_sig_rules()
        hits: List[Dict[str, Any]] = []
        tested = 0
        for rule in rules[:80]:
            path = str(rule.get("path", ""))
            if not path:
                continue
            tested += 1
            r = _get(ctx, base + (path if path.startswith("/") else "/" + path))
            if r is None or _status(r) != 200:
                continue
            body = _text(r)
            pat = rule.get("match")
            try:
                ok = True if not pat else bool(re.search(pat, body[:200000]))
            except re.error:
                ok = False
            if ok:
                hits.append({"id": rule.get("id", "?"),
                             "name": rule.get("name", rule.get("id", "?")),
                             "path": path,
                             "severity": rule.get("severity", "info")})
        data: Dict[str, Any] = {"rules_tested": tested, "matches": len(hits)}
        if hits:
            data["findings"] = hits
            sev = [h["severity"] for h in hits]
            data["severity"] = ("high" if "high" in sev else
                                "medium" if "medium" in sev else "low")
        return self.ok(target, data)


# =========================================================================== #
#  25  iamexpose — exposed cloud-credential / IAM artifacts
# =========================================================================== #
_IAM_PATHS = {
    "/.aws/credentials": r"aws_access_key_id|aws_secret_access_key",
    "/.aws/config": r"\[profile |region\s*=",
    "/credentials.json": r"\"type\"\s*:\s*\"service_account\"|private_key_id",
    "/service-account.json": r"service_account|private_key_id",
    "/.azure/credentials": r"\[.*\]|client_secret",
    "/.boto": r"aws_access_key_id|gs_access_key_id",
    "/gcloud/credentials.db": r"access_token|refresh_token",
    "/iam-policy.json": r"\"Effect\"\s*:\s*\"Allow\"|\"Action\"\s*:\s*\"\*\"",
    "/policy.json": r"\"Action\"\s*:\s*\"\*\"|\"Resource\"\s*:\s*\"\*\"",
}


@register
class IamExpose(Module):
    id = "iamexpose"
    name = "Exposed cloud IAM / credential files"
    category = "Cloud"
    target_kind = "url"

    def run(self, target: str, ctx: Context) -> Result:
        base = ensure_scheme(target).rstrip("/")
        hits: List[Dict[str, Any]] = []
        for path, pat in _IAM_PATHS.items():
            r = _get(ctx, base + path)
            if r is None or _status(r) != 200:
                continue
            body = _text(r)
            try:
                if re.search(pat, body[:100000]):
                    over = bool(re.search(r"\"Action\"\s*:\s*\"\*\"|"
                                          r"\"Resource\"\s*:\s*\"\*\"", body))
                    hits.append({"path": path,
                                 "over_permissive": over,
                                 "severity": "high"})
            except re.error:
                continue
        data: Dict[str, Any] = {"paths_checked": len(_IAM_PATHS),
                                "exposed": len(hits)}
        if hits:
            data["findings"] = hits
            data["severity"] = "high"
        return self.ok(target, data)
