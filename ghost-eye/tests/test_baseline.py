"""Tests for the corpus baseline / anomaly engine.

The engine is easy to write and easy to make useless. These tests pin the four
properties that decide which it is:

  1. it refuses to rank rarity on a corpus too small to know anything
  2. it suppresses identifier fields, which are unique per host by nature and
     would otherwise flag every host forever
  3. re-learning the same host does not make that host's values look normal
  4. a host is not compared against a corpus that already contains its own scan
"""

from __future__ import annotations

import pytest

from ghost_eye.baseline import (MIN_CORPUS, Baseline, anomaly_report,
                                observations)
from ghost_eye.core import Result


def _result(module, target, data):
    r = Result(module=module, target=target)
    r.data = data
    r.status = "ok"
    return r


def _host(target, server="nginx", extra=None):
    data = {"server": server, "status_code": "200"}
    data.update(extra or {})
    return [_result("headers", target, data)]


@pytest.fixture()
def base(tmp_path):
    b = Baseline(str(tmp_path / "b.db"))
    yield b
    b.close()


def _populate(b, n, server="nginx"):
    """n ordinary hosts that all look the same."""
    for i in range(n):
        b.learn(_host(f"host{i}.example", server=server), host=f"host{i}.example")


# --------------------------------------------------------------------------- #
class TestObservationExtraction:
    def test_fields_are_namespaced_by_module(self):
        obs = observations([_result("headers", "a.com", {"server": "nginx"}),
                            _result("cert", "a.com", {"server": "other"})])
        assert obs == {"headers.server": "nginx", "cert.server": "other"}

    @pytest.mark.parametrize("data", [
        {"note": "some explanatory prose"},      # bookkeeping, not an observation
        {"elapsed": "1.24"},
        {"server": "none"},
        {"server": ""},
        {"summary": "x" * 400},                  # a blob, not a comparable value
    ])
    def test_unlearnable_values_are_dropped(self, data):
        assert observations([_result("m", "a.com", data)]) == {}

    def test_accepts_stored_scan_rows_not_just_result_objects(self):
        rows = [{"module": "headers", "target": "a.com", "data": {"server": "nginx"}}]
        assert observations(rows) == {"headers.server": "nginx"}


class TestSmallCorpusHonesty:
    """The mistake attribution's IDF had to be corrected for: treating
    frequency over a handful of hosts as knowledge about the world."""

    def test_below_min_corpus_it_says_so_instead_of_guessing(self, base):
        _populate(base, 3)
        rep = base.anomalies(_host("odd.example", server="weird-server-9000"),
                             host="odd.example")
        assert rep["anomalies"] == []
        assert rep["corpus_hosts"] == 3
        assert "at least" in rep["note"]

    def test_once_the_corpus_is_big_enough_it_scores(self, base):
        _populate(base, MIN_CORPUS + 2)
        rep = base.anomalies(_host("odd.example", server="weird-server-9000"),
                             host="odd.example")
        assert rep["anomaly_count"] >= 1
        hit = next(a for a in rep["anomalies"] if a["field"] == "server")
        assert hit["value"] == "weird-server-9000"
        assert hit["unique_to_this_host"] is True
        assert hit["rarity"] == 1.0


class TestTheCommonIsNotReported:
    def test_a_value_everyone_has_is_never_an_anomaly(self, base):
        _populate(base, MIN_CORPUS + 5)
        rep = base.anomalies(_host("normal.example"), host="normal.example")
        assert [a for a in rep["anomalies"] if a["field"] == "server"] == []

    def test_a_minority_value_above_the_threshold_is_not_flagged(self, base):
        # 10 hosts on nginx, 4 on apache -> apache is 28%, above the 15% bar
        _populate(base, 10)
        for i in range(4):
            base.learn(_host(f"ap{i}.example", server="apache"),
                       host=f"ap{i}.example")
        rep = base.anomalies(_host("new.example", server="apache"),
                             host="new.example")
        assert [a for a in rep["anomalies"] if a["field"] == "server"] == []


class TestIdentifierSuppression:
    """Every host has its own IP and certificate serial. Without this guard the
    engine reports every host as maximally anomalous in those fields, forever —
    the fastest way to make an anomaly feed worthless."""

    def test_a_unique_per_host_field_is_not_flagged(self, base):
        for i in range(MIN_CORPUS + 4):
            base.learn([_result("dns", f"h{i}.example",
                                {"ip": f"198.51.100.{i}", "server": "nginx"})],
                       host=f"h{i}.example")
        assert base.is_identifier("dns.ip")
        rep = base.anomalies([_result("dns", "new.example",
                                      {"ip": "198.51.100.222", "server": "nginx"})],
                             host="new.example")
        assert [a for a in rep["anomalies"] if a["field"] == "ip"] == []

    def test_a_shared_field_is_not_mistaken_for_an_identifier(self, base):
        _populate(base, MIN_CORPUS + 4)
        assert not base.is_identifier("headers.server")

    def test_identifier_check_needs_a_corpus_too(self, base):
        _populate(base, 2)
        assert not base.is_identifier("headers.server")


