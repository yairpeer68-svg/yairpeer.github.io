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


def test_wave25_githuborg_leakix_dshield_xposedornot():
    def router(url):
        if "api.github.com/orgs/acme/public_members" in url:
            return _Resp(j=[{"login": "alice"}, {"login": "bob"}])
        if "api.github.com/orgs/acme" in url:
            return _Resp(j={"login": "acme", "name": "Acme Inc",
                            "public_repos": 12, "followers": 99,
                            "location": "NYC", "blog": "https://acme.com"})
        if "leakix.net" in url:
            return _Resp(j={"Services": [{"host": "1.2.3.4", "port": 443,
                            "service": {"software": {"name": "nginx"}},
                            "geoip": {"country_name": "US"}}],
                            "Leaks": [{"host": "1.2.3.4", "event_source": "GitConfig",
                                       "severity": "high"}]})
        if "isc.sans.edu" in url:
            return _Resp(j={"ip": {"count": 7, "attacks": 3,
                            "mindate": "2024-01-01", "maxdate": "2024-02-01",
                            "network": "1.2.0.0/16"}})
        if "xposedornot.com" in url:
            return _Resp(j={"breaches": [["LinkedIn", "Dropbox"]]})
        return _Resp(j={})
    go = REGISTRY["githuborg"].run("acme.com", _ctx(router)).data
    assert go["login"] == "acme" and go["public_repos"] == 12
    assert "alice" in go["public_members"] and go["member_count"] == 2
    lx = REGISTRY["leakix"].run("acme.com", _ctx(router)).data
    assert lx["service_count"] == 1 and lx["services"][0]["software"] == "nginx"
    assert lx["leaks"][0]["plugin"] == "GitConfig"
    ds = REGISTRY["dshield"].run("1.2.3.4", _ctx(router)).data
    assert ds["reports"] == 7 and ds["severity"] == "high"
    xo = REGISTRY["xposedornot"].run("ceo@acme.com", _ctx(router)).data
    assert xo["breached"] is True and "LinkedIn" in xo["breaches"] and xo["breach_count"] == 2


def test_wave26_sucuri_xposeddomain_crtshorg_ipwhoisapp():
    def router(url):
        if "sitecheck.sucuri.net" in url:
            return _Resp(j={"software": {"cms": ["WordPress"]},
                            "blacklists": {"warnings": [{"a": 1}]},
                            "warnings": {"x": 1}})
        if "xposedornot.com/v1/breaches" in url:
            return _Resp(j={"exposedBreaches": [
                {"breach": "AcmeLeak", "xposed_records": 1000,
                 "xposed_date": "2023", "industry": "tech"}]})
        if "crt.sh" in url and "O=acme" in url:
            return _Resp(j=[{"common_name": "acme.com",
                             "name_value": "www.acme.com\nacme.net"}])
        if "ipwhois.app" in url:
            return _Resp(j={"success": True, "country": "US", "city": "NYC",
                            "isp": "AcmeISP", "org": "Acme", "asn": "AS64500"})
        return _Resp(j={})
    su = REGISTRY["sucuri"].run("acme.com", _ctx(router)).data
    assert su["cms"] == ["WordPress"] and su["blacklisted"] is True and su["severity"] == "high"
    xd = REGISTRY["xposeddomain"].run("acme.com", _ctx(router)).data
    assert xd["count"] == 1 and xd["breaches"][0]["name"] == "AcmeLeak"
    co = REGISTRY["crtshorg"].run("acme.com", _ctx(router)).data
    assert "acme.com" in co["cert_domains"] and "acme.net" in co["registrable_domains"]
    iw = REGISTRY["ipwhoisapp"].run("1.2.3.4", _ctx(router)).data
    assert iw["asn"] == "AS64500" and iw["city"] == "NYC"


def test_wave27_tranco_otxmalware_iplocation_githubuser():
    def router(url):
        if "tranco-list.eu" in url:
            return _Resp(j={"ranks": [{"date": "2024-01-01", "rank": 1500},
                                      {"date": "2023-12-01", "rank": 1600}]})
        if "otx.alienvault.com" in url and "/malware" in url:
            return _Resp(j={"count": 2, "data": [
                {"hash": "abc123", "detections": {"avast": "X"}},
                {"hash": "def456"}]})
        if "api.iplocation.net" in url:
            return _Resp(j={"country_name": "United States", "country_code2": "US",
                            "isp": "AcmeISP"})
        if "api.github.com/users/octocat" in url:
            return _Resp(j={"login": "octocat", "name": "The Octocat",
                            "company": "@github", "location": "SF",
                            "public_repos": 8, "followers": 9000,
                            "html_url": "https://github.com/octocat"})
        return _Resp(j={})
    tr = REGISTRY["tranco"].run("acme.com", _ctx(router)).data
    assert tr["latest_rank"] == 1500 and tr["ranked"] is True
    om = REGISTRY["otxmalware"].run("acme.com", _ctx(router)).data
    assert om["count"] == 2 and om["severity"] == "high" and om["samples"][0]["hash"] == "abc123"
    il = REGISTRY["iplocationnet"].run("1.2.3.4", _ctx(router)).data
    assert il["country"] == "United States" and il["isp"] == "AcmeISP"
    gu = REGISTRY["githubuser"].run("octocat", _ctx(router)).data
    assert gu["login"] == "octocat" and gu["followers"] == 9000 and gu["company"] == "@github"


