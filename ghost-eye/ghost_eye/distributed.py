"""Distributed scanning (feature 75).

A shared SQLite job queue that lets **several Ghost Eye workers** — processes on
one box, or machines sharing a queue file over NFS/a synced dir — cooperate on a
large target list without ever scanning the same target twice.

  * ``JobQueue.enqueue(target, profile)`` — a coordinator adds work.
  * ``JobQueue.claim(worker)`` — a worker atomically grabs one pending job
    (``BEGIN IMMEDIATE`` serialises claimers, so no double-processing).
  * ``JobQueue.complete(id, summary)`` — the worker records the outcome.
  * ``run_worker(...)`` — the loop: claim → scan → complete, until drained.

CLI:  ``ghost_eye.py --queue jobs.db --enqueue -T targets.txt -p perimeter``
      ``ghost_eye.py --queue jobs.db --worker``   (run on as many hosts as you like)

Reconnaissance/detection only — the workers run the same safe modules the CLI
does; this just spreads them out.
"""

from __future__ import annotations

import json
import os
import socket
import time
from pathlib import Path
from typing import Any, Dict, List, Optional


class JobQueue:
    def __init__(self, path: str) -> None:
        import sqlite3
        self.path = path
        if path != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        # isolation_level=None -> explicit transaction control for atomic claim
        self.conn = sqlite3.connect(path, timeout=30, isolation_level=None)
        self.conn.execute("PRAGMA journal_mode=WAL")
        self.conn.execute(
            """CREATE TABLE IF NOT EXISTS queue(
                   id INTEGER PRIMARY KEY AUTOINCREMENT,
                   target TEXT, profile TEXT, status TEXT DEFAULT 'pending',
                   worker TEXT, created REAL, claimed REAL, finished REAL,
                   summary TEXT)""")

    def enqueue(self, target: str, profile: str = "quick") -> int:
        cur = self.conn.execute(
            "INSERT INTO queue(target,profile,status,created) VALUES(?,?,?,?)",
            (target, profile, "pending", time.time()))
        return cur.lastrowid

    def enqueue_many(self, targets: List[str], profile: str = "quick") -> int:
        n = 0
        for t in targets:
            t = t.strip()
            if t:
                self.enqueue(t, profile)
                n += 1
        return n

    def claim(self, worker: str) -> Optional[Dict[str, Any]]:
        """Atomically claim the oldest pending job. Returns it, or None if the
        queue is drained. Safe under concurrent workers."""
        try:
            self.conn.execute("BEGIN IMMEDIATE")
            row = self.conn.execute(
                "SELECT id,target,profile FROM queue WHERE status='pending' "
                "ORDER BY created LIMIT 1").fetchone()
            if not row:
                self.conn.execute("COMMIT")
                return None
            jid, target, profile = row
            self.conn.execute(
                "UPDATE queue SET status='running',worker=?,claimed=? WHERE id=?",
                (worker, time.time(), jid))
            self.conn.execute("COMMIT")
            return {"id": jid, "target": target, "profile": profile}
        except Exception:  # noqa: BLE001
            try:
                self.conn.execute("ROLLBACK")
            except Exception:  # noqa: BLE001
                pass
            return None

    def complete(self, jid: int, summary: Dict[str, Any],
                 status: str = "done") -> None:
        self.conn.execute(
            "UPDATE queue SET status=?,finished=?,summary=? WHERE id=?",
            (status, time.time(), json.dumps(summary, ensure_ascii=False), jid))

    def stats(self) -> Dict[str, Any]:
        cur = self.conn.execute(
            "SELECT status,COUNT(*) FROM queue GROUP BY status")
        by = {s: n for s, n in cur.fetchall()}
        return {"pending": by.get("pending", 0), "running": by.get("running", 0),
                "done": by.get("done", 0), "error": by.get("error", 0),
                "total": sum(by.values())}

    def results(self, limit: int = 200) -> List[Dict[str, Any]]:
        cur = self.conn.execute(
            "SELECT id,target,profile,status,worker,summary FROM queue "
            "ORDER BY id LIMIT ?", (limit,))
        out = []
        for jid, target, profile, status, worker, summary in cur.fetchall():
            try:
                s = json.loads(summary) if summary else None
            except Exception:  # noqa: BLE001
                s = None
            out.append({"id": jid, "target": target, "profile": profile,
                        "status": status, "worker": worker, "summary": s})
        return out

    def requeue_stale(self, older_than: float = 3600) -> int:
        """Return jobs stuck 'running' (a worker died) back to 'pending'."""
        cutoff = time.time() - older_than
        cur = self.conn.execute(
            "UPDATE queue SET status='pending',worker=NULL WHERE status='running' "
            "AND claimed < ?", (cutoff,))
        return cur.rowcount

    def close(self) -> None:
        try:
            self.conn.close()
        except Exception:  # noqa: BLE001
            pass


def worker_id() -> str:
    return f"{socket.gethostname()}:{os.getpid()}"


def run_worker(queue_path: str, cfg, worker: str = "",
               max_jobs: int = 0, poll: float = 2.0, idle_exits: int = 3,
               scan_fn=None) -> Dict[str, Any]:
    """Claim-scan-complete loop. Exits after ``idle_exits`` empty polls (queue
    drained) or ``max_jobs`` completed. ``scan_fn(target, profile, cfg)`` runs a
    scan and returns a summary dict; the default uses the real engine."""
    q = JobQueue(queue_path)
    wid = worker or worker_id()
    done = 0
    idle = 0
    if scan_fn is None:
        scan_fn = _default_scan
    try:
        while True:
            job = q.claim(wid)
            if not job:
                idle += 1
                if idle >= idle_exits:
                    break
                time.sleep(poll)
                continue
            idle = 0
            try:
                summary = scan_fn(job["target"], job["profile"], cfg)
                q.complete(job["id"], summary, "done")
            except Exception as exc:  # noqa: BLE001
                q.complete(job["id"], {"error": str(exc)}, "error")
            done += 1
            if max_jobs and done >= max_jobs:
                break
    finally:
        stats = q.stats()
        q.close()
    return {"worker": wid, "completed": done, "queue": stats}


def _default_scan(target: str, profile: str, cfg) -> Dict[str, Any]:
    """Run a real scan for one target and return a compact summary."""
    from .core import get_module, REGISTRY, Context
    from . import engine, workflow
    from .reporting_ext import score_findings
    from .webapp import build_session  # reuse the hardened session builder
    recipes = workflow.load_recipes(None)
    ids = recipes.get(profile, recipes.get("quick", []))
    mods = [get_module(i) for i in ids if get_module(i)]
    session = build_session(timeout=15)
    ctx = Context(config=cfg, session=session, timeout=15)
    results = engine.run_scan(mods, target, ctx, parallel=4)
    score = score_findings(results)
    return {"target": target, "profile": profile, "modules": len(results),
            "risk_level": score.get("risk_level"),
            "risk_score": score.get("risk_score"),
            "findings": len(score.get("findings", []))}
