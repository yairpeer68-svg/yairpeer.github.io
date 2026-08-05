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


def test_otxpulse_and_ipinfo():
    def router(url):
        if "otx" in url:
            return _Resp(j={"pulse_info": {"count": 2, "pulses": [
                {"name": "Emotet campaign", "created": "2024-01-01T00:00:00",
                 "tags": ["emotet"]}]}})
        if "ipinfo.io" in url:
            return _Resp(j={"hostname": "host.acme.com", "country": "US",
                            "org": "AS64500 ACME"})
        return _Resp(j={})
    o = REGISTRY["otxpulse"].run("acme.com", _ctx(router)).data
    assert o["threat_pulses"] == 2 and o["flagged"] is True
    assert o["pulses"][0]["name"] == "Emotet campaign"
    i = REGISTRY["ipinfo"].run("1.2.3.4", _ctx(router)).data
    assert i["org"] == "AS64500 ACME" and i["country"] == "US"
    assert "note" in REGISTRY["ipinfo"].run("acme.com", _ctx(router)).data


def test_merklemap_filters_to_domain():
    def router(url):
        if "merklemap" in url:
            return _Resp(j={"results": [{"domain": "api.acme.com"},
                                        {"domain": "www.acme.com"},
                                        {"domain": "evil.com"}]})
        return _Resp(j={})
    d = REGISTRY["merklemap"].run("acme.com", _ctx(router)).data
    assert set(d["subdomains"]) == {"api.acme.com", "www.acme.com"}


def test_uriblock_uses_dns(monkeypatch):
    import dns.resolver

    class _Ans:
        def __init__(self, v): self.v = v
        def __iter__(self): return iter([self.v])

    def fake_resolve(self, qname, rtype):
        if qname.startswith("acme.com.multi.surbl.org"):
            return _Ans("127.0.0.2")           # listed on SURBL
        raise Exception("NXDOMAIN")

    monkeypatch.setattr(dns.resolver.Resolver, "resolve", fake_resolve)
    d = REGISTRY["uriblock"].run("acme.com", _ctx(lambda u: _Resp(j={}))).data
    assert "SURBL" in d["listed_on"] and d["listed_count"] == 1
    assert d["severity"] == "medium"


def test_npmsearch_and_dockerhub():
    def router(url):
        if "registry.npmjs" in url:
            return _Resp(j={"objects": [{"package": {
                "name": "@acme/ui", "description": "Acme UI kit",
                "publisher": {"username": "acmedev"},
                "date": "2024-01-01T00:00:00"}}]})
        if "hub.docker.com" in url:
            return _Resp(j={"results": [{"repo_name": "acme/api",
                                         "short_description": "Acme API",
                                         "star_count": 42}]})
        return _Resp(j={})
    n = REGISTRY["npmsearch"].run("acme.com", _ctx(router)).data
    assert n["count"] == 1 and n["npm_packages"][0]["name"] == "@acme/ui"
    assert n["npm_packages"][0]["publisher"] == "acmedev"
    dh = REGISTRY["dockerhub"].run("acme.com", _ctx(router)).data
    assert dh["query"] == "acme" and dh["docker_images"][0]["repo"] == "acme/api"


def test_package_registries():
    def router(url):
        if "crates.io" in url:
            return _Resp(j={"crates": [{"name": "acme-cli", "description": "Acme CLI",
                                        "downloads": 1200}]})
        if "rubygems.org" in url:
            return _Resp(j=[{"name": "acme_client", "info": "Acme client",
                             "downloads": 500}])
        if "packagist.org" in url:
            return _Resp(j={"results": [{"name": "acme/sdk", "description": "SDK",
                                         "downloads": 300}]})
        return _Resp(j={})
    assert REGISTRY["cratesio"].run("acme.com", _ctx(router)).data["crates"][0]["name"] == "acme-cli"
    assert REGISTRY["rubygems"].run("acme.com", _ctx(router)).data["gems"][0]["name"] == "acme_client"
    assert REGISTRY["packagist"].run("acme.com", _ctx(router)).data["packages"][0]["name"] == "acme/sdk"
    # org label derived from the second-level domain
    assert REGISTRY["cratesio"].run("acme.com", _ctx(router)).data["query"] == "acme"


