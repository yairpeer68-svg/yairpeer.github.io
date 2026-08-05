"""Tests for the free / keyless OSINT source modules — parsing + host filtering."""

from __future__ import annotations

from ghost_eye.core import REGISTRY, Context


class _Resp:
    def __init__(self, j=None, t="", code=200):
        self._j, self.text, self.status_code = j, t, code

    def json(self):
        if self._j is None:
            raise ValueError("no json")
        return self._j


class _Sess:
    def __init__(self, router):
        self.router = router

    def get(self, url, timeout=15, **kw):
        return self.router(url)

    def post(self, url, timeout=15, **kw):
        return self.router(url)


def _ctx(router):
    return Context(config={}, session=_Sess(router), timeout=5)


def _router(url):
    if "certspotter" in url:
        return _Resp(j=[{"dns_names": ["a.acme.com", "www.acme.com", "evil.com"]}])
    if "bufferover" in url:
        return _Resp(j={"FDNS_A": ["1.2.3.4,mail.acme.com", "5.6.7.8,acme.com"]})
    if "hackertarget" in url:
        return _Resp(t="api.acme.com,9.9.9.9\nvpn.acme.com,9.9.9.8")
    if "subdomain.center" in url:
        return _Resp(j=["shop.acme.com", "acme.com"])
    if "alienvault" in url and "passive_dns" in url:
        return _Resp(j={"passive_dns": [{"hostname": "cdn.acme.com", "address": "1.1.1.1"}]})
    if "alienvault" in url:
        return _Resp(j={"url_list": [{"hostname": "blog.acme.com"}]})
    if "hudsonrock" in url:
        return _Resp(j={"total": 42, "data": {"employees": 5}})
    if "threatfox" in url:
        return _Resp(j={"data": [{"ioc": "acme.com", "threat_type": "botnet",
                                  "malware_printable": "X"}]})
    if "urlhaus" in url:
        return _Resp(j={"query_status": "ok", "urls": [{"url": "http://acme.com/mal"}]})
    if "emailrep" in url:
        return _Resp(j={"reputation": "low", "suspicious": True,
                        "details": {"credentials_leaked": True, "data_breach": True}})
    if "bgpview" in url:
        return _Resp(j={"data": {"prefixes": [{"prefix": "1.2.3.0/24",
                                               "asn": {"asn": 64500, "name": "ACME-AS"}}]}})
    if "ip-api" in url:
        return _Resp(j={"status": "success", "org": "ACME", "as": "AS64500"})
    if "robtex" in url:
        return _Resp(j={"pas": [{"o": "acme.com"}, {"o": "other.com"}]})
    return _Resp(j={})


def test_subdomain_sources_parse_and_filter():
    ctx = _ctx(_router)
    for mid in ("certspotter", "bufferover", "hackertarget", "subdomaincenter", "otxrep"):
        res = REGISTRY[mid].run("acme.com", ctx)
        subs = res.data.get("subdomains", [])
        assert all(s == "acme.com" or s.endswith(".acme.com") for s in subs)
        assert "evil.com" not in subs                # off-domain names dropped
    # certspotter specifically kept the in-scope names
    assert set(REGISTRY["certspotter"].run("acme.com", ctx).data["subdomains"]) \
        >= {"a.acme.com", "www.acme.com"}


def test_threat_and_breach_sources():
    ctx = _ctx(_router)
    assert REGISTRY["threatfox"].run("acme.com", ctx).data["listed"] is True
    assert REGISTRY["urlhaus"].run("acme.com", ctx).data["malware_urls"] == 1
    hr = REGISTRY["hudsonrock"].run("acme.com", ctx).data
    assert hr["total"] == 42 and hr.get("severity") == "high"
    er = REGISTRY["emailrep"].run("ceo@acme.com", ctx).data
    assert er["credentials_leaked"] is True


def test_ip_kind_sources():
    ctx = _ctx(_router)
    assert REGISTRY["bgpview"].run("1.2.3.4", ctx).data["prefixes"][0]["asn"] == 64500
    assert REGISTRY["ipapi"].run("1.2.3.4", ctx).data["org"] == "ACME"
    rob = REGISTRY["robtex"].run("1.2.3.4", ctx).data
    assert "acme.com" in rob["related_domains"]
    # ip modules ignore a non-IP target gracefully
    assert "note" in REGISTRY["bgpview"].run("acme.com", ctx).data


def test_all_free_sources_registered():
    for mid in ("certspotter", "bufferover", "hackertarget", "subdomaincenter",
                "otxrep", "robtex", "bgpview", "ipapi", "cymruasn", "threatfox",
                "urlhaus", "hudsonrock", "emailrep", "grepapp", "searchcode"):
        assert mid in REGISTRY
