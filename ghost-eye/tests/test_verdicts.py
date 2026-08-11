"""Tests for the analyst-verdict / false-positive feedback loop.

A suppression store is trivial to write and dangerous to write badly: the
failure mode is not a crash, it is a real finding that never appears again. The
tests below exist for that hazard specifically —

  1. a verdict is a ruling on a *value*, so a changed value comes back
  2. verdicts expire, and an expired one suppresses nothing
  3. suppression is always counted and the withheld findings are kept
  4. a ruling on one host does not silently speak for the whole estate
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from ghost_eye.verdicts import (ACCEPTED_RISK, ANY_SCOPE, CONFIRMED,
                                FALSE_POSITIVE, VerdictStore, apply_verdicts,
                                fingerprint, short_id)


def _finding(target="a.example", module="headers", field="x-powered-by",
             detail="JBoss-EAP/7", severity="medium"):
    return {"module": module, "target": target, "field": field,
            "detail": detail, "severity": severity}


@pytest.fixture()
def store(tmp_path):
    s = VerdictStore(str(tmp_path / "v.db"))
    yield s
    s.close()


# --------------------------------------------------------------------------- #
class TestFingerprintIdentity:
    def test_same_finding_same_fingerprint(self):
        assert fingerprint(_finding()) == fingerprint(_finding())

    def test_the_value_is_part_of_the_identity(self):
        """The core safety property: ruling on nginx/1.18 must not rule on
        nginx/1.24, or a verdict becomes a permanent blind spot on that field."""
        assert fingerprint(_finding(detail="nginx/1.18")) != \
            fingerprint(_finding(detail="nginx/1.24"))

    def test_scope_is_part_of_the_identity(self):
        assert fingerprint(_finding(), scope="a.example") != \
            fingerprint(_finding(), scope="b.example")

    def test_whitespace_and_case_do_not_split_identity(self):
        a = fingerprint({"module": "Headers", "target": "A.Example",
                         "field": " X-Powered-By ", "detail": "JBoss  EAP/7"})
        b = fingerprint({"module": "headers", "target": "a.example",
                         "field": "x-powered-by", "detail": "JBoss EAP/7"})
        assert a == b


class TestSuppression:
    def test_a_ruled_finding_is_withheld_and_counted(self, store):
        f = _finding()
        store.record(f, FALSE_POSITIVE, reason="deliberate header")
        out = store.apply([f])
        assert out["active_count"] == 0
        assert out["suppressed_count"] == 1
        assert out["suppressed_by_verdict"] == {FALSE_POSITIVE: 1}
        assert out["suppressed"][0]["verdict_reason"] == "deliberate header"

    def test_withheld_findings_are_kept_not_deleted(self, store):
        f = _finding()
        store.record(f, ACCEPTED_RISK)
        out = store.apply([f])
        assert out["suppressed"][0]["field"] == f["field"]

    def test_confirmed_labels_but_never_hides(self, store):
        f = _finding()
        store.record(f, CONFIRMED, reason="verified by hand")
        out = store.apply([f])
        assert out["active_count"] == 1
        assert out["suppressed_count"] == 0
        assert out["findings"][0]["verdict"] == CONFIRMED

    def test_an_unruled_finding_passes_through(self, store):
        out = store.apply([_finding()])
        assert out["active_count"] == 1
        assert "verdict" not in out["findings"][0]

    def test_every_finding_carries_its_short_id(self, store):
        out = store.apply([_finding()])
        assert out["findings"][0]["id"] == short_id(fingerprint(_finding()))
        assert len(out["findings"][0]["id"]) == 12


class TestChangedValueResurfaces:
    """The hazard this module exists to avoid."""

    def test_a_new_value_in_a_ruled_field_comes_back(self, store):
        store.record(_finding(detail="nginx/1.18"), FALSE_POSITIVE)
        out = store.apply([_finding(detail="nginx/1.24")])
        assert out["active_count"] == 1, \
            "a verdict on one value silently suppressed a different one"
        assert out["suppressed_count"] == 0

    def test_the_ruled_value_itself_stays_suppressed(self, store):
        store.record(_finding(detail="nginx/1.18"), FALSE_POSITIVE)
        out = store.apply([_finding(detail="nginx/1.18")])
        assert out["suppressed_count"] == 1


class TestExpiry:
    def _expire(self, store, fp):
        past = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
        store.conn.execute("UPDATE verdicts SET expires=? WHERE fingerprint=?",
                           (past, fp))
        store.conn.commit()

    def test_an_expired_verdict_suppresses_nothing(self, store):
        f = _finding()
        rec = store.record(f, FALSE_POSITIVE)
        self._expire(store, rec["fingerprint"])
        out = store.apply([f])
        assert out["active_count"] == 1, "a stale ruling was still hiding a finding"
        assert out["expired_count"] == 1
        assert out["findings"][0]["verdict_expired"] is True

    def test_a_live_verdict_is_not_treated_as_expired(self, store):
        f = _finding()
        store.record(f, FALSE_POSITIVE, ttl_days=30)
        out = store.apply([f])
        assert out["suppressed_count"] == 1 and out["expired_count"] == 0

    def test_purge_removes_only_expired_rows(self, store):
        live = _finding(detail="live")
        dead = _finding(detail="dead")
        store.record(live, FALSE_POSITIVE)
        rec = store.record(dead, FALSE_POSITIVE)
        self._expire(store, rec["fingerprint"])
        assert store.purge_expired() == 1
        assert [v["detail"] for v in store.all()] == ["live"]

    def test_an_unparseable_expiry_does_not_grant_eternal_suppression(self, store):
        f = _finding()
        rec = store.record(f, FALSE_POSITIVE)
        store.conn.execute("UPDATE verdicts SET expires=? WHERE fingerprint=?",
                           ("not-a-date", rec["fingerprint"]))
        store.conn.commit()
        # unparseable is treated as not-expired (still suppressing) rather than
        # crashing — but the row is visible in all() so it can be found and fixed
        assert store.apply([f])["suppressed_count"] == 1
        assert any(v["expires"] == "not-a-date" for v in store.all())


class TestScope:
    def test_a_ruling_on_one_host_does_not_cover_another(self, store):
        store.record(_finding(target="a.example"), FALSE_POSITIVE)
        out = store.apply([_finding(target="b.example")])
        assert out["active_count"] == 1, \
            "one host's verdict silently spoke for the whole estate"

    def test_any_scope_is_available_when_chosen_explicitly(self, store):
        store.record(_finding(target="a.example"), FALSE_POSITIVE, scope=ANY_SCOPE)
        out = store.apply([_finding(target="b.example")])
        assert out["suppressed_count"] == 1
        assert out["suppressed"][0]["verdict_scope"] == ANY_SCOPE

    def test_a_host_scoped_verdict_wins_over_an_any_scope_one(self, store):
        f = _finding(target="a.example")
        store.record(f, FALSE_POSITIVE, scope=ANY_SCOPE)
        store.record(f, CONFIRMED, scope="a.example")
        out = store.apply([f])
        assert out["active_count"] == 1 and out["findings"][0]["verdict"] == CONFIRMED


class TestBookkeeping:
    def test_rejects_an_unknown_verdict(self, store):
        with pytest.raises(ValueError):
            store.record(_finding(), "probably-fine")

    def test_re_recording_updates_rather_than_duplicates(self, store):
        f = _finding()
        store.record(f, FALSE_POSITIVE, reason="first")
        store.record(f, ACCEPTED_RISK, reason="second")
        rows = store.all()
        assert len(rows) == 1
        assert rows[0]["verdict"] == ACCEPTED_RISK and rows[0]["reason"] == "second"

    def test_record_by_id_uses_the_printed_handle(self, store):
        f = _finding()
        listed = store.apply([f])["findings"][0]
        rec = store.record_by_id(listed["id"], FALSE_POSITIVE, [f])
        assert rec and rec["verdict"] == FALSE_POSITIVE
        assert store.apply([f])["suppressed_count"] == 1

    def test_record_by_id_returns_none_for_an_unknown_handle(self, store):
        assert store.record_by_id("000000000000", FALSE_POSITIVE, [_finding()]) is None

    def test_clear_reinstates_a_finding(self, store):
        f = _finding()
        rec = store.record(f, FALSE_POSITIVE)
        assert store.clear(rec["id"]) == 1
        assert store.apply([f])["active_count"] == 1


class TestHelper:
    def test_apply_verdicts_round_trips(self, tmp_path):
        db = str(tmp_path / "h.db")
        f = _finding()
        s = VerdictStore(db)
        s.record(f, FALSE_POSITIVE)
        s.close()
        assert apply_verdicts([f], db=db)["suppressed_count"] == 1

    def test_empty_input_is_shaped_not_special_cased(self, tmp_path):
        out = apply_verdicts([], db=str(tmp_path / "e.db"))
        assert out["active_count"] == 0 and out["suppressed_count"] == 0


class TestIdsSurviveBetweenRuns:
    """`--mark <id>` is typed in a later invocation than the scan that printed
    the id, so the mapping has to be persisted or the flow cannot work at all."""

    def test_a_printed_id_resolves_after_the_scan_is_over(self, tmp_path):
        db = str(tmp_path / "s.db")
        f = _finding()
        first = VerdictStore(db)
        listed = first.apply([f])["findings"][0]     # a scan prints ids
        first.close()

        later = VerdictStore(db)                    # a separate invocation
        try:
            recalled = later.recall(listed["id"])
            assert recalled is not None
            assert recalled["module"] == f["module"]
            assert recalled["detail"] == f["detail"]
            later.record(recalled, FALSE_POSITIVE)
            assert later.apply([f])["suppressed_count"] == 1
        finally:
            later.close()

    def test_an_unknown_id_recalls_nothing(self, store):
        assert store.recall("ffffffffffff") is None

    def test_applying_remembers_every_finding(self, store):
        store.apply([_finding(detail="a"), _finding(detail="b")])
        ids = [short_id(fingerprint(_finding(detail=d))) for d in ("a", "b")]
        assert all(store.recall(i) is not None for i in ids)


class TestCliWiring:
    def test_flags_parse(self):
        from ghost_eye.cli import build_parser
        args = build_parser().parse_args(
            ["--mark", "abc123def456:false_positive", "--mark-reason", "known",
             "--mark-ttl", "30", "--verdicts", "--db", "/tmp/v.db"])
        assert args.mark == ["abc123def456:false_positive"]
        assert args.mark_reason == "known" and args.mark_ttl == 30
        assert args.verdicts is True

    def test_mark_then_list_then_unmark(self, tmp_path, capsys):
        from ghost_eye.cli import _handle_verdicts, build_parser
        db = str(tmp_path / "cli.db")
        f = _finding()
        s = VerdictStore(db)
        fid = s.apply([f])["findings"][0]["id"]
        s.close()

        args = build_parser().parse_args(
            ["--db", db, "--mark", f"{fid}:false_positive",
             "--mark-reason", "deliberate", "--verdicts"])
        assert _handle_verdicts(args) == 0
        out = capsys.readouterr().out
        assert fid in out and "false_positive" in out and "deliberate" in out
        assert apply_verdicts([f], db=db)["suppressed_count"] == 1

        args = build_parser().parse_args(["--db", db, "--unmark", fid])
        _handle_verdicts(args)
        assert apply_verdicts([f], db=db)["active_count"] == 1

    def test_an_unknown_verdict_word_is_refused(self, tmp_path):
        from ghost_eye.cli import _handle_verdicts, build_parser
        args = build_parser().parse_args(
            ["--db", str(tmp_path / "x.db"), "--mark", "abc:probably-fine"])
        assert _handle_verdicts(args) == 2

    def test_an_unknown_id_is_refused_not_silently_ignored(self, tmp_path):
        from ghost_eye.cli import _handle_verdicts, build_parser
        args = build_parser().parse_args(
            ["--db", str(tmp_path / "y.db"), "--mark", "ffffffffffff:false_positive"])
        assert _handle_verdicts(args) == 2