def test_wave28_gitlabuser_hnuser_arinrdap_otxurls():
    def router(url):
        if "gitlab.com/api/v4/users" in url:
            return _Resp(j=[{"id": 42, "username": "alice", "name": "Alice",
                             "state": "active", "web_url": "https://gitlab.com/alice"}])
        if "hacker-news.firebaseio.com" in url:
            return _Resp(j={"id": "pg", "karma": 155000, "created": 1160418111,
                            "about": "Founder of <a href=x>YC</a>",
                            "submitted": [1, 2, 3]})
        if "rdap.arin.net" in url:
            return _Resp(j={"name": "ACME-NET", "handle": "NET-1-2-0-0-1",
                            "startAddress": "1.2.0.0", "endAddress": "1.2.255.255",
                            "cidr0_cidrs": [{"v4prefix": "1.2.0.0", "length": 16}],
                            "entities": [{"vcardArray": ["vcard", [
                                ["version", {}, "text", "4.0"],
                                ["fn", {}, "text", "Acme Corp"]]]}]})
        if "otx.alienvault.com" in url and "url_list" in url:
            return _Resp(j={"actual_size": 2, "url_list": [
                {"url": "http://acme.com/a", "hostname": "acme.com"},
                {"url": "http://x.acme.com/b", "hostname": "x.acme.com"}]})
        return _Resp(j={})
    gl = REGISTRY["gitlabuser"].run("alice", _ctx(router)).data
    assert gl["id"] == 42 and gl["username"] == "alice"
    hn = REGISTRY["hnuser"].run("pg", _ctx(router)).data
    assert hn["karma"] == 155000 and "YC" in hn["about"] and hn["submissions"] == 3
    ar = REGISTRY["arinrdap"].run("1.2.3.4", _ctx(router)).data
    assert ar["name"] == "ACME-NET" and ar["org"] == "Acme Corp" and "1.2.0.0/16" in ar["cidrs"]
    ou = REGISTRY["otxurls"].run("acme.com", _ctx(router)).data
    assert ou["url_count"] == 2 and "x.acme.com" in ou["subdomains"]


def test_wave29_dockeruser_reddituser_digitalside_incolumitas():
    def router(url):
        if "hub.docker.com/v2/users/alice" in url:
            return _Resp(j={"username": "alice", "full_name": "Alice A",
                            "company": "Acme", "location": "NYC",
                            "date_joined": "2015-01-01"})
        if "reddit.com/user/bob" in url:
            return _Resp(j={"data": {"name": "bob", "link_karma": 500,
                            "comment_karma": 1200, "created_utc": 1300000000,
                            "verified": True, "is_gold": False}})
        if "osint.digitalside.it" in url:
            return _Resp(t="# feed\nevil.com\nacme.com\nbad.net\n")
        if "api.incolumitas.com" in url:
            return _Resp(j={"asn": {"asn": "AS64500", "org": "Acme"},
                            "company": {"name": "Acme Corp"},
                            "location": {"country": "US"},
                            "is_datacenter": True, "is_vpn": False,
                            "is_proxy": False, "is_tor": False, "is_abuser": True})
        return _Resp(j={})
    du = REGISTRY["dockeruser"].run("alice", _ctx(router)).data
    assert du["username"] == "alice" and du["company"] == "Acme"
    ru = REGISTRY["reddituser"].run("bob", _ctx(router)).data
    assert ru["comment_karma"] == 1200 and ru["verified"] is True
    ds = REGISTRY["digitalside"].run("acme.com", _ctx(router)).data
    assert ds["listed"] is True and ds["severity"] == "critical"
    ic = REGISTRY["incolumitas"].run("1.2.3.4", _ctx(router)).data
    assert ic["asn"] == "AS64500" and ic["is_datacenter"] is True and ic["is_abuser"] is True


def test_wave30_npmuser_gists_ipapiis_dnsseccaa():
    def router(url):
        if "registry.npmjs.org" in url:
            return _Resp(j={"objects": [{"package": {"name": "acme-sdk",
                            "version": "1.0.0", "description": "SDK"}}]})
        if "api.github.com/users/alice/gists" in url:
            return _Resp(j=[{"id": "g1", "description": "dotfiles",
                             "files": {"config.env": {}, "notes.md": {}},
                             "html_url": "https://gist.github.com/alice/g1"}])
        if "api.ipapi.is" in url:
            return _Resp(j={"asn": {"asn": 64500, "org": "Acme"},
                            "company": {"name": "Acme Corp", "type": "hosting"},
                            "location": {"country": "US"},
                            "is_datacenter": True, "is_abuser": False,
                            "abuse": {"email": "abuse@acme.com"}})
        if "dns.google/resolve" in url and "type=DNSKEY" in url:
            return _Resp(j={"Answer": [{"data": "257 3 13 abc"}]})
        if "dns.google/resolve" in url and "type=CAA" in url:
            return _Resp(j={"Answer": [{"data": "0 issue \"letsencrypt.org\""}]})
        return _Resp(j={})
    nu = REGISTRY["npmuser"].run("alice", _ctx(router)).data
    assert nu["count"] == 1 and nu["packages"][0]["name"] == "acme-sdk"
    gg = REGISTRY["githubgists"].run("alice", _ctx(router)).data
    assert gg["count"] == 1 and "config.env" in gg["gists"][0]["files"]
    ia = REGISTRY["ipapiis"].run("1.2.3.4", _ctx(router)).data
    assert ia["asn"] == 64500 and ia["is_datacenter"] is True and ia["abuse_email"] == "abuse@acme.com"
    dc = REGISTRY["dnsseccaa"].run("acme.com", _ctx(router)).data
    assert dc["dnssec_enabled"] is True and "letsencrypt.org" in dc["caa_issuers"]


def test_wave31_ripeiprdap_dnslytics_devto_lookalike():
    def router(url):
        if "rdap.db.ripe.net" in url:
            return _Resp(j={"name": "ACME-RIPE", "handle": "NET-EU",
                            "country": "NL",
                            "cidr0_cidrs": [{"v4prefix": "1.2.0.0", "length": 16}],
                            "entities": [{"vcardArray": ["vcard", [
                                ["version", {}, "text", "4.0"],
                                ["fn", {}, "text", "Acme Europe BV"]]]}]})
        if "freeapi.dnslytics.net" in url:
            return _Resp(j={"data": [{"ip": "1.2.3.4", "type": "A", "domains": 12}]})
        if "dev.to/api/users" in url:
            return _Resp(j={"username": "alice", "name": "Alice",
                            "github_username": "alice-gh",
                            "twitter_username": "alice_tw", "location": "NYC"})
        if "dns.google/resolve" in url:
            # only the omission "ace.com" is "registered"
            if "name=ace.com" in url:
                return _Resp(j={"Status": 0, "Answer": [
                    {"type": 1, "data": "6.6.6.6"}]})
            return _Resp(j={"Status": 3, "Answer": []})
        return _Resp(j={})
    rr = REGISTRY["ripeiprdap"].run("1.2.3.4", _ctx(router)).data
    assert rr["org"] == "Acme Europe BV" and "1.2.0.0/16" in rr["cidrs"]
    dl = REGISTRY["dnslytics"].run("acme.com", _ctx(router)).data
    assert dl["count"] == 1 and dl["records"][0]["domains_on_ip"] == 12
    dv = REGISTRY["devtouser"].run("alice", _ctx(router)).data
    assert dv["github"] == "alice-gh" and dv["twitter"] == "alice_tw"
    la = REGISTRY["lookalike"].run("acme.com", _ctx(router)).data
    assert la["count"] == 1 and la["registered_lookalikes"][0]["domain"] == "ace.com"
    assert la["registered_lookalikes"][0]["ips"] == ["6.6.6.6"] and la["severity"] == "medium"