class TestLearningIsIdempotent:
    """Re-scanning one host must not teach the baseline that its values are
    what the world looks like."""

    def test_relearning_the_same_host_stores_nothing_new(self, base):
        scan = _host("a.example")
        assert base.learn(scan, host="a.example") > 0
        assert base.learn(scan, host="a.example") == 0
        assert base.corpus_size() == 1

    def test_repeated_scans_do_not_dilute_rarity(self, base):
        _populate(base, MIN_CORPUS + 2)
        odd = _host("odd.example", server="weird-server-9000")
        for _ in range(10):
            base.learn(odd, host="odd.example")
        rep = base.anomalies(odd, host="odd.example")
        hit = next(a for a in rep["anomalies"] if a["field"] == "server")
        # the host's own ten scans are one host, and it is excluded from its
        # own prevalence — so the value is still unique in the corpus
        assert hit["seen_on_hosts"] == 0
        assert hit["unique_to_this_host"] is True


class TestSelfExclusion:
    def test_a_host_is_not_compared_against_its_own_learned_scan(self, base):
        _populate(base, MIN_CORPUS + 2)
        odd = _host("odd.example", server="weird-server-9000")
        base.learn(odd, host="odd.example")          # already in the corpus
        rep = base.anomalies(odd, host="odd.example")
        assert rep["anomaly_count"] >= 1, \
            "the host taught the baseline its own value and then hid behind it"


class TestHousekeeping:
    def test_summary_reports_readiness(self, base):
        _populate(base, 2)
        assert base.summary()["ready"] is False
        _populate(base, MIN_CORPUS + 2)
        s = base.summary()
        assert s["ready"] is True and s["corpus_hosts"] >= MIN_CORPUS

    def test_forget_removes_a_host(self, base):
        _populate(base, 4)
        assert base.forget("host0.example") > 0
        assert base.corpus_size() == 3

    def test_learn_many_ingests_stored_scans(self, base):
        scans = [{"target": f"s{i}.example",
                  "results": [{"module": "headers", "target": f"s{i}.example",
                               "data": {"server": "nginx"}}]}
                 for i in range(5)]
        assert base.learn_many(scans) > 0
        assert base.corpus_size() == 5


class TestReportHelper:
    def test_scoring_happens_before_learning(self, tmp_path):
        db = str(tmp_path / "r.db")
        b = Baseline(db)
        _populate(b, MIN_CORPUS + 2)
        b.close()
        odd = _host("odd.example", server="weird-server-9000")
        rep = anomaly_report(odd, db=db, target="odd.example", learn=True)
        assert rep["anomaly_count"] >= 1, \
            "learning ran first and the scan normalised itself"
        assert rep["learned_observations"] > 0
        assert rep["corpus_hosts"] == MIN_CORPUS + 3

    def test_target_is_inferred_from_the_results(self, tmp_path):
        db = str(tmp_path / "t.db")
        rep = anomaly_report(_host("inferred.example"), db=db)
        assert rep["host"] == "inferred.example"


# --------------------------------------------------------------------------- #
#  CLI wiring
# --------------------------------------------------------------------------- #
class TestCliIntegration:
    def test_flags_parse(self):
        from ghost_eye.cli import build_parser
        args = build_parser().parse_args(
            ["-t", "x.com", "-m", "headers", "--anomalies", "--baseline-learn",
             "--db", "/tmp/x.db"])
        assert args.anomalies and args.baseline_learn and args.db == "/tmp/x.db"

    def test_end_to_end_learn_then_score(self, tmp_path, capsys):
        """A full scan-shaped payload through the CLI printer, twice: the
        second host must be scored against the first ten."""
        from ghost_eye.cli import _print_anomalies
        from ghost_eye.baseline import anomaly_report
        db = str(tmp_path / "cli.db")
        for i in range(MIN_CORPUS + 2):
            anomaly_report(_host(f"n{i}.example"), db=db,
                           target=f"n{i}.example", learn=True)
        rep = anomaly_report(_host("odd.example", server="weird-server-9000"),
                             db=db, target="odd.example", learn=True)
        _print_anomalies(rep, scored=True)
        out = capsys.readouterr().out
        assert "weird-server-9000" in out
        assert "ONLY THIS HOST" in out
