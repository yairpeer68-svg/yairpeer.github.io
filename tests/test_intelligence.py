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


def test_surface_diff_detects_new_exposure():
    from ghost_eye import workflow
    prev = [Result("Subdomain enumeration", "x.com", "ok",
                   {"subdomains": ["api.x.com", "www.x.com"]}),
            Result("Tech", "x.com", "ok", {"cms": "WordPress"})]
    curr = prev + [
        Result("Subdomain enumeration", "x.com", "ok",
               {"subdomains": ["api.x.com", "www.x.com", "dev.x.com"]}),
        Result("Port scan", "x.com", "ok", {"services": "6379/redis"}),
        Result("CVE", "x.com", "ok", {"cve": "CVE-2021-44228"})]
    diff = workflow.surface_diff(prev, curr, "x.com")
    assert diff["changed"] and not diff["first_scan"]
    assert "dev.x.com" in diff["new_subdomains"]
    assert "6379/redis" in diff["new_services"]
    assert "CVE-2021-44228" in diff["new_cves"]
    # an unchanged surface must not be flagged
    assert workflow.surface_diff(curr, curr, "x.com")["changed"] is False
    # first-ever scan is not a "change"
    assert workflow.surface_diff([], curr, "x.com")["first_scan"] is True


def test_notify_change_only_sends_on_change():
    from ghost_eye import workflow

    class _Resp:
        status_code = 200

    class _Sess:
        def __init__(self):
            self.calls = []

        def post(self, url, **kw):
            self.calls.append((url, kw))
            return _Resp()

    diff = {"target": "x.com", "changed": True, "total_new": 1,
            "new_subdomains": ["dev.x.com"], "new_ips": [], "new_services": [],
            "new_cves": [], "new_technologies": [], "new_leaks": [],
            "new_cloud": [], "new_emails": []}
    s = _Sess()
    assert workflow.notify_change(
        diff, "https://hooks.slack.com/services/T/B/z", session=s) is True
    assert s.calls and "dev.x.com" in s.calls[0][1]["json"]["text"]
    # no webhook / no change → no send
    assert workflow.notify_change(diff, "", session=s) is False
    assert workflow.notify_change(
        {"changed": False}, "https://hooks.slack.com/x", session=s) is False


def test_graph_excludes_osint_reference_noise():
    """OSINT dork reference sites (github/pastebin/google) and URL-encode
    artifacts must never appear as graph entities — only the target's own hosts."""
    from ghost_eye.intelligence import correlate, knowledge_graph
    from ghost_eye.intelligence.correlation import is_noise_domain
    assert is_noise_domain("github.com", "example.com")
    assert is_noise_domain("3agithub.com", "example.com")     # %3a artifact
    assert is_noise_domain("20example.com", "example.com")    # %20 artifact
    assert is_noise_domain("2a.example.com", "example.com")   # %2a artifact
    assert not is_noise_domain("api.partner.com", "example.com")

    res = [
        Result("OSINT dork", "example.com", "ok",
               {"result": "site:github.com found", "ref": "pastebin.com"}),
        Result("DNS records", "example.com", "ok",
               {"ns": "https://www.google.com/search?q=site:github.com",
                "a": ["93.184.216.34"]}),
        Result("Subdomain enumeration", "example.com", "ok",
               {"subdomains": ["api.example.com"]}),
    ]
    intel = correlate(res, "example.com")
    kg = knowledge_graph(res, "example.com", intel)
    labels = {e["label"] for e in kg["entities"]}
    assert not (labels & {"github.com", "pastebin.com", "www.google.com",
                          "3agithub.com"})
    assert "api.example.com" in labels        # real subdomain kept


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


# --- graph risk analytics (wave 2: features 17-19, 24) --------------------

def _risk_sample():
    return _sample() + [
        Result("CVE correlation (NVD)", "example.com", "ok",
               {"cves": ["nginx affected by CVE-2021-23017",
                         "CVE-2019-11043"]}),
        Result("Security headers", "example.com", "ok",
               {"scripts": ["https://cdn.jsdelivr.net/npm/jquery@3/jquery.js",
                            "https://www.googleapis.com/maps/api.js",
                            "https://cdnjs.cloudflare.com/ajax/x.js"]}),
    ]