def test_wave32_iprdap_spfdmarc_certissuers_mxintel():
    def router(url):
        if "rdap.org/ip" in url:
            return _Resp(j={"name": "APNIC-NET", "handle": "AP-1",
                            "country": "SG", "type": "ALLOCATED",
                            "cidr0_cidrs": [{"v4prefix": "1.2.0.0", "length": 16}],
                            "entities": [{"vcardArray": ["vcard", [
                                ["version", {}, "text", "4.0"],
                                ["fn", {}, "text", "Acme APAC"]]]}]})
        if "type=TXT" in url and "_dmarc" in url:
            return _Resp(j={"Answer": [{"data": "v=DMARC1; p=none; rua=mailto:x@acme.com"}]})
        if "type=TXT" in url:
            return _Resp(j={"Answer": [{"data": "v=spf1 include:a include:b include:c +all"}]})
        if "type=MX" in url:
            return _Resp(j={"Answer": [{"data": "10 acme-com.mail.protection.outlook.com."}]})
        if "crt.sh" in url:
            return _Resp(j=[{"issuer_name": "C=US, O=Let's Encrypt, CN=R3"},
                            {"issuer_name": "C=US, O=Let's Encrypt, CN=R3"},
                            {"issuer_name": "C=US, O=DigiCert Inc, CN=X"}])
        return _Resp(j={})
    ir = REGISTRY["iprdap"].run("1.2.3.4", _ctx(router)).data
    assert ir["org"] == "Acme APAC" and "1.2.0.0/16" in ir["cidrs"] and ir["type"] == "ALLOCATED"
    sd = REGISTRY["spfdmarc"].run("acme.com", _ctx(router)).data
    assert sd["spf_all"] == "+all" and sd["dmarc_policy"] == "none" and sd["spoofable"] is True
    assert sd["severity"] == "high"
    ci = REGISTRY["certissuers"].run("acme.com", _ctx(router)).data
    assert ci["total_certs"] == 3 and ci["issuers"][0]["ca"] == "Let's Encrypt" and ci["issuers"][0]["certs"] == 2
    mx = REGISTRY["mxintel"].run("acme.com", _ctx(router)).data
    assert "Microsoft 365" in mx["mail_providers"] and mx["mx_count"] == 1


def test_wave33_nsintel_txtsaas_proxycheck_asnprefixes():
    def router(url):
        if "type=NS" in url:
            return _Resp(j={"Answer": [{"data": "ns1.acme.awsdns-10.net."},
                                       {"data": "ns2.acme.awsdns-20.org."}]})
        if "type=TXT" in url:
            return _Resp(j={"Answer": [
                {"data": "google-site-verification=abc123"},
                {"data": "atlassian-domain-verification=xyz"},
                {"data": "v=spf1 -all"}]})
        if "proxycheck.io" in url:
            return _Resp(j={"1.2.3.4": {"proxy": "yes", "type": "VPN",
                            "provider": "NordVPN", "asn": "AS64500",
                            "risk": 80, "country": "US"}})
        if "api.bgpview.io/ip/" in url:
            return _Resp(j={"data": {"prefixes": [{"asn": {"asn": 64500}}]}})
        if "api.bgpview.io/asn/64500/prefixes" in url:
            return _Resp(j={"data": {"ipv4_prefixes": [{"prefix": "1.2.0.0/16"},
                                                       {"prefix": "3.4.0.0/16"}],
                                     "ipv6_prefixes": []}})
        return _Resp(j={})
    ns = REGISTRY["nsintel"].run("acme.com", _ctx(router)).data
    assert "AWS Route 53" in ns["dns_providers"] and ns["ns_count"] == 2
    tx = REGISTRY["txtsaas"].run("acme.com", _ctx(router)).data
    assert "Atlassian" in tx["vendors"] and "Google Workspace/Search Console" in tx["vendors"]
    pc = REGISTRY["proxycheck"].run("1.2.3.4", _ctx(router)).data
    assert pc["type"] == "VPN" and pc["risk"] == 80 and pc["severity"] == "high"
    ap = REGISTRY["asnprefixes"].run("1.2.3.4", _ctx(router)).data
    assert ap["asn"] == 64500 and ap["ipv4_count"] == 2 and "3.4.0.0/16" in ap["ipv4_prefixes"]


