"""OSINT dossier — render the whole OSINT picture into one shareable Markdown
report: organisation intel, assets with source attribution, resolved identities,
breach / infostealer / phishing exposure, and secrets recovered from archives.

Pure formatting over an already-assembled ``intelligence_report`` dict — no
scanning, no network.
"""

from __future__ import annotations

from typing import Any, Dict, List


def _find(results: List[dict], module_substr: str, key: str, default=None):
    for r in results or []:
        if module_substr.lower() in str(r.get("module", "")).lower():
            data = r.get("data", {}) or {}
            if key in data:
                return data[key]
    return default


def osint_dossier(report: Dict[str, Any],
                  raw_results: List[dict] | None = None) -> str:
    """Render an OSINT dossier (Markdown) from an intelligence_report dict."""
    tgt = report.get("target", "")
    out: List[str] = []
    A = out.append

    A(f"# OSINT dossier — {tgt}")
    A("")
    A(f"- **Grade:** {report.get('grade', '?')}  ·  "
      f"**Risk:** {report.get('risk_level', '?')}  ·  "
      f"**Score:** {report.get('score', 0)}/100")
    counts = report.get("counts", {}) or {}
    A(f"- **Assets:** {counts.get('assets', 0)}  ·  "
      f"**Subdomains:** {counts.get('subdomains', 0)}  ·  "
      f"**IPs:** {counts.get('ips', 0)}  ·  "
      f"**Emails:** {counts.get('emails', 0)}")
    A("- _Reconnaissance / detection only — authorised testing._")
    A("")

    # organisation intelligence (Wikidata etc.)
    org = _find(raw_results, "wikidata", "organisation")
    if org:
        A("## Organisation")
        parent = _find(raw_results, "wikidata", "parent_company")
        country = _find(raw_results, "wikidata", "country")
        industry = _find(raw_results, "wikidata", "industry")
        A(f"- **Name:** {org}")
        if parent:
            A(f"- **Parent company:** {parent}")
        if country:
            A(f"- **Country:** {country}")
        if industry:
            A(f"- **Industry:** {industry}")
        A("")

    # resolved identities
    ig = report.get("identity_graph", {}) or {}
    if ig.get("people"):
        A(f"## Identities ({ig.get('resolved_identities', 0)} resolved)")
        rels = ig.get("relationships", [])
        for p in ig.get("people", [])[:40]:
            em = [r["to"].replace("email:", "") for r in rels
                  if r["from"] == "person:" + p.lower() and r["type"] == "has_email"]
            un = [r["to"].replace("username:", "") for r in rels
                  if r["from"] == "person:" + p.lower() and r["type"] == "has_username"]
            extra = []
            if em:
                extra.append("✉ " + ", ".join(em))
            if un:
                extra.append("@ " + ", ".join(un))
            A(f"- **{p}**" + (" — " + " · ".join(extra) if extra else ""))
        A("")

    # assets with source attribution
    sm = report.get("source_matrix", {}) or {}
    if sm.get("subdomains"):
        summ = sm.get("summary", {})
        A(f"## Assets & source attribution "
          f"({summ.get('subdomains', 0)} subdomains from "
          f"{summ.get('distinct_sources', 0)} sources)")
        A("| Subdomain | Sources | Confidence |")
        A("|---|---|---|")
        for r in sm["subdomains"][:60]:
            A(f"| {r['asset']} | {', '.join(r['sources'][:8])} "
              f"({r['corroboration']}) | {r['confidence']} |")
        A("")

    # breach / infostealer / phishing exposure
    exposure: List[str] = []
    hr = _find(raw_results, "hudsonrock", "total")
    if hr:
        exposure.append(f"- **Infostealer exposure (Hudson Rock):** {hr} machine(s)")
    ph = _find(raw_results, "phishstats", "phishing_reports")
    if ph:
        exposure.append(f"- **Phishing reports (PhishStats):** {ph}")
    pd = _find(raw_results, "psbdmp", "paste_dumps")
    if pd:
        exposure.append(f"- **Paste-dump appearances:** {pd}")
    leaks = (report.get("intelligence", {}) or {}).get("leak_indicators", [])
    if leaks:
        exposure.append(f"- **Leak indicators:** {len(leaks)}")
    if exposure:
        A("## Exposure")
        out.extend(exposure)
        A("")

    # secrets recovered from archives
    secrets = _find(raw_results, "waybacksecrets", "findings")
    if secrets:
        A(f"## Archived secrets ({len(secrets)})")
        A("_Found in Wayback-archived content — may be removed from the live "
          "site but still leaked. Rotate them._")
        for s in secrets[:20]:
            A(f"- `{s.get('type')}` {s.get('match')} — {s.get('archived_url')} "
              f"({s.get('timestamp')})")
        A("")

    # confidence summary
    conf = (report.get("risk_heatmap") or {})
    if sm.get("summary"):
        A("## Confidence")
        A(f"- Multi-source subdomains: "
          f"{sm['summary'].get('multi_source_subdomains', 0)} "
          f"of {sm['summary'].get('subdomains', 0)}")
    A("")
    A("---")
    A("_Generated by Ghost Eye — free/keyless OSINT. "
      "Findings are heuristic; verify before acting._")
    return "\n".join(out)
