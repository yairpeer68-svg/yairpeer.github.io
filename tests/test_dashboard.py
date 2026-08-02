"""Dashboard wiring tests — assert the browser dashboard actually surfaces the
Personal Cyber Intelligence Platform layer:

* the static single-page app wires the new renderers (analyst, knowledge graph,
  entity correlation, timeline) into the Intelligence panel, and
* the live /api/job/<id>/intel endpoint returns the full report (knowledge
  graph + correlation + timeline + analysis) the panel needs.

The second test runs the real webapp in-process and speaks HTTP to it, so the
API contract the dashboard depends on is verified without a browser.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from ghost_eye.config import Config
from ghost_eye.core import Result
from ghost_eye.webapp import Handler, JobManager

_INDEX = (Path(__file__).resolve().parent.parent
          / "ghost_eye" / "web_static" / "index.html")


def test_static_app_wires_platform_renderers():
    html = _INDEX.read_text(encoding="utf-8")
    # the render functions exist
    for fn in ("function kgraph(", "function renderAnalyst(",
               "function renderCorr(", "function renderTimeline("):
        assert fn in html, f"missing {fn}"
    # and loadIntel() actually calls them
    seg = html.split("async function loadIntel(")[1].split(
        "async function loadInventory(")[0]
    for call in ("renderAnalyst(d.analysis)", "kgraph(kg)",
                 "renderCorr(d.correlation)", "renderTimeline(d.timeline)"):
        assert call in seg, f"loadIntel does not call {call}"


def _seed(jm) -> str:
    res = [
        Result("Subdomain enumeration", "example.com", "ok",
               {"subdomains": ["api.example.com", "dev.example.com"]}),
        Result("DNS records", "api.example.com", "ok", {"a": ["93.184.216.34"]}),
        Result("DNS records", "dev.example.com", "ok", {"a": ["93.184.216.34"]}),
        Result("Technology fingerprint / CMS", "example.com", "ok",
               {"cms": "WordPress", "server": "nginx"}),
        Result("WHOIS", "example.com", "ok",
               {"created": "1995-08-14", "expires": "2026-08-13"}),
        Result("Email auth", "example.com", "ok",
               {"spf": "v=spf1", "dmarc": "v=DMARC1; p=none"}),
    ]
    jid = "dashtest0001"
    jm.jobs[jid] = {
        "id": jid, "target": "example.com", "status": "done",
        "total": len(res), "done": len(res), "current": "",
        "results": [r.as_dict() for r in res], "_results_obj": res,
        "risk": "HIGH", "started": time.time(), "finished": time.time(),
        "error": None, "cancel": False, "_modules": [], "options": {},
    }
    return jid


def test_intel_endpoint_returns_full_platform_report():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.jobs = JobManager(Config())          # type: ignore[attr-defined]
    httpd.scheduler = None                      # type: ignore[attr-defined]
    httpd.scope = type("S", (), {"empty": True})()  # type: ignore[attr-defined]
    httpd.auth_token = ""                       # type: ignore[attr-defined]
    jid = _seed(httpd.jobs)
    port = httpd.server_address[1]
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/job/{jid}/intel",
                timeout=10) as r:
            assert r.getcode() == 200
            data = json.loads(r.read())
    finally:
        httpd.shutdown()

    # the dashboard's Intelligence panel needs all of these keys
    for key in ("knowledge_graph", "correlation", "timeline", "analysis",
                "graph", "intelligence", "organization"):
        assert key in data, f"/intel payload missing {key}"
    assert data["knowledge_graph"]["counts"]["entities"] >= 3
    assert data["knowledge_graph"]["relationships"]
    assert data["analysis"]["headline"]
    assert "no LLM" in data["analysis"]["method"]
    assert any(r["type"] == "resolves_to"
               for r in data["knowledge_graph"]["relationships"])
