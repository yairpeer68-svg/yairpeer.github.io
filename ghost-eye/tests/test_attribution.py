"""Tests for the infrastructure attribution engine.

An attribution engine is judged on what it *refuses* to claim. Most of these
tests are adversarial: shared hosting, shared CDN, shared CA — all the things
that make naive tools announce that half the internet is one operator.
"""

from __future__ import annotations

import pytest

from ghost_eye.core import Result
from ghost_eye.intelligence import attribute, extract_fingerprints
from ghost_eye.intelligence.attribution import (PRIOR, _fuse, _idf, _is_common,
                                                evidence_weight)


def _cdn_noise(hosts):
    """Every host behind the same CDN, nameservers and ASN — pure noise."""
    out = []
    for h in hosts:
        out.append(Result("DNS records", h,
                          data={"nameservers": "ns1.cloudflare.com,ns2.cloudflare.com"}))
        out.append(Result("GeoIP + ASN enrichment", h, data={"asn": "13335"}))
    return out


# --------------------------------------------------------------------------- #
#  the maths
# --------------------------------------------------------------------------- #
class TestSelectivityMaths:
    def test_universal_value_carries_no_information(self):
        """Present on every host -> zero, once the corpus is big enough for
        'every host' to actually mean something (see _idf's docstring)."""
        for n in (10, 50, 100):
            assert _idf(n, n) == 0.0
            assert evidence_weight("cert_serial", "abc", n, n) == 0.0

    def test_two_host_comparison_still_works(self):
        """With N=2 everything shared is trivially 'universal'; zeroing it
        would break the commonest question — are these two hosts related?"""
        assert _idf(2, 2) > 0.5
        assert evidence_weight("cert_serial", "0af3e19b77c2", 2, 2) > 0.5

    def test_unique_value_is_maximally_selective(self):
        assert _idf(1, 100) == 1.0

    def test_rarer_values_score_higher(self):
        assert _idf(2, 100) > _idf(50, 100) > _idf(95, 100)

    def test_small_corpus_is_not_over_penalised(self):
        """2-of-4 does not make a Google Analytics property 'common in the
        world' — frequency over a tiny corpus is a bad rarity estimate."""
        assert _idf(2, 4) > _idf(2, 10)
        assert _idf(2, 4) > 0.8

    def test_evidence_type_ordering_is_respected(self):
        w = lambda k: evidence_weight(k, "v", 2, 10)  # noqa: E731
        assert w("cert_serial") > w("ga_id") > w("favicon_hash") > w("ns_set") > w("asn")

    def test_weak_evidence_alone_cannot_link(self):
        # a shared ASN must never on its own reach the default 0.75 threshold
        assert _fuse([evidence_weight("asn", "13335", 2, 10)]) < 0.75

    def test_several_weak_signals_can_accumulate(self):
        assert _fuse([0.3, 0.3, 0.3]) > 0.3

    def test_fusion_is_bounded(self):
        assert 0.0 <= _fuse([0.99, 0.99, 0.99]) <= 1.0
        assert _fuse([]) == 0.0

    def test_every_prior_is_a_probability(self):
        assert all(0.0 < v <= 1.0 for v in PRIOR.values())


class TestCommonValueDetection:
    def test_shared_infrastructure_is_recognised(self):
        for v in ("ns1.cloudflare.com", "aspmx.l.google.com", "lets encrypt"):
            assert _is_common(v)

    def test_hex_serial_is_not_mistaken_for_a_ca_label(self):
        """Regression: substring matching made the CA label 'e1' match inside
        the serial 0af3e19b77c2, discarding the strongest evidence there is."""
        assert not _is_common("0af3e19b77c2")
        assert not _is_common("99aa88bb77cc")
        assert _is_common("e1")           # the actual CA label still matches

    def test_customer_infrastructure_is_not_common(self):
        assert not _is_common("ns1.acme-internal.net")


# --------------------------------------------------------------------------- #
#  extraction
# --------------------------------------------------------------------------- #
class TestExtraction:
    def test_pulls_identifiers_from_any_module_shape(self):
        res = [
            Result("Technology stack", "a.com", data={"analytics": "UA-1234567-1"}),
            Result("Whatever", "a.com", data={"tags": {"nested": "GTM-ABCD123"}}),
            Result("TLS certificate", "a.com", data={"serial": "0AF3E19B77C2"}),
        ]
        fps = extract_fingerprints(res)
        assert fps["a.com"]["ga_id"] == {"ua-1234567-1"}
        assert fps["a.com"]["gtm_id"] == {"gtm-abcd123"}
        assert fps["a.com"]["cert_serial"] == {"0af3e19b77c2"}

    def test_nameserver_sets_compare_as_a_set(self):
        res = [Result("DNS", "a.com", data={"nameservers": "ns2.x.com,ns1.x.com"}),
               Result("DNS", "b.com", data={"nameservers": "ns1.x.com,ns2.x.com"})]
        fps = extract_fingerprints(res)
        assert fps["a.com"]["ns_set"] == fps["b.com"]["ns_set"]

    def test_ignores_placeholder_values(self):
        res = [Result("m", "a.com", data={"serial": "none", "asn": "unknown"})]
        assert extract_fingerprints(res).get("a.com", {}) == {}


