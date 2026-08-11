"""Content-Security-Policy as an asset-discovery source.

A CSP is a list, written by the target themselves, of every host their pages are
allowed to talk to. Nobody publishes their infrastructure more accurately than
that — it is maintained by the people who know, it breaks the site when it is
wrong, and it costs one HTTP request to read. Subdomain brute-forcing guesses;
a CSP simply tells you.

Most tooling treats CSP purely as a hardening check ("is unsafe-inline set?")
and throws the host list away. This module mines it as intelligence:

* **Per-directive meaning.** A host in ``connect-src`` is an API the front end
  calls; in ``frame-ancestors`` it is a partner permitted to embed the site; in
  ``form-action`` it is where credentials may be posted. Flattening them into
  one "domains" list, as the older ``cspdomains`` module did, throws away the
  part that says *what each host is*.
* **Report endpoints.** ``report-uri`` / ``report-to`` point at whoever collects
  violation reports, and are very often an internal or vendor hostname that
  appears nowhere else in public DNS.
* **Report-Only is a preview.** Sites stage the *next* policy in
  ``Content-Security-Policy-Report-Only`` before enforcing it, so it routinely
  names hosts that are being built out and are not live yet.
* **The delta is the finding.** Cross-referenced against the hosts a scan
  already discovered, the answer becomes "CSP names 7 hosts your subdomain
  enumeration missed" — which is the whole point.

Reading a header is passive; nothing here contacts the hosts it discovers.
"""

from __future__ import annotations

import re
from typing import Any, Dict, Iterable, List, Optional, Set

# Directives whose sources are hosts worth harvesting, and what a host in each
# one actually means. Ordered most- to least-interesting for reporting.
DIRECTIVE_MEANING: Dict[str, str] = {
    "connect-src": "an API/websocket the front end talks to",
    "form-action": "where this site is allowed to POST forms — credentials go here",
    "frame-ancestors": "who may embed this site — named partners",
    "frame-src": "embedded applications (payments, chat, video)",
    "script-src": "code executed in the page's origin",
    "script-src-elem": "code executed in the page's origin",
    "worker-src": "background workers",
    "child-src": "embedded contexts",
    "manifest-src": "app manifest host",
    "media-src": "audio/video hosts",
    "img-src": "image/asset hosts (often a CDN or bucket)",
    "font-src": "font hosts",
    "style-src": "stylesheet hosts",
    "default-src": "fallback for anything not named above",
    "base-uri": "permitted <base> targets",
    "object-src": "plugin content",
}

# Report sinks: not content sources, but they name a collector — frequently an
# internal hostname that shows up nowhere else.
REPORT_DIRECTIVES = ("report-uri", "report-to", "report_uri")

# Keyword sources that are not hosts.
_KEYWORDS = {
    "'self'", "'none'", "'unsafe-inline'", "'unsafe-eval'", "'strict-dynamic'",
    "'unsafe-hashes'", "'report-sample'", "'wasm-unsafe-eval'", "'inline-speculation-rules'",
}

# Directive values that weaken the policy, with why they matter.
WEAKNESSES: Dict[str, str] = {
    "'unsafe-inline'": "inline script/style permitted — CSP provides little XSS protection",
    "'unsafe-eval'": "eval() permitted — string-to-code execution is allowed",
    "'unsafe-hashes'": "inline event handlers permitted",
    "data:": "data: URIs permitted — a common CSP bypass for script/img exfiltration",
    "*": "wildcard source — any host is allowed",
    "http:": "plaintext scheme permitted — content may be injected in transit",
    "'strict-dynamic'": "trust propagates to scripts loaded by trusted scripts",
}

# Multi-label public suffixes. Not the full PSL (that is a 200KB download this
# package will not take on), but the ones that actually appear: without them
# "foo.example.co.uk" reduces to "co.uk" and every unrelated .co.uk host in the
# policy is misfiled as the target's own infrastructure.
_MULTI_SUFFIXES = {
    "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk", "net.uk", "sch.uk",
    "co.il", "org.il", "net.il", "ac.il", "gov.il", "muni.il", "idf.il",
    "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
    "co.nz", "net.nz", "org.nz", "govt.nz", "ac.nz",
    "com.br", "net.br", "org.br", "gov.br", "edu.br",
    "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "lg.jp",
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
    "co.in", "net.in", "org.in", "gov.in", "ac.in", "edu.in",
    "com.mx", "com.ar", "com.tr", "com.sg", "com.hk", "com.tw", "com.my",
    "com.ua", "com.pl", "com.ru", "co.za", "org.za", "co.kr", "or.kr",
    "com.es", "com.pt", "co.id", "or.id", "com.ph", "com.vn", "com.sa",
    "github.io", "gitlab.io", "pages.dev", "workers.dev", "vercel.app",
    "netlify.app", "herokuapp.com", "azurewebsites.net", "cloudfront.net",
    "amazonaws.com", "s3.amazonaws.com", "web.app", "firebaseapp.com",
}