def test_dotnet_cloudnative_gitlab_registries():
    def router(url):
        if "nuget.org" in url:
            return _Resp(j={"data": [{"id": "Acme.Sdk", "description": "SDK",
                                      "totalDownloads": 9000, "authors": "Acme"}]})
        if "artifacthub.io" in url:
            return _Resp(j={"packages": [{"name": "acme-chart",
                                          "repository": {"name": "acme", "kind": 0},
                                          "stars": 5}]})
        if "gitlab.com" in url:
            return _Resp(j=[{"path_with_namespace": "acme/backend",
                             "description": "backend", "star_count": 12}])
        return _Resp(j={})
    assert REGISTRY["nuget"].run("acme.com", _ctx(router)).data["packages"][0]["id"] == "Acme.Sdk"
    assert REGISTRY["artifacthub"].run("acme.com", _ctx(router)).data["artifacts"][0]["name"] == "acme-chart"
    assert REGISTRY["gitlabsearch"].run("acme.com", _ctx(router)).data["projects"][0]["path"] == "acme/backend"


def test_public_mentions_hn_reddit():
    def router(url):
        if "hn.algolia" in url:
            return _Resp(j={"hits": [{"title": "Acme raises Series B",
                                      "author": "pg", "points": 120,
                                      "num_comments": 45, "objectID": "999",
                                      "created_at": "2024-01-01T00:00:00Z"}]})
        if "reddit.com" in url:
            return _Resp(j={"data": {"children": [{"data": {
                "title": "Acme outage", "subreddit": "sysadmin", "score": 88,
                "num_comments": 30, "permalink": "/r/sysadmin/x"}}]}})
        return _Resp(j={})
    hn = REGISTRY["hackernews"].run("acme.com", _ctx(router)).data
    assert hn["count"] == 1 and hn["mentions"][0]["title"] == "Acme raises Series B"
    assert hn["mentions"][0]["hn"].endswith("id=999")
    rd = REGISTRY["reddit"].run("acme.com", _ctx(router)).data
    assert rd["count"] == 1 and rd["mentions"][0]["subreddit"] == "sysadmin"
    assert rd["mentions"][0]["permalink"] == "https://reddit.com/r/sysadmin/x"


def test_news_devqa_filings():
    def router(url):
        if "gdeltproject" in url:
            return _Resp(j={"articles": [{"title": "Acme launches", "url": "https://n/acme",
                                          "domain": "n", "sourcecountry": "US",
                                          "seendate": "20240101T000000Z"}]})
        if "stackexchange.com" in url:
            return _Resp(j={"items": [{"title": "Acme API", "tags": ["acme"],
                                       "score": 7, "question_id": 123,
                                       "is_answered": True}]})
        if "efts.sec.gov" in url:
            return _Resp(j={"hits": {"total": {"value": 3}, "hits": [
                {"_id": "x", "_source": {"display_names": ["ACME INC"],
                                         "root_form": "10-K", "file_date": "2024-02-01"}}]}})
        return _Resp(j={})
    assert REGISTRY["gdelt"].run("acme.com", _ctx(router)).data["news"][0]["title"] == "Acme launches"
    se = REGISTRY["stackexchange"].run("acme.com", _ctx(router)).data
    assert se["posts"][0]["question_id"] == 123
    sec = REGISTRY["secedgar"].run("acme.com", _ctx(router)).data
    assert sec["filings"][0]["company"] == "ACME INC" and sec["total"] == 3


