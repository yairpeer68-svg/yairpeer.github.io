"""Tests for assignment and the audit log.

The assignment tests are mostly about refusing a bad status, because a status
you cannot filter on silently loses the finding. The audit tests are mostly
about two properties: that a broken log never breaks the API, and that a
credential pasted into a note or a detail string does not end up on disk.
"""

from __future__ import annotations

import json

import pytest

from ghost_eye.collab import (OPEN_STATUSES, STATUSES, Assignments, AuditLog,
                              redact)


# --------------------------------------------------------------------------- #
class TestAssignments:
    def test_a_finding_can_be_owned(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        entry = a.assign("fp1", assignee="dana", status="investigating",
                         target="example.com")
        assert entry["assignee"] == "dana"
        assert entry["status"] == "investigating"
        assert a.get("fp1")["assignee"] == "dana"

    def test_an_unknown_status_is_refused_not_stored(self, tmp_path):
        """Storing a typo makes the finding vanish from every filtered view."""
        a = Assignments(tmp_path / "as.json")
        with pytest.raises(ValueError):
            a.assign("fp1", status="in progress-ish")
        assert a.get("fp1") == {}

    def test_the_status_set_is_closed_and_small(self):
        assert "open" in STATUSES and "resolved" in STATUSES
        assert "resolved" not in OPEN_STATUSES
        assert len(STATUSES) <= 6, "a status list nobody can remember is free text"

    def test_an_empty_key_is_refused(self, tmp_path):
        with pytest.raises(ValueError):
            Assignments(tmp_path / "as.json").assign("  ")

    def test_it_survives_a_restart(self, tmp_path):
        path = tmp_path / "as.json"
        Assignments(path).assign("fp1", assignee="dana")
        assert Assignments(path).get("fp1")["assignee"] == "dana"

    def test_a_corrupt_file_does_not_brick_startup(self, tmp_path):
        path = tmp_path / "as.json"
        path.write_text("{not json at all")
        a = Assignments(path)
        assert a.all() == {}
        a.assign("fp1", assignee="dana")       # and it recovers on the next write
        assert Assignments(path).get("fp1")["assignee"] == "dana"

    def test_reopening_is_visible_in_the_history(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        a.assign("fp1", assignee="dana", status="resolved")
        a.assign("fp1", assignee="dana", status="open")
        history = a.get("fp1")["history"]
        assert [h["status"] for h in history] == ["resolved", "open"]

    def test_created_is_preserved_across_updates(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        first = a.assign("fp1", assignee="dana")
        second = a.assign("fp1", assignee="ravi", status="remediating")
        assert second["created"] == first["created"]

    def test_history_is_bounded(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        for _ in range(60):
            a.assign("fp1", status="open")
        assert len(a.get("fp1")["history"]) <= 20

    def test_unassign_removes_it(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        a.assign("fp1", assignee="dana")
        assert a.unassign("fp1") is True
        assert a.unassign("fp1") is False
        assert a.get("fp1") == {}

    def test_apply_leaves_unowned_findings_alone(self, tmp_path):
        """'Nobody owns this' and 'someone owns it and did nothing' are
        different states and must stay distinguishable."""
        a = Assignments(tmp_path / "as.json")
        a.assign("fp1", assignee="dana", status="remediating")
        findings = [{"id": "fp1"}, {"id": "fp2"}]
        a.apply(findings)
        assert findings[0]["assignee"] == "dana"
        assert "assignee" not in findings[1]
        assert "workflow_status" not in findings[1]

    def test_summary_counts_open_work_per_owner(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        a.assign("f1", assignee="dana", status="open")
        a.assign("f2", assignee="dana", status="remediating")
        a.assign("f3", assignee="dana", status="resolved")
        a.assign("f4", status="open")
        s = a.summary()
        assert s["total"] == 4
        assert s["open"] == 3                      # resolved is not open work
        assert s["by_owner"]["dana"] == 2
        assert s["by_owner"]["(unassigned)"] == 1

    def test_a_long_name_is_truncated_not_rejected(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        entry = a.assign("fp1", assignee="x" * 500)
        assert 0 < len(entry["assignee"]) <= 80

    def test_a_note_is_redacted_before_storage(self, tmp_path):
        a = Assignments(tmp_path / "as.json")
        a.assign("fp1", note="creds are api_key=SUPERSECRETVALUE12345")
        assert "SUPERSECRETVALUE12345" not in json.dumps(a.all())


# --------------------------------------------------------------------------- #
class TestRedaction:
    @pytest.mark.parametrize("text", [
        "token: abc123def456",
        "api_key=abc123def456",
        "password: hunter2",
        "Authorization: Basic Zm9vOmJhcg==",
        "bearer eyJhbGciOiJIUzI1NiJ9",
        "sk-proj-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
    ])
    def test_secret_shapes_do_not_survive(self, text):
        out = redact(text)
        assert "***" in out
        for token in ("abc123def456", "hunter2", "Zm9vOmJhcg",
                      "eyJhbGciOiJIUzI1NiJ9", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"):
            assert token not in out

    def test_ordinary_text_is_left_readable(self):
        assert redact("deleted 12 scans older than 90 days") == \
            "deleted 12 scans older than 90 days"

    def test_output_is_bounded(self):
        assert len(redact("a" * 10000)) <= 400


# --------------------------------------------------------------------------- #
class TestAuditLog:
    def test_an_action_is_recorded(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        log.record("scan", detail="12 modules", target="example.com")
        entries = log.tail()
        assert len(entries) == 1
        assert entries[0]["action"] == "scan"
        assert entries[0]["target"] == "example.com"

    def test_it_is_newest_first(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        for i in range(3):
            log.record(f"a{i}")
        assert [e["action"] for e in log.tail()] == ["a2", "a1", "a0"]

    def test_it_survives_a_restart(self, tmp_path):
        path = tmp_path / "audit.jsonl"
        AuditLog(path).record("scan", target="example.com")
        assert AuditLog(path).tail()[0]["target"] == "example.com"

    def test_one_corrupt_line_does_not_lose_the_rest(self, tmp_path):
        path = tmp_path / "audit.jsonl"
        log = AuditLog(path)
        log.record("first")
        with path.open("a", encoding="utf-8") as fh:
            fh.write("{half a line\n")
        log.record("third")
        actions = [e["action"] for e in log.tail()]
        assert "first" in actions and "third" in actions

    def test_an_unwritable_path_never_raises(self, tmp_path):
        """An audit log that cannot be written must not turn a working API
        into a broken one."""
        blocker = tmp_path / "blocker"
        blocker.write_text("i am a file, not a directory")
        log = AuditLog(blocker / "sub" / "audit.jsonl")
        log.record("scan")                       # must not raise
        assert log.tail()[0]["action"] == "scan"  # in-memory fallback still works

    def test_a_failure_is_recorded_as_a_failure(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        log.record("email-send", detail="connection refused", ok=False)
        assert log.tail()[0]["ok"] is False
        assert log.summary()["failures"] == 1

    def test_secrets_do_not_reach_the_file(self, tmp_path):
        path = tmp_path / "audit.jsonl"
        AuditLog(path).record("keys", detail="token: SUPERSECRET1234567890")
        assert "SUPERSECRET1234567890" not in path.read_text()

    def test_the_file_stays_bounded(self, tmp_path):
        path = tmp_path / "audit.jsonl"
        log = AuditLog(path, cap=20)
        for i in range(200):
            log.record(f"a{i}")
        assert len(path.read_text().splitlines()) <= 60

    def test_filtering_by_action(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        log.record("scan")
        log.record("assign")
        log.record("scan")
        assert len(log.tail(action="scan")) == 2

    def test_summary_reports_who_and_what(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        log.record("scan", actor="10.0.0.5")
        log.record("scan", actor="10.0.0.5")
        log.record("assign", actor="10.0.0.9")
        s = log.summary()
        assert s["total"] == 3
        assert s["by_action"]["scan"] == 2
        assert s["by_actor"]["10.0.0.5"] == 2
        assert s["first"] and s["last"]

    def test_there_is_no_delete_or_edit_method(self):
        """Append-only is the whole point; an editable audit log is a diary."""
        for forbidden in ("delete", "remove", "edit", "clear", "purge", "update"):
            assert not hasattr(AuditLog, forbidden), \
                f"AuditLog.{forbidden} would make the log editable"


class TestActiveActors:
    """Presence, honestly: derived from what was done, because a shared token
    gives no user identity to build a real presence protocol on."""

    def test_a_recent_actor_is_listed(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        log.record("scan", actor="10.0.0.5")
        active = log.active(30)
        assert [a["actor"] for a in active] == ["10.0.0.5"]
        assert active[0]["actions"] == 1
        assert active[0]["last_action"] == "scan"

    def test_actions_are_counted_per_actor(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        for _ in range(3):
            log.record("scan", actor="10.0.0.5")
        log.record("assign", actor="10.0.0.9")
        counts = {a["actor"]: a["actions"] for a in log.active(30)}
        assert counts == {"10.0.0.5": 3, "10.0.0.9": 1}

    def test_an_old_action_is_not_presence(self, tmp_path):
        """Someone who scanned last Tuesday is not in the room with you."""
        path = tmp_path / "audit.jsonl"
        path.write_text('{"at":"2020-01-01T00:00:00Z","actor":"ghost",'
                        '"action":"scan","ok":true}\n')
        assert AuditLog(path).active(30) == []

    def test_an_empty_log_has_nobody_active(self, tmp_path):
        assert AuditLog(tmp_path / "audit.jsonl").active(30) == []

    def test_the_window_is_respected(self, tmp_path):
        log = AuditLog(tmp_path / "audit.jsonl")
        log.record("scan", actor="10.0.0.5")
        assert log.active(1)          # just happened
        assert log.active(1440)       # a wider window still contains it