def test_wave34_spfvendors_wildcard_asninfo_htdns():
    def router(url):
        if "type=TXT" in url and "_spf.google" in url:
            return _Resp(j={"Answer": [{"data": "v=spf1 ip4:1.2.3.4 -all"}]})
        if "type=TXT" in url and "name=acme.com" in url:
            return _Resp(j={"Answer": [{"data":
                "v=spf1 include:_spf.google.com include:sendgrid.net -all"}]})
        if "type=A" in url:
            # wildcard: every random probe resolves to same IP
            return _Resp(j={"Answer": [{"type": 1, "data": "9.9.9.9"}]})
        if "api.bgpview.io/ip/" in url:
            return _Resp(j={"data": {"prefixes": [{"asn": {"asn": 64500}}]}})
        if "api.bgpview.io/asn/64500" in url and "/prefixes" not in url:
            return _Resp(j={"data": {"name": "ACME-AS",
                            "description_short": "Acme Networks",
                            "country_code": "US",
                            "abuse_contacts": ["abuse@acme.com"],
                            "rir_allocation": {"rir_name": "ARIN",
                                               "date_allocated": "2001-01-01"}}})
        if "api.hackertarget.com/dnslookup" in url:
            return _Resp(t='A : 1.2.3.4\nMX : 10 mail.acme.com\nNS : ns1.acme.com')
        return _Resp(j={})
    sv = REGISTRY["spfvendors"].run("acme.com", _ctx(router)).data
    assert "Google Workspace" in sv["vendors"] and "SendGrid" in sv["vendors"]
    wc = REGISTRY["wildcarddns"].run("acme.com", _ctx(router)).data
    assert wc["wildcard"] is True and "9.9.9.9" in wc["wildcard_ips"]
    ai = REGISTRY["asninfo"].run("1.2.3.4", _ctx(router)).data
    assert ai["asn"] == 64500 and "abuse@acme.com" in ai["abuse_contacts"] and ai["rir"] == "ARIN"
    hd = REGISTRY["htdns"].run("acme.com", _ctx(router)).data
    assert "A" in hd["record_types"] and "MX" in hd["record_types"] and hd["count"] == 3


def test_wave35_dkim_soa_sslbl_ghactivity():
    def router(url):
        if "default._domainkey" in url:
            return _Resp(j={"Answer": [{"data":
                "v=DKIM1; k=rsa; p=" + "A" * 220}]})
        if "_domainkey" in url:
            return _Resp(j={})
        if "type=SOA" in url:
            return _Resp(j={"Answer": [{"type": 6,
                "data": "ns1.acme.com. hostmaster.acme.com. 2024010101 7200 3600 1209600 3600"}]})
        if "sslipblacklist.json" in url:
            return _Resp(j=[{"ip_address": "6.6.6.6", "dstport": 443,
                             "listing_reason": "Dridex C2", "listingdate": "2024-01-01"}])
        if "api.github.com/users/alice/events" in url:
            return _Resp(j=[{"type": "PushEvent", "repo": {"name": "acme/app"},
                             "created_at": "2024-01-01T09:00:00Z"},
                            {"type": "PushEvent", "repo": {"name": "acme/app"},
                             "created_at": "2024-01-01T09:30:00Z"}])
        return _Resp(j={})
    dk = REGISTRY["dkimscan"].run("acme.com", _ctx(router)).data
    assert dk["count"] == 1 and dk["selectors_found"][0]["selector"] == "default"
    so = REGISTRY["soaintel"].run("acme.com", _ctx(router)).data
    assert so["primary_ns"] == "ns1.acme.com" and so["admin_email"] == "hostmaster@acme.com"
    sb = REGISTRY["sslbl"].run("6.6.6.6", _ctx(router)).data
    assert sb["listed"] is True and sb["severity"] == "critical" and sb["port"] == 443
    ga = REGISTRY["githubactivity"].run("alice", _ctx(router)).data
    assert ga["events"] == 2 and "acme/app" in ga["active_repos"] and 9 in ga["peak_hours_utc"]


def test_wave36_rpki_binarydefense_dane_pagelinks():
    def router(url):
        if "network-info" in url:
            return _Resp(j={"data": {"prefix": "1.2.0.0/16", "asns": ["64500"]}})
        if "rpki-validation" in url:
            return _Resp(j={"data": {"status": "invalid",
                            "validating_roas": [{"origin": "64501"}]}})
        if "binarydefense.com/banlist" in url:
            return _Resp(t="# banlist\n1.2.3.4\n9.9.9.9\n")
        if "type=TLSA" in url and "_25._tcp" in url:
            return _Resp(j={"Answer": [{"type": 52, "data": "3 1 1 abcd"}]})
        if "type=TLSA" in url:
            return _Resp(j={})
        if "hackertarget.com/pagelinks" in url:
            return _Resp(t="https://acme.com/a\nhttps://cdn.jsdelivr.net/x\n"
                         "https://www.acme.com/b\nhttps://google-analytics.com/g")
        return _Resp(j={})
    rp = REGISTRY["rpki"].run("1.2.3.4", _ctx(router)).data
    assert rp["status"] == "invalid" and rp["severity"] == "high" and rp["asn"] == "64500"
    bd = REGISTRY["binarydefense"].run("1.2.3.4", _ctx(router)).data
    assert bd["listed"] is True and bd["severity"] == "high"
    dn = REGISTRY["danetlsa"].run("acme.com", _ctx(router)).data
    assert dn["dane_enabled"] is True and dn["tlsa_by_service"].get("SMTP") == 1
    pl = REGISTRY["pagelinks"].run("acme.com", _ctx(router)).data
    assert "cdn.jsdelivr.net" in pl["external_domains"] and "www.acme.com" in pl["internal_hosts"]


def test_wave37_phisharmy_ipsum_ghkeys_ghorgs():
    def router(url):
        if "phishing.army" in url:
            return _Resp(t="# blocklist\nevil.com\nacme.com\n")
        if "ipsum/master/ipsum.txt" in url:
            return _Resp(t="# IPsum\n6.6.6.6\t7\n1.2.3.4\t3\n")
        if "github.com/alice.keys" in url:
            return _Resp(t="ssh-ed25519 AAAAC3NzaC1lZDI1 comment\nssh-rsa AAAAB3Nza")
        if "api.github.com/users/alice/orgs" in url:
            return _Resp(j=[{"login": "acme-corp", "description": "Acme"},
                            {"login": "oss-foundation", "description": "OSS"}])
        return _Resp(j={})
    pa = REGISTRY["phisharmy"].run("acme.com", _ctx(router)).data
    assert pa["listed"] is True and pa["severity"] == "critical"
    ip = REGISTRY["ipsum"].run("1.2.3.4", _ctx(router)).data
    assert ip["blocklist_hits"] == 3 and ip["severity"] == "high"
    gk = REGISTRY["githubkeys"].run("alice", _ctx(router)).data
    assert gk["key_count"] == 2 and "ssh-ed25519" in gk["key_types"]
    go = REGISTRY["githuborgs"].run("alice", _ctx(router)).data
    assert go["count"] == 2 and go["orgs"][0]["login"] == "acme-corp"