def test_risk_heatmap_scores_and_bands():
    from ghost_eye.intelligence import knowledge_graph, risk_heatmap, correlate
    res = _risk_sample()
    intel = correlate(res, "example.com")
    kg = knowledge_graph(res, "example.com", intel)
    heat = risk_heatmap(kg)
    # every entity now carries a numeric risk + a band in its attrs
    for e in kg["entities"]:
        assert 0 <= e["attrs"]["risk"] <= 100
        assert e["attrs"]["risk_band"] in ("low", "medium", "high", "critical")
    assert heat["max"] >= 25          # leaks/cves push at least one node up
    assert sum(heat["band_counts"].values()) == len(kg["entities"])
    # the target should be among the hottest hosts (it inherits leak+cve danger)
    assert any(h["kind"] == "target" for h in heat["hottest_hosts"])


def test_attack_paths_reach_target():
    from ghost_eye.intelligence import (knowledge_graph, risk_heatmap,
                                        attack_paths, correlate)
    res = _risk_sample()
    intel = correlate(res, "example.com")
    kg = knowledge_graph(res, "example.com", intel)
    risk_heatmap(kg)
    ap = attack_paths(kg)
    assert ap["count"] >= 1
    p = ap["paths"][0]
    assert p["steps"][-1]["kind"] == "target"     # every chain ends at target
    assert p["entry_kind"] in ("leak", "cve", "exposure")
    assert p["band"] in ("low", "medium", "high", "critical")


def test_enrich_tech_cve_links_and_supply_chain():
    from ghost_eye.intelligence import (knowledge_graph, enrich_tech_cve,
                                        supply_chain, correlate)
    res = _risk_sample()
    intel = correlate(res, "example.com")
    kg = knowledge_graph(res, "example.com", intel)
    added = enrich_tech_cve(kg, res)
    assert added >= 1
    tech_cve = [r for r in kg["relationships"]
                if r["type"] == "affected_by" and r["from"].startswith("tech:")]
    assert tech_cve                                # nginx -> CVE edge exists
    sc = supply_chain(kg, res, "example.com")
    provs = {d["provider"] for d in sc["dependencies"]}
    assert "jsdelivr" in provs                     # specific brand, not "cdn"
    # dependency nodes are added to the graph and linked to the target
    assert any(e["kind"] == "dependency" for e in kg["entities"])


def test_report_exposes_wave2_sections():
    from ghost_eye import workflow
    rep = workflow.intelligence_report(_risk_sample(), "example.com")
    assert "risk_heatmap" in rep and "attack_paths" in rep
    assert "supply_chain" in rep
    assert rep["risk_heatmap"]["band_counts"]


# --- graph export + unified multi-target graph (wave 3/5: features 39, 4) ---

def test_graphml_and_gexf_are_valid_xml():
    import xml.dom.minidom as minidom
    from ghost_eye.intelligence import (knowledge_graph, risk_heatmap,
                                        to_gexf, to_graphml, correlate)
    res = _risk_sample()
    intel = correlate(res, "example.com")
    kg = knowledge_graph(res, "example.com", intel)
    risk_heatmap(kg)
    gml = to_graphml(kg)
    minidom.parseString(gml)              # raises if malformed
    assert "graphml" in gml and "<node" in gml and "risk" in gml
    gexf = to_gexf(kg)
    minidom.parseString(gexf)
    assert "gexf" in gexf and "<edge" in gexf


def test_unified_graph_merges_and_links_shared_infra():
    from ghost_eye.core import Result
    from ghost_eye.intelligence import (knowledge_graph, risk_heatmap,
                                        unified_graph, correlate)

    def kg_for(t, ip):
        res = [Result("dns", t, "ok", {"A": [ip]}),
               Result("cloud", t, "ok", {"provider": "AWS amazonaws"})]
        intel = correlate(res, t)
        kg = knowledge_graph(res, t, intel)
        risk_heatmap(kg)
        return t, kg

    u = unified_graph([kg_for("a.com", "1.2.3.4"), kg_for("b.com", "1.2.3.4")])
    assert u["counts"]["targets"] == 2
    # the shared IP appears once and ties both targets together
    ips = [e for e in u["entities"] if e["kind"] == "ip" and e["label"] == "1.2.3.4"]
    assert len(ips) == 1
    shared = {r["type"] for r in u["relationships"]}
    assert "shared_between" in shared
    assert any(s["kind"] == "ip" for s in u["shared_infrastructure"])


# --- advanced OSINT: multi-hop deep-dive auto-pivot ------------------------

