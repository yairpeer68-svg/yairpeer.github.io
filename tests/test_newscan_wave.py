"""Tests for the new detection modules (jssecrets, sigscan, iamexpose),
EPSS/CISA-KEV enrichment and the PCI-DSS / SOC2 compliance frameworks."""

from __future__ import annotations

from ghost_eye.core import REGISTRY, Context


class _Resp:
    def __init__(self, text="", code=200, js=None):
        self.text = text
        self.status_code = code
        self._js = js or {}

    def json(self):
        return self._js


class _Sess:
    """Serves canned responses keyed by URL substring."""

    def __init__(self, routes, default=None):
        self.routes = routes
        self.default = default if default is not None else _Resp("", 404)

    def get(self, url, params=None, timeout=15, **kw):
        for frag, resp in self.routes.items():
            if frag in url:
                return resp
        return self.default


def _ctx(sess):
    return Context(config={}, session=sess, timeout=5)


# --- 21 jssecrets ----------------------------------------------------------

def test_jssecrets_finds_leaked_key_in_inline_js():
    html = ('<html><body><script>var k="AKIAIOSFODNN7EXAMPLE";'
            'var g="AIzaSyА";</script></body></html>')
    m = REGISTRY["jssecrets"]
    res = m.run("http://x.test", _ctx(_Sess({"x.test": _Resp(html)})))
    assert res.status == "ok"
    assert res.data["secrets_found"] >= 1
    kinds = {f["type"] for f in res.data.get("findings", [])}
    assert "aws_access_key_id" in kinds
    # the raw key must be redacted, never echoed in full
    assert all("AKIAIOSFODNN7EXAMPLE" != f["match"] for f in res.data["findings"])


def test_jssecrets_clean_page_reports_nothing():
    m = REGISTRY["jssecrets"]
    res = m.run("http://x.test", _ctx(_Sess({"x.test": _Resp("<html>ok</html>")})))
    assert res.data["secrets_found"] == 0


# --- 24 sigscan ------------------------------------------------------------

def test_sigscan_matches_builtin_git_config():
    routes = {"/.git/config": _Resp("[core]\n[remote \"origin\"]", 200)}
    m = REGISTRY["sigscan"]
    res = m.run("http://x.test", _ctx(_Sess(routes)))
    assert res.status == "ok"
    ids = {h["id"] for h in res.data.get("findings", [])}
    assert "git-config" in ids


def test_sigscan_no_false_positive_on_404s():
    m = REGISTRY["sigscan"]
    res = m.run("http://x.test", _ctx(_Sess({}, default=_Resp("nope", 404))))
    assert res.data["matches"] == 0


# --- 25 iamexpose ----------------------------------------------------------

def test_iamexpose_detects_aws_credentials():
    routes = {"/.aws/credentials":
              _Resp("[default]\naws_access_key_id=AKIA...\naws_secret_access_key=x", 200)}
    m = REGISTRY["iamexpose"]
    res = m.run("http://x.test", _ctx(_Sess(routes)))
    assert res.data["exposed"] >= 1
    assert any(h["path"] == "/.aws/credentials" for h in res.data["findings"])


def test_iamexpose_flags_over_permissive_policy():
    routes = {"/policy.json": _Resp('{"Action":"*","Resource":"*"}', 200)}
    m = REGISTRY["iamexpose"]
    res = m.run("http://x.test", _ctx(_Sess(routes)))
    assert any(h.get("over_permissive") for h in res.data.get("findings", []))


# --- 17/18 EPSS + CISA KEV enrichment -------------------------------------

def test_epss_and_kev_sources_parse():
    import ghost_eye.modules.exploit_intel as ei
    ei._KEV_LOADED[0] = False
    ei._KEV_CACHE.clear()
    sess = _Sess({
        "first.org": _Resp(js={"data": [{"epss": "0.97", "percentile": "0.99"}]}),
        "cisa.gov": _Resp(js={"vulnerabilities": [
            {"cveID": "CVE-2021-44228", "dueDate": "2021-12-24",
             "vulnerabilityName": "Log4Shell",
             "knownRansomwareCampaignUse": "Known"}]}),
    })
    assert ei._src_epss("CVE-2021-44228", sess, 5)["epss"] == 0.97
    kev = ei._src_kev("CVE-2021-44228", sess, 5)
    assert kev["known_exploited"] is True and kev["kev_ransomware"] is True
    assert ei._src_kev("CVE-2000-0001", sess, 5)["known_exploited"] is False


def test_kev_empty_catalog_is_safe():
    import ghost_eye.modules.exploit_intel as ei
    ei._KEV_LOADED[0] = False
    ei._KEV_CACHE.clear()
    sess = _Sess({}, default=_Resp(js={}))
    assert ei._src_kev("CVE-1", sess, 5)["known_exploited"] is False
    assert ei._src_epss("CVE-1", sess, 5) == {}


