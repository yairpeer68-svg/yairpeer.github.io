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
    for act in ("exploits", "risk", "compliance", "screenshots", "trend"):
        assert f'data-act="{act}"' in html, f"missing button {act}"
    assert "function loadTrend(" in html and "function trendChart(" in html
    # change-alert monitoring: an alert-webhook option wired into the scan
    assert 'id="o-alert"' in html
    assert "alert_webhook:" in html
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


def test_persist_fires_surface_alert_on_change(tmp_path, monkeypatch):
    """A re-scan whose surface grew must compute the diff and fire the alert;
    a first scan (no history) must not. Transport is stubbed."""
    from ghost_eye import reporting, workflow
    db = str(tmp_path / "alert.db")
    monkeypatch.setenv("GHOSTEYE_DB", db)
    # seed a previous scan for the target
    st = reporting.Store(db)
    st.save_scan("old", "x.com",
                 [Result("Subdomain enumeration", "x.com", "ok",
                         {"subdomains": ["api.x.com"]})], "LOW", 5)
    st.close()

    sent = []
    monkeypatch.setattr(workflow, "notify_change",
                        lambda diff, url, **k: sent.append((diff, url)) or True)

    jm = JobManager(Config())
    job = {"id": "new", "target": "x.com", "status": "done",
           "_results_obj": [Result("Subdomain enumeration", "x.com", "ok",
                                   {"subdomains": ["api.x.com", "dev.x.com"]})],
           "results": [], "risk": {},
           "options": {"alert_webhook": "https://hooks.slack.com/services/x"}}
    jm._persist(job)
    assert sent, "alert was not fired on a changed surface"
    diff, url = sent[0]
    assert "dev.x.com" in diff["new_subdomains"]
    assert job["surface_change"]["changed"] is True

    # a brand-new target (no history) must NOT alert
    sent.clear()
    job2 = {"id": "first", "target": "brandnew.com", "status": "done",
            "_results_obj": [Result("Subdomain enumeration", "brandnew.com",
                                    "ok", {"subdomains": ["a.brandnew.com"]})],
            "results": [], "risk": {},
            "options": {"alert_webhook": "https://hooks.slack.com/services/x"}}
    jm._persist(job2)
    assert not sent, "first scan must not fire a change alert"


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


def test_trend_endpoint_from_saved_history(tmp_path, monkeypatch):
    """/api/trend re-correlates saved scans into an intelligence trend with
    per-scan knowledge-graph churn."""
    from ghost_eye import reporting
    db = str(tmp_path / "trend.db")
    monkeypatch.setenv("GHOSTEYE_DB", db)
    st = reporting.Store(db)
    s1 = [Result("Subdomain enumeration", "acme.com", "ok",
                 {"subdomains": ["api.acme.com", "www.acme.com"]})]
    st.save_scan("old", "acme.com", s1, "MEDIUM", 20)
    s2 = s1 + [Result("Subdomain enumeration", "acme.com", "ok",
                      {"subdomains": ["api.acme.com", "www.acme.com",
                                      "dev.acme.com"]})]
    st.save_scan("new", "acme.com", s2, "HIGH", 45)
    st.close()

    httpd = _make_server()
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/trend?target=acme.com",
                timeout=15) as r:
            data = json.loads(r.read())
    finally:
        httpd.shutdown()
    assert data["scans"] == 2
    assert "series" in data and len(data["series"]) == 2
    assert "dev.acme.com" in data["series"][1].get("new_entities", [])
    assert "deltas" in data and "subdomains" in data["deltas"]
