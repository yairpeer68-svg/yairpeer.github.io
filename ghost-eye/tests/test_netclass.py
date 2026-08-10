"""Tests for CDN/WAF edge filtering.

The point of this layer is subtraction: most addresses a scan returns are
shared edge nodes, not the target's servers. These tests pin that the
classifier knows the difference, that the difference propagates into the asset
inventory and the attribution engine, and that a stale/failed range refresh can
never silently wipe the bundled ranges.
"""

from __future__ import annotations

import pytest

from ghost_eye import netclass
from ghost_eye.core import Result
from ghost_eye.inventory import build_inventory
from ghost_eye.netclass import (classify_ip, classify_ips, filter_ips,
                                harvest_ips, ip_report, is_cdn, refresh_ranges)


class TestClassification:
    @pytest.mark.parametrize("ip,provider", [
        ("104.16.132.229", "Cloudflare"),
        ("172.67.1.1", "Cloudflare"),
        ("2606:4700::1111", "Cloudflare"),
        ("151.101.1.69", "Fastly"),
        ("23.32.5.5", "Akamai"),
        ("13.107.21.200", "Azure Front Door"),
    ])
    def test_known_edge_ranges(self, ip, provider):
        c = classify_ip(ip)
        assert c["kind"] == "cdn" and c["provider"] == provider
        assert is_cdn(ip)

    @pytest.mark.parametrize("ip", ["8.8.8.8", "93.184.216.34", "91.198.174.192"])
    def test_ordinary_addresses_are_origin_candidates(self, ip):
        assert classify_ip(ip)["kind"] == "origin"
        assert not is_cdn(ip)

    @pytest.mark.parametrize("ip", ["192.168.1.1", "10.0.0.5", "127.0.0.1",
                                    "169.254.1.1",
                                    # RFC5737 documentation ranges: not routable
                                    "203.0.113.9", "198.51.100.7", "192.0.2.1"])
    def test_private_space(self, ip):
        assert classify_ip(ip)["kind"] == "private"

    def test_cloud_is_not_cdn(self):
        """A DigitalOcean box is hosting, not a fronted edge — it stays a
        legitimate origin lead, just an annotated one."""
        c = classify_ip("159.65.1.1")
        assert c["kind"] == "cloud" and c["provider"] == "DigitalOcean"
        assert not is_cdn("159.65.1.1")

    def test_garbage_is_rejected(self):
        assert classify_ip("not-an-ip")["kind"] == "invalid"
        assert classify_ip("")["kind"] == "invalid"

    def test_filter_keeps_only_origins(self):
        ips = ["104.16.1.1", "1.2.3.4", "192.168.0.1", "5.6.7.8", "151.101.1.1"]
        assert filter_ips(ips) == ["1.2.3.4", "5.6.7.8"]

    def test_classify_dedupes(self):
        assert len(classify_ips(["1.2.3.4", "1.2.3.4", "1.2.3.4"])) == 1

    def test_every_bundled_cidr_parses(self):
        import ipaddress
        for provider, cidrs in list(netclass.CDN_RANGES.items()) + \
                list(netclass.CLOUD_RANGES.items()):
            for cidr in cidrs:
                ipaddress.ip_network(cidr, strict=False)  # must not raise


