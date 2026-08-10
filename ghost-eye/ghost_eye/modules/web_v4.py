"""Web AppSec surface detection (v3.8 new features). Detection only — these
modules fingerprint risky surface, they never send exploit payloads.
FOR AUTHORISED SECURITY TESTING ONLY."""

from __future__ import annotations

import re
from typing import Dict, List
from urllib.parse import urljoin, urlparse, parse_qs

from ..core import Context, Module, clean_host, ensure_scheme, register


def _fetch(ctx: Context, host: str):
    return ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)


def _script_srcs(base: str, html: str) -> List[str]:
    out = []
    for m in re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', html, re.I):
        out.append(urljoin(base, m))
    return out


# --------------------------------------------------------------------------- #
#  #4 Prototype-pollution indicators
# --------------------------------------------------------------------------- #
@register
class ProtoPollution(Module):
    id, name, category = "protopollute", "Prototype pollution indicators", "Web"
    target_kind = "url"

    # (lib, regex capturing version, first safe version)
    _VULN_LIBS = [
        ("jquery", re.compile(r"jquery[.-]?(\d+\.\d+\.\d+)", re.I), (3, 4, 0)),
        ("lodash", re.compile(r"lodash[.-]?(\d+\.\d+\.\d+)", re.I), (4, 17, 12)),
        ("hoek", re.compile(r"hoek[.-]?(\d+\.\d+\.\d+)", re.I), (4, 2, 1)),
    ]
    _SINKS = [
        (re.compile(r"__proto__"), "__proto__ referenced in client code"),
        (re.compile(r"constructor\s*\.\s*prototype"), "constructor.prototype access"),
        (re.compile(r"\$\.extend\s*\(\s*true"), "jQuery deep $.extend(true, …)"),
        (re.compile(r"\b(deepmerge|deep-merge|lodash\.merge|_\.merge)\b"), "recursive merge helper"),
        (re.compile(r"Object\.assign\s*\([^)]*location|Object\.assign\s*\([^)]*param", re.I),
         "Object.assign fed from URL/param"),
    ]

    def _cmp(self, ver: str, safe) -> bool:
        try:
            parts = tuple(int(x) for x in ver.split(".")[:3])
            return parts < safe
        except ValueError:
            return False

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _fetch(ctx, host)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        base = ensure_scheme(host)
        blob = r.text[:200_000]
        # pull in a couple of external scripts too
        for src in _script_srcs(base, blob)[:4]:
            try:
                blob += "\n" + ctx.session.get(src, timeout=ctx.timeout).text[:100_000]
            except Exception:  # noqa: BLE001
                continue
        vuln_libs, sinks = [], []
        for name, rx, safe in self._VULN_LIBS:
            m = rx.search(blob)
            if m and self._cmp(m.group(1), safe):
                vuln_libs.append(f"{name} {m.group(1)} (< {'.'.join(map(str, safe))})")
        for rx, label in self._SINKS:
            if rx.search(blob):
                sinks.append(label)
        # URL params are the usual injection entry point
        params = list(parse_qs(urlparse(base).query).keys())
        risk = ("high" if vuln_libs and sinks else "medium" if (vuln_libs or sinks)
                else "informational")
        return self.ok(host, {
            "vulnerable_libraries": vuln_libs or "none detected",
            "client_side_sinks": sinks or "none detected",
            "url_parameters": params or "none",
            "risk": risk,
            "note": "prototype-pollution indicators only — verify by testing "
                    "'?__proto__[x]=y' style inputs in an authorised assessment"})


