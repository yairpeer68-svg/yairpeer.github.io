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


def _router2(url):
    if "reverseiplookup" in url:
        return _Resp(t="acme.com\nblog.acme.com\nother-tenant.com")
    if "iptoasn" in url:
        return _Resp(j={"as_number": 64500, "as_description": "ACME-AS",
                        "as_country_code": "US", "announced": True})
    if "feodotracker" in url:
        return _Resp(j=[{"ip_address": "1.2.3.4", "malware": "Emotet",
                         "status": "online", "first_seen": "2024-01-01"}])
    if "keybase" in url:
        return _Resp(j={"them": [{"basics": {"username": "alice"}},
                                 {"basics": {"username": "bob"}}]})
    if "psbdmp" in url:
        return _Resp(j={"data": [{"id": "abc", "time": "2024-01-01"}]})
    if "IPv4" in url and "passive_dns" in url:
        return _Resp(j={"passive_dns": [{"hostname": "x.acme.com"}]})
    if "IPv4" in url and "general" in url:
        return _Resp(j={"pulse_info": {"count": 3}})
    return _Resp(j={})


def test_wave2_ip_sources():
    ctx = _ctx(_router2)
    rev = REGISTRY["reverseip"].run("1.2.3.4", ctx).data
    assert "acme.com" in rev["related_domains"]
    assert REGISTRY["iptoasn"].run("1.2.3.4", ctx).data["asn"] == 64500
    feo = REGISTRY["feodo"].run("1.2.3.4", ctx).data
    assert feo["c2_listed"] is True and feo["severity"] == "critical"
    otx = REGISTRY["otxip"].run("1.2.3.4", ctx).data
    assert otx["flagged"] is True and "x.acme.com" in otx["related_domains"]


def test_wave2_domain_sources():
    ctx = _ctx(_router2)
    kb = REGISTRY["keybase"].run("acme.com", ctx).data
    assert set(kb["keybase_users"]) == {"alice", "bob"}
    ps = REGISTRY["psbdmp"].run("acme.com", ctx).data
    assert ps["paste_dumps"] == 1 and ps.get("severity") == "medium"


def test_favicon_mmh3_pivot_hashes():
    class _RIcon:
        status_code = 200
        def __init__(self, c): self.content = c
    class _SIcon:
        def get(self, url, timeout=15, **kw):
            return _RIcon(b"\x00\x01ICON" * 30) if "favicon" in url else _RIcon(b"")
    ctx = _ctx(lambda u: None)
    ctx.session = _SIcon()
    d = REGISTRY["favicmmh3"].run("acme.com", ctx).data
    assert d["favicon_md5"] and d["favicon_bytes"] > 0


def test_identity_graph_links_people():
    from ghost_eye.core import Result
    from ghost_eye.intelligence import identity_graph
    res = [Result("emails", "acme.com", "ok",
                  {"emails": ["john.smith@acme.com", "jane.doe@acme.com"]}),
           Result("username", "acme.com", "ok",
                  {"p": ["https://github.com/johnsmith",
                         "https://twitter.com/jane_d"]}),
           Result("emailpattern", "acme.com", "ok",
                  {"names_found": ["John Smith", "Jane Doe"]})]
    g = identity_graph(res, "acme.com")
    assert g["resolved_identities"] >= 2
    assert "johnsmith" in g["usernames"]
    kinds = g["counts"]["by_kind"]
    assert kinds.get("person", 0) >= 2 and kinds.get("email", 0) >= 2
    # John Smith is linked to both his e-mail and his github handle
    rels = {(r["from"], r["type"], r["to"]) for r in g["relationships"]}
    assert ("person:john smith", "has_email", "email:john.smith@acme.com") in rels
    assert ("person:john smith", "has_username", "username:johnsmith") in rels