class TestScanReport:
    def _results(self):
        return [
            Result("DNS records", "acme.com",
                   data={"a": "104.16.1.1, 172.67.9.9"}),          # CF edge
            Result("Mail servers", "acme.com", data={"mx_ip": "93.184.216.34"}),
            Result("Subdomain enum", "acme.com",
                   data={"hosts": {"dev.acme.com": "91.198.174.192"}}),
            Result("Internal", "acme.com", data={"leak": "10.0.0.5"}),
        ]

    def test_harvest_finds_every_ip(self):
        assert set(harvest_ips(self._results())) == {
            "104.16.1.1", "172.67.9.9", "93.184.216.34", "91.198.174.192", "10.0.0.5"}

    def test_origin_candidates_exclude_edge_and_private(self):
        rep = ip_report(self._results(), "acme.com")
        assert set(rep["origin_candidates"]) == {"93.184.216.34", "91.198.174.192"}
        assert set(rep["cdn_edge_ips"]) == {"104.16.1.1", "172.67.9.9"}
        assert rep["private_ips"] == ["10.0.0.5"]
        assert rep["behind_cdn"] is True
        assert rep["cdn_providers"]["Cloudflare"]

    def test_fully_fronted_is_flagged(self):
        res = [Result("DNS", "acme.com", data={"a": "104.16.1.1, 104.16.2.2"})]
        rep = ip_report(res, "acme.com")
        assert rep["fully_fronted"] is True
        assert rep["origin_candidates"] == []
        assert "origin was not exposed" in rep["note"]

    def test_not_fronted(self):
        res = [Result("DNS", "acme.com", data={"a": "93.184.216.34"})]
        rep = ip_report(res, "acme.com")
        assert rep["behind_cdn"] is False and rep["fully_fronted"] is False

    def test_empty_is_safe(self):
        rep = ip_report([], "acme.com")
        assert rep["total_ips"] == 0 and rep["origin_candidates"] == []


class TestInventoryIntegration:
    def test_inventory_separates_edge_from_origin(self):
        res = [Result("DNS", "acme.com",
                      data={"a": "104.16.1.1", "origin": "93.184.216.34"})]
        inv = build_inventory(res, "acme.com")
        assert inv["origin_ips"] == ["93.184.216.34"]
        assert inv["cdn_ips"] == ["104.16.1.1"]
        assert inv["cdn_providers"] == ["Cloudflare"]
        assert inv["counts"]["origin_ips"] == 1


class TestAttributionIntegration:
    def test_shared_cdn_ip_is_not_evidence_of_ownership(self):
        """Two unrelated sites answering from the same Cloudflare node must not
        be linked — this is the exact false positive the filter exists for."""
        from ghost_eye.intelligence.attribution import evidence_weight
        edge = evidence_weight("ip", "104.16.1.1", 2, 10)
        real = evidence_weight("ip", "93.184.216.34", 2, 10)
        assert edge < real / 5
        assert edge < 0.05

    def test_attribution_ignores_shared_edge_addresses(self):
        from ghost_eye.intelligence import attribute
        res = []
        for h in ("a.com", "b.com", "c.com"):
            res.append(Result("DNS", h, data={"a": "104.16.1.1"}))
        rep = attribute(res, "a.com")
        assert rep["estate_count"] == 0


class TestRangeRefresh:
    def test_refresh_updates_from_published_list(self):
        class _R:
            status_code = 200
            text = "\n".join(f"93.184.{i}.0/24" for i in range(8))

        class _S:
            def get(self, url, **kw):
                return _R()
        before = dict(netclass.CDN_RANGES)
        try:
            updated = refresh_ranges(session=_S())
            assert updated.get("Cloudflare", 0) >= 8
            assert classify_ip("93.184.3.7")["kind"] == "cdn"
        finally:
            netclass.CDN_RANGES.clear()
            netclass.CDN_RANGES.update(before)
            netclass._CDN_NETS = netclass._compile(netclass.CDN_RANGES)

    def test_failed_refresh_keeps_bundled_ranges(self):
        """A dead endpoint must never leave the classifier with no ranges."""
        class _S:
            def get(self, url, **kw):
                raise OSError("network down")
        refresh_ranges(session=_S())
        assert is_cdn("104.16.132.229"), "bundled Cloudflare range was lost"

    def test_partial_response_is_rejected(self):
        class _R:
            status_code = 200
            text = "93.184.100.0/24"       # suspiciously short: 1 CIDR

        class _S:
            def get(self, url, **kw):
                return _R()
        refresh_ranges(session=_S())
        assert is_cdn("104.16.132.229"), "a 1-line response replaced the list"
