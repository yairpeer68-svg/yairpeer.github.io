"""Module health harness — catch silent failure across 551 modules.

A recon tool's worst failure isn't a crash; it's a module that keeps returning
*something* long after the source behind it changed. `commoncrawl` did exactly
that — it queried a hard-coded crawl index and quietly went stale for months,
and nothing flagged it because it never raised and the smoke test only checks
"returns a Result".

This harness answers the question the smoke test can't: **does this module
actually still work today?** It runs each module against a stable, known-good
canary target over the real network and classifies the outcome:

    healthy    ran, returned data, and (if the module declares a shape) the
               shape matched
    degraded   ran without error but returned nothing — ambiguous: the canary
               may genuinely have nothing, or the source may be broken
    broken     errored, or returned data whose shape is wrong (a strong signal
               the upstream source or format changed) — the silent-failure case
    no_key     needs an API key that isn't configured — skipped, not a fault
    skipped    opted out of health checks (health_target = False)

It needs the network, so it is **never** part of CI or the offline test suite;
it is a diagnostic you run on demand (`ghost-eye --check-health`). The harness's
own logic is what the offline tests cover, using mock modules.

A module opts into real shape-checking by declaring ``expect`` (see
``core.Module``) — a list of keys a healthy result must carry, or a predicate.
Without it, health falls back to the generic "ran and returned data".
"""

from __future__ import annotations

import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional

from .core import Context, Module, Result, build_session
from .reporting import _flatten

# stable, well-known-good probe targets per target_kind
DEFAULT_CANARY: Dict[str, str] = {
    "domain": "example.com",
    "host": "example.com",
    "url": "https://example.com",
    "ip": "1.1.1.1",            # Cloudflare resolver — stable, always up
    "username": "torvalds",     # exists on GitHub, GitLab, many sites
    "email": "security@example.com",
}

HEALTHY, DEGRADED, BROKEN, NO_KEY, SKIPPED = (
    "healthy", "degraded", "broken", "no_key", "skipped")

# error text that means "not broken, just unconfigured"
_KEY_HINTS = ("api key", "api-key", "requires the", "set the", "_api_key",
              "missing api", "no key", "token env", "provide a key")


def canary_for(module: Module) -> Optional[str]:
    """The probe target for a module, or None if it opts out."""
    ht = getattr(module, "health_target", None)
    if ht is False:
        return None
    if ht:
        return str(ht)
    return DEFAULT_CANARY.get(getattr(module, "target_kind", "host"), "example.com")


def check_expect(expect: Any, data: Dict[str, Any]) -> Optional[bool]:
    """Evaluate a module's declared shape against its result data.

    Returns True/False, or None when no shape was declared. A list of keys is
    matched against the *flattened* data (so 'a.b' matches nested), each with a
    non-empty value; a callable is called with the raw data.
    """
    if expect is None:
        return None
    if callable(expect):
        try:
            return bool(expect(data))
        except Exception:  # noqa: BLE001 - a throwing predicate == unhealthy
            return False
    _EMPTY = ("", "none", "None", "[]", "{}", "null")

    def _present(value: Any) -> bool:
        return value not in (None, "", [], {}) and str(value).strip() not in _EMPTY

    flat: Dict[str, str] = {}
    _flatten("", data or {}, flat)
    top = data or {}
    for key in expect:
        k = str(key)
        # satisfied only when the key exists AND carries a non-empty value,
        # whether at the top level or as a nested tail (a.b.key)
        if k in top and _present(top[k]):
            continue
        if any((fk == k or fk.endswith("." + k)) and _present(v)
               for fk, v in flat.items()):
            continue
        return False
    return True


def classify(module: Module, result: Result, elapsed: float,
             error: Optional[str] = None) -> Dict[str, Any]:
    """Turn one probe outcome into a health verdict."""
    mid = getattr(module, "id", "?")
    base = {"id": mid, "name": getattr(module, "name", mid),
            "category": getattr(module, "category", ""),
            "elapsed": round(elapsed, 2)}

    if error is not None:                      # harness-level failure/timeout
        return {**base, "status": BROKEN, "detail": error}

    status = getattr(result, "status", "error")
    err = (getattr(result, "error", "") or "")
    if status == "error":
        if any(h in err.lower() for h in _KEY_HINTS):
            return {**base, "status": NO_KEY, "detail": err[:120]}
        return {**base, "status": BROKEN, "detail": err[:160] or "module errored"}

    data = getattr(result, "data", {}) or {}
    if status == "empty" or not data:
        return {**base, "status": DEGRADED,
                "detail": "ran without error but returned no data"}

    shape = check_expect(getattr(module, "expect", None), data)
    if shape is False:
        return {**base, "status": BROKEN,
                "detail": "result is missing its expected fields — the upstream "
                          "source or format has likely changed (silent failure)"}
    return {**base, "status": HEALTHY,
            "detail": "shape verified" if shape else "returned data"}


