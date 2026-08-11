"""Tests for CSP-driven asset discovery.

Two things make this worth having rather than a header-grader: it keeps what
each directive *means* about a host, and it gets registrable-domain arithmetic
right. The second is not academic — the older `cspdomains` module used
``host.split(".")[-2:]``, which reduces ``shop.example.co.uk`` to ``co.uk`` and
therefore files every unrelated ``.co.uk`` host in the policy as the target's
own infrastructure. That bug is pinned below.
"""

from __future__ import annotations

import pytest

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye.core import REGISTRY, Context
from ghost_eye.cspmap import (analyse, collect_policies, csp_asset_map,
                              parse_csp, registrable_domain, source_host)


# --------------------------------------------------------------------------- #
class TestRegistrableDomain:
    @pytest.mark.parametrize("host,expected", [
        ("example.com", "example.com"),
        ("www.example.com", "example.com"),
        ("a.b.c.example.com", "example.com"),
        # the bug: a naive [-2:] gives "co.uk" and relates the whole registry
        ("shop.example.co.uk", "example.co.uk"),
        ("example.co.uk", "example.co.uk"),
        ("deep.a.example.org.il", "example.org.il"),
        ("api.example.com.au", "example.com.au"),
        # a vendor-owned suffix must not make two tenants look related
        ("alice.github.io", "alice.github.io"),
        ("bob.github.io", "bob.github.io"),
        ("localhost", "localhost"),
        ("", ""),
    ])
    def test_apex(self, host, expected):
        assert registrable_domain(host) == expected

    def test_two_tenants_of_one_platform_are_not_the_same_apex(self):
        assert registrable_domain("alice.github.io") != \
            registrable_domain("bob.github.io")


class TestParsing:
    def test_directives_and_sources(self):
        parsed = parse_csp("default-src 'self'; script-src 'self' cdn.example.com")
        assert parsed["default-src"] == ["'self'"]
        assert parsed["script-src"] == ["'self'", "cdn.example.com"]

    def test_a_repeated_directive_keeps_the_union(self):
        parsed = parse_csp("img-src a.com; img-src b.com")
        assert parsed["img-src"] == ["a.com", "b.com"]

    def test_a_valueless_directive_is_still_recorded(self):
        assert parse_csp("upgrade-insecure-requests") == \
            {"upgrade-insecure-requests": []}

    @pytest.mark.parametrize("source,expected", [
        ("https://cdn.example.com", "cdn.example.com"),
        ("cdn.example.com:8443", "cdn.example.com"),
        ("https://cdn.example.com/path/x.js", "cdn.example.com"),
        ("*.example.com", "*.example.com"),
        ("wss://ws.example.com", "ws.example.com"),
        ("'self'", ""),
        ("'unsafe-inline'", ""),
        ("'nonce-abc123'", ""),
        ("'sha256-abc123='", ""),
        ("data:", ""),
        ("https:", ""),
        ("*", ""),
        ("", ""),
    ])
    def test_source_host(self, source, expected):
        assert source_host(source) == expected


class TestAnalysis:
    POLICY = ("default-src 'self'; "
              "script-src 'self' cdn.example.com 'unsafe-inline'; "
              "connect-src api.example.com metrics.vendor.io; "
              "form-action 'self' checkout.example.com; "
              "frame-ancestors partner.co.uk; "
              "img-src * data:; "
              "report-uri https://csp-collector.internal.example.com/report")

    def _report(self, **kw):
        return analyse("www.example.com", {"enforced": self.POLICY}, **kw)

    def test_own_infrastructure_is_separated_from_third_parties(self):
        rep = self._report()
        assert "api.example.com" in rep["own_infrastructure"]
        assert "cdn.example.com" in rep["own_infrastructure"]
        assert "metrics.vendor.io" in rep["third_parties"]
        assert "partner.co.uk" in rep["third_parties"]

    def test_each_host_keeps_the_directive_that_gives_it_meaning(self):
        rep = self._report()
        assert rep["hosts_by_directive"]["connect-src"] == \
            ["api.example.com", "metrics.vendor.io"]
        assert rep["hosts_by_directive"]["form-action"] == ["checkout.example.com"]
        assert "an API" in rep["directive_meaning"]["connect-src"]

    def test_report_endpoints_are_surfaced(self):
        """Often an internal hostname that appears nowhere else."""
        rep = self._report()
        assert any("internal.example.com" in e for e in rep["report_endpoints"])

    def test_weaknesses_are_reported_with_a_reason(self):
        rep = self._report()
        sources = {w["source"] for w in rep["weaknesses"]}
        assert "'unsafe-inline'" in sources and "*" in sources and "data:" in sources
        assert all(w["why"] for w in rep["weaknesses"])

    def test_the_delta_against_known_hosts_is_the_finding(self):
        rep = self._report(known_hosts=["www.example.com", "api.example.com"])
        assert "cdn.example.com" in rep["new_hosts_not_otherwise_found"]
        assert "api.example.com" not in rep["new_hosts_not_otherwise_found"]

    def test_wildcards_are_not_offered_as_discovered_hosts(self):
        rep = analyse("www.example.com",
                      {"enforced": "script-src *.example.com"})
        assert rep["new_hosts_not_otherwise_found"] == "none"
        assert "*.example.com" in rep["own_infrastructure"]

    def test_a_co_uk_third_party_is_not_claimed_as_own(self):
        """The bug this module had to get right."""
        rep = analyse("shop.example.co.uk",
                      {"enforced": "script-src cdn.unrelated.co.uk "
                                   "assets.example.co.uk"})
        assert "assets.example.co.uk" in rep["own_infrastructure"]
        assert "cdn.unrelated.co.uk" in rep["third_parties"]


