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
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

from ghost_eye.config import Config
from ghost_eye.core import Result
from ghost_eye.webapp import Handler, JobManager, Scheduler

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
    # change-alert monitoring: an alert-webhook option wired into the scan,
    # and into the schedule form for continuous monitoring
    assert 'id="o-alert"' in html
    assert "alert_webhook:" in html
    assert 'id="schedAlert"' in html
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


def test_static_app_surfaces_error_reasons():
    """Errored modules must show their reason (err-line) and auto-expand so the
    failure is visible without a tap — the 'log' the user was missing."""
    html = _INDEX.read_text(encoding="utf-8")
    assert 'r.status==="error"' in html and "err-line" in html
    assert "let autoErr=new Set()" in html
    # errored modules are auto-added to the expanded set on render
    assert 'r.status==="error" && !autoErr.has(r.module)' in html


def test_osint_page_is_served():
    """The graph-first OSINT dashboard is served at /osint."""
    httpd = _make_server()
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/osint",
                                    timeout=10) as r:
            assert r.getcode() == 200
            body = r.read().decode("utf-8")
    finally:
        httpd.shutdown()
    assert 'id="net"' in body and "OSINT" in body


def test_osint_static_graph_wiring():
    """The OSINT page has the force-directed graph, entity model, filters and
    click-to-pivot wiring."""
    osint = _INDEX.parent / "osint.html"
    assert osint.exists()
    html = osint.read_text(encoding="utf-8")
    for tok in ("function investigate(", "function layout(", "function buildGraph(",
                "function selectNode(", "function renderProfile(",
                "function renderEntity(", "const KMETA=", 'id="net"',
                "knowledge_graph", "/api/scan"):
        assert tok in html, f"osint.html missing {tok}"
    # console ↔ osint cross-links exist
    assert 'href="/osint"' in _INDEX.read_text(encoding="utf-8")


def test_pwa_assets_served():
    """The dashboard is an installable PWA: manifest + service worker + icons are
    served from the root with correct content types."""
    httpd = _make_server()
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        checks = {}
        for path, want in [("/manifest.webmanifest", "manifest"),
                           ("/sw.js", "javascript"),
                           ("/static/icon-192.png", "image/png")]:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}",
                                        timeout=10) as r:
                checks[path] = (r.getcode() == 200,
                                want in r.headers.get("Content-Type", ""),
                                len(r.read()) > 0)
    finally:
        httpd.shutdown()
    for path, (ok_code, ok_type, ok_body) in checks.items():
        assert ok_code and ok_type and ok_body, f"{path}: {checks[path]}"
    # both pages register the service worker and link the manifest
    for name in ("index.html", "osint.html"):
        html = (_INDEX.parent / name).read_text(encoding="utf-8")
        assert 'rel="manifest"' in html and 'navigator.serviceWorker.register' in html


def test_osint_graph_polish_and_keys_wiring():
    """The OSINT page has graph search / cluster / PNG export and the API-keys
    modal wired in."""
    html = (_INDEX.parent / "osint.html").read_text(encoding="utf-8")
    for tok in ('id="gsearch"', 'id="cluster"', 'id="export"',
                "function exportPNG(", "function kindCenters(",
                'id="keysbtn"', 'id="keysmodal"', "function openKeys(",
                '/api/keys'):
        assert tok in html, f"osint.html missing {tok}"


