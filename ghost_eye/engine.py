"""Central scan engine.

The single place where a module is actually executed. The CLI, the web
dashboard and the JSON API all build a `Context` and drive their scans through
here, so the "run a module safely, turn a crash into an error Result, log it"
contract lives in exactly one location instead of being duplicated per entry
point.

Sequential mode (parallel<=1) is used by the CLI; threaded mode powers the
dashboard's live-streaming job engine. Both share `execute_module`, so error
handling is identical everywhere.
"""

from __future__ import annotations

from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from typing import Callable, List, Optional, Sequence

from .core import Context, Module, Result, record_error

# on_result(module, result) -> None  — called as each module finishes
ResultHook = Callable[[Module, Result], None]
CancelFn = Callable[[], bool]


def execute_module(module: Module, target: str, ctx: Context) -> Result:
    """Run one module and NEVER raise.

    A crash becomes a `status="error"` Result and is appended to the persistent
    error log (with a traceback). A module that returns a non-Result is also
    coerced into an error Result, so downstream code can always trust the type.
    """
    mid = getattr(module, "id", "?")
    try:
        res = module.run(target, ctx)
    except Exception as exc:  # noqa: BLE001 - one module must never kill a scan
        record_error(f"module {mid}", target, exc)
        return Result(getattr(module, "name", mid), target,
                      status="error", error=str(exc))
    if not isinstance(res, Result):
        record_error(f"module {mid}", target,
                     f"returned {type(res).__name__}, expected Result")
        return Result(getattr(module, "name", mid), target,
                      status="error", error="module did not return a Result")
    return res


def run_scan(modules: Sequence[Module], target: str, ctx: Context, *,
             parallel: int = 1, on_result: Optional[ResultHook] = None,
             should_cancel: Optional[CancelFn] = None) -> List[Result]:
    """Execute `modules` against `target`.

    parallel<=1  -> sequential, deterministic order (CLI).
    parallel>1   -> ThreadPoolExecutor, results delivered as they complete
                    (dashboard). `on_result` fires per module; `should_cancel`
                    is polled to allow a mid-scan stop.
    """
    results: List[Result] = []

    if parallel <= 1:
        for module in modules:
            if should_cancel and should_cancel():
                break
            res = execute_module(module, target, ctx)
            results.append(res)
            if on_result:
                on_result(module, res)
        return results

    with ThreadPoolExecutor(max_workers=parallel) as ex:
        futures = {ex.submit(execute_module, m, target, ctx): m for m in modules}
        pending = set(futures)
        while pending:
            if should_cancel and should_cancel():
                break
            done, pending = wait(pending, timeout=0.4,
                                 return_when=FIRST_COMPLETED)
            for fut in done:
                module = futures[fut]
                res = fut.result()          # execute_module never raises
                results.append(res)
                if on_result:
                    on_result(module, res)
    return results