def registrable_domain(host: str) -> str:
    """The registrable ("apex") domain of a hostname.

    ``a.b.example.co.uk`` -> ``example.co.uk``, not ``co.uk``. Getting this
    wrong is how a tool decides that every unrelated ``.co.uk`` in a policy
    belongs to the target.
    """
    name = _hostname(host)
    if not name or _is_ip(name):
        return name
    labels = name.split(".")
    if len(labels) < 2:
        return name
    if len(labels) >= 3 and ".".join(labels[-2:]) in _MULTI_SUFFIXES:
        return ".".join(labels[-3:])
    return ".".join(labels[-2:])


def _hostname(value: str) -> str:
    """Reduce anything host-shaped to a bare hostname.

    This is fed both CSP sources (already bare hostnames, mostly) and the scan
    target, which is routinely a full URL. Splitting ``http://127.0.0.1:8894``
    on dots without stripping the scheme and port produces ``0.1:8894`` and
    reports it as the site's apex domain — nonsense that then propagates into
    every "is this host ours?" decision downstream.
    """
    # str() first: `(value or "")` keeps a non-empty non-string intact and
    # the .strip() below then raises. This function promises to reduce
    # "anything host-shaped", so it has to survive being handed a number.
    name = str(value or "").strip().lower()
    if "//" in name:                      # scheme://…
        name = name.split("//", 1)[1]
    name = name.split("/", 1)[0]          # path
    name = name.split("?", 1)[0].split("#", 1)[0]
    if "@" in name:                       # userinfo
        name = name.rsplit("@", 1)[1]
    if name.startswith("["):              # bracketed IPv6, optionally with :port
        end = name.find("]")
        if end > 0:
            return name[:end + 1]
    elif name.count(":") == 1:            # host:port (a bare IPv6 has more)
        name = name.split(":", 1)[0]
    return name.strip(".")


def _is_ip(value: str) -> bool:
    return bool(re.fullmatch(r"[\d.]+|\[?[0-9a-fA-F:]+\]?", value or "")) and \
        any(c in (value or "") for c in ".:")


def parse_csp(policy: str) -> Dict[str, List[str]]:
    """A CSP string -> {directive: [source, ...]}, lower-cased directives."""
    out: Dict[str, List[str]] = {}
    for chunk in (policy or "").split(";"):
        parts = chunk.split()
        if not parts:
            continue
        name = parts[0].lower()
        # a repeated directive is a policy error; keep the union rather than
        # silently dropping either half
        out.setdefault(name, [])
        for src in parts[1:]:
            if src not in out[name]:
                out[name].append(src)
    return out


def source_host(source: str) -> str:
    """The hostname a CSP source expression refers to, or "" if it is not one."""
    src = (source or "").strip()
    if not src or src.lower() in _KEYWORDS or src.startswith("'"):
        return ""
    if re.fullmatch(r"(?:sha|nonce)-[A-Za-z0-9+/=_-]+", src.strip("'")):
        return ""
    if src in ("*", "data:", "blob:", "filesystem:", "mediastream:",
               "http:", "https:", "ws:", "wss:"):
        return ""
    src = re.sub(r"^[a-z][a-z0-9+.-]*://", "", src, flags=re.I)   # scheme
    src = src.split("/")[0]                                        # path
    src = re.sub(r":\d+$", "", src)                                # port
    if not src or "." not in src:
        return ""
    if not re.fullmatch(r"[*A-Za-z0-9._-]+", src):
        return ""
    return src.lower().strip(".")


def collect_policies(session, host: str, timeout: int = 15) -> Dict[str, Any]:
    """Every policy the site publishes: enforced header, Report-Only, and meta.

    Report-Only is fetched deliberately — a site stages its *next* policy there,
    so it names hosts that are being built out before they are live.
    """
    out = {"enforced": "", "report_only": "", "meta": "", "errors": []}
    for scheme in ("https", "http"):
        try:
            resp = session.get(f"{scheme}://{host}", timeout=timeout,
                               allow_redirects=True)
        except Exception as exc:  # noqa: BLE001
            out["errors"].append(f"{scheme}: {str(exc)[:60]}")
            continue
        headers = getattr(resp, "headers", {}) or {}
        lower = {str(k).lower(): v for k, v in dict(headers).items()}
        out["enforced"] = lower.get("content-security-policy", "") or ""
        out["report_only"] = lower.get(
            "content-security-policy-report-only", "") or ""
        text = getattr(resp, "text", "") or ""
        match = re.search(
            r'<meta[^>]+http-equiv=["\']?content-security-policy["\']?[^>]*'
            r'content=["\']([^"\']+)', text[:200_000], re.I)
        if match:
            out["meta"] = match.group(1)
        break
    return out


