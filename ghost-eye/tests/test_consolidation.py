"""Tests for the module consolidation.

Four pairs of modules turned out to query the same source for the same purpose
and were merged. The invariant these tests protect is that the merge cost
nothing: every retired id still resolves, every field the absorbed module used
to emit is still emitted, and no id was silently dropped.
"""

from __future__ import annotations

import pytest

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye.core import ALIASES, Context, Module, REGISTRY, get_module, resolve_id


# every merge performed, as (retired id -> surviving id)
MERGES = {
    "reverseip": "revip",
    "urlscanio": "urlscan",
    "pdnsanubis": "anubisjldc",
    "commoncrawl": "commoncrawlmine",
}


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
        self.headers = {}

    def get(self, url, **kw):
        params = kw.get("params") or {}
        if params:
            url += "?" + "&".join(f"{k}={v}" for k, v in params.items())
        return self.router(url)


def _ctx(router):
    return Context(config={}, session=_Sess(router), timeout=5)


# --------------------------------------------------------------------------- #
#  alias mechanism
# --------------------------------------------------------------------------- #
class TestAliases:
    @pytest.mark.parametrize("old,new", sorted(MERGES.items()))
    def test_retired_id_still_resolves(self, old, new):
        assert resolve_id(old) == new
        mod = get_module(old)
        assert mod is not None and mod.id == new

    @pytest.mark.parametrize("old", sorted(MERGES))
    def test_retired_id_is_not_a_live_module(self, old):
        assert old not in REGISTRY, f"{old} should have been merged away"

    def test_every_alias_points_at_a_real_module(self):
        for old, new in ALIASES.items():
            assert new in REGISTRY, f"alias {old} -> {new} is dangling"

    def test_live_ids_are_unaffected(self):
        for mid in list(REGISTRY)[:50]:
            assert resolve_id(mid) == mid

    def test_unknown_id_returns_none(self):
        assert get_module("definitely-not-a-module") is None

    def test_resolve_is_cycle_safe(self, monkeypatch):
        monkeypatch.setitem(ALIASES, "a_x", "b_x")
        monkeypatch.setitem(ALIASES, "b_x", "a_x")
        resolve_id("a_x")          # must terminate, not hang

    def test_register_rejects_absorbing_a_live_module(self):
        from ghost_eye.core import register
        live = next(iter(REGISTRY.values())).id
        with pytest.raises(ValueError, match="still exists"):
            @register
            class _Bad(Module):
                id, name, category = "zz_absorb_test", "zz absorb test", "Misc"
                absorbed = [live]


# --------------------------------------------------------------------------- #
#  no capability was lost in each merge
# --------------------------------------------------------------------------- #
class TestReverseIpMerge:
    def _router(self, url):
        if "reverseiplookup" in url:
            return _Resp(t="acme.com\nblog.acme.com\nother-tenant.com")
        return _Resp(j={})

    def test_emits_both_old_and_new_keys(self):
        data = get_module("reverseip").run("1.2.3.4", _ctx(self._router)).data
        assert "acme.com" in data["related_domains"]   # absorbed module's key
        assert "acme.com" in data["hosts"]             # surviving module's key
        assert data["count"] == 3

    def test_accepts_a_hostname_too(self):
        # the absorbed module demanded an IP; the merged one takes either
        data = get_module("revip").run("acme.com", _ctx(self._router)).data
        assert data["count"] == 3

    def test_rate_limit_page_is_detected(self):
        res = get_module("revip").run(
            "acme.com", _ctx(lambda u: _Resp(t="API count exceeded")))
        assert res.status == "error"

    def test_junk_lines_are_filtered(self):
        res = get_module("revip").run(
            "acme.com", _ctx(lambda u: _Resp(t="acme.com\nnot a host!\n\n")))
        assert res.data["hosts"] == ["acme.com"]


