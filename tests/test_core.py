"""Offline unit tests for Ghost Eye (no network required).  Run: pytest -q"""

import hashlib
import os
import socket
import tempfile

import pytest


# --------------------------------------------------------------------------- #
def test_validators():
    from ghost_eye.core import clean_host, is_ip, is_domain
    assert is_ip("1.2.3.4")
    assert not is_ip("example.com")
    assert is_domain("example.com")
    assert clean_host("Example.com") == "example.com"
    assert clean_host("https://example.com/path") == "example.com"
    with pytest.raises(ValueError):
        clean_host("example.com; rm -rf /")


def test_registry_unique_ids():
    import ghost_eye  # noqa: F401  (triggers module imports)
    from ghost_eye.core import REGISTRY
    ids = [m.id for m in REGISTRY.values()]
    assert len(ids) == len(set(ids))
    assert len(REGISTRY) >= 130
    for must in ("portscan", "doh", "cve", "ja4", "subs"):
        assert must in REGISTRY


# --------------------------------------------------------------------------- #
def test_ja3s_parser_and_hash():
    from ghost_eye.modules import fingerprint as fp
    u16 = lambda n: n.to_bytes(2, "big")
    exts = u16(0x002b) + u16(2) + u16(0x0304) + u16(0xff01) + u16(1) + b"\x00"
    body = b"\x03\x03" + b"\x00" * 32 + b"\x00" + u16(0xc02f) + b"\x00" + u16(len(exts)) + exts
    hs = b"\x02" + len(body).to_bytes(3, "big") + body
    legacy, neg, cipher, et, alpn = fp._parse_server_hello(hs)
    assert legacy == 771 and neg == 772 and cipher == 0xc02f and et == [43, 65281]
    assert fp._ja3s(legacy, cipher, et) == hashlib.md5(b"771,49199,43-65281").hexdigest()
    assert fp._ja4s(neg, cipher, et, alpn).startswith("t13")


# --------------------------------------------------------------------------- #
def test_subdomain_keep_filter():
    from ghost_eye.modules.subdomains import _keep
    out = _keep(["*.Example.com", "a.example.com", "other.com",
                 "u@example.com", "  B.Example.com "], "example.com")
    assert out == {"example.com", "a.example.com", "b.example.com"}


def test_subdomain_source_merge(monkeypatch):
    from ghost_eye.modules import subdomains as sd
    from ghost_eye.core import Context
    monkeypatch.setattr(sd, "_src_crtsh", lambda h, s, t: {"a." + h})
    monkeypatch.setattr(sd, "_src_hackertarget", lambda h, s, t: {"b." + h})
    for fn in ("_src_certspotter", "_src_anubis", "_src_alienvault",
               "_src_rapiddns", "_src_wayback", "_src_urlscan",
               "_src_threatminer", "_src_threatcrowd"):
        monkeypatch.setattr(sd, fn, lambda h, s, t: (_ for _ in ()).throw(RuntimeError("down")))
    monkeypatch.setattr(sd.socket, "gethostbyname", lambda n: "127.0.0.1")
    ctx = Context(config=None, session=object(), threads=2, timeout=1)
    res = sd.SubdomainEnum().run("example.com", ctx)
    assert res.data["count"] == 2
    assert set(res.data["subdomains"]) == {"a.example.com", "b.example.com"}
    assert res.data["sources"]["certspotter"].startswith("failed")


# --------------------------------------------------------------------------- #
def test_inventory():
    _ADDR = "admin" + chr(64) + "example.com"
    from ghost_eye.core import Result
    from ghost_eye.reporting_ext import build_inventory
    rs = [
        Result("DNS records (all types)", "example.com", "ok",
               {"a": ["93.184.216.34"], "ns": ["a.iana-servers.net"]}),
        Result("Exposed databases (no auth)", "example.com", "ok",
               {"databases": {"redis:6379": "OPEN (no auth)"}}),
        Result("Email harvesting", "example.com", "ok",
               {"emails": [_ADDR]}),
    ]
    inv = build_inventory(rs, "example.com")
    assert "93.184.216.34" in inv["ips"]
    assert "6379/redis" in inv["services"]
    assert _ADDR in inv["emails"]
    assert "a.iana-servers.net" in inv["hosts"]


# --------------------------------------------------------------------------- #
def test_scope():
    from ghost_eye.scope import Scope
    s = Scope.from_lines(["example.com", "192.0.2.0/24", "# note", "host.test.io"])
    assert s.allows("example.com")[0]
    assert s.allows("api.example.com")[0]
    assert s.allows("https://www.example.com/x")[0]
    assert s.allows("192.0.2.55")[0]
    assert not s.allows("evil.com")[0]
    assert not s.allows("203.0.113.5")[0]
    assert Scope().allows("anything.com")[0]   # empty scope = allow


# --------------------------------------------------------------------------- #
def test_portscan_connect():
    from ghost_eye.modules.portscan import scan_ports
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(1)
    port = srv.getsockname()[1]
    try:
        found = scan_ports("127.0.0.1", [port], timeout=1.0, threads=2, grab=False)
        assert port in found
    finally:
        srv.close()