def test_wikipedia_and_codeberg():
    def router(url):
        if "list=search" in url:
            return _Resp(j={"query": {"search": [{"title": "Acme Corporation"}]}})
        if "prop=extracts" in url:
            return _Resp(j={"query": {"pages": {"42": {
                "extract": "Acme Corporation is a fictional company."}}}})
        if "codeberg.org" in url:
            return _Resp(j={"data": [{"full_name": "acme/tools",
                                      "description": "Acme tools", "stars_count": 9,
                                      "language": "Go",
                                      "html_url": "https://codeberg.org/acme/tools"}]})
        return _Resp(j={})
    wp = REGISTRY["wikipedia"].run("acme.com", _ctx(router)).data
    assert wp["title"] == "Acme Corporation"
    assert wp["summary"].startswith("Acme Corporation")
    assert wp["url"] == "https://en.wikipedia.org/wiki/Acme_Corporation"
    cb = REGISTRY["codeberg"].run("acme.com", _ctx(router)).data
    assert cb["count"] == 1 and cb["repos"][0]["repo"] == "acme/tools"
    assert cb["repos"][0]["stars"] == 9


def test_wave21_ipguide_pdns_swh_columbus():
    def router(url):
        if "ip.guide" in url:
            return _Resp(j={"network": {"cidr": "1.2.0.0/16",
                            "autonomous_system": {"asn": 64500,
                                                  "organization": "Acme Net"}},
                            "location": {"country": "US", "city": "NYC"}})
        if "mnemonic.no" in url:
            return _Resp(j={"data": [{"query": "acme.com", "answer": "vpn.acme.com"},
                                     {"query": "mail.acme.com", "answer": "1.2.3.4"}]})
        if "softwareheritage" in url:
            return _Resp(j=[{"url": "https://github.com/acme/repo",
                             "origin_visit_type": "git"}])
        if "columbus.elmasy.com" in url:
            return _Resp(j=["www", "api", "vpn"])
        return _Resp(j={})
    ig = REGISTRY["ipguide"].run("1.2.3.4", _ctx(router)).data
    assert ig["asn"] == 64500 and ig["org"] == "Acme Net" and ig["prefix"] == "1.2.0.0/16"
    pd = REGISTRY["pdnsmnemonic"].run("acme.com", _ctx(router)).data
    assert "vpn.acme.com" in pd["subdomains"] and "mail.acme.com" in pd["subdomains"]
    sw = REGISTRY["swheritage"].run("acme.com", _ctx(router)).data
    assert sw["count"] == 1 and sw["origins"][0]["url"].endswith("acme/repo")
    cb = REGISTRY["columbus"].run("acme.com", _ctx(router)).data
    assert "www.acme.com" in cb["subdomains"] and "api.acme.com" in cb["subdomains"]


def test_wave22_crtsh_bitbucket_sourcegraph_greynoise():
    def router(url):
        if "crt.sh" in url:
            return _Resp(j=[{"name_value": "www.acme.com\n*.acme.com"},
                            {"name_value": "vpn.acme.com"}])
        if "bitbucket.org" in url:
            return _Resp(j={"values": [{"full_name": "acme/api",
                            "description": "API", "language": "python",
                            "links": {"html": {"href": "https://bitbucket.org/acme/api"}}}]})
        if "sourcegraph.com" in url:
            return _Resp(j={"Results": [{"repository": "github.com/acme/app",
                                         "path": "config.yml"}]})
        if "greynoise.io" in url:
            return _Resp(j={"noise": True, "riot": False,
                            "classification": "malicious", "name": "Scanner",
                            "last_seen": "2024-01-01"})
        return _Resp(j={})
    cs = REGISTRY["crtsh"].run("acme.com", _ctx(router)).data
    assert "www.acme.com" in cs["subdomains"] and "vpn.acme.com" in cs["subdomains"]
    bb = REGISTRY["bitbucket"].run("acme.com", _ctx(router)).data
    assert bb["count"] == 1 and bb["repos"][0]["repo"] == "acme/api"
    sg = REGISTRY["sourcegraph"].run("acme.com", _ctx(router)).data
    assert sg["count"] == 1 and sg["hits"][0]["repo"] == "github.com/acme/app"
    gn = REGISTRY["greynoise"].run("9.9.9.9", _ctx(router)).data
    assert gn["noise"] is True and gn["classification"] == "malicious"


