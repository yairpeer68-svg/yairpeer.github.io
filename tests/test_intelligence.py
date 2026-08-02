"""Tests for the intelligence / correlation layer."""

from __future__ import annotations

from ghost_eye.core import Result


def _sample():
    return [
        Result("Subdomain enumeration", "example.com", "ok",
               {"subdomains": ["api.example.com", "dev.example.com",
                               "www.example.com"]}),
        Result("Technology fingerprint / CMS", "example.com", "ok",
               {"cms": "WordPress", "server": "nginx", "cdn": "Cloudflare",
                "js": "React"}),
        Result("Cloud provider", "example.com", "ok",
               {"provider": "AWS amazonaws.com"}),
        Result("SSL certificate analysis", "example.com", "ok",
               {"issuer": "Let's Encrypt",
                "san": "example.com, api.example.com, *.example.com"}),
        Result("Email auth", "example.com", "ok",
               {"spf": "v=spf1 include:_spf.google.com",
                "dmarc": "v=DMARC1; p=none"}),
        Result("TLS", "example.com", "ok",
               {"tlsv1.0": "legacy protocol enabled"}),
        Result("Leaked credentials check", "example.com", "ok",
               {"breach": "3 credentials found in public leak"}),
        Result("DNS", "example.com", "ok", {"a": ["93.184.216.34"]}),
    ]


def test_correlate_counts_and_classification():
    from ghost_eye.intelligence import correlate
    intel = correlate(_sample(), "example.com")
    assert "api.example.com" in intel["subdomains"]
    # tech is classified into buckets
    assert "WordPress" in intel["technologies"]["cms"]
    assert "React" in intel["technologies"]["framework"]
    assert "nginx" in intel["technologies"]["server"]
    # cloud detected
    assert "AWS" in intel["cloud"] and "Cloudflare" in intel["cloud"]
    # email posture reads DMARC p=none as a weakness
    assert intel["email_security"]["dmarc"] is True
    assert "p=none" in " ".join(intel["email_security"]["issues"]).lower()
    # leak indicator surfaced
    assert intel["counts"]["leak_indicators"] >= 1
    assert intel["counts"]["subdomains"] >= 3


def test_classifier_no_false_positives():
    from ghost_eye.intelligence import correlate
    # a plain site that merely loads Google Fonts and mentions Amazon in copy
    res = [Result("Tech", "shop.com", "ok",
                  {"html": "buy on amazon! <link href=fonts.googleapis.com/css>",
                   "dmarc": "v=DMARC1; p=none"})]
    intel = correlate(res, "shop.com")
    # googleapis/amazon brand mentions must NOT be read as cloud infrastructure
    assert "GCP" not in intel["cloud"] and "AWS" not in intel["cloud"]
    # a bare DMARC 'p=' must not be misread as a DKIM record
    assert intel["email_security"]["dkim"] is False


def test_organization_profile_uses_and_risks():
    from ghost_eye.intelligence import correlate, organization_profile
    intel = correlate(_sample(), "example.com")
    prof = organization_profile(intel, _sample())
    assert "WordPress" in prof["uses"] and "AWS" in prof["uses"]
    risks = " ".join(prof["main_risks"]).lower()
    assert "dev.example.com" in risks          # exposed non-prod subdomain
    assert "tls" in risks                       # outdated TLS flagged


def test_correlate_collects_screenshots():
    from ghost_eye.intelligence import correlate
    # a 1x1 gif data URI stands in for a captured thumbnail
    img = ("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAA"
           "AAABAAEAAAIBRAA7")
    res = _sample() + [
        Result("Website screenshot (visual recon)", "www.example.com", "ok",
               {"final_url": "https://www.example.com", "title": "Example",
                "screenshot": img})]
    intel = correlate(res, "example.com")
    assert intel["counts"]["screenshots"] == 1
    assert intel["screenshots"][0]["host"] == "www.example.com"
    assert intel["screenshots"][0]["image"].startswith("data:image")


