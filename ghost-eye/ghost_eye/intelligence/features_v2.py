"""Features batch 1 — advanced analysis over scan results (no server, no API key).

Pure functions over a list of ``Result`` objects — fully testable without any
network. They aggregate the many free/keyless OSINT modules into higher-level,
decision-ready intelligence:

  * email_security_audit  — unified SPF/DKIM/DMARC/DANE/CAA/MX spoofing grade (#77)
  * supply_chain_map      — every third-party vendor the target relies on (#76)
  * attack_surface_techniques — MITRE ATT&CK reconnaissance mapping (#18)
  * secrets_report        — consolidated exposed-secret / credential findings (#50)
  * investigation_narrative — plain-language summary of the whole picture (#6)

Correlation / analysis only.
"""

from __future__ import annotations

from typing import Any, Dict, List

from ..core import Result


def _by_module(results: List[Result]) -> Dict[str, Dict[str, Any]]:
    """Map module-id -> its data dict (last ok result wins)."""
    out: Dict[str, Dict[str, Any]] = {}
    for r in results or []:
        if getattr(r, "status", "") in ("ok", "OK") or getattr(r, "data", None):
            d = getattr(r, "data", None)
            if isinstance(d, dict):
                out[getattr(r, "module", "")] = d
    return out


# --------------------------------------------------------------------------- #
#  #77  Unified e-mail spoofing-posture audit
# --------------------------------------------------------------------------- #
def email_security_audit(results: List[Result]) -> Dict[str, Any]:
    """Combine every e-mail-security signal into one graded posture."""
    m = _by_module(results)
    score = 100
    checks: Dict[str, Any] = {}
    issues: List[str] = []

    spf = m.get("spfdmarc") or {}
    if spf:
        checks["spf"] = spf.get("spf_all") or ("present" if spf.get("spf") else "missing")
        checks["dmarc_policy"] = spf.get("dmarc_policy") or "missing"
        if not spf.get("spf"):
            score -= 20
            issues.append("no SPF record")
        elif spf.get("spf_all") in ("+all", "?all"):
            score -= 15
            issues.append(f"weak SPF qualifier {spf.get('spf_all')}")
        if (spf.get("spf_lookups") or 0) > 10:
            score -= 10
            issues.append("SPF exceeds 10 DNS lookups (permerror)")
        pol = (spf.get("dmarc_policy") or "").lower()
        if not spf.get("dmarc"):
            score -= 25
            issues.append("no DMARC record")
        elif pol in ("", "none"):
            score -= 15
            issues.append("DMARC p=none (monitoring only)")

    dkim = m.get("dkimscan") or {}
    if dkim:
        checks["dkim_selectors"] = dkim.get("count", 0)
        if not dkim.get("count"):
            score -= 10
            issues.append("no DKIM selector found")
        if dkim.get("weak_keys"):
            score -= 8
            issues.append("weak DKIM key(s)")

    dnssec = m.get("dnsseccaa") or {}
    if dnssec:
        checks["dnssec"] = dnssec.get("dnssec_enabled")
        checks["caa"] = dnssec.get("caa_present")
        if not dnssec.get("dnssec_enabled"):
            score -= 5
            issues.append("DNSSEC not enabled")
        if not dnssec.get("caa_present"):
            score -= 5
            issues.append("no CAA policy")

    dane = m.get("danetlsa") or {}
    if dane:
        checks["dane"] = dane.get("dane_enabled")

    mx = m.get("mxintel") or {}
    if mx:
        checks["mail_providers"] = mx.get("mail_providers") or []
        if mx.get("mx_count") == 0:
            checks["mx"] = "no MX"

    score = max(0, min(100, score))
    grade = ("A" if score >= 90 else "B" if score >= 80 else "C" if score >= 65
             else "D" if score >= 50 else "F")
    return {"score": score, "grade": grade, "checks": checks, "issues": issues,
            "spoofable": grade in ("D", "F") or "no DMARC record" in issues,
            "note": "unified e-mail-spoofing posture from SPF/DKIM/DMARC/DNSSEC/CAA/"
                    "DANE/MX signals."}