def test_keys_endpoints_save_and_report(tmp_path, monkeypatch):
    """The dashboard can save an API key (persisted) and query which keys are
    set — without ever returning the value."""
    monkeypatch.setenv("GHOSTEYE_CONFIG", str(tmp_path / "cfg.ini"))
    monkeypatch.setenv("GHOSTEYE_NO_KEYRING", "1")   # deterministic file backend
    httpd = _make_server()
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    def call(path, method="GET", body=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(f"http://127.0.0.1:{port}{path}",
                                     data=data, method=method)
        if data:
            req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.getcode(), json.loads(r.read())
    try:
        code, listing = call("/api/keys")
        assert code == 200
        names = {k["name"] for k in listing["keys"]}
        assert {"virustotal", "abuseipdb", "deepseek"} <= names
        assert all(k["set"] is False for k in listing["keys"])
        # no key values are ever present in the listing
        assert "SECRET123" not in json.dumps(listing)

        code, saved = call("/api/keys", "POST",
                           {"name": "virustotal", "value": "SECRET123"})
        assert code == 200 and saved["ok"] is True

        code, listing2 = call("/api/keys")
        vt = [k for k in listing2["keys"] if k["name"] == "virustotal"][0]
        assert vt["set"] is True
        assert "SECRET123" not in json.dumps(listing2)     # still never returned

        # an unknown key name is rejected
        try:
            call("/api/keys", "POST", {"name": "nope", "value": "x"})
            bad = 200
        except urllib.error.HTTPError as e:
            bad = e.code
        assert bad == 400
    finally:
        httpd.shutdown()

    # the value actually persisted to the config
    from ghost_eye.config import Config
    assert Config().api_key("virustotal") == "SECRET123"


def test_portfolio_endpoint(tmp_path, monkeypatch):
    """The portfolio board summarises the latest saved scan per target."""
    from ghost_eye import reporting
    monkeypatch.setenv("GHOSTEYE_DB", str(tmp_path / "p.db"))
    st = reporting.Store(str(tmp_path / "p.db"))
    st.save_scan("a1", "acme.com",
                 [Result("Subs", "acme.com", "ok",
                         {"subdomains": ["a.acme.com", "b.acme.com"]})], "LOW", 5)
    st.save_scan("b1", "foo.com",
                 [Result("Subs", "foo.com", "ok",
                         {"subdomains": ["x.foo.com"]})], "LOW", 5)
    st.close()
    httpd = _make_server()
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/portfolio",
                                    timeout=15) as r:
            d = json.loads(r.read())
    finally:
        httpd.shutdown()
    targets = {t["target"] for t in d["targets"]}
    assert {"acme.com", "foo.com"} <= targets
    assert d["totals"]["targets"] == 2


def test_triage_ack_endpoints_and_mute(tmp_path, monkeypatch):
    """Acknowledged items persist, are filtered from new-exposure lists, and a
    re-scan that only adds an acked item does not alert."""
    from ghost_eye import triage, workflow
    monkeypatch.setenv("GHOSTEYE_ACKS", str(tmp_path / "acks.json"))
    monkeypatch.setenv("GHOSTEYE_DB", str(tmp_path / "db.sqlite"))

    # endpoints
    httpd = _make_server()
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        req = urllib.request.Request(
            f"http://127.0.0.1:{port}/api/acks", method="POST",
            data=json.dumps({"target": "x.com", "item": "dev.x.com"}).encode())
        req.add_header("Content-Type", "application/json")
        with urllib.request.urlopen(req, timeout=10) as r:
            assert "dev.x.com" in json.loads(r.read())["acks"]
        with urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/acks?target=x.com", timeout=10) as r:
            assert json.loads(r.read())["acks"] == ["dev.x.com"]
    finally:
        httpd.shutdown()
    assert triage.filter_new("x.com", ["dev.x.com", "new.x.com"]) == ["new.x.com"]

    # a re-scan adding only the acked subdomain must NOT fire an alert
    fired = []
    monkeypatch.setattr(workflow, "notify_change",
                        lambda diff, url, **k: fired.append(diff) or True)
    jm = JobManager(Config())
    store = jm.cfg  # noqa: F841 (ensure attr exists)
    from ghost_eye import reporting
    st = reporting.Store(str(tmp_path / "db.sqlite"))
    st.save_scan("old", "x.com",
                 [Result("Subs", "x.com", "ok", {"subdomains": ["api.x.com"]})],
                 "LOW", 5)
    st.close()
    job = {"id": "n", "target": "x.com", "status": "done",
           "_results_obj": [Result("Subs", "x.com", "ok",
                                   {"subdomains": ["api.x.com", "dev.x.com"]})],
           "results": [], "risk": {},
           "options": {"alert_webhook": "https://hooks.slack.com/x"}}
    jm._persist(job)
    assert not fired, "acked-only change must not alert"
    assert job["surface_change"]["changed"] is False


