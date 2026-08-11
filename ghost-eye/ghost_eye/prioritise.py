"""Fix-order — turning 240 findings into the three that matter this week.

A scan that reports "47 critical" has not finished the job. CVSS scores a
vulnerability in the abstract: how bad it *would* be, for anyone, if reachable
and if exploited. It says nothing about whether anyone is exploiting it, or
whether your instance can be reached at all — so sorting by CVSS produces a
list nobody can act on, and the real emergency sits at number 31.

This module ranks by the three things that actually decide what to fix first:

  * **Is it being exploited?** CISA KEV means "in the wild, right now". EPSS
    (FIRST.org) gives the probability of exploitation in the next 30 days —
    an empirical forecast, not a severity opinion.
  * **Can it be reached?** Derived from the scan itself: did we see the service
    answer, is the host behind a WAF, is the address private.
  * **How bad if it lands?** CVSS, kept — but as one term among three rather
    than the whole ranking.

On the honesty of reachability
------------------------------
The tempting move is to score an unreachable finding to zero and drop it. That
is wrong, and this module does not do it: a scan that did not observe a service
exposed has *not established* that it is unreachable — it may sit behind auth,
on an odd port, or on a host the scan never touched. Absence of evidence is not
evidence of absence, so unobserved exposure **lowers** priority and is labelled
``not observed exposed``; it never removes a finding and never claims safety.

Everything here is arithmetic over data other modules already gathered, plus
one batched EPSS lookup. No exploitation, no probing.
"""

from __future__ import annotations

import re
from typing import Any, Dict, Iterable, List, Optional

from .reporting import _flatten

_CVE_RE = re.compile(r"CVE-\d{4}-\d{4,7}", re.I)
_EPSS_URL = "https://api.first.org/data/v1/epss"

# How reachable the scan actually observed the affected host to be.
REACH_CONFIRMED = "confirmed exposed"        # we got a live answer
REACH_FRONTED = "exposed behind CDN/WAF"     # reachable, but filtered first
REACH_UNOBSERVED = "not observed exposed"    # no evidence either way
REACH_PRIVATE = "private address only"       # RFC1918 / loopback

_REACH_WEIGHT = {
    REACH_CONFIRMED: 1.0,
    REACH_FRONTED: 0.7,      # a WAF raises the bar, it does not close the door
    REACH_UNOBSERVED: 0.4,   # unknown, not safe — see the module docstring
    REACH_PRIVATE: 0.15,
}

# What "being exploited" is worth, before reachability.
_KEV_WEIGHT = 1.0            # on CISA KEV: exploited in the wild
_WEAPONISED_WEIGHT = 0.75    # a Metasploit/Exploit-DB module exists
_PUBLIC_POC_WEIGHT = 0.55


def extract_cve_context(results) -> Dict[str, Dict[str, Any]]:
    """CVE -> where it was seen: which hosts, which modules, which fields.

    A CVE is only actionable when you know *what* of yours has it, so the
    ranking carries its evidence rather than a bare identifier.
    """
    out: Dict[str, Dict[str, Any]] = {}
    for r in results or []:
        module = getattr(r, "module", "") or ""
        target = getattr(r, "target", "") or ""
        data = getattr(r, "data", None)
        if data is None and isinstance(r, dict):
            module = r.get("module", "")
            target = r.get("target", "")
            data = r.get("data")
        flat: Dict[str, str] = {}
        _flatten("", data or {}, flat)
        for key, val in flat.items():
            for raw in _CVE_RE.findall(f"{key} {val}"):
                cve = raw.upper()
                entry = out.setdefault(cve, {"cve": cve, "hosts": [],
                                             "modules": [], "evidence": []})
                if target and target not in entry["hosts"]:
                    entry["hosts"].append(target)
                if module and module not in entry["modules"]:
                    entry["modules"].append(module)
                if len(entry["evidence"]) < 3:
                    entry["evidence"].append(f"{module}.{key} = {str(val)[:100]}")
    return out


def fetch_epss(cves: Iterable[str], session, timeout: int = 20,
               batch: int = 100) -> Dict[str, Dict[str, float]]:
    """EPSS scores for many CVEs, batched.

    The per-CVE endpoint used elsewhere costs one request each, which is 240
    requests for a real estate; FIRST accepts a comma-separated list, so this
    is the same answer in three.
    """
    ids = [c.upper() for c in dict.fromkeys(cves or []) if c]
    out: Dict[str, Dict[str, float]] = {}
    for i in range(0, len(ids), max(1, batch)):
        chunk = ids[i:i + batch]
        try:
            resp = session.get(_EPSS_URL, params={"cve": ",".join(chunk)},
                               timeout=timeout)
            if getattr(resp, "status_code", 0) != 200:
                continue
            for row in (resp.json() or {}).get("data", []) or []:
                cve = str(row.get("cve", "")).upper()
                if not cve:
                    continue
                out[cve] = {"epss": _as_float(row.get("epss")),
                            "percentile": _as_float(row.get("percentile"))}
        except Exception:  # noqa: BLE001 - a dead source must not stop ranking
            continue
    return out


def _as_float(value: Any) -> float:
    try:
        return round(float(value or 0), 5)
    except (TypeError, ValueError):
        return 0.0