def test_osint_deep_dive_pivots_and_merges():
    from ghost_eye.core import Result
    from ghost_eye.intelligence import deep_dive

    def run_fn(target, module_ids, cfg):
        if target == "acme.com":
            return [Result("related", "acme.com", "ok",
                           {"related_domains": ["acme-corp.com"]}),
                    Result("emails", "acme.com", "ok",
                           {"emails": ["ceo@acme.com"]}),
                    Result("subs", "acme.com", "ok",
                           {"subdomains": ["www.acme.com", "api.acme.com"]})]
        if target == "acme-corp.com":
            return [Result("subs", "acme-corp.com", "ok",
                           {"subdomains": ["mail.acme-corp.com"]})]
        if target == "ceo@acme.com":
            return [Result("breachcheck", "ceo@acme.com", "ok",
                           {"breach": "found in 2 leaks"})]
        return []

    out = deep_dive("acme.com", run_fn=run_fn, depth=1)
    assert out["seed"] == "acme.com"
    # hop 0 processes the seed and discovers a related domain + an email
    assert out["hops"][0]["discovered_counts"]["domain"] >= 1
    assert out["hops"][0]["discovered_counts"]["email"] >= 1
    # hop 1 pivots onto them (so more than one entity is processed in total)
    assert out["entities_processed"] >= 3
    # provenance records the parent that led to each discovered entity
    parents = {p["entity"]: p["parent"] for p in out["provenance"]}
    assert parents.get("acme-corp.com") == "acme.com"
    # everything merges into one graph
    assert out["counts"]["entities"] >= 5


def test_osint_deep_dive_depth_zero_is_seed_only():
    from ghost_eye.core import Result
    from ghost_eye.intelligence import deep_dive
    calls = []

    def run_fn(target, module_ids, cfg):
        calls.append(target)
        return [Result("related", target, "ok",
                       {"related_domains": ["other.com"]})]

    out = deep_dive("acme.com", run_fn=run_fn, depth=0)
    assert out["entities_processed"] == 1 and calls == ["acme.com"]


def test_source_matrix_attributes_assets():
    from ghost_eye.core import Result
    from ghost_eye.intelligence import source_matrix
    res = [Result("certspotter", "x", "ok", {"subdomains": ["api.acme.com", "www.acme.com"]}),
           Result("hackertarget", "x", "ok", {"subdomains": ["api.acme.com"]}),
           Result("otxrep", "x", "ok", {"subdomains": ["api.acme.com"], "ips": ["1.2.3.4"]}),
           Result("sitedossier", "x", "ok", {"subdomains": ["weird.acme.com"]})]
    m = source_matrix(res, "acme.com")
    top = m["subdomains"][0]
    assert top["asset"] == "api.acme.com" and top["corroboration"] == 3
    assert top["confidence"] == "high"
    assert set(top["sources"]) == {"certspotter", "hackertarget", "otxrep"}
    assert m["summary"]["multi_source_subdomains"] == 1
    # a single-source asset is low confidence
    weird = next(r for r in m["subdomains"] if r["asset"] == "weird.acme.com")
    assert weird["confidence"] == "low"


def test_osint_dossier_markdown():
    from ghost_eye.core import Result
    from ghost_eye import reporting_ext
    import tempfile, os
    res = [Result("subs", "acme.com", "ok", {"subdomains": ["api.acme.com"]}),
           Result("certspotter", "acme.com", "ok", {"subdomains": ["api.acme.com"]}),
           Result("wikidata", "acme.com", "ok",
                  {"organisation": "Acme Inc", "parent_company": "Acme Holdings"}),
           Result("hudsonrock", "acme.com", "ok", {"total": 12}),
           Result("waybacksecrets", "acme.com", "ok",
                  {"findings": [{"type": "aws_access_key_id", "match": "AKIA…x",
                                 "archived_url": "http://acme.com/a.js",
                                 "timestamp": "20200101"}]})]
    p = tempfile.mktemp(suffix=".md")
    reporting_ext.export_ext(res, p, "osint", "acme.com")
    md = open(p, encoding="utf-8").read()
    os.remove(p)
    assert "# OSINT dossier — acme.com" in md
    assert "## Organisation" in md and "Acme Inc" in md
    assert "## Assets & source attribution" in md and "api.acme.com" in md
    assert "Hudson Rock" in md               # exposure section
    assert "Archived secrets" in md and "aws_access_key_id" in md
