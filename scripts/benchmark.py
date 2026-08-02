#!/usr/bin/env python3
"""Offline benchmark for the Ghost Eye scan engine.

Uses synthetic modules (no network), so it measures the *engine + result
pipeline* itself: orchestration overhead, how parallelism helps under simulated
I/O latency, throughput, and memory held. Real network scans are dominated by
remote latency; this isolates what Ghost Eye actually controls.

    python3 scripts/benchmark.py
    python3 scripts/benchmark.py --targets 100 --modules 15 --latency-ms 20
"""
from __future__ import annotations

import argparse
import os
import resource
import sys
import time
import tracemalloc
from types import SimpleNamespace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ghost_eye import engine  # noqa: E402
from ghost_eye.core import Result  # noqa: E402


class _MockModule:
    """A module that returns a small Result, optionally after a fake I/O wait."""

    def __init__(self, idx: int, latency: float):
        self.id = self.name = f"mock{idx}"
        self._latency = latency

    def run(self, target, ctx):
        if self._latency:
            time.sleep(self._latency)
        return Result(self.name, target, "ok",
                      {"finding": "value", "n": idx_payload(target)})


def idx_payload(target: str) -> int:
    return len(target)


def _run(targets: int, modules: int, parallel: int, latency: float) -> dict:
    mods = [_MockModule(i, latency) for i in range(modules)]
    ctx = SimpleNamespace(config=None, session=None, threads=parallel, timeout=5)
    held = []
    tracemalloc.start()
    t0 = time.perf_counter()
    for k in range(targets):
        held.extend(engine.run_scan(mods, f"t{k}.example.com", ctx,
                                    parallel=parallel))
    secs = time.perf_counter() - t0
    _cur, peak = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    rss_kb = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss  # KiB on Linux
    runs = targets * modules
    return {
        "targets": targets, "modules": modules, "parallel": parallel,
        "latency_ms": int(latency * 1000), "module_runs": runs,
        "secs": round(secs, 3), "runs_per_s": round(runs / secs, 1),
        "results_held": len(held), "py_peak_mb": round(peak / 1e6, 1),
        "rss_mb": round(rss_kb / 1024, 1),
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--targets", type=int, default=100)
    ap.add_argument("--modules", type=int, default=15)
    ap.add_argument("--latency-ms", type=int, default=20)
    args = ap.parse_args()
    lat = args.latency_ms / 1000.0

    configs = [
        ("engine overhead (no I/O)", args.targets, args.modules, 1, 0.0),
        ("engine overhead, parallel=10", args.targets, args.modules, 10, 0.0),
        (f"sequential, {args.latency_ms}ms I/O", args.targets, args.modules, 1, lat),
        (f"parallel=3, {args.latency_ms}ms I/O", args.targets, args.modules, 3, lat),
        (f"parallel=10, {args.latency_ms}ms I/O", args.targets, args.modules, 10, lat),
        ("scale: 10 targets", 10, args.modules, 3, lat),
    ]
    print(f"Ghost Eye engine benchmark  (targets×modules synthetic, no network)\n")
    hdr = (f"{'scenario':<32} {'runs':>6} {'secs':>7} {'runs/s':>9} "
           f"{'py_peak':>8} {'rss':>7}")
    print(hdr)
    print("-" * len(hdr))
    for label, tgt, mod, par, la in configs:
        r = _run(tgt, mod, par, la)
        print(f"{label:<32} {r['module_runs']:>6} {r['secs']:>7} "
              f"{r['runs_per_s']:>9} {r['py_peak_mb']:>6}MB {r['rss_mb']:>5}MB")
    print("\nnote: py_peak = Python objects held (tracemalloc); rss = process "
          "peak resident set. Synthetic modules isolate engine cost from network.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
