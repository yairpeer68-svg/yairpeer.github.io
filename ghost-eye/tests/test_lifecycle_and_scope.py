"""Lifecycle, resource release, and scope on every path that reaches the wire.

The load/abuse gate asks "can a caller make this consume more?". These are the
neighbouring questions it does not answer, and each one below was a real defect:

  * does every job reach a terminal state, on every failure path?
  * does every resource a job opens get released?
  * is the scope guard applied to *every* attacker-controlled value that
    becomes an outbound destination — not just the one named `target`?

The last one is the sharp end. `/api/verify-origin` scope-checked `host` and
then connected to every IP in `candidates` without checking any of them, which
turns a scoped recon tool into a request-forgery primitive against anything
routable from the host running it.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request

import pytest

TOKEN = "LIFETOKEN"
AUTH = {"X-Ghost-Token": TOKEN}


@pytest.fixture(scope="module")
def api(tmp_path_factory):
    from ghost_eye import webapp
    from ghost_eye.scope import Scope

    port = 8923
    scope_file = tmp_path_factory.mktemp("scope") / "scope.txt"
    scope_file.write_text("example.com\n", encoding="utf-8")

    def _serve():
        webapp.serve(host="127.0.0.1", port=port,
                     db=str(tmp_path_factory.mktemp("lf") / "l.db"),
                     scope_file=str(scope_file), auth_token=TOKEN, quiet=True)

    threading.Thread(target=_serve, daemon=True).start()
    for _ in range(50):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=1).read()
            break
        except Exception:  # noqa: BLE001
            time.sleep(0.1)
    del Scope
    yield f"http://127.0.0.1:{port}"


def call(url, *, method="GET", body=None, headers=None, timeout=20):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw or "{}")
        except ValueError:
            return e.code, {"raw": raw[:200]}


# --------------------------------------------------------------------------- #
class TestOriginCandidatesAreValidated:
    """The candidates become direct HTTP destinations. Scope-checking only the
    hostname beside them is scope-checking the label on the envelope."""

    def test_a_candidate_outside_scope_is_refused(self, api):
        """A genuinely public address, so this exercises the scope branch and
        not the private-range one — 203.0.113.x is RFC 5737 documentation
        space, which Python already classifies as private."""
        code, out = call(api + "/api/verify-origin", method="POST", headers=AUTH,
                         body={"host": "example.com", "candidates": ["8.8.8.8"]})
        assert code == 403, f"got {code}: {out}"
        assert "scope" in json.dumps(out).lower(), out

    @pytest.mark.parametrize("bad", [
        "127.0.0.1", "localhost", "169.254.169.254",   # cloud metadata
        "10.0.0.5", "192.168.1.1", "172.16.0.1",
        "0.0.0.0", "::1", "fd00::1",
    ])
    def test_private_and_loopback_candidates_are_refused(self, api, bad):
        """169.254.169.254 is the cloud metadata endpoint. A recon tool that
        will fetch it on request hands over the host's own credentials."""
        code, out = call(api + "/api/verify-origin", method="POST", headers=AUTH,
                         body={"host": "example.com", "candidates": [bad]})
        assert code == 403, f"{bad} was accepted ({code}): {out}"

    @pytest.mark.parametrize("junk", [
        "not-an-ip", "example.com", "http://evil.test", "1.2.3.4:8080/x",
        "1.2.3.4 1.2.3.5", "*", "", "   ",
    ])
    def test_a_candidate_that_is_not_a_bare_ip_is_refused(self, api, junk):
        """The probe builds a URL from this. Anything that is not exactly an
        IP literal is either a bug or an attempt."""
        code, _ = call(api + "/api/verify-origin", method="POST", headers=AUTH,
                       body={"host": "example.com", "candidates": [junk]})
        assert code in (400, 403), f"{junk!r} was accepted"

    def test_the_candidate_list_is_bounded(self, api):
        code, _ = call(api + "/api/verify-origin", method="POST", headers=AUTH,
                       body={"host": "example.com",
                             "candidates": [f"203.0.113.{i % 254}" for i in range(5000)]})
        assert code in (400, 403)

    def test_a_refusal_is_audited(self, api):
        call(api + "/api/verify-origin", method="POST", headers=AUTH,
             body={"host": "example.com", "candidates": ["169.254.169.254"]})
        _, out = call(api + "/api/audit?limit=50", headers=AUTH)
        assert any(not e.get("ok", True) for e in out["entries"]), \
            "a refused origin probe left no audit trail"

    def test_the_validator_is_reusable_and_explains_itself(self):
        from ghost_eye.webapp import validate_candidates
        ok, reason = validate_candidates(["169.254.169.254"], scope=None)
        assert not ok and "metadata" in reason.lower()


