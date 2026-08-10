"""Reporting extensions (new features #64-#71)."""

from __future__ import annotations

import html as _html
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from .core import Console, Result, log
from .reporting import _flatten

# severity heuristics: (regex, severity, label)
_SEV_RULES: List[Tuple[re.Pattern, str, str]] = [
    (re.compile(r"\b(VULNERABLE|critical|OPEN Docker|OPEN with community|"
                r"reflects origin WITH credentials|RCE|takeover)\b", re.I), "critical", "critical"),
    (re.compile(r"\b(EXPOSED|OPEN \(|PUBLIC|introspection ENABLED|no auth|"
                r"DANGLING|cleartext|MISCONFIGURED|HIGH RISK|EXPIRED)\b", re.I), "high", "high"),
    (re.compile(r"\b(weak|missing|no DMARC|no SPF|no CSP|outdated|"
                r"unsafe-inline|unsafe-eval|p=none|legacy protocol|"
                r"not enforced|catch_all|no STARTTLS)\b", re.I), "medium", "medium"),
    (re.compile(r"\b(present|advertised|disclosed|info|hidden)\b", re.I), "low", "low"),
]
_SEV_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4}


def score_findings(results: List[Result]) -> Dict[str, Any]:
    """Feature #66 - assign severity to each flagged finding + overall score."""
    findings: List[Dict[str, str]] = []
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        for key, val in flat.items():
            text = f"{key} {val}"
            sev = None
            for rx, s, _ in _SEV_RULES:
                if rx.search(text):
                    sev = s
                    break
            if sev and sev != "low":
                findings.append({"module": r.module, "target": r.target,
                                 "field": key, "detail": str(val)[:200], "severity": sev})
    counts = {s: 0 for s in _SEV_ORDER}
    for f in findings:
        counts[f["severity"]] += 1
    risk = counts["critical"] * 40 + counts["high"] * 15 + counts["medium"] * 5
    level = ("CRITICAL" if counts["critical"] else "HIGH" if counts["high"]
             else "MEDIUM" if counts["medium"] else "LOW")
    findings.sort(key=lambda f: _SEV_ORDER[f["severity"]])
    return {"risk_score": risk, "risk_level": level, "counts": counts,
            "findings": findings}


def dedup_findings(results: List[Result]) -> List[Dict[str, str]]:
    """Feature #71 - collapse duplicate findings across modules."""
    seen = set()
    out = []
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        for k, v in flat.items():
            sig = (k.split(".")[-1], str(v))
            if sig in seen:
                continue
            seen.add(sig)
            out.append({"module": r.module, "field": k, "value": str(v)[:160]})
    return out


def export_markdown(results: List[Result], path: str, target: str = "") -> str:
    """Feature #64."""
    score = score_findings(results)
    lines = [f"# Ghost Eye report — {target or (results[0].target if results else '')}",
             f"_Generated {datetime.now(timezone.utc).isoformat()}_", "",
             f"**Risk: {score['risk_level']}** (score {score['risk_score']}) — "
             + ", ".join(f"{k}: {v}" for k, v in score["counts"].items() if v), ""]
    if score["findings"]:
        lines += ["## Prioritised findings", "",
                  "| Severity | Module | Field | Detail |", "|---|---|---|---|"]
        for f in score["findings"]:
            detail_escaped = f['detail'].replace('|', r'\|')
            lines.append(f"| {f['severity'].upper()} | {f['module']} | "
                         f"`{f['field']}` | {detail_escaped} |")
        lines.append("")
    lines.append("## Full results")
    for r in results:
        lines += ["", f"### {r.module}  ({r.status})"]
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        if not flat:
            lines.append("_no data_")
        for k, v in flat.items():
            lines.append(f"- **{k}**: {str(v).replace(chr(10), ' ')}")
    Path(path).write_text("\n".join(lines), encoding="utf-8")
    return path


def export_sarif(results: List[Result], path: str, target: str = "") -> str:
    """Feature #65 - SARIF 2.1.0 for CI security gates."""
    level_map = {"critical": "error", "high": "error",
                 "medium": "warning", "low": "note", "info": "note"}
    score = score_findings(results)
    sarif_results = []
    rules = {}
    for f in score["findings"]:
        rid = re.sub(r"[^a-zA-Z0-9]", "_", f["module"])
        rules.setdefault(rid, {"id": rid, "name": f["module"],
                               "shortDescription": {"text": f["module"]}})
        sarif_results.append({
            "ruleId": rid,
            "level": level_map.get(f["severity"], "note"),
            "message": {"text": f"[{f['severity']}] {f['field']}: {f['detail']}"},
            "locations": [{"physicalLocation": {
                "artifactLocation": {"uri": f["target"]}}}],
        })
    doc = {
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "version": "2.1.0",
        "runs": [{
            "tool": {"driver": {"name": "GhostEye", "version": "3.4.0",
                                "rules": list(rules.values())}},
            "results": sarif_results,
        }],
    }
    Path(path).write_text(json.dumps(doc, indent=2), encoding="utf-8")
    return path