def test_scheduled_report_delivery(tmp_path, monkeypatch):
    """A job with a report_webhook pushes a summary on completion."""
    from ghost_eye import workflow
    monkeypatch.setenv("GHOSTEYE_DB", str(tmp_path / "db.sqlite"))
    sent = []
    monkeypatch.setattr(workflow, "notify",
                        lambda results, target, url, **k: sent.append((target, url)) or True)
    jm = JobManager(Config())
    job = {"id": "r", "target": "x.com", "status": "done",
           "_results_obj": [Result("Subs", "x.com", "ok", {"subdomains": ["a.x.com"]})],
           "results": [], "risk": {},
           "options": {"report_webhook": "https://hooks.slack.com/rep"}}
    jm._persist(job)
    assert sent and sent[0][1].endswith("/rep")


def test_new_features_static_wiring():
    osint = (_INDEX.parent / "osint.html").read_text(encoding="utf-8")
    for tok in ('id="portbtn"', "function openPortfolio(", "function askGraph(",
                'id="ackbtn"', "/api/acks", "/api/portfolio"):
        assert tok in osint, f"osint.html missing {tok}"
    console = _INDEX.read_text(encoding="utf-8")
    assert 'id="schedReport"' in console and "report_webhook" in console


def test_static_app_has_mobile_drawer():
    """The controls panel is a slide-in drawer on mobile (starts collapsed,
    backdrop scrim, toggle wiring) — the professional-UI redesign."""
    html = _INDEX.read_text(encoding="utf-8")
    assert 'id="scrim"' in html
    assert 'class="panel collapsed"' in html          # starts closed on mobile
    assert "function setDrawer(" in html
    assert "document.body.classList.toggle(\"drawer\"" in html


def test_handler_crash_returns_500(monkeypatch, tmp_path):
    """A crashing handler must be caught, logged, and returned as 500 — never a
    silent dead connection with no trace."""
    import urllib.error

    from ghost_eye import webapp
    monkeypatch.setenv("GHOSTEYE_ERRORLOG", str(tmp_path / "err.log"))

    def boom(self, parsed):
        raise RuntimeError("boom")
    monkeypatch.setattr(webapp.Handler, "_trend", boom)

    httpd = _make_server()
    httpd.quiet = True                          # type: ignore[attr-defined]
    port = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    try:
        try:
            urllib.request.urlopen(
                f"http://127.0.0.1:{port}/api/trend?target=x", timeout=10)
            code = 200
        except urllib.error.HTTPError as e:
            code = e.code
            body = json.loads(e.read())
    finally:
        httpd.shutdown()
    assert code == 500
    assert "server error" in body["error"]
    # the crash was recorded to the error log
    assert (tmp_path / "err.log").exists()
    assert "boom" in (tmp_path / "err.log").read_text()


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


def test_schedule_forwards_alert_webhook_for_monitoring():
    """A monitored schedule must pass its alert webhook into every job it fires,
    so continuous re-scans emit change alerts (no real scan is run)."""
    jm = JobManager(Config())
    sched = Scheduler(jm)
    captured = {}

    def fake_create(target, modules, options):
        captured["target"] = target
        captured["options"] = options
        return "jid1"
    jm.create = fake_create  # type: ignore[assignment]

    sid = sched.add("x.com", 60, {"mode": "modules", "value": ["headers"]},
                    {"alert_webhook": "https://hooks.slack.com/services/x"})
    try:
        stored = [s for s in sched.list_all() if s["id"] == sid][0]
        assert stored["options"]["alert_webhook"]
        sched._fire(sid)  # simulate the interval firing
        assert captured.get("target") == "x.com"
        assert captured["options"].get("alert_webhook") == \
            "https://hooks.slack.com/services/x"
    finally:
        sched.remove(sid)


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
