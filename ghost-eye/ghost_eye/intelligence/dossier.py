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


def entity_dossier(inv: Dict[str, Any]) -> str:
    """Render a *person / entity* investigation (from
    ``workflow.entity_investigation``) as a shareable Markdown dossier.

    Person-focused rather than domain-focused: confirmed profiles first, the
    linked-identity picture, discovered e-mails, and — because this is an OSINT
    lookup — the OPSEC exposure it created. Pure formatting, no scanning.
    """
    seed = inv.get("seed", "")
    kind = inv.get("kind", "entity")
    out: List[str] = []
    A = out.append

    A(f"# Entity dossier — {seed}")
    A("")
    A(f"- **Seed:** `{seed}`  ·  **Kind:** {kind}")
    profiles = inv.get("profiles", []) or []
    confirmed = inv.get("confirmed_profiles", []) or []
    A(f"- **Profiles found:** {len(profiles)}  "
      f"(**{len(confirmed)}** high-confidence)")
    emails = inv.get("linked_emails", []) or []
    if emails:
        A(f"- **Linked e-mails:** {', '.join(emails[:10])}")
    A("- _Reconnaissance / OSINT only — authorised use._")
    A("")

    # confirmed accounts first, then the rest, each with its confidence
    if profiles:
        A("## Accounts / profiles")

        def _row(p: Dict[str, Any]) -> str:
            conf = str(p.get("confidence", "") or "?")
            site = p.get("site", "?")
            url = p.get("url", "")
            tag = f" _({conf})_" if conf and conf != "?" else ""
            return f"- **{site}**{tag} — {url}" if url else f"- **{site}**{tag}"

        ranked = sorted(profiles, key=lambda p: (
            0 if str(p.get("confidence", "")).lower() in ("high", "confirmed")
            else 1, str(p.get("site", "")).lower()))
        for p in ranked[:80]:
            A(_row(p))
        A("")

    # linked-identity clusters (same human across handles/e-mails)
    ident = inv.get("identity_graph", {}) or {}
    clusters = ident.get("identities") or ident.get("clusters") or []
    if clusters:
        A("## Linked identities")
        A("_Handles / e-mails that appear to belong to the same person._")
        for c in clusters[:20]:
            if isinstance(c, dict):
                members = c.get("members") or c.get("nodes") or []
                label = c.get("label") or c.get("name") or "cluster"
                A(f"- **{label}:** " + ", ".join(str(m) for m in members[:12]))
            else:
                A(f"- {c}")
        A("")

    # finding confidence roll-up
    fc = inv.get("finding_confidence", {}) or {}
    if fc.get("by_confidence"):
        bc = fc["by_confidence"]
        A("## Confidence")
        A(f"- Findings by confidence: "
          f"confirmed {bc.get('confirmed', 0)}, high {bc.get('high', 0)}, "
          f"medium {bc.get('medium', 0)}, low {bc.get('low', 0)}")
        A(f"- Verified fraction: {fc.get('verified_fraction', 0)}")
        A("")

    # OPSEC — what this investigation itself disclosed
    op = inv.get("opsec", {}) or {}
    thirds = op.get("third_parties_contacted", []) or []
    A("## OPSEC exposure")
    A(f"- Exposure: **{op.get('exposure', 'unknown')}**")
    if thirds:
        A(f"- This lookup disclosed `{seed}` to "
          f"{op.get('third_party_count', len(thirds))} third parties:")
        for t in thirds[:20]:
            A(f"  - {t.get('host')} ({t.get('requests')} request(s))")
    else:
        A("- No third parties recorded.")
    A("")

    A("---")
    A("_Generated by Ghost Eye — entity OSINT. Profiles are canary-checked; "
      "verify ownership before acting. Authorised use only._")
    return "\n".join(out)