class TestEveryNetworkEndpointIsScoped:
    """Derived from the router rather than from a list someone maintains: a
    new endpoint that reaches the network is caught the day it is added."""

    def test_no_post_route_reaches_the_wire_unscoped(self):
        import inspect
        import re
        from ghost_eye import webapp
        post = inspect.getsource(webapp.Handler._post_route)
        routes = re.findall(r'if path == "(/api/[a-z0-9_-]+)":\s*\n\s+'
                            r'return self\.(\w+)\(', post)
        assert routes, "the router shape changed; this test is now blind"
        unscoped = []
        for path, name in routes:
            fn = getattr(webapp.Handler, name, None)
            if fn is None:
                continue
            body = inspect.getsource(fn)
            reaches = any(k in body for k in ("workflow.", "jobs.create",
                                              "verify_origin", "entity_investigation"))
            guarded = ("scope" in body) or ("_scope_check" in body)
            if reaches and not guarded:
                unscoped.append(f"{path} -> {name}")
        assert not unscoped, f"network-reaching and unscoped: {unscoped}"


class TestJobsAlwaysReachATerminalState:
    """A thread that dies leaves status='running' for ever: the UI spins, the
    worker budget is never returned, and nothing is persisted."""

    def test_a_context_failure_still_ends_the_job(self, api):
        """Context construction happened outside the try, so a bad proxy left
        the job wedged in 'running' with its workers held."""
        code, out = call(api + "/api/scan", method="POST", headers=AUTH,
                         body={"target": "example.com",
                               "selection": {"mode": "modules", "value": ["headers"]},
                               "options": {"proxy": "not://a valid proxy",
                                           "timeout": 3}})
        assert code == 200, out
        jid = out["job_id"]
        for _ in range(60):
            time.sleep(0.5)
            _, snap = call(api + f"/api/job/{jid}", headers=AUTH)
            if snap.get("status") != "running":
                break
        assert snap["status"] in ("done", "error", "cancelled"), \
            f"job stuck in {snap.get('status')!r}"

    def test_the_worker_budget_survives_a_failed_job(self, api):
        from ghost_eye.webapp import WORKER_BUDGET, MAX_TOTAL_WORKERS
        # give the previous test's job a moment to unwind
        for _ in range(20):
            if WORKER_BUDGET.available == MAX_TOTAL_WORKERS:
                break
            time.sleep(0.5)
        assert WORKER_BUDGET.available == MAX_TOTAL_WORKERS, \
            f"{MAX_TOTAL_WORKERS - WORKER_BUDGET.available} worker(s) leaked"

    def test_context_construction_is_inside_the_guarded_block(self):
        import inspect
        from ghost_eye import webapp
        src = inspect.getsource(webapp.JobManager._run)
        assert src.index("try:") < src.index("_make_ctx"), \
            "_make_ctx runs before the try, so its failure kills the thread"


class TestResourcesAreReleased:
    def test_the_session_is_closed_when_a_job_ends(self):
        import inspect
        from ghost_eye import webapp
        src = inspect.getsource(webapp.JobManager._run)
        assert "close_session" in src or ".close()" in src, \
            "requests.Session holds a connection pool; nothing closes it"

    def test_it_is_closed_on_the_failure_path_too(self):
        import inspect
        from ghost_eye import webapp
        src = inspect.getsource(webapp.JobManager._run)
        finally_block = src[src.rindex("finally:"):]
        assert "close" in finally_block, \
            "the session is only closed on the happy path"


class TestStateWritesAreAtomic:
    """A crash mid-write leaves half a JSON file, and the next read silently
    returns {} — the state is not corrupted, it is *gone*, quietly."""

    def test_the_writer_uses_a_temp_file_and_replace(self):
        import inspect
        from ghost_eye import webapp
        src = inspect.getsource(webapp.atomic_write_json)
        assert "os.replace" in src, "state is written in place"
        assert src.index("json.dumps") < src.index("open("), \
            "the file is opened before the value is known to be serialisable"

    def test_a_crash_mid_write_leaves_the_old_state_readable(self, tmp_path):
        from ghost_eye.webapp import atomic_write_json
        path = tmp_path / "notes.json"
        atomic_write_json(path, {"a": 1})
        try:
            atomic_write_json(path, {"b": object()})   # not serialisable
        except Exception:  # noqa: BLE001
            pass
        assert json.loads(path.read_text()) == {"a": 1}, \
            "a failed write destroyed the previous state"

    def test_no_temp_files_are_left_behind(self, tmp_path):
        from ghost_eye.webapp import atomic_write_json
        path = tmp_path / "notes.json"
        for i in range(5):
            atomic_write_json(path, {"n": i})
        assert [p.name for p in tmp_path.iterdir()] == ["notes.json"]
