"""Regression tests for defects found by a manual audit of the codebase.

Each class here pins one bug that the existing (green) suite did not catch,
because in every case the code *ran fine* — it just quietly produced less than
it should have. That is the failure mode this project cares about most, so the
fixes get tests rather than trust.
"""

from __future__ import annotations

import json

import pytest

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye import netclass, origin
from ghost_eye.core import REGISTRY, Context
from ghost_eye.modules.emerging import fetch_nuclei_recent
from ghost_eye.registry_data import _as_code, normalise


# --------------------------------------------------------------------------- #
#  Registry loading: one bad row must not cost the whole file
# --------------------------------------------------------------------------- #
class TestRegistryRobustness:
    """``int(row.get("m_code", 404))`` raised on a null/str code, and because
    normalisation ran over the whole document, a single malformed row threw the
    *entire* registry away. A 3000-site sweep then reported "registry is empty"
    with no error anywhere."""

    @pytest.mark.parametrize("raw,expected", [
        (None, 404), ("", 404), ("nope", 404), ([], 404),
        (200, 200), ("301", 301), ([302], 302), (404.0, 404),
    ])
    def test_codes_survive_community_data(self, raw, expected):
        assert _as_code(raw, 404) == expected

    def test_one_broken_whatsmyname_row_costs_only_that_row(self):
        doc = {"sites": [
            {"name": "broken", "uri_check": "https://a/{account}",
             "m_code": None, "e_code": "not-a-number"},
            {"name": "fine", "uri_check": "https://b/{account}", "e_code": 200},
        ]}
        sites = normalise(doc)
        assert [s.name for s in sites] == ["broken", "fine"]
        assert sites[0].absent_code == 404      # fell back, did not explode

    def test_one_broken_sherlock_row_costs_only_that_row(self):
        doc = {"Broken": {"url": "https://a/{}", "errorCode": None},
               "Fine": {"url": "https://b/{}", "errorCode": 404}}
        assert len(normalise(doc)) == 2

    def test_whatsmyname_carries_no_regex(self):
        """`regex=s.get("strip_bad_char") and None` was always-None nonsense
        dressed up as a lookup; it must be an explicit None."""
        sites = normalise({"sites": [{"name": "x", "uri_check": "https://x/{account}",
                                      "strip_bad_char": "-"}]})
        assert sites[0].regex is None
        assert sites[0].username_ok("any.name-here")


# --------------------------------------------------------------------------- #
#  username_max = 0 means "no cap", in both directions
# --------------------------------------------------------------------------- #
class TestSourceHealthCap:
    """`usernamescan` documents 0 as "no cap" and honours it; `sourcehealth`
    sliced `sites[:0]` and audited nothing at all."""

    class _Cfg:
        def __init__(self, cap):
            self.cap = cap

        def get(self, key, default=None):
            return self.cap if key == "username_max" else default

    class _Sess:
        headers: dict = {}

        def get(self, url, **kw):
            class _R:
                status_code, text, headers, history, url = 404, "", {}, [], url
            return _R()

    def _audited(self, cap):
        ctx = Context(config=self._Cfg(cap), session=self._Sess(), timeout=1)
        return REGISTRY["sourcehealth"].run("registry", ctx).data["sites_audited"]

    def test_zero_means_every_site_not_no_sites(self):
        assert self._audited(0) > 100

    def test_a_real_cap_still_caps(self):
        assert self._audited(5) == 5