def analyse(host: str, policies: Dict[str, Any],
            known_hosts: Optional[Iterable[str]] = None) -> Dict[str, Any]:
    """Turn the collected policies into an asset map plus a hardening view."""
    apex = registrable_domain(host)
    known = {str(h).strip().lower().lstrip("*.") for h in (known_hosts or []) if h}

    by_directive: Dict[str, List[str]] = {}
    report_endpoints: List[str] = []
    weaknesses: List[Dict[str, str]] = []
    seen_sources: Set[str] = set()
    staged_only: Set[str] = set()

    for label in ("enforced", "meta", "report_only"):
        policy = policies.get(label) or ""
        if not policy:
            continue
        parsed = parse_csp(policy)
        for directive, sources in parsed.items():
            if directive in REPORT_DIRECTIVES:
                for src in sources:
                    if src not in report_endpoints:
                        report_endpoints.append(src)
                continue
            for src in sources:
                low = src.lower()
                if low in WEAKNESSES and label != "report_only":
                    entry = {"directive": directive, "source": src,
                             "why": WEAKNESSES[low]}
                    if entry not in weaknesses:
                        weaknesses.append(entry)
                name = source_host(src)
                if not name:
                    continue
                bucket = by_directive.setdefault(directive, [])
                if name not in bucket:
                    bucket.append(name)
                if label == "report_only" and name not in seen_sources:
                    staged_only.add(name)
                seen_sources.add(name)

    own, related, external = [], [], []
    for name in sorted(seen_sources):
        bare = name.lstrip("*.")
        if bare == apex or bare.endswith("." + apex):
            own.append(name)
        elif _shares_a_name_token(bare, apex):
            related.append(name)
        else:
            external.append(name)

    undiscovered = sorted(h for h in own + related
                          if h.lstrip("*.") not in known and "*" not in h)

    return {
        "host": host,
        "apex": apex,
        "csp_present": bool(policies.get("enforced") or policies.get("meta")),
        "report_only_present": bool(policies.get("report_only")),
        "hosts_by_directive": {
            d: sorted(v) for d, v in sorted(
                by_directive.items(),
                key=lambda kv: list(DIRECTIVE_MEANING).index(kv[0])
                if kv[0] in DIRECTIVE_MEANING else 99)},
        "directive_meaning": {d: DIRECTIVE_MEANING[d] for d in by_directive
                              if d in DIRECTIVE_MEANING},
        "own_infrastructure": own,
        "probably_related": related,
        "third_parties": external,
        "third_party_count": len(external),
        "report_endpoints": report_endpoints or "none",
        "staged_in_report_only": sorted(staged_only) or "none",
        "new_hosts_not_otherwise_found": undiscovered or "none",
        "new_host_count": len(undiscovered),
        "weaknesses": weaknesses or "none",
        "errors": policies.get("errors") or "none",
        "note": ("a CSP is the target's own list of hosts their pages may talk "
                 "to — the most accurate asset source there is, and it costs one "
                 "request. connect-src names APIs, form-action names where "
                 "credentials may be posted, frame-ancestors names partners, and "
                 "report-uri names whoever collects violations (often an "
                 "internal host). Report-Only is where the *next* policy is "
                 "staged, so it can name infrastructure that is not live yet."),
    }


def _shares_a_name_token(name: str, apex: str) -> bool:
    """A different registrable domain that still carries the org's name.

    ``example-cdn.net`` next to ``example.com`` is very likely the same people;
    ``fonts.googleapis.com`` is not. Only tokens long enough to be a name count
    — matching on three characters would relate half the internet.
    """
    brand = apex.split(".")[0]
    if len(brand) < 4:
        return False
    other = registrable_domain(name).split(".")[0]
    return brand in other or other in brand


def csp_asset_map(session, host: str, known_hosts: Optional[Iterable[str]] = None,
                  timeout: int = 15) -> Dict[str, Any]:
    """Fetch and analyse in one call."""
    return analyse(host, collect_policies(session, host, timeout), known_hosts)
