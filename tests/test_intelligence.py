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

    def fake_capture(url, timeout=15):
        seen.append(url)
        return {"final_url": url, "title": "x", "screenshot": img,
                "backend": "mock"}
    monkeypatch.setattr("ghost_eye.modules.screenshot.capture", fake_capture)
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
