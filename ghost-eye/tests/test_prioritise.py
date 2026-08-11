"""Tests for the fix-order engine.

The whole claim of this module is that its ranking beats sorting by CVSS. These
tests make that claim falsifiable — and pin the honesty property that keeps it
from becoming dangerous: an unobserved service is *unknown*, never *safe*.
"""

from __future__ import annotations

import pytest

from ghost_eye.core import Result
from ghost_eye.prioritise import (REACH_CONFIRMED, REACH_FRONTED, REACH_PRIVATE,
                                  REACH_UNOBSERVED, exploitation_pressure,
                                  extract_cve_context, fetch_epss,
                                  host_reachability, prioritise)


def _r(module, target, data):
    res = Result(module=module, target=target)
    res.data = data
    return res


def _live_host(target="a.example", cve="CVE-2021-44228"):
    return [_r("headers", target, {"status_code": "200", "server": "nginx"}),
            _r("cve", target, {"findings": f"{cve} in log4j"})]


# --------------------------------------------------------------------------- #
class TestContextExtraction:
    def test_a_cve_carries_where_it_was_seen(self):
        ctx = extract_cve_context(_live_host())
        entry = ctx["CVE-2021-44228"]
        assert entry["hosts"] == ["a.example"]
        assert "cve" in entry["modules"]
        assert entry["evidence"] and "log4j" in entry["evidence"][0]

    def test_the_same_cve_on_two_hosts_is_one_entry(self):
        results = _live_host("a.example") + _live_host("b.example")
        ctx = extract_cve_context(results)
        assert sorted(ctx["CVE-2021-44228"]["hosts"]) == ["a.example", "b.example"]

    def test_case_is_normalised(self):
        ctx = extract_cve_context([_r("m", "a.com", {"x": "cve-2024-1234"})])
        assert "CVE-2024-1234" in ctx

    def test_no_cves_is_an_empty_map_not_an_error(self):
        assert extract_cve_context([_r("m", "a.com", {"x": "nothing here"})]) == {}


class TestReachability:
    def test_a_live_answer_is_confirmed_exposure(self):
        rep = host_reachability(_live_host(), "a.example")
        assert rep["reachability"] == REACH_CONFIRMED
        assert rep["live_response"] is True

    def test_a_cdn_fronted_host_is_reachable_but_filtered(self):
        results = [_r("cdnfilter", "a.example",
                      {"status_code": "200", "behind_cdn": "True"})]
        assert host_reachability(results, "a.example")["reachability"] == REACH_FRONTED

    def test_a_cdn_range_ip_also_counts_as_fronted(self):
        results = [_r("dns", "a.example", {"status_code": "200",
                                           "ip": "104.16.132.229"})]
        assert host_reachability(results, "a.example")["reachability"] == REACH_FRONTED

    def test_a_private_only_host_is_not_reachable_from_outside(self):
        results = [_r("dns", "a.example", {"ip": "10.0.0.5"})]
        assert host_reachability(results, "a.example")["reachability"] == REACH_PRIVATE

    def test_no_evidence_is_unobserved_not_safe(self):
        """The property that keeps this module from being dangerous."""
        results = [_r("whois", "a.example", {"registrar": "Example Registrar"})]
        rep = host_reachability(results, "a.example")
        assert rep["reachability"] == REACH_UNOBSERVED
        assert rep["weight"] > 0, "an unobserved service was scored as harmless"

    def test_open_ports_count_as_exposure(self):
        results = [_r("portscan", "a.example", {"open": "22; 443"})]
        rep = host_reachability(results, "a.example")
        assert rep["reachability"] == REACH_CONFIRMED
        assert "443" in rep["open_ports"]


class TestExploitationPressure:
    def test_kev_outranks_everything(self):
        assert exploitation_pressure({"known_exploited": True})["pressure"] == 1.0

    def test_weaponised_outranks_a_bare_poc(self):
        w = exploitation_pressure({"weaponised": True})["pressure"]
        p = exploitation_pressure({"exploit_available": True})["pressure"]
        assert w > p

    def test_a_high_epss_raises_a_quiet_cve(self):
        quiet = exploitation_pressure({})["pressure"]
        forecast = exploitation_pressure({"epss": 0.92})
        assert forecast["pressure"] > quiet
        assert "EPSS forecasts" in forecast["why"]

    def test_a_low_epss_never_lowers_a_kev_cve(self):
        """'Not predicted' is not evidence against something already in use."""
        out = exploitation_pressure({"known_exploited": True, "epss": 0.01})
        assert out["pressure"] == 1.0

    def test_no_data_is_not_zero(self):
        assert exploitation_pressure({})["pressure"] > 0