# --------------------------------------------------------------------------- #
#  Live range refresh covers more than Cloudflare
# --------------------------------------------------------------------------- #
class TestJsonRangeRefresh:
    """`refresh_ranges()` promised "the providers' own published endpoints" but
    only ever refreshed Cloudflare, because the other CDNs publish JSON rather
    than a newline list."""

    class _Sess:
        def get(self, url, **kw):
            if "fastly" in url:
                payload = {"addresses": [f"102.44.{i}.0/24" for i in range(9)],
                           "ipv6_addresses": ["2001:db8:f::/48"]}
            elif "cloudfront" in url:
                payload = {"CLOUDFRONT_GLOBAL_IP_LIST":
                           [f"102.45.{i}.0/24" for i in range(7)],
                           "CLOUDFRONT_REGIONAL_EDGE_IP_LIST": []}
            else:
                payload = {}

            class _R:
                status_code = 200
                text = ""

                @staticmethod
                def json():
                    return payload
            return _R()

    def test_json_providers_are_refreshed(self):
        before = {k: list(v) for k, v in netclass.CDN_RANGES.items()}
        try:
            updated = netclass.refresh_ranges(session=self._Sess())
            assert updated.get("Fastly", 0) == 10
            assert updated.get("CloudFront", 0) == 7
            assert netclass.classify_ip("102.44.3.9") == {
                "ip": "102.44.3.9", "kind": "cdn", "provider": "Fastly"}
        finally:
            netclass.CDN_RANGES.clear()
            netclass.CDN_RANGES.update(before)
            netclass._CDN_NETS = netclass._compile(netclass.CDN_RANGES)

    def test_a_thin_json_response_is_rejected(self):
        """Same partial-read guard the text sources already had: never replace
        a full bundled range with two entries from a truncated response."""
        class _Thin:
            def get(self, url, **kw):
                class _R:
                    status_code = 200

                    @staticmethod
                    def json():
                        return {"addresses": ["102.44.0.0/24"]}
                return _R()
        netclass.refresh_ranges(session=_Thin())
        assert netclass.is_cdn("151.101.1.69"), "bundled Fastly range was lost"


# --------------------------------------------------------------------------- #
#  Nuclei templates: the third source the docstring always claimed
# --------------------------------------------------------------------------- #
class TestNucleiSource:
    class _Ctx:
        timeout = 5

        def __init__(self, rows, code=200):
            outer = self

            class _S:
                def get(self, url, **kw):
                    class _R:
                        status_code = code

                        @staticmethod
                        def json():
                            return outer.rows
                    return _R()
            self.rows = rows
            self.session = _S()

    @staticmethod
    def _commit(msg, days_ago):
        from datetime import datetime, timedelta, timezone
        when = (datetime.now(timezone.utc) - timedelta(days=days_ago)).isoformat()
        return {"commit": {"message": msg, "author": {"date": when}},
                "html_url": "https://github.com/x"}

    def test_only_cve_bearing_commits_within_the_window(self):
        rows = [self._commit("Create CVE-2025-40001.yaml", 2),
                self._commit("chore: fix lint", 1),
                self._commit("Add CVE-2019-1111 template", 400)]
        out = fetch_nuclei_recent(self._Ctx(rows), 21)
        assert [t["cve"] for t in out] == ["CVE-2025-40001"]

    def test_a_cve_is_reported_once(self):
        rows = [self._commit("CVE-2025-9999 first", 1),
                self._commit("CVE-2025-9999 follow-up", 1)]
        assert len(fetch_nuclei_recent(self._Ctx(rows), 21)) == 1

    def test_source_failure_is_reported_not_raised(self):
        out = fetch_nuclei_recent(self._Ctx([], code=403), 21)
        assert out and "_error" in out[0]