# --------------------------------------------------------------------------- #
#  #76  Third-party supply-chain inventory + risk
# --------------------------------------------------------------------------- #
def supply_chain_map(results: List[Result]) -> Dict[str, Any]:
    """Aggregate every third-party vendor/domain the target depends on."""
    m = _by_module(results)
    vendors: Dict[str, set] = {}

    def add(category: str, items):
        for it in items or []:
            if it:
                vendors.setdefault(category, set()).add(str(it))

    add("analytics/trackers", (m.get("trackers") or {}).get("trackers"))
    add("external-js", (m.get("jsassets") or {}).get("external_js_domains"))
    add("csp-allowed", (m.get("cspdomains") or {}).get("external_domains"))
    add("preconnect", (m.get("preconnects") or {}).get("preconnect_domains"))
    add("email-senders", (m.get("spfvendors") or {}).get("vendors"))
    add("saas-verifications", (m.get("txtsaas") or {}).get("vendors"))
    add("mail-provider", (m.get("mxintel") or {}).get("mail_providers"))
    add("dns-provider", (m.get("nsintel") or {}).get("dns_providers"))
    add("hosting", (m.get("cnamemap") or {}).get("hosting"))
    idp = (m.get("idpfinger") or {}).get("vendor")
    if idp and idp != "unknown/self-hosted":
        add("identity-provider", [idp])
    add("ad-partners", (m.get("adstxt") or {}).get("ad_partners"))

    flat = sorted({v for s in vendors.values() for v in s})
    out = {k: sorted(v)[:40] for k, v in vendors.items()}
    risk = "high" if len(flat) >= 20 else "medium" if len(flat) >= 8 else "low"
    return {"by_category": out, "unique_vendors": len(flat),
            "vendors": flat[:120], "supply_chain_risk": risk,
            "note": "each third party is a potential supply-chain compromise vector; "
                    "more third parties = larger indirect attack surface."}


# --------------------------------------------------------------------------- #
#  #18  MITRE ATT&CK reconnaissance-technique mapping
# --------------------------------------------------------------------------- #
_ATTACK_MAP = [
    ("T1595", "Active Scanning", {"wpusers", "openapi", "wpstack", "sitemapscan",
                                  "tokenhunt", "envexposed", "gitexposed", "srvscan"}),
    ("T1590", "Gather Victim Network Information",
     {"dns", "subs", "crtsh", "certspotter", "bgpviewsearch", "ripedb", "asnprefixes",
      "asninfo", "asnupstreams", "nsintel", "cnamemap", "iprdap", "arinrdap"}),
    ("T1592", "Gather Victim Host Information",
     {"wpstack", "openapi", "trackers", "jsassets", "idpfinger", "mobileapps",
      "httpsrr", "manifest"}),
    ("T1589", "Gather Victim Identity Information",
     {"emails", "emailpattern", "certemails", "githubemail", "githubgpg", "wpusers",
      "feeds", "humanstxt", "keybaseuser"}),
    ("T1591", "Gather Victim Org Information",
     {"gleif", "secedgar", "wikidata", "githuborg", "wikipedia"}),
    ("T1598", "Phishing for Information",
     {"lookalike", "openphish", "phisharmy", "phishdb", "hagezi"}),
    ("T1596", "Search Open Technical Databases",
     {"shodan", "internetdb", "leakix", "otxrep", "urlhaus", "threatfox", "ipsum"}),
    ("T1597", "Search Closed Sources", set()),
    ("T1593", "Search Open Websites/Domains",
     {"github", "sourcegraph", "grepapp", "searchcode", "hackernews", "reddit",
      "gdelt", "stackexchange", "pagelinks"}),
]


def attack_surface_techniques(results: List[Result]) -> Dict[str, Any]:
    """Map which ATT&CK reconnaissance techniques produced data for this target."""
    fired = {getattr(r, "module", "") for r in results or []
             if getattr(r, "data", None)}
    techniques = []
    for tid, name, mods in _ATTACK_MAP:
        hit = sorted(fired & mods)
        if hit:
            techniques.append({"technique": tid, "name": name,
                               "tactic": "Reconnaissance", "modules": hit[:20],
                               "coverage": len(hit)})
    techniques.sort(key=lambda t: t["coverage"], reverse=True)
    return {"tactic": "Reconnaissance (TA0043)",
            "techniques": techniques, "technique_count": len(techniques),
            "note": "ATT&CK reconnaissance techniques exercised against this target — "
                    "maps the OSINT footprint to a defensive framework."}


