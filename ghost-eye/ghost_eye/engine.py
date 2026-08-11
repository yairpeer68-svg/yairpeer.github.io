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

import time
from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait
from typing import Callable, List, Optional, Sequence

from .core import Context, Module, Result, record_error

# on_result(module, result) -> None  — called as each module finishes
ResultHook = Callable[[Module, Result], None]
CancelFn = Callable[[], bool]


class AdaptiveRateLimiter:
    """Feature 66 — a self-tuning throttle. It watches the outcome of recent
    modules and adjusts the delay it inserts before the next one: errors /
    timeouts (a sign the target or the network is pushing back) widen the delay
    multiplicatively; clean successes narrow it back down. Thread-safe.

    ``base`` is the floor delay (seconds), ``ceiling`` the cap. Start it at 0 to
    make it a no-op until the first backoff is triggered.
    """

    def __init__(self, base: float = 0.0, ceiling: float = 5.0,
                 grow: float = 1.7, shrink: float = 0.85) -> None:
        import threading
        self.base = max(0.0, float(base))
        self.ceiling = max(self.base, float(ceiling))
        self.grow = grow
        self.shrink = shrink
        self.delay = self.base
        self._lock = threading.Lock()
        self.backoffs = 0

    def wait(self) -> None:
        with self._lock:
            d = self.delay
        if d > 0:
            time.sleep(min(d, self.ceiling))

    # error text that means "the far end is pushing back", as opposed to a
    # module that simply found nothing — these widen the delay hardest
    _BACKOFF_WORDS = ("timeout", "timed out", "rate", "429", "throttl",
                      "too many requests")

    def observe(self, res: Result) -> None:
        err = (getattr(res, "error", "") or "").lower()
        # `error` is normally only populated when status == "error", so testing
        # the text *only* in the non-error branch (as this used to) meant the
        # rate-limit keywords were never actually consulted.
        bad = (getattr(res, "status", "") == "error"
               or any(w in err for w in self._BACKOFF_WORDS))
        with self._lock:
            if bad:
                self.delay = min(self.ceiling,
                                 max(self.base, 0.25 if self.delay == 0
                                     else self.delay * self.grow))
                self.backoffs += 1
            else:
                self.delay = max(self.base, self.delay * self.shrink)

    def snapshot(self) -> dict:
        with self._lock:
            return {"delay": round(self.delay, 3), "ceiling": self.ceiling,
                    "backoffs": self.backoffs}


def execute_module(module: Module, target: str, ctx: Context) -> Result:
    """Run one module and NEVER raise.

    A crash becomes a `status="error"` Result and is appended to the persistent
    error log (with a traceback). A module that returns a non-Result is also
    coerced into an error Result, so downstream code can always trust the type.
    """
    mid = getattr(module, "id", "?")
    started = time.time()
    def _ms() -> int:
        return int((time.time() - started) * 1000)
    try:
        res = module.run(target, ctx)
    except Exception as exc:  # noqa: BLE001 - one module must never kill a scan
        record_error(f"module {mid}", target, exc)
        return Result(getattr(module, "name", mid), target,
                      status="error", error=str(exc), elapsed_ms=_ms())
    if not isinstance(res, Result):
        record_error(f"module {mid}", target,
                     f"returned {type(res).__name__}, expected Result")
        return Result(getattr(module, "name", mid), target,
                      status="error", error="module did not return a Result",
                      elapsed_ms=_ms())
    # a module that timed itself keeps its own number; everything else gets the
    # measurement it never had
    if not getattr(res, "elapsed_ms", 0):
        res.elapsed_ms = _ms()
    return res


def run_scan(modules: Sequence[Module], target: str, ctx: Context, *,
             parallel: int = 1, on_result: Optional[ResultHook] = None,
             should_cancel: Optional[CancelFn] = None,
             rate: Optional[AdaptiveRateLimiter] = None) -> List[Result]:
    """Execute `modules` against `target`.

    parallel<=1  -> sequential, deterministic order (CLI).
    parallel>1   -> ThreadPoolExecutor, results delivered as they complete
                    (dashboard). `on_result` fires per module; `should_cancel`
                    is polled to allow a mid-scan stop.

    `rate` (an AdaptiveRateLimiter) throttles between modules and widens the
    delay when the target/network starts erroring or rate-limiting us.
    """
    results: List[Result] = []

    if parallel <= 1:
        for module in modules:
            if should_cancel and should_cancel():
                break
            if rate:
                rate.wait()
            res = execute_module(module, target, ctx)
            if rate:
                rate.observe(res)
            results.append(res)
            if on_result:
                on_result(module, res)
        return results

    with ThreadPoolExecutor(max_workers=parallel) as ex:
        def _run(m: Module) -> Result:
            if rate:
                rate.wait()
            r = execute_module(m, target, ctx)
            if rate:
                rate.observe(r)
            return r
        futures = {ex.submit(_run, m): m for m in modules}
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