# --- 39 PCI-DSS / SOC2 compliance frameworks ------------------------------

def test_pci_and_soc2_frameworks_available():
    from ghost_eye import workflow
    from ghost_eye.core import Result
    results = [Result("cert", "x", "ok", {"grade": "A"}),
               Result("nmap", "x", "ok", {"ports": [443]}),
               Result("jssecrets", "x", "ok", {"secrets_found": 0})]
    for fw in ("pci_dss", "soc2"):
        rep = workflow.compliance_check(results, fw)
        assert rep["framework"] == fw
        assert rep.get("controls")
        assert "error" not in rep


# --- originhunt CDN classification (origin-IP unmasking) -------------------

def test_originhunt_cdn_range_classification():
    import ghost_eye.modules.newscan_wave as nw
    assert nw._cdn_of("104.16.1.1") == "Cloudflare"
    assert nw._cdn_of("151.101.1.1") == "Fastly"
    assert nw._cdn_of("13.32.0.5") == "CloudFront"
    assert nw._cdn_of("8.8.8.8") == ""          # a normal IP is not a CDN
    assert nw._cdn_of("not-an-ip") == ""


def test_originhunt_runs_and_returns_result():
    m = REGISTRY["originhunt"]
    res = m.run("example.com", _ctx(_Sess({})))
    assert res.status in ("ok", "empty")
    assert "cdn_detected" in res.data and "candidate_origins" in res.data


# --- advisor layer (features 68/69/70/72/65/66) ---------------------------

def _report():
    from ghost_eye.core import Result
    from ghost_eye import workflow
    r = [Result("Security headers", "admin.example.com", "ok", {"hsts": "missing"}),
         Result("subs", "example.com", "ok",
                {"subs": ["admin.example.com", "api.example.com", "www.example.com"]}),
         Result("github", "example.com", "ok", {"leaks": ["api_key leaked"]})]
    return workflow.intelligence_report(r, "example.com")


def test_report_carries_advisor_sections():
    rep = _report()
    assert "asset_sensitivity" in rep and "remediation" in rep
    assert "management_brief" in rep
    assert rep["asset_sensitivity"]["counts"]["critical"] >= 1   # admin.*
    assert rep["remediation"]["count"] >= 1
    assert rep["management_brief"]["headline"]


def test_anomaly_detection_flags_growth():
    from ghost_eye.intelligence import anomaly_detection
    rep = _report()
    a = anomaly_detection(rep, {"assets": 1, "subdomains": 1, "leaks": 0, "score": 95})
    assert a["anomalies"]
    assert a["verdict"]


def test_question_answer_and_ai_summary_offline():
    from ghost_eye.intelligence import question_answer, ai_summary
    rep = _report()
    assert "subdomain" in question_answer(rep, "list subdomains").get("answer", "").lower() \
        or question_answer(rep, "list subdomains").get("items")
    s = ai_summary(rep)                        # no key -> deterministic
    assert s["source"].startswith("deterministic")
    assert s["summary"]


# --- OSINT power pack: email pattern, cert pivot, confidence ---------------

def test_emailpattern_infers_and_generates():
    m = REGISTRY["emailpattern"]
    page = ('<p>John Smith - john.smith@acme.com</p>'
            '<p>Jane Doe jane.doe@acme.com</p><p>CEO: Bob Jones</p>')
    routes = {"acme.com": _Resp(page), "/team": _Resp(page)}
    res = m.run("acme.com", _ctx(_Sess(routes)))
    assert res.data["inferred_pattern"] == "first.last"
    assert res.data["email_count"] >= 2
    # an exec whose address was never published gets a generated candidate
    assert "bob.jones@acme.com" in res.data.get("generated_candidates", [])


def test_certpivot_handshake_failure_is_graceful():
    m = REGISTRY["certpivot"]
    res = m.run("no-such-host-xyz.invalid", _ctx(_Sess({})))
    # a failed TLS handshake must not raise — it returns an error Result
    assert res.status in ("error", "ok")


def test_annotate_confidence_by_corroboration():
    from ghost_eye.intelligence import annotate_confidence
    kg = {"entities": [
        {"id": "a", "kind": "ip", "sources": ["dns", "cert", "whois"]},
        {"id": "b", "kind": "subdomain", "sources": ["subs"]},
        {"id": "c", "kind": "domain", "sources": ["related", "cert"]}],
        "relationships": []}
    summary = annotate_confidence(kg)
    assert summary["by_confidence"] == {"high": 1, "medium": 1, "low": 1}
    by = {e["id"]: e["attrs"]["source_confidence"] for e in kg["entities"]}
    assert by == {"a": "high", "b": "low", "c": "medium"}
