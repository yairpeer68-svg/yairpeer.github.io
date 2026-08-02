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


def test_knowledge_graph_is_interactive():
    """The knowledge graph must be filterable by entity kind and focusable by
    clicking a node — assert the wiring is present in the static app."""
    html = _INDEX.read_text(encoding="utf-8")
    # pure renderer + state + updater exist
    for tok in ("function kgSvg(", "function kgUpdate(",
                "let KGSTATE=", "data-kgnode=", 'class="kgfilter"',
                "data-kgreset="):
        assert tok in html, f"missing interactive piece: {tok}"
    # click handler toggles focus; change handler filters by kind
    assert 'e.target.closest("[data-kgnode]")' in html
    assert "KGSTATE.focus" in html and "KGSTATE.hidden" in html
    assert 'e.target.closest(".kgfilter")' in html


def test_static_app_wires_action_panels():
    """Every CLI-only capability now has a dashboard control + loader."""
    html = _INDEX.read_text(encoding="utf-8")
    for act in ("exploits", "risk", "compliance", "screenshots"):
        assert f'data-act="{act}"' in html, f"missing button {act}"
    for fmt in ("exec", "intel"):
        assert f'data-fmt="{fmt}"' in html, f"missing report button {fmt}"
    for fn in ("function loadExploits(", "function loadRisk(",
               "function loadCompliance(", "function loadScreenshots("):
        assert fn in html, f"missing loader {fn}"
    # the export/intel click handler routes the new actions
    for route in ('b.dataset.act==="exploits"', 'b.dataset.act==="risk"',
                  'b.dataset.act==="compliance"',
                  'b.dataset.act==="screenshots"'):
        assert route in html, f"click handler missing {route}"


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


def _make_server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    httpd.jobs = JobManager(Config())          # type: ignore[attr-defined]
    httpd.scheduler = None                      # type: ignore[attr-defined]
    httpd.scope = type("S", (), {"empty": True})()  # type: ignore[attr-defined]
    httpd.auth_token = ""                       # type: ignore[attr-defined]
    return httpd


def test_screenshots_endpoint_merges_into_job(monkeypatch):
    """POST /screenshots runs the visual-recon sweep and merges the thumbnails
    into the job so the Intelligence gallery shows them (browser stubbed)."""
    from ghost_eye import workflow
    img = ("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAA"
           "AAABAAEAAAIBRAA7")

    def fake_capture_surface(results, target="", max_shots=10):
        return [Result("Website screenshot (visual recon)", "api.example.com",
                       "ok", {"final_url": "https://api.example.com",
                              "title": "API", "screenshot": img})]
    monkeypatch.setattr(workflow, "capture_surface", fake_capture_surface)

    httpd = _make_server()
    jid = _seed(httpd.jobs)
    before = len(httpd.jobs.results_obj(jid))
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/api/job/{jid}/screenshots?max=5",
            method="POST")
        with urllib.request.urlopen(req, timeout=10) as r:
            assert r.getcode() == 200
            data = json.loads(r.read())
    finally:
        pass
    # the shot was merged into the job's results
    after = len(httpd.jobs.results_obj(jid))
    httpd.shutdown()
    assert data["count"] == 1
    assert data["screenshots"][0]["image"].startswith("data:image")
    assert after == before + 1


def test_risk_and_compliance_endpoints():
    httpd = _make_server()
    jid = _seed(httpd.jobs)
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/job/{jid}/risk", timeout=10) as r:
            risk = json.loads(r.read())
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/job/{jid}/compliance"
                f"?framework=owasp_top10", timeout=10) as r:
            comp = json.loads(r.read())
    finally:
        httpd.shutdown()
    assert "prioritised" in risk and "overall_risk" in risk
    assert "controls" in comp and comp["framework"] == "owasp_top10"
