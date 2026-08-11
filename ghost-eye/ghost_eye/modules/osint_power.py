"""OSINT power pack — two high-leverage passive OSINT modules.

* ``emailpattern`` — harvest a company's public e-mails, **infer its address
  format** (first.last@ / flast@ / first@ …) and, from person names found on the
  site, **generate the most likely addresses** for them. The classic move for
  turning "a domain" into "a list of probable e-mail addresses".

* ``certpivot`` — read the target's TLS certificate and pivot on it: every
  ``subjectAltName`` is a **sibling domain on the same certificate**, plus the
  issuer / subject organisation and validity. A strong, reliable relationship
  source that needs no third-party service.

Both are passive/read-only, wrap all I/O in try/except and degrade gracefully.
Reconnaissance/detection only. FOR AUTHORISED SECURITY TESTING ONLY.
"""

from __future__ import annotations

import re
import socket
import ssl
from typing import Any, Dict, List, Set
from urllib.parse import urljoin

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

_EMAIL_RE = re.compile(r"\b[A-Za-z0-9._%+\-]+@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})\b")
# a conservative "First Last" title-case name pair
_NAME_RE = re.compile(r"\b([A-Z][a-z]{1,15})\s+([A-Z][a-z]{1,15})\b")
_NAME_STOP = {
    "The", "This", "That", "Our", "Your", "About", "Contact", "Home", "Privacy",
    "Terms", "All", "Rights", "Reserved", "Read", "More", "Learn", "Sign", "Log",
    "Get", "New", "Cookie", "Policy", "Team", "Menu", "Skip", "Main", "Company",
    "Services", "Products", "Solutions", "Support", "Careers", "Blog", "News",
}


def _fetch(ctx: Context, url: str) -> str:
    try:
        r = ctx.session.get(url, timeout=getattr(ctx, "timeout", 15))
        if getattr(r, "status_code", 0) == 200:
            return r.text or ""
    except Exception:  # noqa: BLE001
        pass
    return ""


def _classify_pattern(local: str, first: str = "", last: str = "") -> str:
    """Best-effort classify a local-part into a named pattern."""
    lo = local.lower()
    if "." in lo:
        a, b = lo.split(".", 1)
        if a.isalpha() and b.isalpha():
            return "flast" if len(a) == 1 else "first.last"
    if "_" in lo:
        return "first_last"
    if lo.isalpha():
        return "first_or_last"      # ambiguous single token
    return "other"


@register
class EmailPattern(Module):
    id = "emailpattern"
    name = "Corporate e-mail pattern + address generator"
    category = "OSINT"
    target_kind = "domain"

    _PAGES = ("", "/about", "/about-us", "/team", "/our-team", "/people",
              "/contact", "/contact-us", "/staff", "/leadership")

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host)
        emails: Set[str] = set()
        names: Set[str] = set()
        for path in self._PAGES:
            html = _fetch(ctx, urljoin(base, path))
            if not html:
                continue
            for m in _EMAIL_RE.finditer(html):
                addr = m.group(0).lower()
                if addr.endswith("@" + host) or ("@" + host) in addr:
                    emails.add(addr)
            for a, b in _NAME_RE.findall(html):
                if a not in _NAME_STOP and b not in _NAME_STOP:
                    names.add(f"{a} {b}")
            if len(emails) >= 25:
                break

        # infer the dominant pattern from the harvested local parts
        patterns: Dict[str, int] = {}
        for e in emails:
            local = e.split("@", 1)[0]
            p = _classify_pattern(local)
            patterns[p] = patterns.get(p, 0) + 1
        dominant = max(patterns, key=patterns.get) if patterns else ""

        # generate candidate addresses for discovered names using the pattern
        gen: List[str] = []
        fmt = {"first.last": "{f}.{l}", "flast": "{fi}{l}",
               "first_last": "{f}_{l}", "first_or_last": "{f}"}.get(dominant)
        if fmt:
            for name in sorted(names)[:30]:
                f, _, l = name.lower().partition(" ")
                if not l:
                    continue
                addr = fmt.format(f=f, l=l, fi=f[:1]) + "@" + host
                gen.append(addr)

        data: Dict[str, Any] = {
            "emails_found": sorted(emails)[:40],
            "email_count": len(emails),
            "names_found": sorted(names)[:30],
            "inferred_pattern": dominant or "unknown",
            "pattern_breakdown": patterns,
        }
        if gen:
            data["generated_candidates"] = gen[:40]
            data["note"] = ("candidate addresses are inferred from the observed "
                            "pattern — verify before use (SMTP/validation).")
        return self.ok(host, data)


@register
class CertPivot(Module):
    id = "certpivot"
    name = "TLS certificate pivot (sibling domains)"
    category = "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        port = 443
        if ":" in str(target).split("//")[-1]:
            try:
                port = int(str(target).rsplit(":", 1)[-1].split("/")[0])
            except (ValueError, IndexError):
                port = 443
        cert = None
        try:
            ctxs = ssl.create_default_context()
            with socket.create_connection((host, port),
                                          timeout=getattr(ctx, "timeout", 15)) as sock:
                with ctxs.wrap_socket(sock, server_hostname=host) as ss:
                    cert = ss.getpeercert()
        except Exception as exc:  # noqa: BLE001
            return self.fail(target, f"TLS handshake failed: {exc}")
        if not cert:
            return self.ok(host, {"note": "no certificate returned"})

        sans: List[str] = []
        for typ, val in cert.get("subjectAltName", []) or []:
            if typ.lower() == "dns":
                sans.append(str(val).lower().lstrip("*.").rstrip("."))
        # sibling domains = SAN apexes that aren't the target itself
        siblings: Set[str] = set()
        for s in sans:
            if s and s != host and not host.endswith("." + s) and not s.endswith("." + host):
                siblings.add(s)

        def _org(field):
            for rdn in cert.get(field, ()):  # tuple of tuples
                for k, v in rdn:
                    if k in ("organizationName", "commonName"):
                        return v
            return ""

        data: Dict[str, Any] = {
            "san_domains": sorted(set(sans))[:60],
            "san_count": len(set(sans)),
            "related_domains": sorted(siblings)[:40],   # feeds the correlator/pivot
            "issuer_org": _org("issuer"),
            "subject_org": _org("subject"),
            "valid_from": cert.get("notBefore", ""),
            "valid_to": cert.get("notAfter", ""),
            "serial": cert.get("serialNumber", ""),
        }
        if siblings:
            data["note"] = (f"{len(siblings)} sibling domain(s) share this "
                            "certificate — strong infrastructure link.")
        return self.ok(host, data)