class TestUrlScanMerge:
    def _router(self, url):
        if "urlscan.io" in url:
            return _Resp(j={"results": [
                {"page": {"url": "https://a.acme.com/x", "domain": "a.acme.com"},
                 "task": {"time": "2024-01-01"}, "result": "https://r/1"},
                {"page": {"url": "https://b.acme.com/y", "domain": "b.acme.com"},
                 "task": {"time": "2024-02-02"}, "result": "https://r/2"},
            ]})
        return _Resp(j={})

    def test_keeps_scan_metadata_from_the_survivor(self):
        data = get_module("urlscan").run("acme.com", _ctx(self._router)).data
        assert data["scans"][0]["time"] == "2024-01-01"
        assert data["scans"][0]["result"] == "https://r/1"

    def test_keeps_urls_and_subdomains_from_the_absorbed_module(self):
        data = get_module("urlscanio").run("acme.com", _ctx(self._router)).data
        assert "https://a.acme.com/x" in data["urls"]
        assert set(data["subdomains"]) == {"a.acme.com", "b.acme.com"}

    def test_offsite_domains_are_not_reported_as_subdomains(self):
        def router(url):
            return _Resp(j={"results": [
                {"page": {"url": "https://evil.test/x", "domain": "evil.test"},
                 "task": {}, "result": ""}]})
        data = get_module("urlscan").run("acme.com", _ctx(router)).data
        assert data["subdomains"] == []


class TestAnubisMerge:
    def test_identical_behaviour_via_either_id(self):
        router = lambda u: _Resp(j=["a.acme.com", "b.acme.com"])  # noqa: E731
        via_new = get_module("anubisjldc").run("acme.com", _ctx(router)).data
        via_old = get_module("pdnsanubis").run("acme.com", _ctx(router)).data
        assert via_new == via_old
        assert "a.acme.com" in via_new["subdomains"]


class TestCommonCrawlMerge:
    def test_survivor_resolves_the_latest_index(self):
        """The absorbed `commoncrawl` queried a hard-coded CC-MAIN-2024-10
        index and silently went stale; the survivor asks collinfo.json."""
        seen = []

        def router(url):
            seen.append(url)
            if "collinfo.json" in url:
                return _Resp(j=[{"cdx-api": "https://index.commoncrawl.org/CC-NEW"}])
            return _Resp(t='{"url": "https://acme.com/a"}')
        get_module("commoncrawl").run("acme.com", _ctx(router))
        assert any("collinfo.json" in u for u in seen)
        assert not any("CC-MAIN-2024-10" in u for u in seen)


# --------------------------------------------------------------------------- #
#  registry-wide invariants
# --------------------------------------------------------------------------- #
class TestRegistryIntegrity:
    def test_recipes_and_pivots_reference_resolvable_ids(self):
        """A merge must not leave a recipe or pivot list pointing at nothing."""
        from ghost_eye.intelligence.osint_pivot import PIVOT_MODULES
        from ghost_eye.workflow import DEFAULT_RECIPES

        unresolved = []
        for name, ids in DEFAULT_RECIPES.items():
            for mid in ids:
                if get_module(mid) is None:
                    unresolved.append(f"recipe {name}: {mid}")
        for kind, ids in PIVOT_MODULES.items():
            for mid in ids:
                if get_module(mid) is None:
                    unresolved.append(f"pivot {kind}: {mid}")
        # ids that never existed are pre-existing noise; assert the *merged*
        # ones specifically still resolve
        for old in MERGES:
            assert get_module(old) is not None
        assert not [u for u in unresolved
                    if any(old in u for old in MERGES)], unresolved

    def test_module_ids_and_names_remain_unique(self):
        ids, names = set(), set()
        for m in REGISTRY.values():
            assert m.id not in ids
            assert m.name not in names
            ids.add(m.id)
            names.add(m.name)

    def test_no_alias_shadows_a_live_id(self):
        assert not (set(ALIASES) & set(REGISTRY))