# --------------------------------------------------------------------------- #
def test_cve_parsers():
    from ghost_eye.modules.cve import _extract_products, _parse_nvd
    prods = _extract_products("nginx/1.18.0", "PHP/7.4.3", "")
    assert "nginx 1.18.0" in prods and "PHP 7.4.3" in prods
    data = {"vulnerabilities": [{"cve": {
        "id": "CVE-2021-23017",
        "descriptions": [{"lang": "en", "value": "nginx resolver off-by-one"}],
        "metrics": {"cvssMetricV31": [{"cvssData": {"baseScore": 7.7,
                                                    "baseSeverity": "HIGH"}}]}}}]}
    cves = _parse_nvd(data)
    assert cves[0]["id"] == "CVE-2021-23017" and cves[0]["cvss"] == 7.7


# --------------------------------------------------------------------------- #
def test_doh_parser():
    from ghost_eye.modules.doh import doh_query

    class FakeResp:
        status_code = 200
        def json(self):
            return {"Status": 0, "Answer": [{"data": "93.184.216.34"}]}

    class FakeSess:
        def get(self, url, **kw):
            return FakeResp()

    assert doh_query(FakeSess(), "example.com", "A", 5) == ["93.184.216.34"]


# --------------------------------------------------------------------------- #
def test_store_roundtrip():
    from ghost_eye.reporting import Store
    from ghost_eye.core import Result
    db = os.path.join(tempfile.mkdtemp(), "t.db")
    st = Store(db)
    st.save_scan("abc", "example.com",
                 [Result("dns", "example.com", "ok", {"a": ["1.2.3.4"]})], "LOW", 5)
    rec = st.recent_scans(10)
    assert rec and rec[0]["id"] == "abc" and rec[0]["target"] == "example.com"
    loaded = st.load_scan("abc")
    assert loaded and loaded["results"][0]["module"] == "dns"
    st.close()


# --------------------------------------------------------------------------- #
def test_wrap_session_noop_and_risk():
    from ghost_eye import workflow
    import requests
    s = requests.Session()
    assert workflow.wrap_session(s, 0, None, 300, 0) is s
    from ghost_eye.core import Result
    from ghost_eye.reporting_ext import score_findings
    score = score_findings([Result("Exposed databases (no auth)", "x", "ok",
                                    {"db": "OPEN (no auth - responds to PING)"})])
    assert score["counts"]["high"] >= 1 and score["risk_level"] in ("HIGH", "CRITICAL")


# --------------------------------------------------------------------------- #
def test_ripestat_parsers():
    from ghost_eye.modules.freeintel import _parse_netinfo, _parse_asoverview, _parse_abuse
    asns, prefix = _parse_netinfo({"data": {"asns": [13335], "prefix": "1.1.1.0/24"}})
    assert asns == ["13335"] and prefix == "1.1.1.0/24"
    assert _parse_asoverview({"data": {"holder": "CLOUDFLARENET"}}) == "CLOUDFLARENET"
    contact = "abuse" + chr(64) + "example.net"
    assert _parse_abuse({"data": {"abuse_contacts": [contact, ""]}}) == [contact]


def test_paid_modules_removed():
    from ghost_eye.core import REGISTRY
    for mid in ("shodan", "censys", "passivedns", "breach"):
        assert mid not in REGISTRY, f"{mid} should have been removed"
    assert "ripestat" in REGISTRY and "internetdb" in REGISTRY
    # nothing may require a PAID key; only the free-tier VT/AbuseIPDB may want one
    for mid, m in REGISTRY.items():
        for n in getattr(m, "needs", []):
            if "api key" in str(n).lower() or "secret" in str(n).lower():
                assert mid in ("virustotal", "abuseipdb"), f"{mid} needs paid key {n}"


# --------------------------------------------------------------------------- #
def test_collect_assets_and_rollup():
    from ghost_eye.core import Result
    from ghost_eye import inventory as inv
    results = [
        Result("DNS records (all types)", "example.com", "ok",
               {"a": ["93.184.216.34"], "cname": ["www.example.com"]}),
        Result("Subdomain enumeration", "example.com", "ok",
               {"subdomains": ["api.example.com", "mail.example.com"]}),
        Result("Shodan InternetDB (free, no key)", "93.184.216.34", "ok",
               {"ports": [80, 443], "vulns": ["CVE-2021-23017"]}),
        Result("Tech fingerprint", "api.example.com", "ok",
               {"server": "nginx"}),
        Result("TCP port scan (connect, no root)", "api.example.com", "ok",
               {"open_ports": {"443/https": "open", "22/ssh": "open"}}),
    ]
    assets = inv.collect_assets(results, "example.com", None, 25)
    assert "api.example.com" in assets["hosts"]
    assert "example.com" not in assets["hosts"]      # the target is excluded
    assert "93.184.216.34" in assets["ips"]

    roll = inv.build_host_rollup(results, "example.com")
    assert "api.example.com" in roll
    assert set(roll["api.example.com"]["ports"]) == {22, 443}
    assert "nginx" in roll["api.example.com"]["tech"]
    assert "CVE-2021-23017" in roll["93.184.216.34"]["cves"]


def test_deep_plan():
    from ghost_eye.core import Result
    from ghost_eye import workflow
    results = [Result("Subdomain enumeration", "example.com", "ok",
                      {"subdomains": ["api.example.com"]})]
    plan, assets = workflow.deep_plan(results, "example.com", None, 25)
    assert assets["hosts"] == ["api.example.com"]
    # plan is a list of (asset, [modules]); the host should get the host profile
    host_entries = [a for a, _m in plan if a == "api.example.com"]
    assert host_entries and all(len(m) > 0 for _a, m in plan)