# --------------------------------------------------------------------------- #
#  #50 / #54  Consolidated exposed-secret findings
# --------------------------------------------------------------------------- #
def secrets_report(results: List[Result]) -> Dict[str, Any]:
    """Merge every secret/credential-exposure module into one prioritised view."""
    m = _by_module(results)
    findings: List[Dict[str, Any]] = []

    for mid in ("tokenhunt", "waybacktokens"):
        d = m.get(mid) or {}
        for s in d.get("secrets", []) or []:
            findings.append({"source": mid, "type": s.get("type"),
                             "value": s.get("value"), "severity": "critical"})
    env = m.get("envexposed") or {}
    for f in env.get("exposed_files", []) or []:
        findings.append({"source": "envexposed", "type": f"exposed {f.get('path')}",
                         "value": f"{f.get('secret_count', 0)} secret(s)",
                         "severity": "critical"})
    git = m.get("gitexposed") or {}
    if git.get("git_exposed"):
        findings.append({"source": "gitexposed", "type": "public /.git/ repository",
                         "value": ", ".join(git.get("remotes") or []) or "exposed",
                         "severity": "critical"})
    for mid in ("jssecrets", "sigscan", "ghleak", "waybacksecrets"):
        d = m.get(mid) or {}
        n = d.get("count") or d.get("secret_count") or len(d.get("secrets", []) or [])
        if n:
            findings.append({"source": mid, "type": "secret indicators",
                             "value": f"{n} hit(s)", "severity": "high"})

    crit = sum(1 for f in findings if f["severity"] == "critical")
    return {"findings": findings[:100], "count": len(findings),
            "critical": crit,
            "verdict": ("CRITICAL — exposed credentials found" if crit else
                        "elevated" if findings else "no exposed secrets detected"),
            "note": "consolidated credential/secret exposure across live, archived, "
                    "config and repo sources. Values are redacted at the source."}


# --------------------------------------------------------------------------- #
#  #6  Plain-language investigation narrative
# --------------------------------------------------------------------------- #
def investigation_narrative(results: List[Result], target: str = "") -> Dict[str, Any]:
    """Turn the aggregated intelligence into a short human-readable narrative."""
    m = _by_module(results)
    lines: List[str] = []
    target = target or "the target"

    subs = set()
    for mid in ("crtsh", "certspotter", "subs", "hackertarget", "entrustct",
                "columbus", "otxurls"):
        subs.update((m.get(mid) or {}).get("subdomains", []) or [])
    if subs:
        lines.append(f"Discovered {len(subs)} subdomain(s) across certificate-transparency "
                     "and passive-DNS sources.")

    mail = email_security_audit(results)
    if mail.get("checks"):
        verb = "is spoofable" if mail["spoofable"] else "has a reasonable posture"
        lines.append(f"E-mail security graded {mail['grade']} ({mail['score']}/100); "
                     f"the domain {verb}.")

    sc = supply_chain_map(results)
    if sc["unique_vendors"]:
        lines.append(f"Relies on {sc['unique_vendors']} third-party vendor(s) "
                     f"(supply-chain risk: {sc['supply_chain_risk']}).")

    sec = secrets_report(results)
    if sec["count"]:
        lines.append(f"⚠ {sec['count']} secret/credential exposure indicator(s) found "
                     f"— {sec['verdict']}.")

    for feed in ("hagezi", "phishdb", "blocklistproject", "spam404", "openphish",
                 "phisharmy", "digitalside"):
        if (m.get(feed) or {}).get("listed"):
            lines.append(f"Flagged on the {feed} threat feed.")
            break

    idp = (m.get("idpfinger") or {}).get("vendor")
    if idp and idp != "unknown/self-hosted":
        lines.append(f"Single sign-on is provided by {idp}.")

    if not lines:
        lines.append("No significant findings were aggregated for this target.")
    return {"target": target, "narrative": " ".join(lines),
            "bullet_points": lines,
            "note": "auto-generated summary from the aggregated module output."}


# =========================================================================== #
#  Features batch 2 (no server, no API key)
# =========================================================================== #
_THREAT_FEEDS = ("hagezi", "phishdb", "blocklistproject", "spam404", "openphish",
                 "phisharmy", "digitalside", "urlhaus", "threatfox", "sucuri",
                 "otxmalware", "sslbl", "cinsarmy", "ipsum", "greensnow", "talos",
                 "spamhausdrop", "firehol", "firehol2", "firehol3", "dshieldnet",
                 "binarydefense", "emergingthreats", "bruteforceblocker", "feodoaggr",
                 "feodo", "stevenblack")


def entity_risk_scores(results: List[Result], target: str = "") -> Dict[str, Any]:
    """#10 — score the seed by threat-feed hits, exposed secrets and posture."""
    m = _by_module(results)
    score = 0
    reasons: List[str] = []

    feeds_hit = [f for f in _THREAT_FEEDS if (m.get(f) or {}).get("listed")]
    if feeds_hit:
        score += 40 + 5 * len(feeds_hit)
        reasons.append(f"listed on {len(feeds_hit)} threat feed(s): {', '.join(feeds_hit[:6])}")

    sec = secrets_report(results)
    if sec["critical"]:
        score += 35
        reasons.append(f"{sec['critical']} critical secret exposure(s)")
    elif sec["count"]:
        score += 15
        reasons.append("secret-exposure indicators present")

    mail = email_security_audit(results)
    if mail.get("spoofable"):
        score += 15
        reasons.append(f"e-mail spoofable (grade {mail['grade']})")

    ipsum = (m.get("ipsum") or {})
    if ipsum.get("blocklist_hits"):
        score += min(20, int(ipsum.get("blocklist_hits", 0)) * 3)
        reasons.append(f"IP flagged by {ipsum['blocklist_hits']} blocklists (IPsum)")

    for anon in ("vpnapi", "proxycheck", "tornodes"):
        d = m.get(anon) or {}
        if d.get("anonymized") or d.get("is_tor_relay") or str(d.get("proxy")).lower() == "yes":
            score += 5
            reasons.append("associated with anonymised infrastructure")
            break

    score = min(100, score)
    band = ("critical" if score >= 75 else "high" if score >= 50 else
            "medium" if score >= 25 else "low")
    return {"target": target, "risk_score": score, "risk_band": band,
            "reasons": reasons,
            "note": "aggregate risk from threat-feed corroboration, secret exposure, "
                    "e-mail posture and anonymisation signals."}