# --------------------------------------------------------------------------- #
#  attribution behaviour — mostly what it must NOT claim
# --------------------------------------------------------------------------- #
class TestAttribution:
    def test_shared_cdn_alone_creates_no_estate(self):
        """The classic false positive: everything behind Cloudflare is not one
        operator."""
        rep = attribute(_cdn_noise(["a.com", "b.com", "c.com", "d.com"]), "a.com")
        assert rep["estate_count"] == 0
        assert all(ln["confidence"] < 0.75 for ln in rep["links"])

    def test_strong_shared_identity_links_hosts(self):
        res = _cdn_noise(["a.com", "b.com", "c.com", "d.com"])
        for h in ("a.com", "b.com"):
            res.append(Result("Technology stack", h, data={"analytics": "UA-1234567-1"}))
            res.append(Result("TLS certificate", h, data={"serial": "0AF3E19B77C2"}))
        rep = attribute(res, "a.com")
        assert rep["estate_count"] == 1
        estate = rep["estates"][0]
        assert set(estate["members"]) == {"a.com", "b.com"}
        assert estate["confidence"] >= 0.9
        assert "cert_serial" in estate["driving_evidence"]

    def test_unrelated_hosts_stay_out_of_the_estate(self):
        res = _cdn_noise(["a.com", "b.com", "c.com", "d.com"])
        for h in ("a.com", "b.com"):
            res.append(Result("Tech", h, data={"analytics": "UA-1234567-1"}))
        rep = attribute(res, "a.com")
        members = {m for e in rep["estates"] for m in e["members"]}
        assert "c.com" not in members and "d.com" not in members

    def test_every_link_is_explainable(self):
        res = _cdn_noise(["a.com", "b.com"])
        for h in ("a.com", "b.com"):
            res.append(Result("Tech", h, data={"analytics": "UA-9876543-1"}))
        rep = attribute(res, "a.com")
        for link in rep["links"]:
            for ev in link["evidence"]:
                assert {"type", "value", "weight", "seen_on_hosts"} <= set(ev)

    def test_threshold_is_honoured(self):
        res = _cdn_noise(["a.com", "b.com"])
        for h in ("a.com", "b.com"):
            res.append(Result("Net", h, data={"asn": "64500"}))
        strict = attribute(res, "a.com", threshold=0.99)
        loose = attribute(res, "a.com", threshold=0.01)
        assert strict["estate_count"] <= loose["estate_count"]

    def test_needs_at_least_two_hosts(self):
        rep = attribute([Result("m", "a.com", data={"serial": "abc"})], "a.com")
        assert rep["estates"] == [] and "at least two hosts" in rep["note"]

    def test_empty_input_is_safe(self):
        rep = attribute([], "")
        assert rep["estates"] == [] and rep["hosts_analysed"] == 0

    def test_transitive_clustering(self):
        """a~b and b~c should put all three in one estate."""
        res = _cdn_noise(["a.com", "b.com", "c.com"])
        res += [Result("T", "a.com", data={"serial": "AAA111"}),
                Result("T", "b.com", data={"serial": "AAA111"}),
                Result("U", "b.com", data={"analytics": "UA-7654321-1"}),
                Result("U", "c.com", data={"analytics": "UA-7654321-1"})]
        rep = attribute(res, "a.com")
        assert rep["estate_count"] == 1
        assert set(rep["estates"][0]["members"]) == {"a.com", "b.com", "c.com"}

    def test_report_shape(self):
        rep = attribute(_cdn_noise(["a.com", "b.com"]), "a.com")
        for key in ("target", "hosts_analysed", "threshold", "estates",
                    "links", "fingerprint_types", "note"):
            assert key in rep


class TestWorkflowIntegration:
    def test_workflow_entry_point(self):
        from ghost_eye import workflow
        res = _cdn_noise(["a.com", "b.com"])
        for h in ("a.com", "b.com"):
            res.append(Result("T", h, data={"serial": "BBB222"}))
        rep = workflow.attribution_report(res, "a.com")
        assert rep["estate_count"] == 1
