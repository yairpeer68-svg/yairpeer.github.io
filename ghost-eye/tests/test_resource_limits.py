"""Resource behaviour under load and abuse.

Every other test in this suite asks "does it return a wrong answer or crash?"
and probes it with inputs the author chose. This file asks a different
question — "what does it *consume*, and can a caller make it consume more?" —
and it exists because an outside review found four defects that every existing
gate was structurally unable to see:

  * a caller could ask for unbounded thread concurrency,
  * a caller could post an unbounded request body,
  * several jobs at once multiplied concurrency with no ceiling,
  * cancel stopped collecting results without stopping the work.

None of those produces a wrong answer. All of them are real under load, and
none is visible to a single-threaded observer running one job at a time —
which is how every check before this one was written.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request

import pytest

TOKEN = "LIMITSTOKEN"
AUTH = {"X-Ghost-Token": TOKEN}


@pytest.fixture(scope="module")
def api(tmp_path_factory):
    from ghost_eye import webapp

    port = 8921
    threading.Thread(
        target=webapp.serve,
        kwargs={"host": "127.0.0.1", "port": port,
                "db": str(tmp_path_factory.mktemp("lim") / "l.db"),
                "auth_token": TOKEN, "quiet": True},
        daemon=True).start()
    for _ in range(50):
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=1).read()
            break
        except Exception:  # noqa: BLE001
            time.sleep(0.1)
    yield f"http://127.0.0.1:{port}"


def call(url, *, method="GET", body=None, raw=None, headers=None, timeout=15):
    data = raw if raw is not None else (
        json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, method=method, data=data)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.getcode(), json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw_body = e.read().decode()
        try:
            return e.code, json.loads(raw_body or "{}")
        except ValueError:
            return e.code, {"raw": raw_body[:200]}


# --------------------------------------------------------------------------- #
class TestConcurrencyIsBounded:
    """`parallel` came straight from the request body with only a lower bound,
    so one POST could ask for ten thousand threads."""

    def test_the_ceiling_exists_and_is_sane(self):
        from ghost_eye.webapp import MAX_PARALLEL
        assert 1 < MAX_PARALLEL <= 64, \
            "a ceiling nobody can hit is not a ceiling"

    @pytest.mark.parametrize("asked", [10_000, 500, 65, -1, 0])
    def test_a_request_cannot_exceed_it(self, asked):
        from ghost_eye.webapp import MAX_PARALLEL, clamp_parallel
        got = clamp_parallel(asked)
        assert 1 <= got <= MAX_PARALLEL, f"asked {asked}, got {got}"

    def test_a_reasonable_request_is_respected(self):
        from ghost_eye.webapp import clamp_parallel
        assert clamp_parallel(4) == 4

    def test_junk_falls_back_to_the_default_rather_than_raising(self):
        from ghost_eye.webapp import DEFAULT_PARALLEL, clamp_parallel
        for junk in ("abc", None, [], {}, "", float("nan")):
            assert clamp_parallel(junk) == DEFAULT_PARALLEL

    def test_the_engine_clamps_too(self):
        """The CLI reaches run_scan directly and must not be the way around
        the ceiling the API enforces."""
        from ghost_eye.engine import MAX_PARALLEL as ENGINE_MAX
        from ghost_eye.webapp import MAX_PARALLEL
        assert ENGINE_MAX == MAX_PARALLEL, \
            "two different ceilings means one of them is the real one"


class TestGlobalConcurrencyBudget:
    """Four jobs of 32 workers each is 128 threads, and every job looks fine
    on its own. The ceiling has to be global, not per job."""

    def test_a_global_budget_exists(self):
        from ghost_eye.webapp import MAX_TOTAL_WORKERS
        assert MAX_TOTAL_WORKERS >= 8

    def test_it_is_at_least_one_job_worth(self):
        from ghost_eye.webapp import MAX_PARALLEL, MAX_TOTAL_WORKERS
        assert MAX_TOTAL_WORKERS >= MAX_PARALLEL, \
            "a global budget below one job's ceiling deadlocks the first scan"

    def test_concurrent_jobs_share_the_budget(self):
        """The property that matters: N jobs must not mean N x parallel."""
        from ghost_eye.webapp import WORKER_BUDGET, MAX_TOTAL_WORKERS
        taken = []
        for _ in range(20):
            n = WORKER_BUDGET.take(16)
            taken.append(n)
            if n == 0:
                break
        assert sum(taken) <= MAX_TOTAL_WORKERS, \
            f"handed out {sum(taken)} of a {MAX_TOTAL_WORKERS} budget"
        for n in taken:
            WORKER_BUDGET.give(n)

    def test_the_budget_is_returned_when_a_job_ends(self):
        from ghost_eye.webapp import WORKER_BUDGET
        before = WORKER_BUDGET.available
        n = WORKER_BUDGET.take(4)
        assert WORKER_BUDGET.available == before - n
        WORKER_BUDGET.give(n)
        assert WORKER_BUDGET.available == before, "the budget leaked"

    def test_a_starved_job_still_gets_one_worker(self):
        """Zero workers is a job that hangs for ever; one is slow but honest."""
        from ghost_eye.webapp import WORKER_BUDGET
        drained = WORKER_BUDGET.take(WORKER_BUDGET.available)
        try:
            assert WORKER_BUDGET.take(8, minimum=1) == 1
            WORKER_BUDGET.give(1)
        finally:
            WORKER_BUDGET.give(drained)


class TestRequestBodyIsBounded:
    """`int(Content-Length)` then `rfile.read(length)` allocates whatever the
    caller declares."""

    def test_the_limit_exists(self):
        from ghost_eye.webapp import MAX_BODY_BYTES
        assert 1024 <= MAX_BODY_BYTES <= 64 * 1024 * 1024

    def test_an_oversized_body_is_refused_with_413(self, api):
        from ghost_eye.webapp import MAX_BODY_BYTES
        code, out = call(api + "/api/assign", method="POST", headers=AUTH,
                         raw=b'{"key":"' + b"x" * (MAX_BODY_BYTES + 1024) + b'"}')
        assert code == 413, f"got {code}: {out}"

    def test_an_oversized_body_is_refused_without_reading_it(self, api):
        """Refusing after buffering 2GB is not refusing. The check has to be
        answerable from the Content-Length header alone, before routing."""
        import inspect
        from ghost_eye import webapp
        gate = inspect.getsource(webapp.Handler._too_big)
        assert "rfile.read" not in gate, "the size gate reads the socket"
        assert "MAX_BODY_BYTES" in gate
        post = inspect.getsource(webapp.Handler._do_post)
        assert post.index("_too_big") < post.index("_post_route"), \
            "the size gate runs after the handler has already replied"

    def test_a_lying_content_length_does_not_hang_the_worker(self, api):
        """Declaring more than you send must not leave a thread blocked on a
        read that never completes."""
        from ghost_eye.webapp import MAX_BODY_BYTES
        code, _ = call(api + "/api/assign", method="POST",
                       headers={**AUTH, "Content-Length": str(MAX_BODY_BYTES * 4)},
                       raw=b'{"key":"short"}', timeout=10)
        assert code in (400, 413)

    def test_a_normal_body_still_works(self, api):
        code, _ = call(api + "/api/assign", method="POST", headers=AUTH,
                       body={"key": "fp-normal", "assignee": "dana"})
        assert code == 200


class TestCancellationStopsWork:
    """Cancel used to mean "stop collecting results". Queued modules were
    dropped and further session requests refused, but a module already inside
    a request ran to completion — so a cancelled 553-module scan kept working
    for as long as its slowest timeout."""

    def test_the_stop_event_reaches_the_context(self):
        from ghost_eye.core import Context
        assert hasattr(Context(), "stop_event"), \
            "modules have no way to notice a cancel"

    def test_the_engine_checks_it_before_running_a_module(self):
        """The cheapest real cancellation there is: do not start work that was
        cancelled while it sat in the queue."""
        import inspect
        from ghost_eye import engine
        assert "stop_event" in inspect.getsource(engine.execute_module)

    def test_a_cancelled_module_is_not_run(self):
        import threading as _t
        from ghost_eye.core import Context, Module, Result
        ran = []

        class _Slow(Module):
            id, name, category = "slowmod", "slowmod", "test"

            def run(self, target, ctx):
                ran.append(target)
                return Result(module=self.name, target=target)

        from ghost_eye.engine import execute_module
        ctx = Context()
        ctx.stop_event = _t.Event()
        ctx.stop_event.set()
        res = execute_module(_Slow(), "example.com", ctx)
        assert ran == [], "a cancelled module still ran"
        assert res.status in ("error", "empty", "cancelled")

    def test_the_engine_does_not_block_on_shutdown(self):
        """`with ThreadPoolExecutor(...)` calls shutdown(wait=True) on exit, so
        a cancelled CLI scan waits for every running module anyway."""
        import inspect
        from ghost_eye import engine
        src = inspect.getsource(engine.run_scan)
        assert "wait=False" in src, "run_scan still blocks on running modules"

    def test_cancel_is_reported_honestly(self, api):
        """Saying "stopped" while threads still run is the lie that matters."""
        import inspect
        from ghost_eye import webapp
        src = inspect.getsource(webapp.JobManager._run)
        assert "in_flight" in src or "still finishing" in src, \
            "nothing tells the user that in-flight modules are still draining"
