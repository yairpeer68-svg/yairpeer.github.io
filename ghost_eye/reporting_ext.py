"""Reporting extensions (new features #64-#71)."""

from __future__ import annotations

import html as _html
import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Tuple

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
              token: str = "") -> bool:
    """Feature #68 - push results to Elasticsearch / Splunk HEC / generic webhook."""
    import requests
    try:
        if mode == "elasticsearch":
            bulk = ""
            for r in results:
                bulk += json.dumps({"index": {}}) + "\n"
                bulk += json.dumps(r.as_dict()) + "\n"
            resp = requests.post(url.rstrip("/") + "/_bulk", data=bulk,
                                 headers={"Content-Type": "application/x-ndjson"},
                                 timeout=20)
        elif mode == "splunk":
            events = "".join(json.dumps({"event": r.as_dict()}) for r in results)
            resp = requests.post(url, data=events,
                                 headers={"Authorization": f"Splunk {token}"},
                                 timeout=20, verify=False)
        else:  # generic webhook
            resp = requests.post(url, json={"results": [r.as_dict() for r in results]},
                                 timeout=20)
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
    raise ValueError(f"unknown extended format: {fmt}")


# unified asset inventory lives in its own module (clean regex escaping)
from .inventory import build_inventory, build_host_rollup, collect_assets  # noqa: E402,F401
