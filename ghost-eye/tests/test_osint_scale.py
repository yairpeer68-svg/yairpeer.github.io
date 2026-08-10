"""Tests for the data-driven OSINT-at-scale engine.

Covers the registry loader (native + Sherlock + WhatsMyName schemas), the
username/email modules, the canary false-positive guard, and the correlation +
graph integration — so the engine that turns one module into thousands of
sources keeps behaving as designed.
"""

from __future__ import annotations

import json

import pytest

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye import registry_data as rd
from ghost_eye.core import Context, REGISTRY, Result
from ghost_eye.intelligence.correlation import correlate
from ghost_eye.intelligence.graph import build_graph


# --------------------------------------------------------------------------- #
#  fake HTTP
# --------------------------------------------------------------------------- #
class _Resp:
    def __init__(self, code, text="", url="", history=None):
        self.status_code = code
        self.text = text
        self.url = url
        self.history = history or []

    def json(self):
        return json.loads(self.text) if self.text else {}


class _Sess:
    def __init__(self, mapper):
        self.mapper = mapper
        self.headers = {}

    def get(self, url, **kw):
        return self.mapper(url)


class _Cfg:
    def get(self, _k, d=None):
        return d


def _ctx(mapper):
    return Context(config=_Cfg(), session=_Sess(mapper), threads=8, timeout=3)


# --------------------------------------------------------------------------- #
#  registry loader — all three schemas
# --------------------------------------------------------------------------- #
class TestRegistryLoader:
    def test_shipped_registry_loads(self):
        sites = rd.load_sites("username")
        assert len(sites) >= 100, "curated registry unexpectedly small"
        assert all(s.url and ("{u}" in s.url or "{}" in s.url or "{account}" in s.url)
                   for s in sites)

    def test_native_schema(self):
        doc = {"registry": [{"name": "X", "url": "https://x/{u}", "check": "status",
                             "cat": "test"}]}
        sites = rd.normalise(doc)
        assert sites[0].name == "X" and sites[0].build("bob") == "https://x/bob"

    def test_sherlock_schema(self):
        doc = {"Foo": {"url": "https://foo/{}", "errorType": "status_code",
                       "errorCode": 404},
               "Bar": {"url": "https://bar/{}", "errorType": "message",
                       "errorMsg": "Not Found"}}
        sites = {s.name: s for s in rd.normalise(doc)}
        assert sites["Foo"].check == "status" and sites["Foo"].absent_code == 404
        assert sites["Bar"].check == "message"
        assert "Not Found" in sites["Bar"].absent_strings
        assert sites["Foo"].build("joe") == "https://foo/joe"

    def test_whatsmyname_schema(self):
        doc = {"sites": [{"name": "Y", "uri_check": "https://y/{account}",
                          "e_string": "exists", "e_code": 200, "cat": "social"}]}
        sites = rd.normalise(doc)
        assert sites[0].check == "message"
        assert "exists" in sites[0].present_strings
        assert sites[0].build("ann") == "https://y/ann"

    def test_external_dataset_via_env(self, tmp_path, monkeypatch):
        # the whole point: point at a Sherlock file and get its sites
        big = {f"S{i}": {"url": f"https://s{i}/{{}}", "errorType": "status_code"}
               for i in range(2500)}
        f = tmp_path / "data.json"
        f.write_text(json.dumps(big))
        monkeypatch.setenv("GHOSTEYE_USERNAME_SITES", str(f))
        rd._CACHE.clear()
        sites = rd.load_sites("username")
        assert len(sites) == 2500
        rd._CACHE.clear()


# --------------------------------------------------------------------------- #
#  username enumeration + canary guard
# --------------------------------------------------------------------------- #
class TestUsernameScan:
    def _mapper(self):
        def mapper(url):
            u = url.lower()
            if "about.me" in u:                 # always-200 placeholder site
                return _Resp(200, url=url)
            if "github.com/alice" in u or "reddit.com/user/alice" in u:
                return _Resp(200, url=url)
            if "github.com/" in u or "reddit.com/user/" in u:
                return _Resp(404, url=url)
            if "news.ycombinator" in u:
                return _Resp(200, text=("No such user." if "id=alice" not in u
                                        else "karma 10"), url=url)
            return _Resp(404, url=url)
        return mapper

    def test_finds_real_accounts(self):
        res = REGISTRY["usernamescan"].run("alice", _ctx(self._mapper()))
        assert res.status == "ok"
        found = {h["site"] for h in res.data["found_on"]}
        assert "GitHub" in found and "Reddit" in found

    def test_canary_drops_always_200_sites(self):
        res = REGISTRY["usernamescan"].run("alice", _ctx(self._mapper()))
        # About.me answers 200 for the canary too -> must be dropped
        assert "About.me" not in {h["site"] for h in res.data["found_on"]}
        assert "About.me" in res.data["dropped_as_false_positive"]

    def test_message_based_detection(self):
        res = REGISTRY["usernamescan"].run("alice", _ctx(self._mapper()))
        # HackerNews returns "No such user." for absent, karma text for present
        assert "HackerNews" in {h["site"] for h in res.data["found_on"]}

    def test_invalid_username_rejected(self):
        res = REGISTRY["usernamescan"].run("has spaces!", _ctx(self._mapper()))
        assert res.status == "error"

    def test_offline_all_404_finds_nothing(self):
        res = REGISTRY["usernamescan"].run("nobody", _ctx(lambda u: _Resp(404, url=u)))
        assert res.status == "ok" and res.data["found_count"] == 0