class TestRelatedDomains:
    def test_a_differently_registered_domain_carrying_the_brand_is_related(self):
        rep = analyse("www.example.com", {"enforced": "img-src examplecdn.net"})
        assert "examplecdn.net" in rep["probably_related"]

    def test_an_unrelated_vendor_is_not_related(self):
        rep = analyse("www.example.com",
                      {"enforced": "font-src fonts.googleapis.com"})
        assert "fonts.googleapis.com" in rep["third_parties"]

    def test_a_short_brand_does_not_relate_half_the_internet(self):
        rep = analyse("www.abc.com", {"enforced": "img-src abcdefg-unrelated.net"})
        assert "abcdefg-unrelated.net" in rep["third_parties"]


class TestReportOnly:
    def test_report_only_reveals_staged_infrastructure(self):
        """Sites stage the next policy there, so it names hosts not yet live."""
        rep = analyse("www.example.com", {
            "enforced": "script-src cdn.example.com",
            "report_only": "script-src cdn.example.com next-gen.example.com"})
        assert rep["staged_in_report_only"] == ["next-gen.example.com"]
        assert "next-gen.example.com" in rep["own_infrastructure"]

    def test_a_weakness_staged_but_not_enforced_is_not_reported_as_live(self):
        rep = analyse("www.example.com", {
            "enforced": "script-src 'self'",
            "report_only": "script-src 'unsafe-inline'"})
        assert rep["weaknesses"] == "none"


class TestCollection:
    class _Sess:
        def __init__(self, headers, text=""):
            self._headers, self._text = headers, text

        def get(self, url, **kw):
            class _R:
                headers = self._headers
                text = self._text
                status_code = 200
            _R.headers, _R.text = self._headers, self._text
            return _R()

    def test_header_case_does_not_matter(self):
        sess = self._Sess({"content-security-policy": "script-src a.example.com"})
        assert collect_policies(sess, "example.com")["enforced"] == \
            "script-src a.example.com"

    def test_report_only_is_collected_too(self):
        sess = self._Sess({"Content-Security-Policy-Report-Only": "img-src b.com"})
        assert collect_policies(sess, "example.com")["report_only"] == "img-src b.com"

    def test_a_meta_tag_policy_is_found_when_there_is_no_header(self):
        html = ('<meta http-equiv="Content-Security-Policy" '
                'content="script-src meta.example.com">')
        pol = collect_policies(self._Sess({}, html), "example.com")
        assert pol["meta"] == "script-src meta.example.com"

    def test_a_dead_host_reports_the_error_rather_than_raising(self):
        class _Dead:
            def get(self, *a, **kw):
                raise OSError("refused")
        pol = collect_policies(_Dead(), "example.com")
        assert pol["enforced"] == "" and pol["errors"]

    def test_csp_asset_map_is_one_call(self):
        sess = self._Sess({"Content-Security-Policy": "connect-src api.example.com"})
        rep = csp_asset_map(sess, "example.com")
        assert rep["own_infrastructure"] == ["api.example.com"]


class TestModule:
    class _Cfg:
        def get(self, _k, d=None):
            return d

    def _ctx(self, headers, text=""):
        outer_headers, outer_text = headers, text

        class _S:
            def get(self, url, **kw):
                class _R:
                    headers = outer_headers
                    text = outer_text
                    status_code = 200
                return _R()
        return Context(config=self._Cfg(), session=_S(), timeout=5)

    def test_module_reports_the_map(self):
        ctx = self._ctx({"Content-Security-Policy":
                         "connect-src api.example.com; script-src 'unsafe-eval'"})
        data = REGISTRY["cspassets"].run("example.com", ctx).data
        assert data["csp_present"] is True
        assert "api.example.com" in data["own_infrastructure"]

    def test_no_csp_is_said_plainly_not_as_an_empty_map(self):
        data = REGISTRY["cspassets"].run("example.com", self._ctx({})).data
        assert data["csp_present"] is False
        assert "no Content-Security-Policy" in data["note"]

    def test_declares_a_health_expect(self):
        assert REGISTRY["cspassets"].expect == ["csp_present"]