def brand_abuse_report(results: List[Result], target: str = "") -> Dict[str, Any]:
    """#79 — consolidate typosquat / look-alike / phishing-impersonation signals."""
    m = _by_module(results)
    lookalikes = (m.get("lookalike") or {}).get("registered_lookalikes", []) or []
    phishing_urls = (m.get("openphish") or {}).get("phishing_urls", []) or []
    feeds = [f for f in ("openphish", "phisharmy", "phishdb", "digitalside", "hagezi")
             if (m.get(f) or {}).get("listed")]
    total = len(lookalikes) + len(phishing_urls) + len(feeds)
    verdict = ("active brand abuse detected" if total else "no brand-abuse signals")
    return {"target": target,
            "registered_lookalikes": lookalikes[:40],
            "lookalike_count": len(lookalikes),
            "phishing_urls": phishing_urls[:30],
            "impersonation_feeds": feeds,
            "signals": total, "verdict": verdict,
            "note": "brand-protection view: look-alike domains + live phishing "
                    "impersonating this brand. Monitor and take down."}


def export_maltego_csv(results: List[Result], target: str = "") -> Dict[str, Any]:
    """#19 — entities + links as a Maltego-importable CSV (source,type,value)."""
    rows = ["source_entity,link_label,target_entity,target_type"]
    seen = set()

    def add(label, value, typ):
        v = str(value).strip()
        if not v or v.lower() == target.lower():
            return
        key = (label, v, typ)
        if key in seen:
            return
        seen.add(key)
        rows.append(f'{target},{label},"{v}",{typ}')

    m = _by_module(results)
    for mid in ("crtsh", "certspotter", "subs", "hackertarget", "entrustct",
                "columbus", "otxurls", "bufferover"):
        for s in (m.get(mid) or {}).get("subdomains", []) or []:
            add("has_subdomain", s, "maltego.DNSName")
    for mid in ("emails", "certemails", "emailpattern"):
        for e in (m.get(mid) or {}).get("emails", []) or []:
            add("has_email", e if isinstance(e, str) else e.get("email"),
                "maltego.EmailAddress")
    for cat, items in supply_chain_map(results)["by_category"].items():
        for v in items:
            add(f"uses_{cat}", v, "maltego.Domain")
    return {"format": "maltego-csv", "rows": len(rows) - 1,
            "csv": "\n".join(rows[:2000]),
            "note": "import into Maltego as a CSV of links from the seed entity."}


def cross_target_correlation(results_a: List[Result], results_b: List[Result],
                             target_a: str = "A", target_b: str = "B") -> Dict[str, Any]:
    """#8 — find infrastructure/vendors shared between two targets."""
    def facets(results):
        m = _by_module(results)
        vendors = set(supply_chain_map(results)["vendors"])
        ns = set((m.get("nsintel") or {}).get("dns_providers", []) or [])
        mailp = set((m.get("mxintel") or {}).get("mail_providers", []) or [])
        asns = set()
        for mid in ("asninfo", "asnprefixes", "bgpview"):
            a = (m.get(mid) or {}).get("asn")
            if a:
                asns.add(str(a))
        analytics = set((m.get("trackers") or {}).get("analytics_ids", []) or [])
        return {"vendors": vendors, "dns": ns, "mail": mailp, "asns": asns,
                "analytics_ids": analytics}

    fa, fb = facets(results_a), facets(results_b)
    shared = {k: sorted(fa[k] & fb[k]) for k in fa}
    total = sum(len(v) for v in shared.values())
    linked = total > 0 or bool(shared.get("analytics_ids"))
    return {"target_a": target_a, "target_b": target_b,
            "shared": {k: v for k, v in shared.items() if v},
            "shared_count": total,
            "likely_same_owner": bool(shared.get("analytics_ids")) or total >= 3,
            "verdict": ("likely operated by the same entity" if linked else
                        "no shared infrastructure found"),
            "note": "shared analytics IDs / ASNs / vendors strongly suggest common "
                    "ownership between the two targets."}