def test_capture_surface_screenshots_subdomains(monkeypatch):
    from ghost_eye import workflow
    img = ("data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAA"
           "AAABAAEAAAIBRAA7")
    seen = []

    def fake_capture_many(urls, timeout=15):
        out = {}
        for u in urls:
            seen.append(u)
            out[u] = {"final_url": u, "title": "x", "screenshot": img,
                      "backend": "mock"}
        return out
    monkeypatch.setattr("ghost_eye.modules.screenshot.capture_many",
                        fake_capture_many)
    results = [Result("Subdomain enumeration", "acme.com", "ok",
                      {"subdomains": ["api.acme.com", "dev.acme.com"]})]
    shots = workflow.capture_surface(results, "acme.com", max_shots=5)
    hosts = {s.target for s in shots}
    assert "api.acme.com" in hosts and "dev.acme.com" in hosts
    assert all(s.data["screenshot"].startswith("data:image") for s in shots)
    # merged into the intelligence gallery
    intel = workflow.intelligence_report(results + shots, "acme.com")
    assert intel["counts"]["screenshots"] == len(shots)


def test_screenshot_backend_detection(monkeypatch):
    from ghost_eye.modules import screenshot
    monkeypatch.setattr(screenshot.shutil, "which",
                        lambda n: "/usr/bin/chromium" if n == "chromium" else None)
    monkeypatch.setattr(screenshot.glob, "glob", lambda *a, **k: [])
    # no playwright build, but a system chromium is found
    assert screenshot._find_chromium() == "/usr/bin/chromium"


def test_screenshot_thumbnail_encoder():
    from ghost_eye.modules.screenshot import _to_thumbnail
    # a tiny valid PNG (1x1) — encoder must return a data URI
    import base64
    png = base64.b64decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
        "+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
    uri = _to_thumbnail(png)
    assert uri.startswith("data:image/")


def test_graph_build_and_render():
    from ghost_eye.intelligence import build_graph, correlate, render_svg
    g = build_graph(correlate(_sample(), "example.com"))
    kinds = {n["kind"] for n in g["nodes"]}
    assert "target" in kinds and "subdomain" in kinds
    assert len(g["edges"]) == len(g["nodes"]) - 1     # star from the target
    svg = render_svg(g)
    assert svg.startswith("<svg") and "</svg>" in svg


def _rich_sample():
    """A sample with DNS, WHOIS, cert and CVE data for the platform layer."""
    return _sample() + [
        Result("DNS records", "api.example.com", "ok",
               {"a": ["93.184.216.34"]}),
        Result("DNS records", "www.example.com", "ok",
               {"a": ["93.184.216.34"], "mx": "mail.example.com",
                "ns": "ns1.example.com ns2.example.com"}),
        Result("WHOIS", "example.com", "ok",
               {"created": "1995-08-14", "expires": "2026-08-13",
                "registrant org": "Example Inc", "updated": "2024-03-01"}),
        Result("SSL certificate analysis", "example.com", "ok",
               {"not_after": "2025-09-01", "not_before": "2025-06-01"}),
        Result("CVE lookup", "example.com", "ok",
               {"cve": "CVE-2021-44228 in log4j"}),
    ]


def test_knowledge_graph_typed_entities_and_relationships():
    from ghost_eye.intelligence import correlate, knowledge_graph
    intel = correlate(_rich_sample(), "example.com")
    kg = knowledge_graph(_rich_sample(), "example.com", intel)
    kinds = {e["kind"] for e in kg["entities"]}
    # a real knowledge graph has many typed entity kinds, not just hosts
    assert {"target", "subdomain", "ip", "tech", "cve"} <= kinds
    # relationships are typed and directed
    rtypes = {r["type"] for r in kg["relationships"]}
    assert "subdomain_of" in rtypes and "uses" in rtypes
    # DNS output produced a real resolves_to edge to the shared IP
    assert any(r["type"] == "resolves_to" for r in kg["relationships"])
    assert kg["counts"]["entities"] == len(kg["entities"])


def test_entity_correlation_pivots_and_shared_infra():
    from ghost_eye.intelligence import (correlate, entity_correlation,
                                        knowledge_graph)
    intel = correlate(_rich_sample(), "example.com")
    kg = knowledge_graph(_rich_sample(), "example.com", intel)
    corr = entity_correlation(kg)
    # the apex is the most connected pivot
    assert corr["pivot_points"][0]["entity"] == "example.com"
    # two subdomains share one IP -> a shared-infrastructure hub
    hubs = {s["hub"] for s in corr["shared_infrastructure"]}
    assert "93.184.216.34" in hubs