def export_prometheus(results: List[Result], path: str, target: str = "") -> str:
    """Feature #69 - Prometheus text exposition format."""
    score = score_findings(results)
    safe = re.sub(r"[^a-zA-Z0-9_]", "_", target or "target")
    lines = ["# HELP ghosteye_findings Number of findings by severity",
             "# TYPE ghosteye_findings gauge"]
    for sev, n in score["counts"].items():
        lines.append(f'ghosteye_findings{{target="{safe}",severity="{sev}"}} {n}')
    lines += ["# HELP ghosteye_risk_score Overall risk score",
              "# TYPE ghosteye_risk_score gauge",
              f'ghosteye_risk_score{{target="{safe}"}} {score["risk_score"]}',
              "# HELP ghosteye_modules_total Modules run",
              "# TYPE ghosteye_modules_total counter",
              f'ghosteye_modules_total{{target="{safe}"}} {len(results)}', ""]
    Path(path).write_text("\n".join(lines), encoding="utf-8")
    return path


_DASH_TPL = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ghost Eye dashboard - {target}</title>
<style>
:root{{--bg:#0d1117;--panel:#161b22;--line:#30363d;--ink:#e6edf3;--muted:#8b949e;--accent:#58a6ff}}
*{{box-sizing:border-box}}body{{margin:0;font:14px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;background:var(--bg);color:var(--ink)}}
header{{padding:22px;border-bottom:1px solid var(--line);display:flex;flex-wrap:wrap;gap:16px;align-items:center}}
h1{{margin:0;font-size:19px}}.pill{{padding:4px 12px;border-radius:20px;font-weight:600;font-size:12px}}
.critical{{background:#8b2b2b}}.high{{background:#a85323}}.medium{{background:#8a6d1f}}.low{{background:#2d5a3d}}.info{{background:#30363d}}
.ok{{background:#1f6f3f}}.error{{background:#8b2b2b}}.empty{{background:#5a4a1f}}
main{{padding:18px;max-width:1100px;margin:0 auto}}
input,select{{background:var(--panel);border:1px solid var(--line);color:var(--ink);padding:8px 10px;border-radius:8px;font-size:13px}}
.bar{{display:flex;gap:10px;margin-bottom:16px;flex-wrap:wrap}}
.card{{background:var(--panel);border:1px solid var(--line);border-radius:10px;margin-bottom:12px;overflow:hidden}}
.card h2{{margin:0;padding:11px 15px;font-size:14px;border-bottom:1px solid var(--line);color:var(--accent);cursor:pointer;display:flex;justify-content:space-between}}
table{{width:100%;border-collapse:collapse}}td{{padding:6px 15px;border-top:1px solid var(--line);font-size:13px;vertical-align:top}}
td.k{{color:var(--muted);width:240px;white-space:nowrap}}pre{{margin:0;white-space:pre-wrap;word-break:break-word}}
.hidden{{display:none}}footer{{color:var(--muted);text-align:center;padding:22px;font-size:12px}}
</style></head><body>
<header><h1>👁 Ghost Eye</h1>
<span class="pill {risk_class}">RISK: {risk_level} ({risk_score})</span>
<span class="muted">{target} · {ts}</span></header>
<main>
<div class="bar">
<input id="q" placeholder="filter findings…" oninput="flt()" style="flex:1;min-width:200px">
<select id="sev" onchange="flt()"><option value="">all severities</option>
<option>critical</option><option>high</option><option>medium</option><option>low</option></select>
<select id="st" onchange="flt()"><option value="">all statuses</option>
<option>ok</option><option>error</option><option>empty</option></select>
</div>
<div id="cards"></div></main>
<footer>Ghost Eye · authorised security testing only</footer>
<script>
const DATA={data_json};
const sevClass=s=>({{critical:'critical',high:'high',medium:'medium',low:'low'}}[s]||'info');
function esc(s){{return String(s).replace(/[&<>]/g,c=>({{'&':'&amp;','<':'&lt;','>':'&gt;'}}[c]))}}
function render(){{
 const c=document.getElementById('cards');c.innerHTML='';
 DATA.results.forEach((r,i)=>{{
  let rows='';for(const[k,v]of Object.entries(r.flat||{{}})){{
   rows+=`<tr class="row" data-t="${{esc((k+' '+v).toLowerCase())}}" data-sev="${{r.sevByField[k]||''}}">
   <td class="k">${{esc(k)}}</td><td><pre>${{esc(v)}}</pre></td></tr>`;}}
  c.insertAdjacentHTML('beforeend',`<div class="card" data-st="${{r.status}}">
   <h2 onclick="this.nextElementSibling.classList.toggle('hidden')">
   <span>${{esc(r.module)}}</span><span class="pill ${{r.status}}">${{r.status}}</span></h2>
   <table>${{rows||'<tr><td>no data</td></tr>'}}</table></div>`);}});
}}
function flt(){{
 const q=document.getElementById('q').value.toLowerCase();
 const sev=document.getElementById('sev').value;const st=document.getElementById('st').value;
 document.querySelectorAll('.card').forEach(card=>{{
  let any=false;
  card.querySelectorAll('.row').forEach(row=>{{
   const okq=!q||row.dataset.t.includes(q);
   const oks=!sev||row.dataset.sev===sev;
   const show=okq&&oks;row.classList.toggle('hidden',!show);if(show)any=true;}});
  const okst=!st||card.dataset.st===st;
  card.classList.toggle('hidden',!(any&&okst));}});
}}
render();
</script></body></html>"""


def export_dashboard(results: List[Result], path: str, target: str = "") -> str:
    """Feature #70 - interactive single-file HTML dashboard with filter/search."""
    score = score_findings(results)
    sev_by_field_all = {}
    for f in score["findings"]:
        sev_by_field_all.setdefault(f["module"], {})[f["field"]] = f["severity"]
    payload_results = []
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        payload_results.append({"module": r.module, "status": r.status,
                                "flat": flat,
                                "sevByField": sev_by_field_all.get(r.module, {})})
    data_json = json.dumps({"results": payload_results}, ensure_ascii=False)
    doc = _DASH_TPL.format(
        target=_html.escape(target or (results[0].target if results else "")),
        ts=datetime.now(timezone.utc).isoformat(),
        risk_level=score["risk_level"], risk_score=score["risk_score"],
        risk_class=("critical" if score["counts"]["critical"] else
                    "high" if score["counts"]["high"] else
                    "medium" if score["counts"]["medium"] else "low"),
        data_json=data_json)
    Path(path).write_text(doc, encoding="utf-8")
    return path


def push_siem(results: List[Result], url: str, mode: str = "webhook",
              token: str = "", verify: bool = True) -> bool:
    """Feature #68 - push results to Elasticsearch / Splunk HEC / generic webhook.

    `verify` controls TLS certificate verification. It defaults to True: the
    Splunk path sends the HEC token in an Authorization header alongside the
    full findings, and used to do so over an unverified connection, which hands
    both to anyone in the middle. Pass verify=False only for a lab collector
    with a self-signed certificate.
    """
    import requests
    try:
        if mode == "elasticsearch":
            bulk = ""
            for r in results:
                bulk += json.dumps({"index": {}}) + "\n"
                bulk += json.dumps(r.as_dict()) + "\n"
            resp = requests.post(url.rstrip("/") + "/_bulk", data=bulk,
                                 headers={"Content-Type": "application/x-ndjson"},
                                 timeout=20, verify=verify)
        elif mode == "splunk":
            events = "".join(json.dumps({"event": r.as_dict()}) for r in results)
            resp = requests.post(url, data=events,
                                 headers={"Authorization": f"Splunk {token}"},
                                 timeout=20, verify=verify)
        else:  # generic webhook
            resp = requests.post(url, json={"results": [r.as_dict() for r in results]},
                                 timeout=20, verify=verify)
        return resp.status_code < 300
    except Exception as exc:  # noqa: BLE001
        log.warning("SIEM push failed: %s", exc)
        Console.warn(f"SIEM push failed: {exc}")
        return False


def export_ext(results: List[Result], path: str, fmt: str, target: str = "") -> str:
    """Dispatcher for the extended formats."""
    fmt = fmt.lower()
    if fmt in ("md", "markdown"):
        return export_markdown(results, path, target)
    if fmt == "sarif":
        return export_sarif(results, path, target)
    if fmt in ("prom", "prometheus"):
        return export_prometheus(results, path, target)
    if fmt in ("dashboard", "dash"):
        return export_dashboard(results, path, target)
    if fmt in ("exec", "execreport", "executive"):
        return export_exec_report(results, path, target)
    if fmt in ("intel", "intelligence"):
        return export_intel_report(results, path, target)
    if fmt in ("graphml", "gexf"):
        return export_graph(results, path, fmt, target)
    if fmt in ("osint", "dossier"):
        return export_osint_dossier(results, path, target)
    raise ValueError(f"unknown extended format: {fmt}")


def export_graph(results: List[Result], path: str, fmt: str,
                 target: str = "") -> str:
    """Export the typed Knowledge Graph as GraphML or GEXF (feature 39)."""
    from .intelligence import (correlate, knowledge_graph, risk_heatmap,
                               to_gexf, to_graphml)
    intel = correlate(results, target)
    kg = knowledge_graph(results, intel["target"], intel)
    risk_heatmap(kg)  # so exported nodes carry risk / band
    text = to_gexf(kg) if fmt.lower() == "gexf" else to_graphml(kg)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)
    return path

def export_osint_dossier(results: List[Result], path: str, target: str = "") -> str:
    """Render the OSINT dossier (Markdown) from a full intelligence report."""
    from .workflow import intelligence_report
    from .intelligence import osint_dossier
    report = intelligence_report(results, target)
    raw = [{"module": r.module, "data": r.data} for r in results]
    md = osint_dossier(report, raw)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(md)
    return path



# unified asset inventory lives in its own module (clean regex escaping)
from .inventory import build_inventory, build_host_rollup, collect_assets  # noqa: E402,F401


# =========================================================================== #
#  Executive HTML report — the shareable "finished product" deliverable
# =========================================================================== #

_GRADE_COLORS = {"A+": "#2ea043", "A": "#2ea043", "B": "#3fb950",
                 "C": "#d29922", "D": "#db6d28", "F": "#f85149"}
_SEV_COLORS = {"critical": "#f85149", "high": "#db6d28", "medium": "#d29922",
               "low": "#3fb950", "info": "#8b949e"}

_LABELS = {
    "en": {"title": "Executive Security Report", "target": "Target", "date": "Date",
           "modules": "Modules", "grade": "Grade", "risk": "Risk", "graph": "Attack surface",
           "exploits": "Exploit intelligence", "findings": "Prioritised findings",
           "inventory": "Asset inventory", "sev": "Severity", "module": "Module",
           "detail": "Detail", "cve": "CVE", "verdict": "Verdict", "cvss": "CVSS",
           "hosts": "Hosts", "ips": "IPs", "services": "Services", "emails": "Emails",
           "urls": "URLs", "tech": "Technologies", "exploitable": "Exploitable CVEs",
           "none": "no exploitable CVEs found", "foot": "Ghost Eye · authorised testing only"},
    "he": {"title": "דוח אבטחה מנהלים", "target": "יעד", "date": "תאריך",
           "modules": "מודולים", "grade": "ציון", "risk": "סיכון", "graph": "משטח תקיפה",
           "exploits": "מודיעין exploit", "findings": "ממצאים לפי עדיפות",
           "inventory": "מלאי נכסים", "sev": "חומרה", "module": "מודול",
           "detail": "פירוט", "cve": "CVE", "verdict": "מסקנה", "cvss": "CVSS",
           "hosts": "מארחים", "ips": "כתובות IP", "services": "שירותים", "emails": "מיילים",
           "urls": "כתובות", "tech": "טכנולוגיות", "exploitable": "CVE עם exploit",
           "none": "לא נמצאו CVE עם exploit ציבורי", "foot": "Ghost Eye · בדיקות מורשות בלבד"},
}


def _svg_attack_graph(inv: Dict[str, Any], target: str) -> str:
    """Attack-surface graph from an inventory — delegates to the single graph
    renderer in intelligence.graph (no duplicate SVG code)."""
    from .intelligence.graph import build_graph, render_svg
    pseudo = {
        "target": target or inv.get("target", ""),
        "subdomains": inv.get("hosts", []),
        "ips": inv.get("ips", []),
        "cloud": [],
        "technologies": {},
    }
    return render_svg(build_graph(pseudo))


def export_exec_report(results: List[Result], path: str, target: str = "",
                       exploit: Optional[Dict[str, Any]] = None,
                       lang: str = "en") -> str:
    """Render a polished, self-contained executive HTML report."""
    from .workflow import attack_score  # lazy: avoids an import cycle
    L = _LABELS.get(lang, _LABELS["en"])
    rtl = lang == "he"
    scored = score_findings(results)
    a = attack_score(results)
    inv = build_inventory(results, target)
    counts = scored.get("counts", {})
    gcolor = _GRADE_COLORS.get(a["grade"], "#8b949e")
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    total_sev = sum(counts.get(s, 0) for s in _SEV_COLORS) or 1

    def tile(label, value, color="#e6edf3"):
        return (f'<div class="tile"><div class="tv" style="color:{color}">{value}</div>'
                f'<div class="tl">{label}</div></div>')

    exploitable = (exploit or {}).get("exploitable", []) if exploit else []
    tiles = "".join([
        tile(L["hosts"], inv["counts"]["hosts"]),
        tile(L["ips"], inv["counts"]["ips"]),
        tile("critical", counts.get("critical", 0), _SEV_COLORS["critical"]),
        tile("high", counts.get("high", 0), _SEV_COLORS["high"]),
        tile("medium", counts.get("medium", 0), _SEV_COLORS["medium"]),
        tile("low", counts.get("low", 0), _SEV_COLORS["low"]),
        tile(L["exploitable"], len(exploitable),
             _SEV_COLORS["critical"] if exploitable else "#3fb950"),
    ])

    # severity distribution bar
    bar = ""
    for sev in ("critical", "high", "medium", "low", "info"):
        n = counts.get(sev, 0)
        if n:
            pct = 100 * n / total_sev
            bar += (f'<div style="width:{pct:.1f}%;background:{_SEV_COLORS[sev]}" '
                    f'title="{sev}: {n}"></div>')
    _empty_bar = '<div style="width:100%;background:#30363d"></div>'
    bar = '<div class="bar">' + (bar or _empty_bar) + '</div>'

    # findings table
    frows = ""
    for f in scored.get("findings", [])[:25]:
        c = _SEV_COLORS.get(f["severity"], "#8b949e")
        frows += (f'<tr><td><span class="pill" style="background:{c}22;color:{c}">'
                  f'{_html.escape(f["severity"].upper())}</span></td>'
                  f'<td>{_html.escape(f["module"])}</td>'
                  f'<td class="mono">{_html.escape(str(f["detail"])[:120])}</td></tr>')
    findings_tbl = (f'<table><thead><tr><th>{L["sev"]}</th><th>{L["module"]}</th>'
                    f'<th>{L["detail"]}</th></tr></thead><tbody>{frows}</tbody></table>'
                    if frows else '<p class="muted">—</p>')

    # exploit-intel section
    exploit_html = ""
    if exploit and exploit.get("findings"):
        erows = ""
        for e in exploit["findings"][:20]:
            avail = e.get("exploit_available")
            c = _SEV_COLORS["critical"] if avail else "#8b949e"
            verdict = _html.escape(str(e.get("verdict", "")))
            erows += (f'<tr><td class="mono">{_html.escape(e.get("cve",""))}</td>'
                      f'<td>{e.get("cvss") or "—"}</td>'
                      f'<td>{_html.escape(str(e.get("severity","?")))}</td>'
                      f'<td><span class="pill" style="background:{c}22;color:{c}">'
                      f'{verdict}</span></td></tr>')
        exploit_html = (f'<section><h2>{L["exploits"]}</h2><table><thead><tr>'
                        f'<th>{L["cve"]}</th><th>{L["cvss"]}</th><th>{L["sev"]}</th>'
                        f'<th>{L["verdict"]}</th></tr></thead><tbody>{erows}'
                        f'</tbody></table></section>')
    elif exploit is not None:
        exploit_html = f'<section><h2>{L["exploits"]}</h2><p class="muted">{L["none"]}</p></section>'

    # inventory grid
    def inv_block(key, items):
        if not items:
            return ""
        shown = ", ".join(_html.escape(str(x)) for x in items[:40])
        more = f" … (+{len(items) - 40})" if len(items) > 40 else ""
        return f'<div class="inv"><h3>{L[key]} ({len(items)})</h3><p class="mono">{shown}{more}</p></div>'
    inventory_html = "".join(inv_block(k, inv.get(k2, [])) for k, k2 in
                             [("hosts", "hosts"), ("ips", "ips"), ("services", "services"),
                              ("emails", "emails"), ("urls", "urls"), ("tech", "technologies")])

    graph = _svg_attack_graph(inv, target or inv.get("target", ""))

    doc = f"""<!doctype html><html lang="{lang}" dir="{'rtl' if rtl else 'ltr'}"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{L['title']} — {_html.escape(target)}</title><style>
:root{{--bg:#0d1117;--panel:#161b22;--line:#30363d;--ink:#e6edf3;--muted:#8b949e}}
@media(prefers-color-scheme:light){{:root{{--bg:#f6f8fa;--panel:#fff;--line:#d0d7de;--ink:#1f2328;--muted:#57606a}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);
font:14px/1.55 -apple-system,Segoe UI,Roboto,Arial,sans-serif}}
.wrap{{max-width:960px;margin:0 auto;padding:24px}}
header{{display:flex;flex-wrap:wrap;align-items:center;gap:20px;
border-bottom:1px solid var(--line);padding-bottom:18px;margin-bottom:22px}}
.grade{{width:96px;height:96px;border-radius:16px;display:flex;align-items:center;
justify-content:center;font-size:44px;font-weight:800;color:#fff;background:{gcolor};
box-shadow:0 6px 24px {gcolor}55}}
h1{{margin:0 0 4px;font-size:22px}}.meta{{color:var(--muted);font-size:13px}}
.tiles{{display:grid;grid-template-columns:repeat(auto-fit,minmax(110px,1fr));gap:10px;margin:20px 0}}
.tile{{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px;text-align:center}}
.tv{{font-size:26px;font-weight:800}}.tl{{color:var(--muted);font-size:12px;margin-top:4px}}
.bar{{display:flex;height:14px;border-radius:8px;overflow:hidden;margin:8px 0 24px}}
section{{background:var(--panel);border:1px solid var(--line);border-radius:14px;
padding:18px;margin-bottom:18px}}
h2{{margin:0 0 12px;font-size:16px}}h3{{margin:0 0 6px;font-size:13px;color:var(--muted)}}
table{{width:100%;border-collapse:collapse;font-size:13px}}
th,td{{text-align:{'right' if rtl else 'left'};padding:7px 10px;border-top:1px solid var(--line);vertical-align:top}}
th{{color:var(--muted);font-weight:600;border-top:none}}
.pill{{padding:2px 9px;border-radius:20px;font-size:11px;font-weight:700;white-space:nowrap}}
.mono{{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px;word-break:break-word}}
.muted{{color:var(--muted)}}.inv{{margin-bottom:12px}}
.graph{{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:12px;margin-bottom:18px}}
footer{{color:var(--muted);text-align:center;font-size:12px;padding:20px}}
</style></head><body><div class="wrap">
<header><div class="grade">{a['grade']}</div>
<div><h1>👁 {L['title']}</h1>
<div class="meta">{L['target']}: <b>{_html.escape(target)}</b> · {L['date']}: {ts} ·
{L['modules']}: {len(results)} · {L['risk']}: {a['risk_level']} · {a['normalized']}/100</div></div>
</header>
<div class="tiles">{tiles}</div>
{bar}
<div class="graph"><h2 style="margin:4px 6px 8px">{L['graph']}</h2>{graph}</div>
{exploit_html}
<section><h2>{L['findings']}</h2>{findings_tbl}</section>
<section><h2>{L['inventory']}</h2>{inventory_html or '<p class="muted">—</p>'}</section>
<footer>{L['foot']} · {ts}</footer>
</div></body></html>"""
    Path(path).write_text(doc, encoding="utf-8")
    return path


def export_intel_report(results: List[Result], path: str, target: str = "",
                        exploit: Optional[Dict[str, Any]] = None,
                        lang: str = "en") -> str:
    """The 'GHOST EYE INTELLIGENCE REPORT' — a single self-contained HTML page
    that fuses every module's output into an ASM-style picture: asset counts,
    an attack-surface graph, the organization profile, technologies by category,
    email posture, certificates and leak indicators."""
    from .workflow import intelligence_report
    from .intelligence.graph import render_knowledge_svg, render_svg

    rep = intelligence_report(results, target, exploit=exploit)
    intel = rep["intelligence"]
    org = rep["organization"]
    em = intel["email_security"]
    c = intel["counts"]
    gcolor = _GRADE_COLORS.get(rep["grade"], "#8b949e")
    ts = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    esc = _html.escape

    def tile(label, value, color="#e6edf3"):
        return (f'<div class="tile"><div class="tv" style="color:{color}">{value}'
                f'</div><div class="tl">{esc(label)}</div></div>')

    tiles = "".join([
        tile("assets", c["assets"]),
        tile("subdomains", c["subdomains"], "#58a6ff"),
        tile("related domains", c["domains"], "#79c0ff"),
        tile("IPs", c["ips"], "#3fb950"),
        tile("technologies", c["technologies"], "#a371f7"),
        tile("emails", c["emails"]),
        tile("leak indicators", c["leak_indicators"],
             "#f85149" if c["leak_indicators"] else "#3fb950"),
    ])

    def chips(items):
        return "".join(f'<span class="chip">{esc(str(x))}</span>' for x in items)

    tech_html = ""
    for kind, items in intel["technologies"].items():
        tech_html += (f'<div class="row"><span class="k">{esc(kind)}</span>'
                      f'<span>{chips(items)}</span></div>')
    tech_html = tech_html or '<p class="muted">none fingerprinted</p>'

    uses_html = chips(org["uses"])
    risks_html = "".join(f"<li>{esc(str(r))}</li>" for r in org["main_risks"])

    em_color = _GRADE_COLORS.get(em.get("grade", "F"), "#8b949e")
    email_html = (f'<div class="score" style="color:{em_color}">'
                  f'{em["score"]}<span>/100 · {esc(em.get("grade","?"))}</span></div>'
                  f'<div class="muted">SPF {em["spf"]} · DKIM {em["dkim"]} · '
                  f'DMARC {em["dmarc"]} · MTA-STS {em["mta_sts"]}</div>'
                  f'<div class="muted">{esc(", ".join(em.get("issues", [])))}</div>')

    certs = intel["certificates"]
    if certs.get("issuers") or certs.get("san_domains"):
        cert_html = (f'<div class="muted">issuers: '
                     f'{esc(", ".join(certs["issuers"]) or "?")}</div>'
                     f'<div class="row"><span class="k">SAN domains '
                     f'({len(certs["san_domains"])})</span>'
                     f'<span>{chips(certs["san_domains"][:40])}</span></div>')
    else:
        cert_html = '<p class="muted">no certificate data</p>'

    leaks = intel["leak_indicators"]
    leak_html = ("".join(f"<li>{esc(str(x))}</li>" for x in leaks)
                 if leaks else '<li class="muted">no public leak indicators</li>')
    cloud_html = chips(intel["cloud"])
    shots = intel.get("screenshots", [])
    shots_html = ""
    for s in shots[:12]:
        cap = esc(s.get("host", "") or s.get("url", ""))
        shots_html += (f'<figure class="shot"><img loading="lazy" '
                       f'src="{s["image"]}" alt="{cap}"/>'
                       f'<figcaption>{cap}</figcaption></figure>')
    graph_svg = render_svg(rep["graph"])

    # ---- Knowledge Graph + entity correlation + timeline + AI analyst ---- #
    kg = rep.get("knowledge_graph", {})
    corr = rep.get("correlation", {})
    tline = rep.get("timeline", {})
    analysis = rep.get("analysis", {})
    kg_svg = render_knowledge_svg(kg) if kg.get("entities") else ""
    kgc = kg.get("counts", {})

    def _pill(txt):
        return f'<span class="chip">{esc(str(txt))}</span>'

    pivots_html = "".join(
        f'<li><b>{esc(p["entity"])}</b> <span class="muted">({esc(p["kind"])}, '
        f'degree {p["degree"]})</span></li>'
        for p in corr.get("pivot_points", [])[:8]) or \
        '<li class="muted">no strong pivot points</li>'
    shared_html = "".join(
        f'<li><b>{esc(s["hub"])}</b> <span class="muted">({esc(s["kind"])})</span>'
        f' ties {s["connects"]} hosts: {esc(", ".join(s["hosts"][:6]))}</li>'
        for s in corr.get("shared_infrastructure", [])[:6]) or \
        '<li class="muted">no shared infrastructure hubs</li>'
    clusters_html = "".join(
        f'<li>{c["size"]} entities around <b>{esc(c["anchor"])}</b> '
        f'<span class="muted">({esc(c["anchor_kind"])})</span></li>'
        for c in corr.get("clusters", [])[:6]) or \
        '<li class="muted">single cluster</li>'

    _SEVC = {"high": "#f85149", "medium": "#d29922", "info": "#58a6ff",
             "low": "#3fb950"}
    tl_events = tline.get("events", [])
    tl_rows = "".join(
        f'<div class="tlrow"><span class="tldot" style="background:'
        f'{_SEVC.get(e["severity"], "#8b949e")}"></span>'
        f'<span class="tldate">{esc(e["date"])}</span>'
        f'<span class="tlbody"><b>{esc(e["label"])}</b> '
        f'<span class="muted">· {esc(e["host"])} · {esc(e["module"])}</span>'
        f'<br><span class="muted">{esc(e["detail"])}</span></span></div>'
        for e in tl_events[:40])
    tl_insights = "".join(f"<li>{esc(i)}</li>"
                          for i in tline.get("insights", []))
    timeline_html = (
        ('<ul>' + tl_insights + '</ul>' if tl_insights else '')
        + ('<div class="timeline">' + tl_rows + '</div>' if tl_rows
           else '<p class="muted">no dated intelligence in this scan</p>'))

    conf = analysis.get("confidence", "?")
    conf_c = {"high": "#3fb950", "medium": "#d29922", "low": "#f85149"}.get(
        conf, "#8b949e")
    analyst_html = ""
    if analysis:
        assess = "".join(f"<li>{esc(x)}</li>"
                         for x in analysis.get("assessment", []))
        recs = "".join(f"<li>{esc(x)}</li>"
                       for x in analysis.get("recommendations", []))
        analyst_html = (
            f'<div class="headline">{esc(analysis.get("headline", ""))}</div>'
            f'<p>{esc(analysis.get("summary", ""))}</p>'
            f'<h2 style="margin-top:14px">Assessment</h2><ul>{assess}</ul>'
            f'<h2 style="margin-top:14px">How this surface would be approached'
            f'</h2><p class="muted">{esc(analysis.get("attack_narrative", ""))}'
            f'</p>'
            f'<h2 style="margin-top:14px">Recommendations</h2><ol>{recs}</ol>'
            f'<div class="muted" style="margin-top:10px">Confidence: '
            f'<b style="color:{conf_c}">{esc(conf)}</b> · '
            f'{esc(analysis.get("method", ""))}</div>')

    head = ('<!doctype html><html lang="' + esc(lang) + '"><head>'
            '<meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>Ghost Eye Intelligence — ' + esc(target) + '</title><style>'
            ':root{--bg:#0d1117;--panel:#161b22;--line:#30363d;--ink:#e6edf3;--muted:#8b949e}'
            '@media(prefers-color-scheme:light){:root{--bg:#f6f8fa;--panel:#fff;'
            '--line:#d0d7de;--ink:#1f2328;--muted:#57606a}}'
            '*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);'
            'font:14px/1.55 -apple-system,Segoe UI,Roboto,Arial,sans-serif}'
            '.wrap{max-width:1000px;margin:0 auto;padding:24px}'
            'header{display:flex;flex-wrap:wrap;align-items:center;gap:20px;'
            'border-bottom:1px solid var(--line);padding-bottom:18px;margin-bottom:20px}'
            '.grade{width:92px;height:92px;border-radius:16px;display:flex;'
            'align-items:center;justify-content:center;font-size:42px;font-weight:800;'
            'color:#fff;background:' + gcolor + ';box-shadow:0 6px 24px ' + gcolor + '55}'
            'h1{margin:0 0 4px;font-size:21px}.meta{color:var(--muted);font-size:13px}'
            '.tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(105px,1fr));'
            'gap:10px;margin:18px 0}.tile{background:var(--panel);border:1px solid var(--line);'
            'border-radius:12px;padding:13px;text-align:center}'
            '.tv{font-size:24px;font-weight:800}.tl{color:var(--muted);font-size:11px;margin-top:3px}'
            'section{background:var(--panel);border:1px solid var(--line);border-radius:14px;'
            'padding:18px;margin-bottom:16px}h2{margin:0 0 12px;font-size:15px}'
            '.chip{display:inline-block;background:var(--line);border-radius:14px;'
            'padding:3px 10px;margin:2px;font-size:12px}'
            '.row{display:flex;gap:10px;padding:6px 0;border-top:1px solid var(--line);'
            'align-items:baseline}.row .k{color:var(--muted);min-width:120px;font-size:12px}'
            '.muted{color:var(--muted);font-size:12px;margin:4px 0}'
            '.score{font-size:34px;font-weight:800}.score span{font-size:15px;'
            'color:var(--muted);font-weight:600}ul{margin:6px 0;padding-left:20px}li{margin:3px 0}'
            '.graph{background:var(--panel);border:1px solid var(--line);border-radius:14px;'
            'padding:12px;margin-bottom:16px}.two{display:grid;grid-template-columns:1fr 1fr;gap:16px}'
            '@media(max-width:700px){.two{grid-template-columns:1fr}}'
            '.gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:12px}'
            '.shot{margin:0;border:1px solid var(--line);border-radius:10px;overflow:hidden;background:var(--bg)}'
            '.shot img{width:100%;display:block;aspect-ratio:16/10;object-fit:cover;object-position:top}'
            '.shot figcaption{font-size:11px;color:var(--muted);padding:6px 8px;word-break:break-all}'
            '.headline{font-size:16px;font-weight:800;margin:2px 0 10px}'
            '.timeline{border-left:2px solid var(--line);margin:10px 0 2px;padding-left:4px}'
            '.tlrow{display:flex;gap:10px;padding:8px 0 8px 12px;position:relative}'
            '.tldot{width:9px;height:9px;border-radius:50%;flex:0 0 9px;margin-top:4px;'
            'margin-left:-19px;box-shadow:0 0 0 3px var(--panel)}'
            '.tldate{color:var(--muted);font-size:12px;min-width:78px;font-variant-numeric:tabular-nums}'
            '.tlbody{font-size:13px}ol{margin:6px 0;padding-left:20px}'
            'footer{color:var(--muted);text-align:center;font-size:12px;padding:20px}'
            '</style></head><body><div class="wrap">')

    body = (
        '<header><div class="grade">' + rep["grade"] + '</div><div>'
        '<h1>👁 Ghost Eye — Intelligence Report</h1>'
        '<div class="meta">Target: <b>' + esc(rep["target"]) + '</b> · ' + ts +
        ' · risk ' + esc(rep["risk_level"]) + ' · ' + str(rep["score"]) + '/100</div>'
        '</div></header>'
        '<div class="tiles">' + tiles + '</div>'
        + ('<section><h2>🧠 Analyst assessment</h2>' + analyst_html + '</section>'
           if analyst_html else '')
        + '<div class="graph"><h2 style="margin:2px 6px 8px">Attack surface</h2>'
        + graph_svg + '</div>'
        + ('<div class="graph"><h2 style="margin:2px 6px 8px">Knowledge graph — '
           + str(kgc.get("entities", 0)) + ' entities · '
           + str(kgc.get("relationships", 0)) + ' relationships</h2>'
           + kg_svg + '</div>' if kg_svg else '')
        + '<div class="two">'
        '<section><h2>Pivot points</h2><ul>' + pivots_html + '</ul></section>'
        '<section><h2>Shared infrastructure</h2><ul>' + shared_html +
        '</ul><h2 style="margin-top:14px">Correlation clusters</h2><ul>'
        + clusters_html + '</ul></section></div>'
        '<section><h2>🕓 Intelligence timeline</h2>' + timeline_html + '</section>'
        + ('<section><h2>Visual recon (' + str(len(shots)) + ')</h2>'
           '<div class="gallery">' + shots_html + '</div></section>' if shots_html else '')
        + '<div class="two">'
        '<section><h2>Organization profile — uses</h2>' +
        (uses_html or '<span class="muted">n/a</span>') +
        '<h2 style="margin-top:14px">Cloud footprint</h2>' +
        (cloud_html or '<span class="muted">unknown</span>') + '</section>'
        '<section><h2>Main risks</h2><ul>' + risks_html + '</ul></section></div>'
        '<div class="two">'
        '<section><h2>Email security</h2>' + email_html + '</section>'
        '<section><h2>Technologies</h2>' + tech_html + '</section></div>'
        '<section><h2>Certificates</h2>' + cert_html + '</section>'
        '<section><h2>Leak indicators</h2><ul>' + leak_html + '</ul></section>'
        '<footer>Ghost Eye · correlation/detection only · authorised testing only · '
        + ts + '</footer></div></body></html>')

    Path(path).write_text(head + body, encoding="utf-8")
    return path