def host_reachability(results, host: str = "") -> Dict[str, Any]:
    """What the scan actually observed about whether this host is reachable.

    Evidence only: a live HTTP answer or an open port is confirmation; a CDN
    range means reachable-but-filtered; a private address means it is not
    reachable from outside. Anything else is *unobserved*, never "safe".
    """
    live = False
    fronted = False
    private_only = None
    open_ports: List[str] = []
    for r in results or []:
        target = getattr(r, "target", "") or (
            r.get("target", "") if isinstance(r, dict) else "")
        if host and target and host.lower() not in str(target).lower():
            continue
        data = getattr(r, "data", None)
        if data is None and isinstance(r, dict):
            data = r.get("data")
        flat: Dict[str, str] = {}
        _flatten("", data or {}, flat)
        for key, val in flat.items():
            kl = key.lower()
            sval = str(val)
            if kl.endswith("status_code") and sval.strip().isdigit():
                live = True
            if "open" in kl and sval and sval.lower() not in ("none", "[]", ""):
                open_ports += [p for p in re.findall(r"\b\d{1,5}\b", sval)][:12]
            if kl.endswith("behind_cdn") and sval.lower() == "true":
                fronted = True
            for ip in re.findall(r"\b(?:\d{1,3}\.){3}\d{1,3}\b", sval):
                cls = _classify(ip)
                if cls == "private":
                    private_only = True if private_only is None else private_only
                elif cls:
                    private_only = False
                if cls == "cdn":
                    fronted = True

    if private_only is True and not live:
        state = REACH_PRIVATE
    elif live or open_ports:
        state = REACH_FRONTED if fronted else REACH_CONFIRMED
    elif fronted:
        state = REACH_FRONTED
    else:
        state = REACH_UNOBSERVED
    return {"host": host, "reachability": state,
            "weight": _REACH_WEIGHT[state],
            "live_response": live,
            "open_ports": sorted(set(open_ports))[:12],
            "behind_cdn_waf": fronted}


def _classify(ip: str) -> str:
    try:
        from .netclass import classify_ip
        return classify_ip(ip)["kind"]
    except Exception:  # noqa: BLE001
        return ""


def exploitation_pressure(facts: Dict[str, Any]) -> Dict[str, Any]:
    """How hard the world is currently pushing on this CVE, in [0, 1]."""
    epss = _as_float(facts.get("epss"))
    if facts.get("known_exploited"):
        base, why = _KEV_WEIGHT, "on CISA KEV — exploited in the wild"
    elif facts.get("weaponised"):
        base, why = _WEAPONISED_WEIGHT, "weaponised exploit module exists"
    elif facts.get("exploit_available"):
        base, why = _PUBLIC_POC_WEIGHT, "public proof-of-concept exists"
    else:
        base, why = 0.25, "no public exploit found"
    # EPSS is an empirical forecast; let it raise a quiet CVE but never lower a
    # KEV one — "not predicted" is not evidence against something already used.
    pressure = max(base, epss)
    if epss and epss > base:
        why += f"; EPSS forecasts {epss:.0%} exploitation within 30 days"
    return {"pressure": round(pressure, 4), "why": why, "epss": epss}


def prioritise(results, facts_by_cve: Optional[Dict[str, Dict[str, Any]]] = None,
               session=None, timeout: int = 20,
               limit: int = 25) -> Dict[str, Any]:
    """Rank every CVE the scan found into a defensible fix order.

    ``facts_by_cve`` is the per-CVE exploit intelligence (as produced by
    ``modules.exploit_intel.check_cve``). Passing it keeps this function
    offline; omitting it fetches EPSS only, which is keyless and batched.
    """
    context = extract_cve_context(results)
    facts_by_cve = {k.upper(): v for k, v in (facts_by_cve or {}).items()}

    missing = [c for c in context if not _as_float(
        facts_by_cve.get(c, {}).get("epss"))]
    if missing and session is not None:
        for cve, row in fetch_epss(missing, session, timeout).items():
            facts_by_cve.setdefault(cve, {})["epss"] = row["epss"]
            facts_by_cve[cve]["epss_percentile"] = row["percentile"]

    reach_cache: Dict[str, Dict[str, Any]] = {}
    ranked: List[Dict[str, Any]] = []
    for cve, ctx in context.items():
        facts = facts_by_cve.get(cve, {})
        press = exploitation_pressure(facts)
        host = (ctx["hosts"] or [""])[0]
        if host not in reach_cache:
            reach_cache[host] = host_reachability(results, host)
        reach = reach_cache[host]
        severity = _as_float(facts.get("cvss")) / 10.0 or 0.5
        # pressure and reachability decide the order; severity only modulates it,
        # which is the whole point — a reachable, actively-exploited medium
        # outranks an unreachable critical nobody is touching.
        score = press["pressure"] * reach["weight"] * (0.6 + 0.4 * severity)
        ranked.append({
            "cve": cve,
            "priority": round(100 * score, 1),
            "hosts": ctx["hosts"][:6],
            "modules": ctx["modules"][:6],
            "reachability": reach["reachability"],
            "exploitation": press["why"],
            "epss": press["epss"],
            "cvss": facts.get("cvss"),
            "known_exploited": bool(facts.get("known_exploited")),
            "evidence": ctx["evidence"],
        })

    ranked.sort(key=lambda r: (-r["priority"], r["cve"]))
    act_now = [r for r in ranked if r["known_exploited"]
               and r["reachability"] in (REACH_CONFIRMED, REACH_FRONTED)]
    return {
        "cves_found": len(context),
        "fix_order": ranked[:limit],
        "act_now": act_now[:10],
        "act_now_count": len(act_now),
        "unobserved_exposure": sum(
            1 for r in ranked if r["reachability"] == REACH_UNOBSERVED),
        "note": ("ranked by exploitation pressure (CISA KEV / public exploit / "
                 "EPSS 30-day forecast) times observed reachability, with CVSS "
                 "modulating rather than deciding. 'act_now' is the subset that "
                 "is both exploited in the wild and observed reachable. "
                 "'not observed exposed' means this scan saw no evidence of "
                 "exposure — it lowers priority and never asserts safety."),
    }