def test_wave38_httpsrr_srvscan_cinsarmy_stackuser():
    def router(url):
        if "type=HTTPS" in url or "type=65" in url:
            return _Resp(j={"Answer": [{"data": '1 . alpn="h2,h3" ech=AED+ ipv4hint=1.2.3.4'}]})
        if "_sip._tcp" in url and "type=SRV" in url:
            return _Resp(j={"Answer": [{"type": 33, "data": "10 5 5060 sip.acme.com."}]})
        if "type=SRV" in url:
            return _Resp(j={})
        if "cinsscore.com" in url:
            return _Resp(t="1.2.3.4\n9.9.9.9\n")
        if "api.stackexchange.com" in url:
            return _Resp(j={"items": [{"display_name": "Alice", "user_id": 42,
                            "reputation": 12000, "location": "NYC",
                            "link": "https://stackoverflow.com/users/42"}]})
        return _Resp(j={})
    hr = REGISTRY["httpsrr"].run("acme.com", _ctx(router)).data
    assert hr["ech_enabled"] is True and "h3" in hr["alpn"]
    sr = REGISTRY["srvscan"].run("acme.com", _ctx(router)).data
    assert sr["count"] == 1 and sr["services"][0]["service"] == "SIP/VoIP"
    assert "sip.acme.com:5060" in sr["services"][0]["targets"]
    ca = REGISTRY["cinsarmy"].run("1.2.3.4", _ctx(router)).data
    assert ca["listed"] is True and ca["severity"] == "high"
    su = REGISTRY["stackuser"].run("Alice", _ctx(router)).data
    assert su["count"] == 1 and su["matches"][0]["reputation"] == 12000


def test_wave39_domainptr_mozobs_firehol_ghrepos():
    def router(url):
        if "type=A" in url and "in-addr.arpa" not in url:
            return _Resp(j={"Answer": [{"type": 1, "data": "1.2.3.4"}]})
        if "type=AAAA" in url:
            return _Resp(j={})
        if "in-addr.arpa" in url and "type=PTR" in url:
            return _Resp(j={"Answer": [{"type": 12, "data": "server-1.cloudprovider.net."}]})
        if "http-observatory" in url:
            return _Resp(j={"state": "FINISHED", "grade": "D+", "score": 35,
                            "tests_failed": 5, "tests_passed": 7})
        if "firehol" in url:
            return _Resp(t="# firehol\n1.2.0.0/16\n8.8.8.0/24\n")
        if "api.github.com/users/alice/repos" in url:
            return _Resp(j=[{"name": "tool", "language": "Python",
                             "stargazers_count": 3, "pushed_at": "2024-05-01"},
                            {"name": "web", "language": "Python",
                             "stargazers_count": 1, "pushed_at": "2024-04-01"}])
        return _Resp(j={})
    dp = REGISTRY["domainptr"].run("acme.com", _ctx(router)).data
    assert "1.2.3.4" in dp["ips"] and dp["ptr"]["1.2.3.4"][0] == "server-1.cloudprovider.net"
    assert dp["shared_hosting_hint"] is True
    mo = REGISTRY["mozillaobs"].run("acme.com", _ctx(router)).data
    assert mo["grade"] == "D+" and mo["severity"] == "medium"
    fh = REGISTRY["firehol"].run("1.2.3.4", _ctx(router)).data
    assert fh["listed"] is True and fh["matched_range"] == "1.2.0.0/16" and fh["severity"] == "high"
    gr = REGISTRY["githubrepos"].run("alice", _ctx(router)).data
    assert gr["repo_count"] == 2 and "Python" in gr["languages"]


def test_wave40_stevenblack_certemails_firehol2_dockerrepos():
    def router(url):
        if "StevenBlack" in url:
            return _Resp(t="# hosts\n0.0.0.0 evil.com\n0.0.0.0 acme.com\n")
        if "crt.sh" in url:
            return _Resp(j=[{"common_name": "acme.com",
                             "name_value": "admin@acme.com\nwww.acme.com"},
                            {"name_value": "security@acme.com"}])
        if "firehol_level2" in url:
            return _Resp(t="# fh2\n1.2.0.0/16\n")
        if "hub.docker.com/v2/repositories/alice" in url:
            return _Resp(j={"count": 1, "results": [{"name": "internal-api",
                            "pull_count": 500, "star_count": 3,
                            "last_updated": "2024-01-01"}]})
        return _Resp(j={})
    sb = REGISTRY["stevenblack"].run("acme.com", _ctx(router)).data
    assert sb["listed"] is True and sb["severity"] == "medium"
    ce = REGISTRY["certemails"].run("acme.com", _ctx(router)).data
    assert "admin@acme.com" in ce["emails"] and "security@acme.com" in ce["emails"] and ce["count"] == 2
    fh = REGISTRY["firehol2"].run("1.2.3.4", _ctx(router)).data
    assert fh["listed"] is True and fh["matched_range"] == "1.2.0.0/16"
    dr = REGISTRY["dockerrepos"].run("alice", _ctx(router)).data
    assert dr["count"] == 1 and dr["images"][0]["name"] == "alice/internal-api"


def test_wave41_entrustct_geodb_codeberg_gitlabprojects():
    def router(url):
        if "ctsearch.entrust.com" in url:
            return _Resp(j=[{"subjectAltName": "www.acme.com vpn.acme.com evil.com"},
                            {"subjectAltName": ["mail.acme.com"]}])
        if "geolocation-db.com" in url:
            return _Resp(j={"country_name": "United States", "country_code": "US",
                            "state": "NY", "city": "New York", "postal": "10001"})
        if "codeberg.org/api/v1/users/alice" in url:
            return _Resp(j={"login": "alice", "full_name": "Alice A",
                            "location": "NYC", "followers_count": 12,
                            "html_url": "https://codeberg.org/alice"})
        if "gitlab.com/api/v4/users?username=alice" in url:
            return _Resp(j=[{"id": 77}])
        if "gitlab.com/api/v4/users/77/projects" in url:
            return _Resp(j=[{"path_with_namespace": "alice/tool",
                             "description": "cli", "star_count": 4,
                             "web_url": "https://gitlab.com/alice/tool"}])
        return _Resp(j={})
    ec = REGISTRY["entrustct"].run("acme.com", _ctx(router)).data
    assert "www.acme.com" in ec["subdomains"] and "mail.acme.com" in ec["subdomains"]
    assert "evil.com" not in ec["subdomains"]
    gd = REGISTRY["geolocationdb"].run("1.2.3.4", _ctx(router)).data
    assert gd["city"] == "New York" and gd["country_code"] == "US"
    cb = REGISTRY["codeberguser"].run("alice", _ctx(router)).data
    assert cb["login"] == "alice" and cb["followers"] == 12
    gp = REGISTRY["gitlabprojects"].run("alice", _ctx(router)).data
    assert gp["user_id"] == 77 and gp["count"] == 1 and gp["projects"][0]["path"] == "alice/tool"


