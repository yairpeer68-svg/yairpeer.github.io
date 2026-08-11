"""Tests for search across every stored scan.

The question this answers is the one no per-scan search can: "where have I ever
seen this IP / this header / this CVE?". Two properties matter — the SQLite
prefilter must never *miss* a scan that contains the query (it may over-return,
and the precise pass drops the extras), and a query containing LIKE wildcards
must still mean itself.
"""

from __future__ import annotations

import json

from ghost_eye.reporting import Store
from ghost_eye.search import search_scans


def _scan(sid, target, ts, data):
    return {"id": sid, "target": target, "ts": ts,
            "results": [{"module": "headers", "target": target, "data": data}]}


class TestSearchScans:
    def test_a_value_is_found_across_scans(self):
        out = search_scans([
            _scan("a", "one.com", "2026-01-01", {"server": "nginx/1.18"}),
            _scan("b", "two.com", "2026-01-02", {"server": "apache"}),
        ], "nginx")
        assert out["count"] == 1
        assert out["matches"][0]["scan_id"] == "a"
        assert out["matches"][0]["target"] == "one.com"

    def test_the_same_value_in_two_targets_returns_both(self):
        out = search_scans([
            _scan("a", "one.com", "2026-01-01", {"ip": "203.0.113.9"}),
            _scan("b", "two.com", "2026-01-02", {"ip": "203.0.113.9"}),
        ], "203.0.113.9")
        assert out["count"] == 2
        assert set(out["by_target"]) == {"one.com", "two.com"}

    def test_an_exact_value_outranks_a_substring(self):
        out = search_scans([
            _scan("a", "one.com", "2026-01-01", {"note": "the nginx build"}),
            _scan("b", "two.com", "2026-01-02", {"server": "nginx"}),
        ], "nginx")
        assert out["matches"][0]["scan_id"] == "b"

    def test_newest_wins_inside_a_rank_band(self):
        out = search_scans([
            _scan("old", "one.com", "2025-01-01", {"server": "nginx"}),
            _scan("new", "two.com", "2026-06-01", {"server": "nginx"}),
        ], "nginx")
        assert [m["scan_id"] for m in out["matches"]] == ["new", "old"]

    def test_a_field_name_is_searchable_too(self):
        out = search_scans([_scan("a", "x.com", "2026-01-01",
                                  {"x_frame_options": "missing"})], "x_frame")
        assert out["count"] == 1

    def test_search_is_case_insensitive(self):
        out = search_scans([_scan("a", "x.com", "2026-01-01",
                                  {"server": "Nginx"})], "NGINX")
        assert out["count"] == 1

    def test_nested_data_is_flattened_and_searched(self):
        out = search_scans([_scan("a", "x.com", "2026-01-01",
                                  {"tls": {"issuer": "Let's Encrypt"}})],
                           "Let's Encrypt")
        assert out["count"] == 1

    def test_an_empty_query_returns_nothing_rather_than_everything(self):
        out = search_scans([_scan("a", "x.com", "2026-01-01", {"a": "b"})], "  ")
        assert out["count"] == 0 and out["matches"] == []

    def test_no_scans_is_not_an_error(self):
        assert search_scans([], "nginx")["count"] == 0

    def test_a_result_that_is_not_a_dict_is_skipped(self):
        scan = {"id": "a", "target": "x.com", "ts": "2026-01-01",
                "results": ["junk", None, {"module": "h", "data": {"k": "nginx"}}]}
        assert search_scans([scan], "nginx")["count"] == 1

    def test_matches_are_capped_but_the_count_is_honest(self):
        big = _scan("a", "x.com", "2026-01-01",
                    {f"k{i}": "nginx" for i in range(500)})
        out = search_scans([big], "nginx", limit=10)
        assert len(out["matches"]) == 10
        assert out["count"] == 500, "the cap must not lie about how many exist"

    def test_every_match_says_which_scan_it_came_from(self):
        out = search_scans([_scan("a", "x.com", "2026-01-01", {"s": "nginx"})],
                           "nginx")
        m = out["matches"][0]
        assert m["scan_id"] == "a" and m["ts"] == "2026-01-01" and m["module"]


