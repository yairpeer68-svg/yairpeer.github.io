"""Advisory layer — turns findings into *decisions*:

* ``remediation``         — a specific, prioritised fix for each top finding (69)
* ``asset_sensitivity``   — classify hosts by how sensitive they look (70)
* ``anomaly_detection``   — flag what is abnormal vs a historical baseline (68)
* ``management_translation`` — a plain-language, non-technical executive brief (72)
* ``ai_summary``          — an LLM one-paragraph summary when a key is configured,
                            else a deterministic fallback (65)

Everything except ``ai_summary`` (optional LLM) is rule-based, deterministic and
offline. Reasons over what the scan already found; never scans anything.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Optional

# keyword (matched in module name + field + detail) -> concrete remediation
_REMEDIATION = [
    (r"hsts|strict-transport", "Enable HSTS: add `Strict-Transport-Security: "
     "max-age=31536000; includeSubDomains; preload`."),
    (r"x-frame|clickjack", "Set `X-Frame-Options: DENY` (or a restrictive CSP "
     "`frame-ancestors`) to stop clickjacking."),
    (r"content-security|csp", "Deploy a Content-Security-Policy that whitelists "
     "only the sources you actually load."),
    (r"cookie|samesite", "Mark cookies `Secure`, `HttpOnly` and `SameSite=Lax/"
     "Strict`."),
    (r"cors", "Tighten CORS: never reflect arbitrary Origins or allow "
     "credentials with `*`."),
    (r"tls|cipher|weakdh|ssl|certificate|cert", "Fix TLS: disable legacy "
     "protocols/ciphers, renew the certificate and enforce TLS 1.2+."),
    (r"expires_in|expiry|expired", "Renew the certificate / domain before it "
     "expires; automate renewal (ACME)."),
    (r"spf|dkim|dmarc|email", "Harden email auth: publish SPF, DKIM and a "
     "`DMARC p=reject` policy."),
    (r"subdomain|takeover|dangling", "Remove the dangling DNS record or reclaim "
     "the service to prevent subdomain takeover."),
    (r"bucket|s3|gcs|azure", "Make the storage bucket private and audit its ACL "
     "/ bucket policy."),
    (r"secret|key|token|credential|iam|jssecrets|iamexpose", "Rotate the exposed "
     "secret immediately and remove it from the served files/repo."),
    (r"cve|exploit|vuln|wpscan", "Patch the affected component to a fixed "
     "version; prioritise CVEs on CISA KEV."),
    (r"admin|login|dashboard|panel", "Restrict the admin/login panel by IP / VPN "
     "and enforce MFA."),
    (r"dirlisting|backup|\.git|\.env|exposed", "Remove the exposed file/listing "
     "from the web root and block it at the server."),
    (r"waf|ratelimit", "Put a WAF / rate-limiting in front of the exposed "
     "surface."),
    (r"open redirect|redirect", "Validate redirect targets against an allow-list."),
]

# host-label keywords -> sensitivity classification
_SENSITIVE = {
    "critical": [r"\b(admin|root|vpn|db|database|sql|backup|internal|secret|"
                 r"vault|jenkins|gitlab|ci|jira|k8s|kube|iam|auth|sso|ldap)\b"],
    "high": [r"\b(api|staging|stage|dev|test|uat|preprod|beta|portal|dashboard|"
             r"panel|manage|payment|billing|checkout)\b"],
    "medium": [r"\b(mail|smtp|webmail|ftp|files|upload|cdn|static|assets)\b"],
}


def remediation(report: Dict[str, Any],
                findings: Optional[List[dict]] = None) -> Dict[str, Any]:
    """Produce a de-duplicated, severity-ordered list of concrete fixes for the
    findings a scan surfaced (feature 69)."""
    src = findings or []
    if not src:
        # fall back to the scored findings embedded in the report
        src = (report.get("risk_findings")
               or report.get("intelligence", {}).get("findings") or [])
    sev_rank = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4}
    src = sorted(src, key=lambda f: sev_rank.get(f.get("severity", "info"), 4))
    out: List[dict] = []
    seen: set = set()
    for f in src:
        hay = f"{f.get('module', '')} {f.get('field', '')} {f.get('detail', '')}".lower()
        for pat, fix in _REMEDIATION:
            if re.search(pat, hay):
                if fix in seen:
                    break
                seen.add(fix)
                out.append({"severity": f.get("severity", "info"),
                            "issue": f"{f.get('module', '')}: {f.get('field', '')}".strip(": "),
                            "fix": fix})
                break
        if len(out) >= 20:
            break
    return {"recommendations": out, "count": len(out),
            "note": "deterministic, rule-based remediation mapped from findings."}


def asset_sensitivity(kg: Dict[str, Any]) -> Dict[str, Any]:
    """Classify host entities by how sensitive their name looks (feature 70)."""
    buckets: Dict[str, List[str]] = {"critical": [], "high": [], "medium": [],
                                     "low": []}
    for e in kg.get("entities", []):
        if e.get("kind") not in ("target", "subdomain", "domain"):
            continue
        label = str(e.get("label", "")).lower()
        band = "low"
        for level, pats in _SENSITIVE.items():
            if any(re.search(p, label) for p in pats):
                band = level
                break
        buckets[band].append(e.get("label", ""))
        e.setdefault("attrs", {})["sensitivity"] = band
    return {
        "by_level": {k: v[:40] for k, v in buckets.items()},
        "counts": {k: len(v) for k, v in buckets.items()},
        "note": "hosts classified by name (admin/db/vpn -> critical, api/staging "
                "-> high, mail/ftp -> medium). Heuristic — verify before acting.",
    }


def anomaly_detection(current: Dict[str, Any],
                      baseline: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    """Flag what is abnormal in the current scan vs a historical baseline of
    per-metric averages (feature 68). ``baseline`` = {metric: avg}. Returns the
    metrics that deviate sharply plus a short verdict."""
    cur = current.get("counts", {}) or {}
    metrics = {
        "assets": cur.get("assets", 0),
        "subdomains": cur.get("subdomains", len(
            current.get("intelligence", {}).get("subdomains", []))),
        "leaks": cur.get("leaks", len(
            current.get("intelligence", {}).get("leak_indicators", []))),
        "score": current.get("score", 0),
    }
    if not baseline:
        return {"anomalies": [], "baseline": None,
                "note": "no history yet — first scan becomes the baseline."}
    anomalies: List[dict] = []
    for k, v in metrics.items():
        base = float(baseline.get(k, 0) or 0)
        if base <= 0:
            if v > 0 and k in ("leaks",):
                anomalies.append({"metric": k, "now": v, "baseline": 0,
                                  "change": "new", "direction": "up"})
            continue
        delta = (v - base) / base
        if abs(delta) >= 0.4:                 # >=40% swing is notable
            anomalies.append({"metric": k, "now": v, "baseline": round(base, 1),
                              "change": f"{delta * 100:+.0f}%",
                              "direction": "up" if delta > 0 else "down"})
    verdict = ("attack surface changed sharply" if anomalies
               else "within normal historical range")
    return {"anomalies": anomalies, "verdict": verdict,
            "note": "metrics compared to the average of previous scans; "
                    ">=40% swing is flagged."}


def management_translation(report: Dict[str, Any]) -> Dict[str, Any]:
    """A plain-language, non-technical brief for management (feature 72)."""
    grade = report.get("grade", "?")
    risk = str(report.get("risk_level", "unknown")).lower()
    counts = report.get("counts", {}) or {}
    intel = report.get("intelligence", {}) or {}
    n_sub = counts.get("subdomains", len(intel.get("subdomains", [])))
    n_leak = len(intel.get("leak_indicators", []))
    kev = len((report.get("exploitable_cves") or []))
    posture = {"critical": "needs urgent attention",
               "high": "is a real concern",
               "medium": "has room to improve",
               "low": "looks healthy"}.get(risk, "was assessed")
    headline = (f"Our external security posture {posture} "
                f"(grade {grade}).")
    points = [
        f"We found {n_sub} internet-facing system(s) tied to this brand.",
    ]
    if n_leak:
        points.append(f"{n_leak} public data-leak indicator(s) were spotted — "
                      "these should be reviewed first.")
    if kev:
        points.append(f"{kev} known-exploitable weakness(es) were detected and "
                      "should be patched on priority.")
    points.append("None of this required touching customer data; it is what an "
                  "outsider can already see.")
    return {"headline": headline, "points": points,
            "one_liner": headline,
            "note": "auto-translated from the technical findings for a "
                    "non-technical audience."}


def ai_summary(report: Dict[str, Any], api_key: str = "",
               provider: str = "deepseek", session=None) -> Dict[str, Any]:
    """One-paragraph natural-language summary. Uses an LLM when ``api_key`` is
    supplied (feature 65); otherwise returns a deterministic summary so the
    feature always works offline."""
    mt = management_translation(report)
    deterministic = mt["headline"] + " " + " ".join(mt["points"])
    if not api_key:
        return {"summary": deterministic, "source": "deterministic",
                "note": "set a DeepSeek/Claude key to get an LLM-written summary."}
    try:
        import json as _json
        if session is None:
            import requests
            session = requests.Session()
        facts = {
            "grade": report.get("grade"), "risk": report.get("risk_level"),
            "score": report.get("score"), "counts": report.get("counts"),
            "top_risks": (report.get("risk_heatmap", {}) or {}).get("top", [])[:5],
        }
        prompt = ("You are a security analyst. In 4-5 sentences, summarise this "
                  "external attack-surface assessment for a CISO. Be specific and "
                  "actionable. Data:\n" + _json.dumps(facts)[:3500])
        if provider == "deepseek":
            url = "https://api.deepseek.com/chat/completions"
            model = "deepseek-chat"
        else:  # anthropic-style
            url = "https://api.anthropic.com/v1/messages"
            model = "claude-3-5-haiku-latest"
        if provider == "deepseek":
            r = session.post(url, timeout=30,
                             headers={"Authorization": f"Bearer {api_key}"},
                             json={"model": model, "messages": [
                                 {"role": "user", "content": prompt}]})
            txt = r.json()["choices"][0]["message"]["content"].strip()
        else:
            r = session.post(url, timeout=30,
                             headers={"x-api-key": api_key,
                                      "anthropic-version": "2023-06-01"},
                             json={"model": model, "max_tokens": 400,
                                   "messages": [{"role": "user",
                                                 "content": prompt}]})
            txt = r.json()["content"][0]["text"].strip()
        return {"summary": txt or deterministic,
                "source": f"llm:{provider}", "note": "LLM-generated."}
    except Exception:  # noqa: BLE001
        return {"summary": deterministic, "source": "deterministic-fallback",
                "note": "LLM call failed; showing the deterministic summary."}


def question_answer(report: Dict[str, Any], question: str) -> Dict[str, Any]:
    """Deterministic Q&A over the assembled intelligence (RAG-lite, feature 66):
    match the question to the relevant slice of the report and answer from it —
    no LLM required, fully offline and private."""
    q = (question or "").lower()
    intel = report.get("intelligence", {}) or {}

    def _has(*ws):
        return any(w in q for w in ws)

    if _has("subdomain", "hosts", "domains"):
        subs = intel.get("subdomains", [])
        return {"answer": f"{len(subs)} subdomain(s): " + ", ".join(subs[:25]),
                "items": subs[:50]}
    if _has("leak", "breach", "exposed data"):
        leaks = intel.get("leak_indicators", [])
        return {"answer": (f"{len(leaks)} leak indicator(s)." if leaks
                           else "No public leak indicators found."),
                "items": leaks[:20]}
    if _has("cve", "vuln", "exploit"):
        cves = report.get("exploitable_cves", []) or []
        return {"answer": (f"{len(cves)} exploitable CVE(s): " + ", ".join(cves[:15])
                           if cves else "No exploitable CVEs surfaced."),
                "items": cves[:30]}
    if _has("tech", "stack", "framework", "cms"):
        tech = intel.get("technologies", {})
        flat = [t for v in tech.values() for t in v]
        return {"answer": "Technologies: " + ", ".join(flat[:25]), "items": flat}
    if _has("risk", "grade", "score", "posture", "how bad", "secure"):
        return {"answer": f"{report.get('target')}: {report.get('risk_level')} "
                          f"risk, grade {report.get('grade')} "
                          f"({report.get('score')}/100).", "items": []}
    if _has("cloud", "aws", "azure", "gcp"):
        cloud = intel.get("cloud", [])
        return {"answer": "Cloud: " + (", ".join(cloud) or "none detected"),
                "items": cloud}
    return {"answer": "Try asking about subdomains, leaks, CVEs, tech, cloud or "
                      "overall risk.", "items": []}