class TestExploitedAndDetectable:
    """A CVE carrying *both* a KEV listing and a fresh detection template is
    the sharpest signal this module can emit; it had nowhere to appear."""

    class _Sess:
        headers: dict = {}

        def __init__(self, kev, nuclei):
            self._kev, self._nuclei = kev, nuclei

        def get(self, url, **kw):
            payload, text = None, ""
            if "advisories" in url:
                payload = []
            elif "known_exploited" in url:
                payload = {"vulnerabilities": self._kev}
            elif "commits" in url:
                payload = self._nuclei
            else:
                text = "<html></html>"

            class _R:
                status_code = 200
                headers = {"Server": "nginx/1.18.0"}

                @staticmethod
                def json():
                    return payload
            _R.text = text
            return _R()

    class _Cfg:
        def get(self, _k, d=None):
            return d

    def test_kev_and_template_correlate(self):
        from datetime import datetime, timezone
        now = datetime.now(timezone.utc).isoformat()
        kev = [{"cveID": "CVE-2025-5555", "vendorProject": "Nginx",
                "product": "nginx", "vulnerabilityName": "nginx rce",
                "dateAdded": now}]
        nuclei = [{"commit": {"message": "Add CVE-2025-5555 nginx detection",
                              "author": {"date": now}}, "html_url": "u"}]
        ctx = Context(config=self._Cfg(), session=self._Sess(kev, nuclei), timeout=5)
        data = REGISTRY["freshvulns"].run("acme.com", ctx).data
        assert data["exploited_and_detectable"] == ["CVE-2025-5555"]
        assert data["new_template_count"] == 1


# --------------------------------------------------------------------------- #
#  Severity ratings that were computed and then dropped on the floor
# --------------------------------------------------------------------------- #
class TestSeverityIsReported:
    """Four modules worked out a CRITICAL/HIGH rating and returned without it.
    The scan looked complete; the severity simply never reached the operator."""

    class _Cfg:
        def get(self, _k, d=None):
            return d

    def _ctx(self, session):
        return Context(config=self._Cfg(), session=session, timeout=1)

    class _Dead:
        headers: dict = {}

        def get(self, url, **kw):
            raise OSError("down")

        options = post = get

    @pytest.mark.parametrize("mid", ["oauthaudit", "jwtaudit", "hfrecon", "depconfuse"])
    def test_every_audited_module_reports_its_rating(self, mid):
        mod = REGISTRY.get(mid)
        assert mod is not None, f"{mid} disappeared from the registry"
        data = mod.run("https://example.com", self._ctx(self._Dead())).data
        assert data.get("risk"), f"{mid} dropped its severity rating"


# --------------------------------------------------------------------------- #
#  Origin verification keeps the reason a candidate failed
# --------------------------------------------------------------------------- #
class TestOriginErrorsSurvive:
    """`best` was replaced wholesale by a later scheme's score, taking the
    earlier scheme's error text with it — so "https refused the connection"
    vanished from a rejection that was entirely caused by it."""

    def test_https_failure_is_still_reported_after_http_answers(self):
        class _S:
            def get(self, url, **kw):
                if url.startswith("https://"):
                    raise OSError("connection refused")

                class _R:
                    status_code, text, headers = 200, "<html>other site</html>", {}
                return _R()
        baseline = origin.fingerprint(
            type("R", (), {"text": "<html><title>real</title>real page</html>",
                           "headers": {}, "status_code": 200})())
        out = origin.verify_candidate(_S(), "203.0.113.9", "example.com", baseline)
        assert out["scheme"] == "http"
        assert any("connection refused" in e for e in out.get("errors", []))


# --------------------------------------------------------------------------- #
#  Registry data file itself
# --------------------------------------------------------------------------- #
def test_bundled_username_registry_is_valid_json_and_complete():
    from ghost_eye.registry_data import _DATA_DIR, load_sites
    doc = json.loads((_DATA_DIR / "username_sites.json").read_text(encoding="utf-8"))
    raw = doc["registry"] if isinstance(doc, dict) else doc
    sites = load_sites("username")
    signatures = [(r.get("name", "").lower(), r.get("url")) for r in raw]
    assert len(set(signatures)) == len(signatures), "the shipped registry has a duplicate row"
    assert len(sites) == len(raw), "a bundled row failed to normalise"
    for s in sites:
        assert "{u}" in s.url or "{}" in s.url or "{account}" in s.url
        assert s.build("someone") != s.url
