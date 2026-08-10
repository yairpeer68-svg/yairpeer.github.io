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

import ipaddress
import os
import re
import socket
from typing import Any, Dict, List
from urllib.parse import urljoin, urlparse

from ..core import (Context, Module, Result, clean_host, ensure_scheme,
                    record_error, register)


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


# =========================================================================== #
#  originhunt — reveal the real server IP behind a CDN / WAF
# =========================================================================== #
# Published CDN IPv4 ranges (stable). An A record OUTSIDE all of these, reached
# via an origin-revealing channel (SPF/MX/origin subdomains), is a likely true
# origin. Detection/correlation only — no traffic tricks, no exploitation.
# CDN/WAF ranges now live in ghost_eye/netclass.py (14 providers, v4+v6,
# refreshable from the providers' published lists) — see _cdn_of below.
# subdomain prefixes that usually point straight at the origin (not fronted)
_ORIGIN_PREFIXES = ("origin", "direct", "direct-connect", "dev", "staging",
                    "stage", "test", "cpanel", "webmail", "mail", "smtp",
                    "ftp", "server", "backend", "api", "vpn", "portal",
                    "old", "legacy", "app")


def _resolve(name: str) -> List[str]:
    try:
        _, _, ips = socket.gethostbyname_ex(name)
        return sorted(set(ips))
    except OSError:
        return []


def _cdn_of(ip: str) -> str:
    """Which CDN/WAF an address belongs to, via the central classifier.

    This used to consult a private 4-provider table living in this file, which
    meant an Imperva, Sucuri, Azure Front Door or StackPath edge address was
    reported as a *true origin* — a confident false positive. ghost_eye.netclass
    knows 14 providers and is the single place ranges are maintained."""
    from ..netclass import classify_ip
    info = classify_ip(ip)
    return info["provider"] or "" if info["kind"] == "cdn" else ""


def _spf_ips(host: str, ctx: Context) -> List[str]:
    """IPs / include-hosts pulled from the SPF record — mail infra often shares
    the real origin netblock."""
    out: List[str] = []
    try:
        import dns.resolver
        for rr in dns.resolver.resolve(host, "TXT"):
            txt = str(rr).strip('"')
            if "v=spf1" not in txt.lower():
                continue
            for tok in txt.split():
                if tok.startswith("ip4:"):
                    out.append(tok[4:].split("/")[0])
                elif tok.startswith("include:") or tok.startswith("a:"):
                    inc = tok.split(":", 1)[1]
                    out.extend(_resolve(inc))
    except Exception:  # noqa: BLE001
        pass
    return out


@register
class OriginHunt(Module):
    id = "originhunt"
    name = "Origin server IP hunter (behind CDN/WAF)"
    category = "Network"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))

        fronted = _resolve(host)
        fronted_cdn = {ip: _cdn_of(ip) for ip in fronted}
        cdn_name = next((c for c in fronted_cdn.values() if c), "")

        # gather candidate IPs from origin-revealing channels
        candidates: Dict[str, List[str]] = {}

        def _add(via: str, ips: List[str]):
            for ip in ips:
                candidates.setdefault(ip, [])
                if via not in candidates[ip]:
                    candidates[ip].append(via)

        for pfx in _ORIGIN_PREFIXES:
            _add(f"sub:{pfx}", _resolve(f"{pfx}.{host}"))
        _add("spf", _spf_ips(host, ctx))
        try:
            import dns.resolver
            for m in dns.resolver.resolve(host, "MX"):
                mx = str(m.exchange).rstrip(".")
                _add(f"mx:{mx}", _resolve(mx))
        except Exception:  # noqa: BLE001
            pass

        # classify: a candidate not in any CDN range and not equal to a fronted
        # CDN IP is a likely true origin
        likely: List[Dict[str, Any]] = []
        cdn_hits: List[str] = []
        for ip, vias in candidates.items():
            cdn = _cdn_of(ip)
            if cdn:
                cdn_hits.append(ip)
                continue
            if ip in fronted and not cdn_name:
                continue  # apex not behind a known CDN — nothing to unmask
            likely.append({"ip": ip, "via": vias,
                           "confidence": "high" if any(
                               v.startswith(("sub:origin", "sub:direct", "mx:",
                                             "spf")) for v in vias) else "medium"})
        likely.sort(key=lambda x: 0 if x["confidence"] == "high" else 1)

        data: Dict[str, Any] = {
            "fronted_ips": fronted or "none",
            "cdn_detected": cdn_name or "none (may be direct)",
            "candidate_origins": len(likely),
        }
        if likely:
            data["likely_origins"] = likely[:15]
            data["severity"] = "medium" if cdn_name else "info"
            # Don't stop at "here is a list to check by hand": ask each
            # candidate for the site directly and compare what it serves.
            try:
                from ..origin import verify_origins
                verified = verify_origins(
                    ctx.session, str(host), [c["ip"] for c in likely],
                    timeout=max(5, min(ctx.timeout, 10)))
            except Exception as exc:  # noqa: BLE001 - verification is a bonus
                verified = {"error": str(exc)[:120]}
            confirmed = verified.get("confirmed_origins") or []
            possible = verified.get("possible_origins") or []
            data["verification"] = {
                k: v for k, v in verified.items() if k != "baseline"}
            data["confirmed_origins"] = confirmed or "none confirmed"
            if confirmed:
                data["severity"] = "high"
                data["note"] = (
                    f"ORIGIN CONFIRMED: {', '.join(confirmed)} served this site "
                    f"when asked directly with a Host header — the CDN/WAF at "
                    f"{cdn_name or 'the edge'} can be bypassed by contacting it "
                    f"directly. Restrict the origin to accept only CDN traffic.")
            elif possible:
                data["note"] = ("candidates partially matched the site — likely "
                                "the origin but not proven; verify manually.")
            else:
                data["note"] = ("candidates found via origin-revealing channels, "
                                "but none served the target's page when asked "
                                "directly, so none is confirmed as the origin.")
        else:
            data["note"] = ("no non-CDN origin candidate found via passive "
                            "channels; the origin may be well isolated.")
        return self.ok(host, data)