def test_wave42_mobileapps_adstxt_geoip_ghsocials():
    def router(url):
        if "assetlinks.json" in url:
            return _Resp(j=[{"target": {"package_name": "com.acme.app"}}])
        if "apple-app-site-association" in url:
            return _Resp(j={"applinks": {"details": [{"appID": "TEAMID.com.acme.ios"}]}})
        if "/ads.txt" in url:
            return _Resp(t="google.com, pub-123, DIRECT\n# comment\nrubicon.com, 456, RESELLER")
        if "reallyfreegeoip.org" in url:
            return _Resp(j={"country_name": "United States", "country_code": "US",
                            "city": "NYC", "latitude": 40.7, "longitude": -74.0})
        if "api.github.com/users/alice/social_accounts" in url:
            return _Resp(j=[{"provider": "twitter", "url": "https://twitter.com/alice"},
                            {"provider": "mastodon", "url": "https://m.social/@alice"}])
        return _Resp(j={})
    ma = REGISTRY["mobileapps"].run("acme.com", _ctx(router)).data
    assert "com.acme.app" in ma["android_packages"] and "TEAMID.com.acme.ios" in ma["ios_app_ids"]
    at = REGISTRY["adstxt"].run("acme.com", _ctx(router)).data
    assert "google.com" in at["ad_partners"] and "rubicon.com" in at["ad_partners"]
    rg = REGISTRY["reallyfreegeoip"].run("1.2.3.4", _ctx(router)).data
    assert rg["city"] == "NYC" and rg["country_code"] == "US"
    gs = REGISTRY["githubsocials"].run("alice", _ctx(router)).data
    assert gs["count"] == 2 and "twitter" in gs["providers"] and "mastodon" in gs["providers"]


def test_wave43_keybase_ghemail_sitemap_humans():
    def router(url):
        if "keybase.io" in url:
            return _Resp(j={"them": [{"proofs_summary": {"all": [
                {"proof_type": "twitter", "nametag": "alice",
                 "service_url": "https://twitter.com/alice"},
                {"proof_type": "github", "nametag": "alice-gh",
                 "service_url": "https://github.com/alice-gh"}]}}]})
        if "api.github.com/users/alice/events" in url:
            return _Resp(j=[{"type": "PushEvent", "payload": {"commits": [
                {"author": {"email": "alice@acme.com", "name": "Alice"}},
                {"author": {"email": "1234+alice@users.noreply.github.com", "name": "Alice"}}]}}])
        if "/sitemap.xml" in url:
            return _Resp(t="<urlset><url><loc>https://acme.com/admin</loc></url>"
                         "<url><loc>https://acme.com/blog/x</loc></url></urlset>")
        if "/humans.txt" in url:
            return _Resp(t="/* TEAM */\nName: Jane Doe\nSite: @jane_d")
        return _Resp(j={})
    kb = REGISTRY["keybaseuser"].run("alice", _ctx(router)).data
    assert kb["count"] == 2 and "twitter" in kb["services"] and "github" in kb["services"]
    ge = REGISTRY["githubemail"].run("alice", _ctx(router)).data
    assert ge["count"] == 1 and ge["emails"][0]["email"] == "alice@acme.com"
    sm = REGISTRY["sitemapscan"].run("acme.com", _ctx(router)).data
    assert sm["urls_found"] == 2 and "/admin" in sm["sample_paths"]
    hu = REGISTRY["humanstxt"].run("acme.com", _ctx(router)).data
    assert hu["present"] is True and "Jane Doe" in hu["names"] and "@jane_d" in hu["handles"]


class _RespH(_Resp):
    def __init__(self, j=None, t="", code=200, headers=None):
        super().__init__(j, t, code)
        self.headers = headers or {}


def test_wave44_csp_crossdomain_tor_lobsters():
    def router(url):
        if url.rstrip("/") == "https://acme.com":
            return _RespH(t="<html>ok</html>",
                          headers={"Content-Security-Policy":
                                   "default-src 'self'; script-src cdn.jsdelivr.net *.acme.com"})
        if "crossdomain.xml" in url:
            return _Resp(t='<cross-domain-policy><allow-access-from domain="*.partner.com"/>'
                         '<allow-access-from domain="*"/></cross-domain-policy>')
        if "clientaccesspolicy" in url:
            return _Resp(t="")
        if "onionoo.torproject.org" in url:
            return _Resp(j={"relays": [{"nickname": "acmeRelay",
                            "flags": ["Running", "Exit", "Guard"],
                            "country": "de", "or_addresses": ["1.2.3.4:9001"]}]})
        if "lobste.rs" in url:
            return _Resp(j={"username": "alice", "karma": 500,
                            "github_username": "alice-gh", "created_at": "2015"})
        return _Resp(j={})
    cd = REGISTRY["cspdomains"].run("acme.com", _ctx(router)).data
    assert cd["csp_present"] is True and "cdn.jsdelivr.net" in cd["external_domains"]
    cx = REGISTRY["crossdomain"].run("acme.com", _ctx(router)).data
    assert cx["wildcard_trust"] is True and "*.partner.com" in cx["allowed_domains"]
    tn = REGISTRY["tornodes"].run("1.2.3.4", _ctx(router)).data
    assert tn["is_tor_relay"] is True and tn["is_exit"] is True and tn["country"] == "de"
    lb = REGISTRY["lobsters"].run("alice", _ctx(router)).data
    assert lb["karma"] == 500 and lb["github"] == "alice-gh"


