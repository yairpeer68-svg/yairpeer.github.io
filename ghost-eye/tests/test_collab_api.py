"""The collaboration endpoints over a real socket.

The unit tests cover the stores; these cover the wiring — the auth gate, the
status validation, and the one property that has to hold across a restart:
every state-changing call leaves an audit entry, whether or not anyone
remembered to add a line for it.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request

import pytest

TOKEN = "COLLABTOKEN"
AUTH = {"X-Ghost-Token": TOKEN}


@pytest.fixture(scope="module")
def api(tmp_path_factory):
    from ghost_eye import webapp

    import os
    state = tmp_path_factory.mktemp("state")
    db = state / "c.db"
    # a module-local state dir so assignments made here cannot leak into
    # another test file's expectations
    previous = os.environ.get("GHOSTEYE_STATE")
    os.environ["GHOSTEYE_STATE"] = str(state)
    port = 8917
    threading.Thread(
        target=webapp.serve,
        kwargs={"host": "127.0.0.1", "port": port, "db": str(db),
                "auth_token": TOKEN, "quiet": True},
        daemon=True).start()
    for _ in range(50):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=1).read()
            break
        except Exception:  # noqa: BLE001
            time.sleep(0.1)
    yield f"http://127.0.0.1:{port}"
    if previous is None:
        os.environ.pop("GHOSTEYE_STATE", None)
    else:
        os.environ["GHOSTEYE_STATE"] = previous


def call(url, *, method="GET", body=None, headers=None):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None)
    for k, v in {**(headers or {})}.items():
        req.add_header(k, v)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.getcode(), json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except ValueError:
            return e.code, {"raw": raw}


# --------------------------------------------------------------------------- #
class TestAuthGate:
    @pytest.mark.parametrize("path", ["/api/assign", "/api/audit", "/api/email",
                                      "/api/search-all?q=x", "/api/estimate"])
    def test_every_new_endpoint_needs_the_token(self, api, path):
        code, _ = call(api + path)
        assert code == 401, f"{path} answered without a token"

    def test_with_the_token_they_answer(self, api):
        for path in ("/api/assign", "/api/audit", "/api/email", "/api/estimate"):
            code, _ = call(api + path, headers=AUTH)
            assert code == 200, f"{path} refused a valid token"


class TestAssign:
    def test_assign_then_read_back(self, api):
        code, out = call(api + "/api/assign", method="POST", headers=AUTH,
                         body={"key": "fp-alpha", "assignee": "dana",
                               "status": "investigating", "target": "example.com"})
        assert code == 200
        assert out["assignment"]["assignee"] == "dana"
        code, out = call(api + "/api/assign?key=fp-alpha", headers=AUTH)
        assert out["assignment"]["status"] == "investigating"

    def test_an_unknown_status_is_a_400_not_a_silent_store(self, api):
        code, out = call(api + "/api/assign", method="POST", headers=AUTH,
                         body={"key": "fp-bad", "status": "kinda-done"})
        assert code == 400 and "unknown status" in out["error"]
        _, out = call(api + "/api/assign?key=fp-bad", headers=AUTH)
        assert out["assignment"] == {}

    def test_a_missing_key_is_refused(self, api):
        code, _ = call(api + "/api/assign", method="POST", headers=AUTH,
                       body={"assignee": "dana"})
        assert code == 400

    def test_the_status_vocabulary_is_advertised(self, api):
        """A client that has to guess the valid statuses will guess wrong."""
        _, out = call(api + "/api/assign", headers=AUTH)
        assert "open" in out["statuses"] and "resolved" in out["statuses"]

    def test_removing_an_assignment(self, api):
        call(api + "/api/assign", method="POST", headers=AUTH,
             body={"key": "fp-gone", "assignee": "x"})
        _, out = call(api + "/api/assign", method="POST", headers=AUTH,
                      body={"key": "fp-gone", "remove": True})
        assert out["removed"] is True
        _, out = call(api + "/api/assign?key=fp-gone", headers=AUTH)
        assert out["assignment"] == {}

    def test_the_summary_counts_open_work(self, api):
        call(api + "/api/assign", method="POST", headers=AUTH,
             body={"key": "fp-open", "assignee": "ravi", "status": "open"})
        _, out = call(api + "/api/assign", headers=AUTH)
        assert out["summary"]["open"] >= 1


class TestAudit:
    def test_a_state_changing_call_is_recorded(self, api):
        call(api + "/api/assign", method="POST", headers=AUTH,
             body={"key": "fp-audit", "assignee": "dana"})
        _, out = call(api + "/api/audit", headers=AUTH)
        actions = [e["action"] for e in out["entries"]]
        assert "assign" in actions

    def test_a_refused_call_is_recorded_as_a_failure(self, api):
        call(api + "/api/assign", method="POST", headers=AUTH,
             body={"key": "fp-nope", "remove": True})
        _, out = call(api + "/api/audit?action=unassign", headers=AUTH)
        assert any(e["ok"] is False for e in out["entries"])

    def test_it_can_be_filtered_by_action(self, api):
        _, out = call(api + "/api/audit?action=assign", headers=AUTH)
        assert out["entries"]
        assert all(e["action"] == "assign" for e in out["entries"])

    def test_there_is_no_way_to_delete_an_entry(self, api):
        """Append-only is the property; an editable audit log is a diary."""
        for method in ("POST", "DELETE"):
            code, _ = call(api + "/api/audit", method=method, headers=AUTH,
                           body={} if method == "POST" else None)
            assert code == 404, f"{method} /api/audit is routed somewhere"


    def test_recent_actors_are_reported(self, api):
        call(api + "/api/assign", method="POST", headers=AUTH,
             body={"key": "fp-presence", "assignee": "dana"})
        _, out = call(api + "/api/audit", headers=AUTH)
        assert out["active"], "nobody is active right after an action"
        assert out["active_minutes"] > 0
        assert all("actor" in a and "actions" in a for a in out["active"])

    def test_it_does_not_claim_to_be_a_presence_protocol(self, api):
        """A shared token gives no user identity; saying otherwise would be a
        claim the tool cannot back."""
        _, out = call(api + "/api/audit", headers=AUTH)
        assert "presence protocol" in out["note"]

    def test_an_unauthenticated_call_leaves_no_entry(self, api):
        """The auth gate runs before the audit hook, so a 401 is not an
        'action' — otherwise anyone could fill the log from outside."""
        before = call(api + "/api/audit", headers=AUTH)[1]["summary"]["total"]
        call(api + "/api/assign", method="POST",
             body={"key": "fp-x", "assignee": "intruder"})
        after = call(api + "/api/audit", headers=AUTH)[1]["summary"]["total"]
        assert after == before


class TestEmail:
    def test_an_unconfigured_mailer_reports_its_problems(self, api):
        _, out = call(api + "/api/email", headers=AUTH)
        assert out["problems"], "an unconfigured mailer claimed to be fine"
        assert out["smtp"]["configured"] is False

    def test_settings_round_trip_without_returning_the_password(self, api):
        _, out = call(api + "/api/email", method="POST", headers=AUTH,
                      body={"host": "smtp.example.com", "port": 587,
                            "username": "u", "password": "hunter2",
                            "sender": "ghosteye@example.com",
                            "recipients": "dana@example.com"})
        assert out["saved"] is True
        assert "hunter2" not in json.dumps(out)
        _, out = call(api + "/api/email", headers=AUTH)
        assert out["smtp"]["host"] == "smtp.example.com"
        assert out["smtp"]["password_set"] is True
        assert "hunter2" not in json.dumps(out)

    def test_sending_to_a_bad_address_is_a_400_not_a_traceback(self, api):
        code, out = call(api + "/api/email", method="POST", headers=AUTH,
                         body={"action": "send", "to": "not-an-address"})
        assert code == 400 and "invalid recipient" in out["error"]

    def test_sending_for_an_unknown_job_is_a_404(self, api):
        code, _ = call(api + "/api/email", method="POST", headers=AUTH,
                       body={"action": "send", "to": "dana@example.com",
                             "job_id": "no-such-job"})
        assert code == 404


class TestSearchAll:
    def test_a_query_is_required(self, api):
        code, out = call(api + "/api/search-all", headers=AUTH)
        assert code == 400 and "q required" in out["error"]

    def test_an_empty_store_is_an_empty_result_not_an_error(self, api):
        code, out = call(api + "/api/search-all?q=nginx", headers=AUTH)
        assert code == 200
        assert out["count"] == 0 and out["matches"] == []
        assert "stored_scans" in out


class TestEstimate:
    def test_it_answers_without_any_history(self, api):
        _, out = call(api + "/api/estimate?mode=all", headers=AUTH)
        assert out["modules"] > 0
        assert out["have_history"] is False
        assert "no timing history" in out["note"]

    def test_it_names_the_modules_that_spend_a_paid_quota(self, api):
        """A "this scan is free" estimate right before it burns a month of
        your VirusTotal allowance is worse than no estimate."""
        _, out = call(api + "/api/estimate?mode=all", headers=AUTH)
        assert "virustotal" in out["paid_api_modules"]

    def test_a_narrow_selection_estimates_fewer_modules(self, api):
        _, one = call(api + "/api/estimate?mode=modules&value=headers", headers=AUTH)
        _, allm = call(api + "/api/estimate?mode=all", headers=AUTH)
        assert one["modules"] == 1
        assert one["modules"] < allm["modules"]

    def test_an_unknown_module_estimates_nothing_rather_than_everything(self, api):
        _, out = call(api + "/api/estimate?mode=modules&value=no-such-module",
                      headers=AUTH)
        assert out["modules"] == 0