class TestRanking:
    def test_a_reachable_exploited_medium_beats_an_unreachable_critical(self):
        """The claim that justifies this module existing."""
        results = [
            _r("headers", "live.example", {"status_code": "200"}),
            _r("cve", "live.example", {"f": "CVE-2021-44228 log4shell"}),
            _r("dns", "internal.example", {"ip": "10.0.0.9"}),
            _r("cve", "internal.example", {"f": "CVE-2099-9999 theoretical"}),
        ]
        facts = {
            "CVE-2021-44228": {"known_exploited": True, "cvss": 5.0},
            "CVE-2099-9999": {"cvss": 10.0},
        }
        out = prioritise(results, facts_by_cve=facts)
        assert out["fix_order"][0]["cve"] == "CVE-2021-44228"
        assert out["fix_order"][0]["cvss"] == 5.0

    def test_act_now_is_exploited_and_reachable_only(self):
        results = [
            _r("headers", "live.example", {"status_code": "200"}),
            _r("cve", "live.example", {"f": "CVE-2021-44228"}),
            _r("dns", "internal.example", {"ip": "10.0.0.9"}),
            _r("cve", "internal.example", {"f": "CVE-2017-0144"}),
        ]
        facts = {"CVE-2021-44228": {"known_exploited": True},
                 "CVE-2017-0144": {"known_exploited": True}}
        out = prioritise(results, facts_by_cve=facts)
        assert [a["cve"] for a in out["act_now"]] == ["CVE-2021-44228"]
        assert out["act_now_count"] == 1

    def test_unobserved_exposure_is_counted_and_reported(self):
        results = [_r("whois", "a.example", {"registrar": "R"}),
                   _r("cve", "a.example", {"f": "CVE-2024-0001"})]
        out = prioritise(results, facts_by_cve={"CVE-2024-0001": {"cvss": 9.8}})
        assert out["unobserved_exposure"] == 1
        assert out["fix_order"][0]["reachability"] == REACH_UNOBSERVED
        assert "never asserts safety" in out["note"]

    def test_every_ranked_item_explains_itself(self):
        out = prioritise(_live_host(),
                         facts_by_cve={"CVE-2021-44228": {"known_exploited": True}})
        top = out["fix_order"][0]
        assert top["exploitation"] and top["reachability"] and top["evidence"]

    def test_a_scan_with_no_cves_ranks_nothing(self):
        out = prioritise([_r("m", "a.com", {"x": "clean"})])
        assert out["cves_found"] == 0 and out["fix_order"] == []


class TestEpssBatching:
    """240 CVEs used to be 240 requests; FIRST accepts a comma-separated list."""

    class _Sess:
        def __init__(self):
            self.calls = []

        def get(self, url, params=None, **kw):
            self.calls.append((params or {}).get("cve", ""))
            cves = ((params or {}).get("cve") or "").split(",")

            class _R:
                status_code = 200

                @staticmethod
                def json():
                    return {"data": [{"cve": c, "epss": "0.5",
                                      "percentile": "0.9"} for c in cves if c]}
            return _R()

    def test_many_cves_are_fetched_in_one_request(self):
        sess = self._Sess()
        ids = [f"CVE-2024-{i:04d}" for i in range(50)]
        out = fetch_epss(ids, sess)
        assert len(sess.calls) == 1
        assert len(out) == 50 and out["CVE-2024-0000"]["epss"] == 0.5

    def test_batches_are_chunked_at_the_limit(self):
        sess = self._Sess()
        fetch_epss([f"CVE-2024-{i:04d}" for i in range(250)], sess, batch=100)
        assert len(sess.calls) == 3

    def test_duplicates_are_requested_once(self):
        sess = self._Sess()
        fetch_epss(["CVE-2024-0001", "CVE-2024-0001"], sess)
        assert sess.calls[0] == "CVE-2024-0001"

    def test_a_dead_source_does_not_stop_ranking(self):
        class _Dead:
            def get(self, *a, **kw):
                raise OSError("down")
        assert fetch_epss(["CVE-2024-0001"], _Dead()) == {}

    def test_ranking_falls_back_to_epss_when_facts_are_missing(self):
        sess = self._Sess()
        out = prioritise(_live_host(cve="CVE-2024-0001"), session=sess)
        assert out["fix_order"][0]["epss"] == 0.5

    @pytest.mark.parametrize("bad", [None, "", "not-a-number"])
    def test_unparseable_scores_do_not_crash(self, bad):
        assert exploitation_pressure({"epss": bad})["epss"] == 0.0