def test_wave45_idp_jsassets_vpnapi_ghgpg():
    def router(url):
        if "openid-configuration" in url and url.startswith("https://acme.com"):
            return _Resp(j={"issuer": "https://acme.okta.com",
                            "authorization_endpoint": "https://acme.okta.com/oauth2/v1/authorize",
                            "token_endpoint": "https://acme.okta.com/oauth2/v1/token",
                            "scopes_supported": ["openid", "email"],
                            "grant_types_supported": ["authorization_code"]})
        if url.rstrip("/") == "https://acme.com":
            return _Resp(t='<html><script src="https://cdn.jsdelivr.net/x.js"></script>'
                         '<script src="/local.js"></script></html>')
        if "vpnapi.io" in url:
            return _Resp(j={"security": {"vpn": True, "proxy": False, "tor": False, "relay": False},
                            "location": {"country": "US"},
                            "network": {"autonomous_system_number": "AS64500",
                                        "autonomous_system_organization": "VPNCo"}})
        if "api.github.com/users/alice/gpg_keys" in url:
            return _Resp(j=[{"key_id": "ABCD1234",
                             "emails": [{"email": "alice@acme.com", "verified": True}]}])
        return _Resp(j={})
    idp = REGISTRY["idpfinger"].run("acme.com", _ctx(router)).data
    assert idp["idp_found"] is True and idp["vendor"] == "Okta"
    js = REGISTRY["jsassets"].run("acme.com", _ctx(router)).data
    assert "cdn.jsdelivr.net" in js["external_js_domains"] and js["script_count"] == 2
    vp = REGISTRY["vpnapi"].run("1.2.3.4", _ctx(router)).data
    assert vp["vpn"] is True and vp["anonymized"] is True and vp["severity"] == "medium"
    gg = REGISTRY["githubgpg"].run("alice", _ctx(router)).data
    assert "alice@acme.com" in gg["emails"] and "ABCD1234" in gg["key_ids"]


def test_wave46_trackers_preconnects_firehol3_bitbucketws():
    def router(url):
        if url.rstrip("/") == "https://acme.com":
            return _Resp(t='<html><head>'
                         '<script src="https://www.googletagmanager.com/gtm.js?id=GTM-ABCD"></script>'
                         '<script>gtag("config","G-XY12345Z")</script>'
                         '<link rel="preconnect" href="https://fonts.gstatic.com">'
                         '<link rel="dns-prefetch" href="//cdn.acme.com">'
                         '</head></html>')
        if "firehol_level3" in url:
            return _Resp(t="# fh3\n1.2.0.0/16\n")
        if "api.bitbucket.org/2.0/workspaces/acme" in url:
            return _Resp(j={"slug": "acme", "name": "Acme Team", "is_private": False,
                            "created_on": "2018-01-01",
                            "links": {"html": {"href": "https://bitbucket.org/acme/"}}})
        return _Resp(j={})
    tr = REGISTRY["trackers"].run("acme.com", _ctx(router)).data
    assert "Google Tag Manager" in tr["trackers"] and "GTM-ABCD" in tr["analytics_ids"]
    assert "G-XY12345Z" in tr["analytics_ids"]
    pc = REGISTRY["preconnects"].run("acme.com", _ctx(router)).data
    assert "fonts.gstatic.com" in pc["preconnect_domains"]
    fh = REGISTRY["firehol3"].run("1.2.3.4", _ctx(router)).data
    assert fh["listed"] is True and fh["matched_range"] == "1.2.0.0/16"
    bw = REGISTRY["bitbucketws"].run("acme", _ctx(router)).data
    assert bw["slug"] == "acme" and bw["is_private"] is False


def test_wave47_wpusers_feeds_dshieldnet_ghfollowers():
    def router(url):
        if "/wp-json/wp/v2/users" in url:
            return _Resp(j=[{"id": 1, "name": "Admin User", "slug": "admin",
                             "link": "https://acme.com/author/admin"}])
        if "/feed" in url and "wp-json" not in url:
            return _Resp(t='<rss><channel><item><dc:creator>Jane Doe</dc:creator>'
                         '<author>jane@acme.com</author></item></channel></rss>')
        if "dshield.netset" in url:
            return _Resp(t="# dshield\n1.2.0.0/24\n")
        if "api.github.com/users/alice/followers" in url:
            return _Resp(j=[{"login": "bob"}, {"login": "carol"}])
        if "api.github.com/users/alice/following" in url:
            return _Resp(j=[{"login": "dave"}])
        return _Resp(j={})
    wp = REGISTRY["wpusers"].run("acme.com", _ctx(router)).data
    assert "admin" in wp["usernames"] and wp["count"] == 1 and wp["wordpress"] is True
    fd = REGISTRY["feeds"].run("acme.com", _ctx(router)).data
    assert "Jane Doe" in fd["authors"] and "jane@acme.com" in fd["emails"]
    ds = REGISTRY["dshieldnet"].run("1.2.0.5", _ctx(router)).data
    assert ds["listed"] is True and ds["matched_range"] == "1.2.0.0/24" and ds["severity"] == "high"
    gf = REGISTRY["githubfollowers"].run("alice", _ctx(router)).data
    assert "bob" in gf["followers_sample"] and "dave" in gf["following_sample"]


def test_wave48_openapi_wpstack_talos_ghstars():
    def router(url):
        if "/openapi.json" in url:
            return _Resp(j={"openapi": "3.0.0", "info": {"title": "Acme API", "version": "1.2"},
                            "paths": {"/users": {}, "/orders": {}}})
        if url.rstrip("/") == "https://acme.com":
            return _Resp(t='<html><link href="/wp-content/plugins/woocommerce/style.css?ver=8.1">'
                         '<script src="/wp-content/themes/storefront/app.js"></script>'
                         '/wp-includes/js/x.js</html>')
        if "talosintelligence.com" in url:
            return _Resp(t="1.2.3.4\n9.9.9.9\n")
        if "api.github.com/users/alice/starred" in url:
            return _Resp(j=[{"full_name": "torvalds/linux", "language": "C"},
                            {"full_name": "acme/tool", "language": "C"}])
        return _Resp(j={})
    oa = REGISTRY["openapi"].run("acme.com", _ctx(router)).data
    assert oa["spec_found"] is True and oa["endpoint_count"] == 2 and oa["title"] == "Acme API"
    ws = REGISTRY["wpstack"].run("acme.com", _ctx(router)).data
    assert ws["wordpress"] is True
    slugs = [p["slug"] for p in ws["plugins"]]
    assert "woocommerce" in slugs
    tl = REGISTRY["talos"].run("1.2.3.4", _ctx(router)).data
    assert tl["listed"] is True and tl["severity"] == "high"
    gs = REGISTRY["githubstars"].run("alice", _ctx(router)).data
    assert "torvalds/linux" in gs["starred_sample"] and "C" in gs["top_interests"]