def _router4(url):
    if "jldc.me/anubis" in url:
        return _Resp(j=["api.acme.com", "www.acme.com", "evil.com"])
    if "phishstats" in url:
        return _Resp(j=[{"url": "http://acme.com/login", "ip": "1.2.3.4", "score": 8}])
    if "web.archive.org/cdx" in url:
        return _Resp(j=[["original"],
                        ["http://acme.com/api/v1/users?id=1&token=x"],
                        ["http://acme.com/backup.sql"],
                        ["http://acme.com/page?q=1"]])
    return _Resp(j={})


def test_wave4_sources():
    ctx = _ctx(_router4)
    assert set(REGISTRY["anubisjldc"].run("acme.com", ctx).data["subdomains"]) \
        == {"api.acme.com", "www.acme.com"}
    ph = REGISTRY["phishstats"].run("acme.com", ctx).data
    assert ph["phishing_reports"] == 1 and ph["severity"] == "high"
    wb = REGISTRY["waybackparams"].run("acme.com", ctx).data
    assert set(wb["parameters"]) >= {"id", "token", "q"}
    assert any("backup.sql" in f for f in wb.get("interesting_files", []))


def _router5(url):
    if "wikidata" in url:
        return _Resp(j={"results": {"bindings": [{
            "item": {"value": "http://www.wikidata.org/entity/Q312"},
            "itemLabel": {"value": "Acme Inc"},
            "parentLabel": {"value": "Acme Holdings"},
            "countryLabel": {"value": "United States"},
            "industryLabel": {"value": "software"}}]}})
    if "peeringdb" in url:
        return _Resp(j={"data": [{"name": "ACME-NET", "website": "https://acme.com",
                                  "info_type": "Content"}]})
    return _Resp(j={})


def test_wikidata_org_intel():
    d = REGISTRY["wikidata"].run("acme.com", _ctx(_router5)).data
    assert d["organisation"] == "Acme Inc"
    assert d["parent_company"] == "Acme Holdings"
    assert d["wikidata_id"] == "Q312"


def test_peeringdb_network_owner(monkeypatch):
    import ghost_eye.modules.osint_freesources as fs
    monkeypatch.setattr(fs, "_ip_to_asn", lambda ip: "64500")
    d = REGISTRY["peeringdb"].run("1.2.3.4", _ctx(_router5)).data
    assert d["asn"] == "AS64500" and d["network_name"] == "ACME-NET"
    # a non-IP target is handled gracefully
    assert "note" in REGISTRY["peeringdb"].run("acme.com", _ctx(_router5)).data


def _router6(url):
    if "collinfo.json" in url:
        return _Resp(j=[{"cdx-api": "https://index.commoncrawl.org/CC-MAIN-2024-10-index"}])
    if "CC-MAIN" in url:
        return _Resp(t='{"url":"http://acme.com/api?token=1"}\n'
                       '{"url":"http://acme.com/backup.sql"}\n'
                       '{"url":"http://acme.com/p?q=2"}')
    if "cdx/search" in url:
        return _Resp(j=[["original", "timestamp"],
                        ["http://acme.com/app.js", "20200101000000"]])
    if "id_/http://acme.com/app.js" in url:
        return _Resp(t='var k="AKIAIOSFODNN7EXAMPLE";')
    return _Resp(j={}, t="")


def test_commoncrawl_deep_mining():
    d = REGISTRY["commoncrawlmine"].run("acme.com", _ctx(_router6)).data
    assert d["indexed_urls"] == 3
    assert set(d["parameters"]) >= {"q", "token"}
    assert any("backup.sql" in f for f in d.get("interesting_files", []))


def test_wayback_historical_secret_scan():
    d = REGISTRY["waybacksecrets"].run("acme.com", _ctx(_router6)).data
    assert d["secrets_found"] >= 1
    assert d["findings"][0]["type"] == "aws_access_key_id"
    # the raw key is redacted, never echoed
    assert "AKIAIOSFODNN7EXAMPLE" != d["findings"][0]["match"]
    assert d.get("severity") == "high"