# --------------------------------------------------------------------------- #
#  #6 CSP bypass gadgets
# --------------------------------------------------------------------------- #
@register
class CspBypass(Module):
    id, name, category = "cspbypass", "CSP bypass gadget finder", "Web"
    target_kind = "url"

    # CDNs that historically host JSONP / Angular / bypass gadgets
    _GADGET_HOSTS = [
        "ajax.googleapis.com", "cdnjs.cloudflare.com", "unpkg.com",
        "cdn.jsdelivr.net", "code.jquery.com", "maxcdn.bootstrapcdn.com",
        "stackpath.bootstrapcdn.com", "www.google.com", "translate.google.com",
        "apis.google.com",
    ]

    def _get_csp(self, r, html: str) -> str:
        csp = r.headers.get("Content-Security-Policy", "")
        if not csp:
            m = re.search(r'http-equiv=["\']Content-Security-Policy["\'][^>]*'
                          r'content=["\']([^"\']+)', html, re.I)
            if m:
                csp = m.group(1)
        return csp

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _fetch(ctx, host)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        csp = self._get_csp(r, r.text[:60_000])
        if not csp:
            return self.ok(host, {"csp": "ABSENT",
                                  "risk": "high",
                                  "note": "no CSP at all — every inline/remote script "
                                          "runs; add a script-src policy"})
        low = csp.lower()
        gadgets, weaknesses = [], []
        if "'unsafe-inline'" in low:
            weaknesses.append("script-src allows 'unsafe-inline'")
        if "'unsafe-eval'" in low:
            weaknesses.append("script-src allows 'unsafe-eval'")
        if re.search(r"(script-src|default-src)[^;]*\*", low):
            weaknesses.append("wildcard * source in script/default-src")
        if "data:" in low and "script-src" in low:
            weaknesses.append("data: allowed as a script source")
        if "object-src" not in low:
            weaknesses.append("no object-src (plugin/flash gadget surface)")
        if "base-uri" not in low:
            weaknesses.append("no base-uri (base-tag injection gadget)")
        for gh in self._GADGET_HOSTS:
            if gh in low:
                gadgets.append(gh)
        has_nonce = "nonce-" in low
        strict = "'strict-dynamic'" in low
        risk = ("high" if (gadgets or "'unsafe-inline'" in low) and not strict
                else "medium" if weaknesses else "low")
        return self.ok(host, {
            "csp": csp[:400],
            "bypass_gadget_cdns": gadgets or "none of the known list",
            "weaknesses": weaknesses or "none obvious",
            "uses_nonce": has_nonce,
            "uses_strict_dynamic": strict,
            "risk": risk,
            "note": "whitelisted CDNs that host JSONP/AngularJS are classic CSP "
                    "bypasses; 'strict-dynamic' + nonces neutralise most of them"})


# --------------------------------------------------------------------------- #
#  #7 Path-traversal / LFI surface (passive)
# --------------------------------------------------------------------------- #
@register
class LfiSurface(Module):
    id, name, category = "lfisurface", "Path-traversal / LFI surface", "Web"
    target_kind = "url"

    _SUSPECT = {"file", "path", "page", "include", "inc", "template", "tpl",
                "doc", "document", "folder", "dir", "root", "pg", "style",
                "lang", "view", "content", "read", "load", "download", "src",
                "filename", "filepath", "url", "site", "cat", "action"}

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = _fetch(ctx, host)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        html = r.text[:250_000]
        base = ensure_scheme(host)
        params: Dict[str, str] = {}
        # links with query strings
        for link in re.findall(r'(?:href|src|action)=["\']([^"\']+)["\']', html, re.I):
            q = parse_qs(urlparse(urljoin(base, link)).query)
            for p in q:
                if p.lower() in self._SUSPECT:
                    params[p] = urljoin(base, link)[:120]
        # form inputs
        for name in re.findall(r'<input[^>]+name=["\']([^"\']+)["\']', html, re.I):
            if name.lower() in self._SUSPECT:
                params.setdefault(name, "form input")
        # the target URL's own params
        for p in parse_qs(urlparse(base).query):
            if p.lower() in self._SUSPECT:
                params.setdefault(p, base[:120])
        risk = "medium" if params else "informational"
        return self.ok(host, {
            "suspect_parameters": params or "none found",
            "count": len(params),
            "risk": risk,
            "note": "parameters whose names imply a file path are traversal/LFI "
                    "candidates — test with encoded '../' and php://filter in an "
                    "authorised assessment. No payloads were sent."})
