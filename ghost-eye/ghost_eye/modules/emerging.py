"""Emerging / freshly-disclosed vulnerability early-warning.

NOT a zero-day *discovery* engine — a true zero-day (unknown to everyone) can't
be found by querying a database, because by definition it isn't in one; that is
vulnerability research (fuzzing, code/binary analysis), which a passive recon
tool neither does nor should. This is the achievable, defensive counterpart:
surfacing vulnerabilities that were disclosed *so recently* the settled
databases (NVD) haven't caught up — the window where you're exposed but the
CVE hasn't propagated yet.

It reads sources that lead NVD by days-to-weeks and are therefore the closest a
lookup can get to "just opened":

  * GitHub Security Advisories (GHSA) — frequently published before NVD
  * CISA KEV recent additions — *actively exploited right now*; a CVE added in
    the last fortnight is an emergency even if it's years old
  * the newest Nuclei detection templates — the community writes a detection
    template within hours of a vuln being weaponised, often days before the
    CVE record settles, so a just-added template is itself an early signal

and, when pointed at a target, cross-references the fresh disclosures against
the product/version tokens the target advertises, so the output is "a vuln just
dropped for software you actually run", not a firehose.

Detection / correlation only, all keyless. FOR AUTHORISED USE ONLY.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register
from .cve import _extract_products

_GHSA = "https://api.github.com/advisories"
_KEV = ("https://www.cisa.gov/sites/default/files/feeds/"
        "known_exploited_vulnerabilities.json")
_NUCLEI = ("https://api.github.com/repos/projectdiscovery/"
           "nuclei-templates/commits")
_DEFAULT_DAYS = 21
_CVE_RE = re.compile(r"CVE-\d{4}-\d{4,7}", re.I)


def _age_days(iso: str) -> int:
    """Whole days since an ISO-8601 timestamp; a large number if unparseable."""
    if not iso:
        return 10_000
    try:
        ts = datetime.fromisoformat(str(iso).replace("Z", "+00:00"))
        if ts.tzinfo is None:
            ts = ts.replace(tzinfo=timezone.utc)
        return max(0, (datetime.now(timezone.utc) - ts).days)
    except Exception:  # noqa: BLE001
        return 10_000


def _product_tokens(host: str, ctx: Context) -> List[str]:
    """The product names the target advertises, lower-cased, for matching."""
    try:
        r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
    except Exception:  # noqa: BLE001
        return []
    server = r.headers.get("Server", "")
    powered = r.headers.get("X-Powered-By", "")
    gen = ""
    m = re.search(r'name=["\']generator["\'][^>]*content=["\']([^"\']+)',
                  r.text[:60_000], re.I)
    if m:
        gen = m.group(1)
    tokens = set()
    for tok in _extract_products(server, powered, gen):
        tokens.add(tok.split()[0].lower())            # bare product name
    for raw in (server, powered, gen):
        for word in re.findall(r"[A-Za-z][A-Za-z0-9_+-]{2,}", raw):
            if word.lower() not in ("mod", "the", "com"):
                tokens.add(word.lower())
    return sorted(t for t in tokens if len(t) >= 3)


def fetch_ghsa(ctx: Context, days: int) -> List[dict]:
    """Recent GitHub Security Advisories, newest first, within `days`."""
    try:
        r = ctx.session.get(_GHSA, params={"sort": "published",
                                           "direction": "desc", "per_page": 100},
                            timeout=ctx.timeout + 5)
        if r.status_code != 200:
            return [{"_error": f"GHSA HTTP {r.status_code}"}]
        rows = r.json()
    except Exception as exc:  # noqa: BLE001
        return [{"_error": f"GHSA: {str(exc)[:80]}"}]
    out = []
    for a in rows if isinstance(rows, list) else []:
        age = _age_days(a.get("published_at") or a.get("published"))
        if age > days:
            continue
        pkgs = []
        for v in a.get("vulnerabilities", []) or []:
            pkg = (v or {}).get("package", {}) or {}
            if pkg.get("name"):
                pkgs.append(f"{pkg.get('ecosystem','')}/{pkg['name']}".strip("/"))
        out.append({
            "id": a.get("ghsa_id"), "cve": a.get("cve_id"),
            "severity": (a.get("severity") or "").lower(),
            "summary": (a.get("summary") or "")[:160],
            "age_days": age, "packages": pkgs[:8],
            "url": a.get("html_url", ""),
        })
    return out


def fetch_kev_recent(ctx: Context, days: int) -> List[dict]:
    """CISA KEV entries added within `days` — actively exploited right now."""
    try:
        r = ctx.session.get(_KEV, timeout=ctx.timeout + 10)
        if r.status_code != 200:
            return [{"_error": f"KEV HTTP {r.status_code}"}]
        cat = r.json().get("vulnerabilities", [])
    except Exception as exc:  # noqa: BLE001
        return [{"_error": f"KEV: {str(exc)[:80]}"}]
    out = []
    for v in cat:
        age = _age_days(v.get("dateAdded"))
        if age > days:
            continue
        out.append({
            "cve": v.get("cveID"), "vendor": v.get("vendorProject"),
            "product": v.get("product"), "name": v.get("vulnerabilityName"),
            "age_days": age, "ransomware": v.get("knownRansomwareCampaignUse"),
            "due": v.get("dueDate"),
        })
    return sorted(out, key=lambda x: x["age_days"])


def fetch_nuclei_recent(ctx: Context, days: int) -> List[dict]:
    """Detection templates added to nuclei-templates within `days`.

    A template lands when the community can already *detect* the issue in the
    wild, which routinely precedes the settled CVE record. Only commits that
    name a CVE are kept — the rest are refactors and false noise.
    """
    try:
        r = ctx.session.get(_NUCLEI, params={"per_page": 100},
                            timeout=ctx.timeout + 5)
        if r.status_code != 200:
            return [{"_error": f"nuclei HTTP {r.status_code}"}]
        rows = r.json()
    except Exception as exc:  # noqa: BLE001
        return [{"_error": f"nuclei: {str(exc)[:80]}"}]
    out, seen = [], set()
    for c in rows if isinstance(rows, list) else []:
        commit = (c or {}).get("commit", {}) or {}
        message = str(commit.get("message", "")).splitlines()[0][:160]
        when = ((commit.get("author") or {}).get("date")
                or (commit.get("committer") or {}).get("date") or "")
        age = _age_days(when)
        if age > days:
            continue
        for cve in {m.upper() for m in _CVE_RE.findall(message)}:
            if cve in seen:
                continue
            seen.add(cve)
            out.append({"cve": cve, "template": message, "age_days": age,
                        "url": c.get("html_url", "")})
    return sorted(out, key=lambda x: x["age_days"])


def _matches(text: str, tokens: List[str]) -> List[str]:
    low = text.lower()
    return [t for t in tokens if re.search(r"(?<![a-z0-9])" + re.escape(t)
                                           + r"(?![a-z0-9])", low)]


@register
class EmergingVulns(Module):
    id, name, category = "freshvulns", "Emerging / freshly-disclosed vulns (early warning)", "Threat Intel"
    target_kind = "domain"
    expect = ["window_days"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            days = int(ctx.config.get("fresh_days") or _DEFAULT_DAYS)  # type: ignore[union-attr]
        except Exception:  # noqa: BLE001
            days = _DEFAULT_DAYS

        tokens = _product_tokens(str(host), ctx)
        ghsa = fetch_ghsa(ctx, days)
        kev = fetch_kev_recent(ctx, days)
        nuclei = fetch_nuclei_recent(ctx, days)
        errors = [x["_error"] for x in list(ghsa) + list(kev) + list(nuclei)
                  if "_error" in x]
        ghsa = [a for a in ghsa if "_error" not in a]
        kev = [k for k in kev if "_error" not in k]
        nuclei = [t for t in nuclei if "_error" not in t]

        # which fresh disclosures plausibly hit the target's own stack
        affecting = []
        for a in ghsa:
            hay = a["summary"] + " " + " ".join(a["packages"])
            hit = _matches(hay, tokens)
            if hit:
                affecting.append({**a, "matched": hit})
        for k in kev:
            hit = _matches(f"{k.get('vendor','')} {k.get('product','')} "
                           f"{k.get('name','')}", tokens)
            if hit:
                affecting.append({"cve": k["cve"], "severity": "kev-actively-exploited",
                                  "summary": k.get("name"), "age_days": k["age_days"],
                                  "matched": hit, "source": "CISA KEV"})
        for t in nuclei:
            hit = _matches(t["template"], tokens)
            if hit:
                affecting.append({"cve": t["cve"],
                                  "severity": "detection-template-published",
                                  "summary": t["template"],
                                  "age_days": t["age_days"], "matched": hit,
                                  "source": "nuclei-templates"})
        affecting.sort(key=lambda x: x.get("age_days", 9999))

        # a CVE that just got both a KEV listing and a detection template is
        # the sharpest signal this module can produce
        kev_cves = {str(k.get("cve", "")).upper() for k in kev}
        armed = sorted({t["cve"] for t in nuclei if t["cve"] in kev_cves})

        crit = [a for a in ghsa if a["severity"] in ("critical", "high")]
        return self.ok(str(host), {
            "window_days": days,
            "target_products": tokens or "none advertised",
            "affecting_your_stack": affecting[:20] or "none matched your products",
            "affecting_count": len(affecting),
            "just_added_to_kev": kev[:20],
            "kev_recent_count": len(kev),
            "fresh_advisories": len(ghsa),
            "fresh_critical_high": len(crit),
            "newest_advisories": sorted(ghsa, key=lambda a: a["age_days"])[:15],
            "new_detection_templates": nuclei[:15] or "none",
            "new_template_count": len(nuclei),
            "exploited_and_detectable": armed or "none",
            "source_errors": errors or "none",
            "note": ("freshly *disclosed* vulnerabilities that may pre-date NVD — "
                     "early warning, not literal zero-day discovery (an unknown "
                     "vuln is not in any database by definition). 'affecting_your_"
                     "stack' cross-references the products this host advertises; "
                     "KEV additions are being exploited in the wild right now."),
        })
