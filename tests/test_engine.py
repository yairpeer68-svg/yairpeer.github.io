"""Tests for the central scan engine — the shared execution path used by the
CLI, the dashboard and the API."""

from __future__ import annotations

from types import SimpleNamespace

from ghost_eye import engine
from ghost_eye.core import Result


def _ctx():
    return SimpleNamespace(config=None, session=None, threads=2, timeout=1)


class _Boom:
    id = name = "boom"
    def run(self, target, ctx):
        raise RuntimeError("kaboom")


class _Bad:
    id = name = "bad"
    def run(self, target, ctx):
        return "not a Result"


class _Good:
    id = name = "good"
    def run(self, target, ctx):
        return Result("good", target, "ok", {"v": 1})


def test_execute_module_crash_becomes_error_and_is_logged(tmp_path, monkeypatch):
    monkeypatch.setenv("GHOSTEYE_ERRORLOG", str(tmp_path / "e.log"))
    r = engine.execute_module(_Boom(), "x", _ctx())
    assert r.status == "error" and "kaboom" in (r.error or "")
    assert "kaboom" in (tmp_path / "e.log").read_text()


def test_execute_module_coerces_non_result():
    r = engine.execute_module(_Bad(), "x", _ctx())
    assert isinstance(r, Result) and r.status == "error"


def test_execute_module_passes_through_ok():
    r = engine.execute_module(_Good(), "x", _ctx())
    assert r.status == "ok" and r.data == {"v": 1}


def test_run_scan_sequential_preserves_order():
    res = engine.run_scan([_Good(), _Boom(), _Good()], "x", _ctx(), parallel=1)
    assert [r.status for r in res] == ["ok", "error", "ok"]


def test_run_scan_parallel_runs_all():
    res = engine.run_scan([_Good()] * 6, "x", _ctx(), parallel=4)
    assert len(res) == 6 and all(r.status == "ok" for r in res)


def test_run_scan_on_result_hook_fires():
    seen = []
    engine.run_scan([_Good(), _Good()], "x", _ctx(), parallel=1,
                    on_result=lambda m, r: seen.append(r.status))
    assert seen == ["ok", "ok"]


def test_run_scan_cancel_stops_early():
    res = engine.run_scan([_Good()] * 5, "x", _ctx(), parallel=1,
                          should_cancel=lambda: True)
    assert res == []


def test_benchmark_harness_runs():
    import importlib.util
    import os
    path = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                        "scripts", "benchmark.py")
    spec = importlib.util.spec_from_file_location("ghosteye_bench", path)
    bench = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bench)
    r = bench._run(targets=3, modules=4, parallel=2, latency=0.0)
    assert r["module_runs"] == 12 and r["results_held"] == 12
    assert r["runs_per_s"] > 0 and r["rss_mb"] > 0
