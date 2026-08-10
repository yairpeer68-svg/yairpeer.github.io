"""Rule-based AI analyst — writes the narrative an analyst would write, from
the correlated intelligence, the knowledge graph, entity correlation and the
timeline. It reasons over structured facts and composes prose; it is fully
deterministic and offline (NO LLM, NO external API), which keeps the tool
private, reproducible and safe for authorised engagements.

Output: a headline, an executive summary, a prioritised assessment, an attack
narrative ("how this surface would be approached"), recommendations and a
stated confidence — the shape of a human analyst's write-up.
"""

from __future__ import annotations

from typing import Any, Dict, List

_GRADE_WORD = {
    "A+": "excellent", "A": "strong", "B": "reasonable",
    "C": "mediocre", "D": "weak", "F": "poor",
}


def _plural(n: int, word: str) -> str:
    if n == 1:
        return f"1 {word}"
    if word.endswith("y") and word[-2:-1].lower() not in "aeiou":
        word = word[:-1] + "ie"
    elif word.endswith(("s", "x", "ch", "sh")):
        word += "e"
    return f"{n} {word}s"


def analyze(report: Dict[str, Any]) -> Dict[str, Any]:
    """Compose the analyst write-up from an assembled platform report.

    `report` is the dict produced by workflow.platform_report(): it carries
    intelligence, organization, knowledge_graph, correlation, timeline, grade,
    risk_level and score.
    """
    intel = report.get("intelligence", {})
    org = report.get("organization", {})
    kg = report.get("knowledge_graph", {})
    corr = report.get("correlation", {})
    timeline = report.get("timeline", {})
    counts = intel.get("counts", {})
    target = report.get("target", "the target")
    grade = report.get("grade", "?")
    risk = report.get("risk_level", "UNKNOWN")
    score = report.get("score", 0)

    n_assets = counts.get("assets", 0)
    n_subs = counts.get("subdomains", 0)
    n_ips = counts.get("ips", 0)
    n_tech = counts.get("technologies", 0)
    n_leaks = counts.get("leak_indicators", 0)
    em = intel.get("email_security", {})

    # ---- headline -------------------------------------------------------- #
    headline = (f"{target}: {risk} risk (grade {grade}, {score}/100) across "
                f"{_plural(n_assets, 'correlated asset')}")

    # ---- executive summary ---------------------------------------------- #
    uses = org.get("uses", [])
    tech_phrase = (", ".join(uses[:4]) + (" and others" if len(uses) > 4 else "")
                   if uses and uses != ["not fingerprinted"]
                   else "no clearly fingerprinted stack")
    cloud = [c for c in intel.get("cloud", []) if "unknown" not in c.lower()]
    cloud_phrase = (f"hosted on {', '.join(cloud)}" if cloud
                    else "on self-hosted or unidentified infrastructure")
    summary = (
        f"Ghost Eye correlated {_plural(n_assets, 'asset')} for {target}: "
        f"{_plural(n_subs, 'subdomain')}, {_plural(n_ips, 'IP')} and "
        f"{_plural(n_tech, 'fingerprinted technology')}. The surface appears "
        f"to run {tech_phrase}, {cloud_phrase}. Overall exposure is assessed "
        f"as {risk} ({_GRADE_WORD.get(grade, 'unclear')} posture, grade "
        f"{grade}). Email authentication scores {em.get('score', '?')}/100 "
        f"(grade {em.get('grade', '?')}).")

    # ---- assessment (prioritised observations) -------------------------- #
    assessment: List[str] = []
    for risk_line in org.get("main_risks", []):
        if "no major risks" not in risk_line.lower():
            assessment.append(risk_line)
    if n_leaks:
        assessment.append(
            f"{_plural(n_leaks, 'public leak indicator')} were surfaced — "
            f"treat any exposed credential as live until proven rotated.")
    pivots = corr.get("pivot_points", [])
    if pivots:
        top = pivots[0]
        assessment.append(
            f"'{top['entity']}' ({top['kind']}) is the most connected entity "
            f"(degree {top['degree']}) — a single pivot that ties much of the "
            f"surface together.")
    shared = corr.get("shared_infrastructure", [])
    if shared:
        s = shared[0]
        assessment.append(
            f"{s['connects']} hosts share {s['kind']} '{s['hub']}' — "
            f"compromising or misconfiguring it would affect all of them.")
    for ins in timeline.get("insights", []):
        low = ins.lower()
        if "expir" in low or "expired" in low or "breach" in low:
            assessment.append(ins.lstrip("⏰🩸🗓✏️ "))
    if not assessment:
        assessment.append("No high-severity issues correlated from this scan; "
                          "posture looks clean at the current depth.")

    # ---- attack narrative ----------------------------------------------- #
    narrative_bits: List[str] = [
        f"An adversary profiling {target} would begin from the "
        f"{_plural(n_subs, 'exposed subdomain')} and "
        f"{_plural(n_ips, 'IP')} mapped here."]
    dev = [s for s in intel.get("subdomains", [])
           if any(m in s.split(".")[0]
                  for m in ("dev", "staging", "test", "qa", "uat", "stg"))]
    if dev:
        narrative_bits.append(
            f"Non-production hosts ({', '.join(dev[:3])}) are the likely first "
            f"foothold — they are typically less monitored and less hardened.")
    if shared:
        narrative_bits.append(
            f"Shared infrastructure ('{shared[0]['hub']}') offers lateral "
            f"reach across {shared[0]['connects']} hosts once inside.")
    if em.get("score", 100) < 70:
        narrative_bits.append(
            "Weak email authentication invites spoofing/phishing of the domain "
            "for social-engineering entry.")
    if n_leaks:
        narrative_bits.append(
            "Leaked credentials shortcut the whole chain — credential-stuffing "
            "the exposed accounts against any auth surface.")
    narrative = " ".join(narrative_bits)

    # ---- recommendations ------------------------------------------------ #
    recs: List[str] = []
    if em.get("score", 100) < 85:
        recs.append("Harden email auth: enforce DMARC p=reject with SPF+DKIM "
                    "aligned, and add MTA-STS.")
    if dev:
        recs.append("Remove or IP-restrict non-production subdomains "
                    f"({', '.join(dev[:3])}); keep them off public DNS.")
    if n_leaks:
        recs.append("Rotate every credential tied to the leak indicators and "
                    "enable MFA on the affected accounts.")
    for ins in timeline.get("insights", []):
        if "expir" in ins.lower():
            recs.append("Renew the flagged certificate/registration before "
                        "expiry to avoid an outage or trust gap.")
            break
    if any("tls" in r.lower() for r in org.get("main_risks", [])):
        recs.append("Disable legacy TLS (1.0/1.1) and weak ciphers; require "
                    "TLS 1.2+.")
    recs.append("Re-run periodically with --save-db to track drift, and review "
                "the knowledge graph for newly exposed pivots.")

    # ---- confidence ----------------------------------------------------- #
    depth = kg.get("counts", {}).get("relationships", 0)
    confidence = ("high" if depth >= 25 and n_assets >= 15 else
                  "medium" if depth >= 8 else "low")

    return {
        "headline": headline,
        "summary": summary,
        "assessment": assessment[:8],
        "attack_narrative": narrative,
        "recommendations": recs[:8],
        "confidence": confidence,
        "method": "rule-based deterministic analysis over correlated "
                  "intelligence (no LLM / no external API)",
    }