def _base(module: Module) -> Dict[str, Any]:
    return {"id": getattr(module, "id", "?"),
            "name": getattr(module, "name", ""),
            "category": getattr(module, "category", "")}


def probe_module(module: Module, ctx: Context, timeout: float = 20.0) -> Dict[str, Any]:
    """Probe one module and classify its health.

    A module with a custom ``health_probe`` (e.g. the exploit-intelligence
    module, which self-tests against known-weaponised CVEs) is trusted to judge
    itself; everything else is run against a canary target and classified by
    whether usable data came back.
    """
    t0 = time.time()
    # 1) custom self-test, when the module knows how to check itself
    try:
        custom = module.health_probe(ctx)
    except Exception as exc:  # noqa: BLE001
        return {**_base(module), "status": BROKEN, "elapsed": round(time.time() - t0, 2),
                "detail": f"health_probe crashed: {str(exc)[:120]}"}
    if custom is not None:
        ok = bool(custom.get("ok"))
        return {**_base(module),
                "status": HEALTHY if ok else BROKEN,
                "detail": custom.get("detail", "self-test " + ("passed" if ok else "FAILED")),
                "checks": custom.get("checks"),
                "elapsed": round(time.time() - t0, 2)}

    # 2) generic canary-target probe
    target = canary_for(module)
    if target is None:
        return {**_base(module), "status": SKIPPED,
                "detail": "opted out (health_target=False)", "elapsed": 0.0}
    from . import engine
    try:
        result = engine.execute_module(module, target, ctx)
        return classify(module, result, time.time() - t0)
    except Exception as exc:  # noqa: BLE001 - execute_module shouldn't raise, but
        return classify(module, None, time.time() - t0,
                        error=f"probe crashed: {str(exc)[:120]}")


def run_health_checks(modules: List[Module], cfg: Any = None,
                      timeout: float = 20.0, workers: int = 12,
                      on_result=None) -> Dict[str, Any]:
    """Probe a set of modules and aggregate a health report.

    NETWORK-BOUND — this actually contacts each module's upstream source, so it
    is for on-demand diagnostics, never CI.
    """
    session = build_session(timeout=int(timeout))
    ctx = Context(config=cfg, session=session, timeout=int(timeout))
    results: List[Dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=max(1, workers)) as ex:
        futs = {ex.submit(probe_module, m, ctx, timeout): m for m in modules}
        for fut in as_completed(futs):
            try:
                r = fut.result()
            except Exception as exc:  # noqa: BLE001
                m = futs[fut]
                r = {"id": getattr(m, "id", "?"), "status": BROKEN,
                     "detail": f"harness error: {exc}", "elapsed": 0.0}
            results.append(r)
            if on_result:
                try:
                    on_result(r)
                except Exception:  # noqa: BLE001
                    pass

    buckets: Dict[str, List[Dict[str, Any]]] = {
        s: [] for s in (HEALTHY, DEGRADED, BROKEN, NO_KEY, SKIPPED)}
    for r in results:
        buckets.setdefault(r["status"], []).append(r)
    checked = len(results) - len(buckets[SKIPPED]) - len(buckets[NO_KEY])
    healthy = len(buckets[HEALTHY])
    return {
        "total": len(results),
        "counts": {s: len(v) for s, v in buckets.items()},
        "health_pct": round(100 * healthy / checked, 1) if checked else 0.0,
        "broken": sorted(buckets[BROKEN], key=lambda r: r["id"]),
        "degraded": sorted(buckets[DEGRADED], key=lambda r: r["id"]),
        "healthy": sorted(r["id"] for r in buckets[HEALTHY]),
        "no_key": sorted(r["id"] for r in buckets[NO_KEY]),
        "skipped": sorted(r["id"] for r in buckets[SKIPPED]),
        "results": sorted(results, key=lambda r: (r["status"], r["id"])),
        "note": "network diagnostic: 'broken' = errored or wrong-shaped output "
                "(check these first); 'degraded' = ran but empty (may be the "
                "canary, may be broken); 'no_key' = needs an API key. Declare "
                "`expect` on a module to catch silent-staleness in its shape.",
    }
