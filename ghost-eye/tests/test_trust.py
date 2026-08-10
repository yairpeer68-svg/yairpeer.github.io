"""Tests for the trust layer: confidence/provenance, OPSEC leak-awareness, and
the source-registry health check.

These pin the behaviour that turns flat findings into signal you can weigh: a
directly-observed fact ranks above a third-party claim above a heuristic guess;
an OSINT scan tells you which third parties it disclosed the target to; and the
data-driven registry can audit its own sources.
"""

from __future__ import annotations

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye import confidence as C
from ghost_eye import opsec
from ghost_eye.core import Context, REGISTRY, Result
from ghost_eye.reporting_ext import score_findings


# --------------------------------------------------------------------------- #
#  confidence / provenance
# --------------------------------------------------------------------------- #
class TestConfidence:
    def test_direct_observation_is_high(self):
        for name in ("Security headers + clickjacking", "TLS certificate",
                     "DNS records", "Port scan", "HSTS preload"):
            assert C.provenance_of(name) == "direct"
            assert C.confidence_of(name, "x MISSING") == "high"

    def test_third_party_claim_is_medium(self):
        for name in ("AbuseIPDB reputation", "Wayback URL harvest",
                     "GitHub leak scan", "Username enumeration at scale"):
            assert C.provenance_of(name) == "third_party"
            assert C.confidence_of(name, "found") == "medium"

    def test_heuristic_is_low(self):
        for name in ("Prototype pollution indicators", "LFI surface",
                     "Path-traversal surface"):
            assert C.provenance_of(name) == "heuristic"
            assert C.confidence_of(name, "x") == "low"

    def test_plural_stem_matches(self):
        # regression: "headers" must match the "header" stem, not fall through
        assert C.provenance_of("Security headers") == "direct"

    def test_text_cue_downgrades(self):
        assert C.confidence_of("Security headers", "possibly missing") == "low"
        assert C.confidence_of("Security headers", "likely absent") == "low"

    def test_explicit_override_wins(self):
        assert C.confidence_of("anything", "x",
                               data={"_confidence": "confirmed"}) == "confirmed"
        assert C.provenance_of("anything", {"_provenance": "direct"}) == "direct"

    def test_score_findings_tags_every_finding(self):
        res = [
            Result("Security headers", "x.com", data={"m": "no CSP (MISSING)"}),
            Result("AbuseIPDB reputation", "1.2.3.4",
                   data={"v": "EXPOSED on feed"}),
            Result("Prototype pollution indicators", "x.com",
                   data={"s": "possible __proto__ EXPOSED"}),
        ]
        scored = score_findings(res)
        assert scored["findings"], "expected some findings"
        for f in scored["findings"]:
            assert f["confidence"] in C.LEVELS
            assert f["provenance"] in ("direct", "third_party", "heuristic")
        assert set(scored["confidence"]["by_confidence"]) == set(C.LEVELS)

    def test_findings_sorted_by_confidence_within_severity(self):
        # two 'high' findings, one direct (high conf) one heuristic (low conf):
        res = [
            Result("Prototype pollution indicators", "x",
                   data={"a": "EXPOSED but possible"}),
            Result("TLS certificate", "x", data={"b": "EXPIRED"}),
        ]
        highs = [f for f in score_findings(res)["findings"]
                 if f["severity"] == "high"]
        if len(highs) >= 2:
            assert C.rank(highs[0]["confidence"]) <= C.rank(highs[-1]["confidence"])