def test_timeline_orders_events_and_flags_expiry():
    from ghost_eye.intelligence import build_timeline
    tl = build_timeline(_rich_sample(), "example.com")
    dates = [e["date"] for e in tl["events"]]
    assert dates == sorted(dates)                     # chronological
    kinds = {e["kind"] for e in tl["events"]}
    assert "registration" in kinds and "expiry" in kinds
    joined = " ".join(tl["insights"]).lower()
    assert "expir" in joined and "registered" in joined


def test_analyst_writes_narrative_without_llm():
    from ghost_eye import workflow
    rep = workflow.platform_report(_rich_sample(), "example.com")
    an = rep["analysis"]
    assert "no LLM" in an["method"]
    assert "example.com" in an["headline"] and an["confidence"] in (
        "low", "medium", "high")
    # the narrative names a concrete first foothold and gives recommendations
    assert "dev.example.com" in an["attack_narrative"]
    assert any("DMARC" in r or "MFA" in r or "TLS" in r
               for r in an["recommendations"])
    # good grammar: no naive 'technologys'
    assert "technologys" not in an["summary"]


def test_platform_report_bundles_every_layer():
    from ghost_eye import workflow
    rep = workflow.platform_report(_rich_sample(), "example.com")
    for key in ("knowledge_graph", "correlation", "timeline", "analysis",
                "intelligence", "organization", "graph"):
        assert key in rep


def test_knowledge_graph_svg_renders():
    from ghost_eye.intelligence import (correlate, knowledge_graph,
                                        render_knowledge_svg)
    intel = correlate(_rich_sample(), "example.com")
    kg = knowledge_graph(_rich_sample(), "example.com", intel)
    svg = render_knowledge_svg(kg)
    assert svg.startswith("<svg") and "</svg>" in svg
    # empty graph degrades gracefully
    empty = render_knowledge_svg({"entities": [], "relationships": []})
    assert "<svg" in empty


def test_intel_html_has_platform_sections(tmp_path):
    from ghost_eye import reporting_ext
    p = reporting_ext.export_intel_report(_rich_sample(),
                                          str(tmp_path / "p.html"),
                                          "example.com")
    html = open(p, encoding="utf-8").read()
    for token in ("Analyst assessment", "Knowledge graph",
                  "Intelligence timeline", "Pivot points",
                  "Recommendations"):
        assert token in html


class _FakeStore:
    """Minimal Store stand-in for intelligence_trend (scans oldest-first)."""

    def __init__(self, scans):
        self._scans = scans

    def scans_for(self, target, limit=100):
        return self._scans


def test_intelligence_trend_tracks_surface_growth():
    from ghost_eye import workflow

    def scan(sid, ts, subs, tech):
        return {"id": sid, "ts": ts, "risk": "", "score": 0, "modules": 2,
                "results": [
                    {"module": "Subdomain enumeration", "target": "x.com",
                     "status": "ok", "data": {"subdomains": subs}},
                    {"module": "Technology fingerprint", "target": "x.com",
                     "status": "ok", "data": tech}]}
    s1 = scan("s1", "2026-07-01T10:00:00",
              ["api.x.com", "www.x.com"], {"cms": "WordPress"})
    s2 = scan("s2", "2026-07-15T10:00:00",
              ["api.x.com", "www.x.com", "dev.x.com"],
              {"cms": "WordPress", "js": "React"})
    trend = workflow.intelligence_trend(_FakeStore([s1, s2]), "x.com")
    assert trend["scans"] == 2
    assert trend["series"][0]["subdomains"] == 2
    assert trend["series"][1]["subdomains"] == 3
    # the second scan's churn names the newly-appeared entities
    new = trend["series"][1]["new_entities"]
    assert "dev.x.com" in new and "React" in new
    assert trend["deltas"]["subdomains"] == 1
    assert trend["deltas"]["entities"] >= 2
    assert trend["direction"] in ("worsening", "improving", "stable")


def test_intelligence_report_and_html(tmp_path):
    from ghost_eye import reporting_ext, workflow
    rep = workflow.intelligence_report(_sample(), "example.com")
    assert rep["target"] == "example.com"
    assert rep["counts"]["assets"] > 0
    assert rep["grade"] in ("A+", "A", "B", "C", "D", "F")
    p = reporting_ext.export_intel_report(_sample(), str(tmp_path / "i.html"),
                                          "example.com")
    html = open(p, encoding="utf-8").read()
    assert "<svg" in html
    assert "Intelligence Report" in html
    assert "WordPress" in html and "AWS" in html