# =========================================================================== #
#  51  expirymon — domain + TLS certificate expiry monitor
# =========================================================================== #
import datetime as _dt
import ssl as _ssl


def _cert_not_after(host: str, timeout: int) -> "tuple":
    try:
        ctx = _ssl.create_default_context()
        with socket.create_connection((host, 443), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host) as ss:
                cert = ss.getpeercert()
        na = cert.get("notAfter")
        if not na:
            return None, None
        exp = _dt.datetime.strptime(na, "%b %d %H:%M:%S %Y %Z")
        days = (exp - _dt.datetime.utcnow()).days
        return exp.strftime("%Y-%m-%d"), days
    except Exception:  # noqa: BLE001
        return None, None


def _domain_expiry(host: str) -> "tuple":
    try:
        import whois  # optional dep
        w = whois.whois(host)
        exp = w.expiration_date
        if isinstance(exp, list):
            exp = exp[0] if exp else None
        if not exp:
            return None, None
        days = (exp - _dt.datetime.now()).days
        return exp.strftime("%Y-%m-%d"), days
    except Exception:  # noqa: BLE001
        return None, None


@register
class ExpiryMon(Module):
    id = "expirymon"
    name = "Domain + TLS certificate expiry monitor"
    category = "Threat Intel"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        t = getattr(ctx, "timeout", 15)
        cert_date, cert_days = _cert_not_after(host, t)
        dom_date, dom_days = _domain_expiry(host)
        data: Dict[str, Any] = {}
        warns: List[str] = []
        if cert_date is not None:
            data["cert_expires"] = cert_date
            data["cert_days_left"] = cert_days
            if cert_days is not None and cert_days < 30:
                warns.append(f"TLS certificate expires in {cert_days} day(s)")
        if dom_date is not None:
            data["domain_expires"] = dom_date
            data["domain_days_left"] = dom_days
            if dom_days is not None and dom_days < 45:
                warns.append(f"domain registration expires in {dom_days} day(s)")
        if warns:
            data["warnings"] = warns
            soon = min([d for d in (cert_days, dom_days) if d is not None] or [999])
            data["severity"] = ("high" if soon < 14 else
                                "medium" if soon < 30 else "low")
        if not data:
            data["note"] = "could not read certificate/whois expiry"
        return self.ok(host, data)


# =========================================================================== #
#  52  dnschange — stable DNS fingerprint for change / defacement monitoring
# =========================================================================== #
import hashlib as _hashlib


@register
class DnsChange(Module):
    id = "dnschange"
    name = "DNS fingerprint (change monitoring)"
    category = "DNS"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        records: Dict[str, List[str]] = {}
        try:
            import dns.resolver
            for rtype in ("A", "AAAA", "MX", "NS", "TXT", "CNAME", "SOA"):
                try:
                    ans = dns.resolver.resolve(host, rtype)
                    records[rtype] = sorted(str(r).strip('"').rstrip(".")
                                            for r in ans)
                except Exception:  # noqa: BLE001
                    continue
        except Exception:  # noqa: BLE001
            # fall back to a plain A lookup so the module still fingerprints
            ips = _resolve(host)
            if ips:
                records["A"] = ips
        # stable fingerprint over the sorted record set — the change-monitor
        # / alert layer diffs this between scans to detect DNS drift.
        blob = "\n".join(f"{k}={','.join(v)}" for k, v in sorted(records.items()))
        fp = _hashlib.sha256(blob.encode()).hexdigest()[:16] if blob else ""
        data: Dict[str, Any] = {"records": records,
                                "record_types": len(records),
                                "dns_fingerprint": fp}
        if not records:
            data["note"] = "no DNS records resolved"
        return self.ok(host, data)