def test_wave49_manifest_cnamemap_spamhausdrop_cratesuser():
    def router(url):
        if "/manifest.json" in url:
            return _Resp(j={"name": "Acme App", "short_name": "Acme",
                            "start_url": "/", "related_applications": [
                                {"platform": "play", "id": "com.acme.app"}]})
        if "type=CNAME" in url and "name=acme.com" in url:
            return _Resp(j={"Answer": [{"type": 5, "data": "acme.myshopify.com."}]})
        if "type=CNAME" in url:
            return _Resp(j={"Answer": [{"type": 5, "data": "d123.cloudfront.net."}]})
        if "spamhaus.org/drop" in url:
            return _Resp(t="; comment\n1.2.0.0/16 ; SBL123\n")
        if "crates.io/api/v1/users/alice" in url:
            return _Resp(j={"user": {"login": "alice", "name": "Alice A",
                            "url": "https://crates.io/users/alice"}})
        return _Resp(j={})
    mf = REGISTRY["manifest"].run("acme.com", _ctx(router)).data
    assert mf["found"] is True and mf["name"] == "Acme App"
    assert mf["related_applications"][0]["id"] == "com.acme.app"
    cm = REGISTRY["cnamemap"].run("acme.com", _ctx(router)).data
    assert "Shopify" in cm["hosting"] and "AWS CloudFront" in cm["hosting"]
    sd = REGISTRY["spamhausdrop"].run("1.2.3.4", _ctx(router)).data
    assert sd["listed"] is True and sd["severity"] == "critical" and sd["matched_range"] == "1.2.0.0/16"
    cu = REGISTRY["cratesuser"].run("alice", _ctx(router)).data
    assert cu["login"] == "alice" and cu["name"] == "Alice A"


def test_wave50_matrix_wikipedia_hnauthor_techniknews():
    def router(url):
        if "/.well-known/matrix/server" in url:
            return _Resp(j={"m.server": "matrix.acme.com:8448"})
        if "/.well-known/matrix/client" in url:
            return _Resp(j={"m.homeserver": {"base_url": "https://matrix.acme.com"}})
        if "list=users" in url:
            return _Resp(j={"query": {"users": [{"name": "Alice", "editcount": 4200,
                            "registration": "2010-01-01T00:00:00Z",
                            "groups": ["*", "user", "sysop"]}]}})
        if "hn.algolia.com" in url:
            return _Resp(j={"nbHits": 2, "hits": [
                {"title": "Show HN: Acme", "url": "https://acme.com", "points": 50,
                 "created_at": "2024-01-01", "objectID": "1"}]})
        if "api.techniknews.net" in url:
            return _Resp(j={"status": "success", "country": "US", "city": "NYC",
                            "isp": "AcmeISP", "as": "AS64500", "proxy": False})
        return _Resp(j={})
    mx = REGISTRY["matrixsrv"].run("acme.com", _ctx(router)).data
    assert mx["matrix"] is True and mx["delegated_server"] == "matrix.acme.com:8448"
    wu = REGISTRY["wikipediauser"].run("Alice", _ctx(router)).data
    assert wu["editcount"] == 4200 and "sysop" in wu["groups"]
    ha = REGISTRY["hnauthor"].run("alice", _ctx(router)).data
    assert ha["total"] == 2 and ha["activity"][0]["title"] == "Show HN: Acme"
    tn = REGISTRY["techniknews"].run("1.2.3.4", _ctx(router)).data
    assert tn["asn"] == "AS64500" and tn["city"] == "NYC"


def test_wave51_nodeinfo_phishdb_asnupstreams_launchpad():
    def router(url):
        if "/.well-known/nodeinfo" in url:
            return _Resp(j={"links": [{"href": "https://acme.com/nodeinfo/2.0"}]})
        if "/nodeinfo/2.0" in url:
            return _Resp(j={"software": {"name": "mastodon", "version": "4.2.1"},
                            "usage": {"users": {"total": 1500}},
                            "openRegistrations": True})
        if "Phishing.Database" in url:
            return _Resp(t="# phishing\nevil.test\nacme.com\n")
        if "api.bgpview.io/ip/" in url:
            return _Resp(j={"data": {"prefixes": [{"asn": {"asn": 64500}}]}})
        if "api.bgpview.io/asn/64500/upstreams" in url:
            return _Resp(j={"data": {"ipv4_upstreams": [
                {"asn": 3356, "name": "Level3"}, {"asn": 174, "name": "Cogent"}]}})
        if "api.launchpad.net" in url:
            return _Resp(j={"name": "alice", "display_name": "Alice A",
                            "karma": 250, "is_valid": True,
                            "web_link": "https://launchpad.net/~alice"})
        return _Resp(j={})
    ni = REGISTRY["nodeinfo"].run("acme.com", _ctx(router)).data
    assert ni["software"] == "mastodon" and ni["total_users"] == 1500
    pd = REGISTRY["phishdb"].run("acme.com", _ctx(router)).data
    assert pd["listed"] is True and pd["severity"] == "critical"
    au = REGISTRY["asnupstreams"].run("1.2.3.4", _ctx(router)).data
    assert au["asn"] == 64500 and au["upstream_count"] == 2
    assert au["upstreams"][0]["asn"] == 3356
    lp = REGISTRY["launchpad"].run("alice", _ctx(router)).data
    assert lp["display_name"] == "Alice A" and lp["karma"] == 250
