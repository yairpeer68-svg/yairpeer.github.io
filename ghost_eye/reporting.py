"""
Reporting & persistence:

  * export_json / export_csv / export_html / export_pdf
  * Store        - SQLite history of every run
  * diff_results - compare two runs of the same module/target
  * notify       - push a summary to Slack / Discord / Telegram webhooks

Covers feature requests #71-#74.
"""

from __future__ import annotations

import csv
import html
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

from .core import Console, Result, log


# --------------------------------------------------------------------------- #
#  Flat export
# --------------------------------------------------------------------------- #
def _flatten(prefix: str, value: Any, out: Dict[str, str]) -> None:
    if isinstance(value, dict):
        for k, v in value.items():
            _flatten(f"{prefix}.{k}" if prefix else str(k), v, out)
    elif isinstance(value, list):
        out[prefix] = "; ".join(str(x) for x in value)
    else:
        out[prefix] = str(value)


def export_json(results: List[Result], path: str) -> str:
    payload = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "results": [r.as_dict() for r in results],
    }
    Path(path).write_text(json.dumps(payload, indent=2, ensure_ascii=False),
                          encoding="utf-8")
    return path


def export_csv(results: List[Result], path: str) -> str:
    rows: List[Dict[str, str]] = []
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        flat.update({"_module": r.module, "_target": r.target,
                     "_status": r.status, "_time": r.started})
        rows.append(flat)
    fields: List[str] = []
    for row in rows:
        for k in row:
            if k not in fields:
                fields.append(k)
    with open(path, "w", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    return path


_HTML_TPL = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Ghost Eye report - {target}</title>
<style>
:root{{--bg:#0d1117;--panel:#161b22;--line:#30363d;--ink:#e6edf3;--muted:#8b949e;--accent:#58a6ff}}
*{{box-sizing:border-box}}body{{margin:0;font:15px/1.5 -apple-system,Segoe UI,Roboto,sans-serif;
background:var(--bg);color:var(--ink)}}header{{padding:28px 22px;border-bottom:1px solid var(--line)}}
h1{{margin:0;font-size:20px;letter-spacing:.5px}}.sub{{color:var(--muted);font-size:13px;margin-top:4px}}
main{{padding:22px;max-width:1000px;margin:0 auto}}.card{{background:var(--panel);border:1px solid var(--line);
border-radius:10px;margin:0 0 16px;overflow:hidden}}.card h2{{margin:0;padding:12px 16px;font-size:15px;
border-bottom:1px solid var(--line);color:var(--accent)}}.card .meta{{padding:4px 16px;color:var(--muted);
font-size:12px}}table{{width:100%;border-collapse:collapse}}td{{padding:7px 16px;border-top:1px solid var(--line);
vertical-align:top;font-size:13px}}td.k{{color:var(--muted);width:230px;white-space:nowrap}}
pre{{margin:0;white-space:pre-wrap;word-break:break-word}}.tag{{font-size:11px;padding:2px 8px;border-radius:20px}}
.ok{{background:#1f6f3f}}.error{{background:#8b2b2b}}.empty{{background:#5a4a1f}}
footer{{color:var(--muted);text-align:center;padding:24px;font-size:12px}}
</style></head><body><header><h1>👁 Ghost Eye report</h1>
<div class="sub">Target: {target} · generated {ts}</div></header><main>{body}</main>
<footer>Ghost Eye · authorised security testing only</footer></body></html>"""


def _result_to_html(r: Result) -> str:
    flat: Dict[str, str] = {}
    _flatten("", r.data, flat)
    rows = "".join(
        f"<tr><td class='k'>{html.escape(k)}</td>"
        f"<td><pre>{html.escape(str(v))}</pre></td></tr>"
        for k, v in flat.items()
    ) or "<tr><td colspan=2><em>no data</em></td></tr>"
    err = f"<div class='meta'>error: {html.escape(r.error or '')}</div>" if r.error else ""
    return (f"<section class='card'><h2>{html.escape(r.module)} "
            f"<span class='tag {r.status}'>{r.status}</span></h2>"
            f"<div class='meta'>{html.escape(r.started)}</div>{err}"
            f"<table>{rows}</table></section>")


def export_html(results: List[Result], path: str, target: str = "") -> str:
    body = "".join(_result_to_html(r) for r in results)
    doc = _HTML_TPL.format(
        target=html.escape(target or (results[0].target if results else "")),
        ts=datetime.now(timezone.utc).isoformat(),
        body=body,
    )
    Path(path).write_text(doc, encoding="utf-8")
    return path


def export_pdf(results: List[Result], path: str, target: str = "") -> str:
    """PDF via reportlab if present, else render the HTML and tell the user."""
    try:
        from reportlab.lib.pagesizes import A4
        from reportlab.lib.units import mm
        from reportlab.pdfgen import canvas
    except ImportError:
        html_path = path.rsplit(".", 1)[0] + ".html"
        export_html(results, html_path, target)
        raise RuntimeError(
            f"reportlab not installed - wrote HTML instead: {html_path} "
            "(pip install reportlab for PDF, or print the HTML to PDF)"
        )
    c = canvas.Canvas(path, pagesize=A4)
    width, height = A4
    y = height - 25 * mm
    c.setFont("Helvetica-Bold", 15)
    c.drawString(20 * mm, y, f"Ghost Eye report - {target}")
    y -= 10 * mm
    c.setFont("Helvetica", 9)
    for r in results:
        if y < 25 * mm:
            c.showPage(); y = height - 25 * mm; c.setFont("Helvetica", 9)
        c.setFont("Helvetica-Bold", 11)
        c.drawString(20 * mm, y, f"{r.module} [{r.status}]"); y -= 6 * mm
        c.setFont("Helvetica", 9)
        flat: Dict[str, str] = {}
        _flatten("", r.data, flat)
        for k, v in flat.items():
            line = f"{k}: {v}"[:110]
            c.drawString(24 * mm, y, line); y -= 5 * mm
            if y < 25 * mm:
                c.showPage(); y = height - 25 * mm; c.setFont("Helvetica", 9)
        y -= 4 * mm
    c.save()
    return path


def export(results: List[Result], path: str, fmt: Optional[str] = None,
           target: str = "") -> str:
    fmt = (fmt or path.rsplit(".", 1)[-1]).lower()
    if fmt == "json":
        return export_json(results, path)
    if fmt == "csv":
        return export_csv(results, path)
    if fmt in ("html", "htm"):
        return export_html(results, path, target)
    if fmt == "pdf":
        return export_pdf(results, path, target)
    raise ValueError(f"unknown report format: {fmt}")


# --------------------------------------------------------------------------- #
#  SQLite history store  (#73) + diff (#72)
# --------------------------------------------------------------------------- #
class Store:
    def __init__(self, path: str = "ghosteye.db") -> None:
        self.conn = sqlite3.connect(path)
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS runs(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   module TEXT, target TEXT, status TEXT,
                   ts TEXT, data TEXT)"""
        )
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS scans(
                   id TEXT PRIMARY KEY, target TEXT, ts TEXT,
                   risk TEXT, score INTEGER, modules INTEGER, results TEXT)"""
        )
        self.conn.commit()

    def save_scan(self, scan_id: str, target: str, results,
                  risk: str = "", score: int = 0) -> None:
        import json as _json
        payload = _json.dumps([r.as_dict() for r in results], ensure_ascii=False)
        self.conn.execute(
            "INSERT OR REPLACE INTO scans(id,target,ts,risk,score,modules,results) "
            "VALUES(?,?,?,?,?,?,?)",
            (scan_id, target, datetime.now(timezone.utc).isoformat(),
             risk, score, len(results), payload))
        self.conn.commit()

    def recent_scans(self, limit: int = 30):
        cur = self.conn.execute(
            "SELECT id,target,ts,risk,score,modules FROM scans "
            "ORDER BY ts DESC LIMIT ?", (limit,))
        return [{"id": i, "target": t, "ts": ts, "risk": rk,
                 "score": sc, "modules": m} for i, t, ts, rk, sc, m in cur.fetchall()]

    def load_scan(self, scan_id: str):
        import json as _json
        cur = self.conn.execute(
            "SELECT target,results FROM scans WHERE id=?", (scan_id,))
        row = cur.fetchone()
        if not row:
            return None
        return {"target": row[0], "results": _json.loads(row[1])}


    def save(self, result: Result) -> None:
        self.conn.execute(
            "INSERT INTO runs(module,target,status,ts,data) VALUES(?,?,?,?,?)",
            (result.module, result.target, result.status, result.started,
             json.dumps(result.data, ensure_ascii=False)),
        )
        self.conn.commit()

    def last_two(self, module: str, target: str) -> List[Dict[str, Any]]:
        cur = self.conn.execute(
            "SELECT data,ts FROM runs WHERE module=? AND target=? "
            "ORDER BY id DESC LIMIT 2", (module, target))
        return [{"data": json.loads(d), "ts": t} for d, t in cur.fetchall()]

    def close(self) -> None:
        self.conn.close()


def diff_results(old: Dict[str, Any], new: Dict[str, Any]) -> Dict[str, List[str]]:
    """Compare two flattened result dicts -> added / removed / changed keys."""
    fo: Dict[str, str] = {}; fn: Dict[str, str] = {}
    _flatten("", old, fo); _flatten("", new, fn)
    added = [k for k in fn if k not in fo]
    removed = [k for k in fo if k not in fn]
    changed = [f"{k}: {fo[k]!r} -> {fn[k]!r}"
               for k in fn if k in fo and fo[k] != fn[k]]
    return {"added": added, "removed": removed, "changed": changed}


# --------------------------------------------------------------------------- #
#  Notifications (#74)
# --------------------------------------------------------------------------- #
def notify(webhook: str, text: str, kind: str = "auto") -> bool:
    """Send `text` to a Slack / Discord / Telegram webhook. kind=auto guesses."""
    import requests
    try:
        if kind == "auto":
            if "hooks.slack.com" in webhook:
                kind = "slack"
            elif "discord.com/api/webhooks" in webhook or "discordapp.com" in webhook:
                kind = "discord"
            elif "api.telegram.org" in webhook:
                kind = "telegram"
            else:
                kind = "slack"
        if kind == "slack":
            payload = {"text": text}
        elif kind == "discord":
            payload = {"content": text[:1900]}
        elif kind == "telegram":
            # webhook should already contain ...sendMessage?chat_id=XXX
            payload = {"text": text}
        else:
            payload = {"text": text}
        resp = requests.post(webhook, json=payload, timeout=15)
        return resp.status_code < 300
    except Exception as exc:  # noqa: BLE001 - network failures are non-fatal
        log.warning("notify failed: %s", exc)
        Console.warn(f"notification failed: {exc}")
        return False