class TestStorePrefilter:
    def _store(self, tmp_path):
        return Store(str(tmp_path / "t.db"))

    def _insert(self, store, sid, target, ts, data):
        store.conn.execute(
            "INSERT INTO scans(id,target,ts,risk,score,modules,results) "
            "VALUES(?,?,?,?,?,?,?)",
            (sid, target, ts, "low", 1, 1,
             json.dumps([{"module": "headers", "target": target, "data": data}])))
        store.conn.commit()

    def test_it_returns_the_scan_that_contains_the_value(self, tmp_path):
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"server": "nginx"})
        self._insert(s, "b", "two.com", "2026-01-02", {"server": "apache"})
        rows = s.search_all("nginx")
        assert [r["id"] for r in rows] == ["a"]
        s.close()

    def test_a_percent_sign_means_itself_not_every_row(self, tmp_path):
        """LIKE wildcards in the query would otherwise match the whole table."""
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"cpu": "100% used"})
        self._insert(s, "b", "two.com", "2026-01-02", {"server": "apache"})
        assert [r["id"] for r in s.search_all("100%")] == ["a"]
        # a bare "%" is the literal character, so it finds the row that has one
        # and not the row that does not — as a wildcard it would match both
        assert [r["id"] for r in s.search_all("%")] == ["a"]
        s.close()

    def test_an_underscore_means_itself(self, tmp_path):
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"x_frame": "missing"})
        self._insert(s, "b", "two.com", "2026-01-02", {"xyframe": "missing"})
        assert [r["id"] for r in s.search_all("x_frame")] == ["a"]
        s.close()

    def test_it_can_be_narrowed_to_one_target(self, tmp_path):
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"server": "nginx"})
        self._insert(s, "b", "two.com", "2026-01-02", {"server": "nginx"})
        assert [r["id"] for r in s.search_all("nginx", target="two.com")] == ["b"]
        s.close()

    def test_newest_first_and_bounded(self, tmp_path):
        s = self._store(tmp_path)
        for i in range(10):
            self._insert(s, f"s{i}", "x.com", f"2026-01-{i + 1:02d}",
                         {"server": "nginx"})
        rows = s.search_all("nginx", limit=3)
        assert [r["id"] for r in rows] == ["s9", "s8", "s7"]
        s.close()

    def test_an_empty_query_hits_no_rows(self, tmp_path):
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"server": "nginx"})
        assert s.search_all("") == []
        s.close()

    def test_a_corrupt_row_is_skipped_not_fatal(self, tmp_path):
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"server": "nginx"})
        s.conn.execute("INSERT INTO scans(id,target,ts,risk,score,modules,results)"
                       " VALUES('bad','x','2026-01-03','low',1,1,'{ nginx')")
        s.conn.commit()
        assert [r["id"] for r in s.search_all("nginx")] == ["a"]
        s.close()

    def test_the_prefilter_feeds_the_precise_pass(self, tmp_path):
        """End to end: SQLite narrows, search_scans ranks."""
        s = self._store(tmp_path)
        self._insert(s, "a", "one.com", "2026-01-01", {"server": "nginx/1.18"})
        out = search_scans(s.search_all("nginx"), "nginx")
        assert out["count"] == 1 and out["matches"][0]["scan_id"] == "a"
        s.close()


class TestTargetOnlyMatches:
    """Searching for a hostname you have scanned and being told "nothing
    matches" is a lie by omission: the answer is "yes, three times — just not
    in any finding value"."""

    def test_a_target_only_hit_is_reported_separately(self):
        out = search_scans([_scan("a", "shop.example.com", "2026-01-01",
                                  {"server": "nginx"})], "shop.example.com")
        assert out["count"] == 0
        assert [m["scan_id"] for m in out["target_matches"]] == ["a"]

    def test_a_value_hit_is_not_also_a_target_hit(self):
        """Otherwise every scan of example.com double-reports itself."""
        out = search_scans([_scan("a", "example.com", "2026-01-01",
                                  {"cname": "example.com"})], "example.com")
        assert out["count"] == 1
        assert out["target_matches"] == []

    def test_target_matches_are_newest_first(self):
        out = search_scans([
            _scan("old", "x.example.com", "2025-01-01", {"server": "nginx"}),
            _scan("new", "x.example.com", "2026-06-01", {"server": "nginx"}),
        ], "x.example.com")
        assert [m["scan_id"] for m in out["target_matches"]] == ["new", "old"]

    def test_an_unrelated_query_matches_neither(self):
        out = search_scans([_scan("a", "example.com", "2026-01-01",
                                  {"server": "nginx"})], "zzz-nothing")
        assert out["count"] == 0 and out["target_matches"] == []