def test_wave23_doh_openphish_ipquery():
    def router(url):
        if "cloudflare-dns.com" in url or "dns.google" in url:
            if "type=MX" in url:
                return _Resp(j={"Answer": [{"data": "10 mail.acme.com."}]})
            if "type=TXT" in url:
                return _Resp(j={"Answer": [{"data": "\"v=spf1 include:_spf.acme.com ~all\""}]})
            if "type=A" in url:
                return _Resp(j={"Answer": [{"data": "1.2.3.4"}]})
            return _Resp(j={})
        if "openphish.com" in url:
            return _Resp(t="https://evil.test/acme.com/login\nhttps://other.test/x\n")
        if "ipquery.io" in url:
            return _Resp(j={"isp": {"asn": "AS64500", "org": "Acme", "isp": "AcmeISP"},
                            "location": {"country": "US", "city": "NYC"},
                            "risk": {"is_vpn": False, "is_proxy": True,
                                     "is_tor": False, "risk_score": 42}})
        return _Resp(j={})
    cf = REGISTRY["dohcloudflare"].run("acme.com", _ctx(router)).data
    assert cf["A"] == ["1.2.3.4"] and cf["MX"] == ["10 mail.acme.com."]
    assert cf["TXT"][0].startswith("v=spf1")
    gg = REGISTRY["dohgoogle"].run("acme.com", _ctx(router)).data
    assert gg["A"] == ["1.2.3.4"]
    op = REGISTRY["openphish"].run("acme.com", _ctx(router)).data
    assert op["count"] == 1 and "acme.com" in op["phishing_urls"][0]
    iq = REGISTRY["ipquery"].run("1.2.3.4", _ctx(router)).data
    assert iq["asn"] == "AS64500" and iq["is_proxy"] is True and iq["risk_score"] == 42


def test_wave24_bgpsearch_ripedb_gleif_freeipapi():
    def router(url):
        if "bgpview.io/search" in url:
            return _Resp(j={"data": {
                "asns": [{"asn": 64500, "name": "ACME-AS"}],
                "ipv4_prefixes": [{"prefix": "1.2.0.0/16", "name": "ACME-NET"}],
                "ipv6_prefixes": []}})
        if "rest.db.ripe.net" in url:
            return _Resp(j={"objects": {"object": [
                {"type": "inetnum", "attributes": {"attribute": [
                    {"name": "inetnum", "value": "1.2.0.0 - 1.2.255.255"},
                    {"name": "netname", "value": "ACME-NET"},
                    {"name": "country", "value": "NL"}]}},
                {"type": "organisation", "attributes": {"attribute": [
                    {"name": "org-name", "value": "Acme BV"}]}}]}})
        if "api.gleif.org" in url:
            return _Resp(j={"data": [{"id": "5299000ACME",
                "attributes": {"entity": {"legalName": {"name": "Acme BV"},
                    "legalAddress": {"country": "NL", "city": "Amsterdam"},
                    "status": "ACTIVE"}}}]})
        if "freeipapi.com" in url:
            return _Resp(j={"countryName": "Netherlands", "regionName": "NH",
                            "cityName": "Amsterdam", "asn": 64500,
                            "asnOrganization": "Acme", "isProxy": False})
        return _Resp(j={})
    bs = REGISTRY["bgpviewsearch"].run("acme.com", _ctx(router)).data
    assert bs["asns"][0]["asn"] == 64500 and bs["prefixes"][0]["prefix"] == "1.2.0.0/16"
    rd = REGISTRY["ripedb"].run("acme.com", _ctx(router)).data
    assert rd["inetnums"][0]["netname"] == "ACME-NET" and "Acme BV" in rd["org_names"]
    gl = REGISTRY["gleif"].run("acme.com", _ctx(router)).data
    assert gl["entities"][0]["lei"] == "5299000ACME" and gl["entities"][0]["country"] == "NL"
    fp = REGISTRY["freeipapi"].run("1.2.3.4", _ctx(router)).data
    assert fp["asn"] == 64500 and fp["city"] == "Amsterdam"