# --------------------------------------------------------------------------- #
#  OPSEC leak-awareness
# --------------------------------------------------------------------------- #
class TestOpsec:
    def test_third_parties_are_identified(self):
        rec = opsec.LeakRecorder(target="example.com")
        for u in ["https://example.com/a", "https://api.example.com/b",
                  "https://ip-api.com/json/1", "https://gravatar.com/x",
                  "https://ip-api.com/json/2"]:
            rec.record(u)
        rep = rec.report()
        thirds = {t["host"] for t in rep["third_parties_contacted"]}
        assert thirds == {"ip-api.com", "gravatar.com"}
        assert "example.com" in rep["target_hosts_contacted"]
        assert "api.example.com" in rep["target_hosts_contacted"]

    def test_request_counts_are_kept(self):
        rec = opsec.LeakRecorder(target="example.com")
        rec.record("https://ip-api.com/1")
        rec.record("https://ip-api.com/2")
        top = rec.report()["third_parties_contacted"][0]
        assert top["host"] == "ip-api.com" and top["requests"] == 2

    def test_subdomain_of_target_is_not_a_leak(self):
        rec = opsec.LeakRecorder(target="example.com")
        assert rec.is_target("https://deep.sub.example.com/x")
        assert not rec.is_target("https://notexample.com/x")

    def test_strict_mode_blocks_third_parties_only(self):
        rec = opsec.LeakRecorder(target="example.com", strict=True)
        assert not rec.should_block("https://example.com/a")
        assert not rec.should_block("https://sub.example.com/a")
        assert rec.should_block("https://ip-api.com/x")

    def test_exposure_levels(self):
        assert "none" in opsec.LeakRecorder("x.com").report()["exposure"]
        rec = opsec.LeakRecorder("x.com")
        for i in range(20):
            rec.record(f"https://svc{i}.com/x")
        assert "high" in rec.report()["exposure"]

    def test_wrap_session_records_and_blocks(self):
        from ghost_eye import workflow

        class _Resp:
            status_code = 200

        class _Sess:
            def __init__(self):
                self.headers = {}

            def request(self, method, url, **kw):
                return _Resp()
        rec = opsec.LeakRecorder(target="example.com", strict=True)
        sess = workflow.wrap_session(_Sess(), recorder=rec)
        sess.request("GET", "https://example.com/a")        # allowed + recorded
        try:
            sess.request("GET", "https://evil.com/a")       # blocked in strict
            blocked_raised = False
        except RuntimeError:
            blocked_raised = True
        assert blocked_raised
        rep = rec.report()
        assert "example.com" in rep["target_hosts_contacted"]
        assert "evil.com" in rep["blocked_in_strict_mode"]

    def test_report_from_results_reconstructs(self):
        res = [Result("m", "example.com",
                      data={"url": "https://ip-api.com/json/1",
                            "profile": "https://github.com/x"})]
        rep = opsec.report_from_results(res, "example.com")
        hosts = {t["host"] for t in rep["third_parties_contacted"]}
        assert "ip-api.com" in hosts and "github.com" in hosts


# --------------------------------------------------------------------------- #
#  source-registry health
# --------------------------------------------------------------------------- #
class TestSourceHealth:
    class _Resp:
        def __init__(self, code, text="", url=""):
            self.status_code = code
            self.text = text
            self.url = url
            self.history = []

    class _Sess:
        def __init__(self, mapper):
            self.mapper = mapper
            self.headers = {}

        def get(self, url, **kw):
            return self.mapper(url)

    class _Cfg:
        def get(self, _k, d=None):
            return d

    def _ctx(self, mapper):
        return Context(config=self._Cfg(), session=self._Sess(mapper),
                       threads=8, timeout=3)

    def test_healthy_when_canary_is_404(self):
        # a well-behaved registry: unknown user => 404 everywhere
        res = REGISTRY["sourcehealth"].run(
            "x", self._ctx(lambda u: self._Resp(404, url=u)))
        assert res.status == "ok"
        assert res.data["healthy"] > 0
        assert res.data["unreliable_count"] == 0

    def test_flags_always_200_sites_as_unreliable(self):
        # a broken environment: 200 for everyone => all sites unreliable
        res = REGISTRY["sourcehealth"].run(
            "x", self._ctx(lambda u: self._Resp(200, url=u)))
        assert res.status == "ok"
        assert res.data["unreliable_count"] == res.data["sites_audited"]
        assert res.data["health_pct"] == 0