def test_phoneharvest_extracts_numbers():
    class _R:
        status_code = 200
        def __init__(self, t): self.text = t
        def json(self): return {}
    class _S:
        def get(self, url, timeout=15, **kw):
            return _R("Call +1 415-555-2671 or +44 20 7946 0958") \
                if ("contact" in url or url.rstrip("/").endswith("acme.com")) else _R("")
    ctx = _ctx(lambda u: None); ctx.session = _S()
    d = REGISTRY["phoneharvest"].run("acme.com", ctx).data
    assert d["count"] >= 2
    raws = {n.get("raw") or n.get("e164") for n in d["phone_numbers"]}
    assert "+14155552671" in raws


def test_ipwhois_parses():
    def router(url):
        if "ipwho.is" in url:
            return _Resp(j={"success": True, "country": "United States",
                            "connection": {"org": "ACME", "asn": 64500}})
        return _Resp(j={})
    d = REGISTRY["ipwhois"].run("1.2.3.4", _ctx(router)).data
    assert d["org"] == "ACME" and d["asn"] == 64500
    assert "note" in REGISTRY["ipwhois"].run("acme.com", _ctx(router)).data


def test_extdomains_classifies_third_parties():
    html = ('<script src="https://cdn.jsdelivr.net/x.js"></script>'
            '<img src="//www.google-analytics.com/a">'
            '<a href="https://acme.com/x">home</a>'
            '<script src="https://js.stripe.com/v3"></script>')
    class _R:
        status_code = 200
        def __init__(self, t): self.text = t
        def json(self): return {}
    class _S:
        def get(self, url, timeout=15, **kw):
            return _R(html) if "acme.com" in url else _R("")
    ctx = _ctx(lambda u: None); ctx.session = _S()
    d = REGISTRY["extdomains"].run("acme.com", ctx).data
    assert d["count"] == 3
    assert "cdn.jsdelivr.net" in d["third_party_domains"]
    assert "acme.com" not in d["third_party_domains"]     # target excluded
    assert "js.stripe.com" in d["by_type"].get("payment", [])


def test_dnsbl_non_ip_is_graceful():
    d = REGISTRY["dnsbl"].run("acme.com", _ctx(lambda u: _Resp(j={}))).data
    assert "note" in d


def test_stopforumspam_and_ipapinet():
    def router(url):
        if "stopforumspam" in url:
            return _Resp(j={"ip": {"appears": 1, "frequency": 42,
                                   "lastseen": "2024-01-01"}})
        if "ipapi.co" in url:
            return _Resp(j={"country_name": "United States", "org": "ACME",
                            "asn": "AS64500"})
        return _Resp(j={})
    s = REGISTRY["stopforumspam"].run("1.2.3.4", _ctx(router)).data
    assert s["listed"] is True and s["frequency"] == 42 and s["severity"] == "medium"
    i = REGISTRY["ipapinet"].run("1.2.3.4", _ctx(router)).data
    assert i["org"] == "ACME" and i["asn"] == "AS64500"
    # non-IP handled gracefully
    assert "note" in REGISTRY["stopforumspam"].run("acme.com", _ctx(router)).data


def test_blocklistde_and_leakcheck():
    def router(url):
        if "blocklist.de" in url:
            return _Resp(t="attacks: 12\nreports: 5")
        if "leakcheck" in url:
            return _Resp(j={"success": True, "found": 3,
                            "fields": ["password"],
                            "sources": [{"name": "Collection1", "date": "2019-01"}]})
        return _Resp(t="")
    b = REGISTRY["blocklistde"].run("1.2.3.4", _ctx(router)).data
    assert b["attacks"] == 12 and b["reports"] == 5 and b["severity"] == "high"
    lk = REGISTRY["leakcheck"].run("ceo@acme.com", _ctx(router)).data
    assert lk["breached"] is True and lk["breach_count"] == 3
    assert lk["sources"][0]["name"] == "Collection1"
    # wrong target kinds handled gracefully
    assert "note" in REGISTRY["blocklistde"].run("acme.com", _ctx(router)).data
    assert "note" in REGISTRY["leakcheck"].run("notanemail", _ctx(router)).data