class TestUsernameVariants:
    def test_generates_and_bounds_variants(self):
        res = REGISTRY["usernamevariants"].run("john.doe",
                                               _ctx(lambda u: _Resp(404, url=u)))
        assert res.status == "ok"
        variants = res.data["variants_generated"]
        assert "john.doe" in variants
        assert "johndoe" in variants          # separator-stripped
        assert len(variants) <= 25


class TestEmailFootprint:
    def test_gravatar_profile_parsed(self):
        profile = {"entry": [{"displayName": "Alice",
                              "accounts": [{"shortname": "twitter",
                                            "username": "alice",
                                            "url": "https://x.com/alice"}]}]}

        def mapper(url):
            if url.endswith(".json"):
                return _Resp(200, text=json.dumps(profile), url=url)
            return _Resp(200, url=url)     # avatar exists
        res = REGISTRY["emailfootprint"].run("alice@example.com", _ctx(mapper))
        assert res.status == "ok"
        assert res.data["gravatar_avatar"] is True
        assert res.data["linked_accounts"][0]["service"] == "twitter"

    def test_invalid_email_rejected(self):
        res = REGISTRY["emailfootprint"].run("not-an-email",
                                             _ctx(lambda u: _Resp(404)))
        assert res.status == "error"

    def test_no_footprint(self):
        res = REGISTRY["emailfootprint"].run("ghost@nowhere.example",
                                             _ctx(lambda u: _Resp(404, url=u)))
        assert res.status == "ok" and res.data["footprint_signals"] == 0


# --------------------------------------------------------------------------- #
#  correlation + graph integration
# --------------------------------------------------------------------------- #
class TestProfileGraph:
    def _results(self):
        return [
            Result("Username enumeration at scale (data-driven)", "alice", data={
                "username": "alice",
                "found_on": [
                    {"site": "GitHub", "url": "https://github.com/alice",
                     "confidence": "high"},
                    {"site": "Reddit", "url": "https://reddit.com/user/alice",
                     "confidence": "high"}]}),
            Result("Email footprint (Gravatar/Libravatar)", "alice@x.com", data={
                "email": "alice@x.com",
                "linked_accounts": [{"service": "twitter", "username": "alice",
                                     "url": "https://x.com/alice"}]}),
        ]

    def test_profiles_reach_correlation(self):
        intel = correlate(self._results(), "alice")
        assert intel["counts"]["profiles"] == 3
        sites = {p["site"] for p in intel["profiles"]}
        assert {"GitHub", "Reddit", "twitter"} <= sites

    def test_profiles_become_graph_nodes(self):
        intel = correlate(self._results(), "alice")
        graph = build_graph(intel)
        profile_nodes = {n["label"] for n in graph["nodes"]
                         if n["kind"] == "profile"}
        assert "GitHub" in profile_nodes and "Reddit" in profile_nodes


# --------------------------------------------------------------------------- #
#  curated registry data quality (feature 80: data-driven regression)
# --------------------------------------------------------------------------- #
class TestRegistryDataQuality:
    def test_every_shipped_site_is_well_formed(self):
        sites = rd.load_sites("username")
        for s in sites:
            assert s.name, "site with no name"
            assert "{u}" in s.url or "{}" in s.url or "{account}" in s.url, \
                f"{s.name}: url has no username placeholder"
            assert s.check in ("status", "message", "redirect"), \
                f"{s.name}: bad check type {s.check!r}"
            assert s.url.startswith("http"), f"{s.name}: url not absolute"

    def test_no_duplicate_sites(self):
        sites = rd.load_sites("username")
        names = [s.name for s in sites]
        assert len(names) == len(set(names)), "duplicate site names in registry"
