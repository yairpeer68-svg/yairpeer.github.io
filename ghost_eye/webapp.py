"""Ghost Eye web dashboard backend (standard library only).

Exposes the existing module registry over a tiny HTTP API and serves a
single-page dashboard. No third-party web framework required.

    python3 ghost_eye_web.py                 # http://127.0.0.1:8777
    python3 ghost_eye_web.py --host 0.0.0.0 --port 9000

Binds to 127.0.0.1 (localhost only) by default. Authorised use only.
"""

from __future__ import annotations

import json
import threading
import time
import uuid
from concurrent.futures import (FIRST_COMPLETED, ThreadPoolExecutor,
                                as_completed, wait)
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, List, Optional
from urllib.parse import parse_qs, urlparse

from .config import Config
from .core import (Colors, Console, Context, REGISTRY, Result, build_session,
                   modules_by_category)
from . import reporting, reporting_ext, workflow

STATIC_DIR = Path(__file__).parent / "web_static"

_CONTENT_TYPES = {
    "json": "application/json", "sarif": "application/json",
    "csv": "text/csv", "html": "text/html", "dashboard": "text/html",
    "pdf": "application/pdf", "md": "text/markdown", "markdown": "text/markdown",
    "prometheus": "text/plain", "prom": "text/plain",
}
_EXT_FORMATS = {"md", "markdown", "sarif", "prometheus", "prom", "dashboard"}


# --------------------------------------------------------------------------- #
#  Job manager - one background scan per job id
# --------------------------------------------------------------------------- #
class JobManager:
    def __init__(self, cfg: Config) -> None:
        self.cfg = cfg
        self.jobs: Dict[str, dict] = {}
        self.lock = threading.Lock()
        self.scope = None        # set by serve(); used to bound deep scans

    def create(self, target: str, modules: List, options: dict) -> str:
        jid = uuid.uuid4().hex[:12]
        with self.lock:
            self.jobs[jid] = {
                "id": jid, "target": target, "status": "running",
                "total": len(modules), "done": 0, "current": "",
                "results": [], "_results_obj": [], "risk": None,
                "started": time.time(), "finished": None, "error": None,
                "cancel": False, "_modules": modules, "options": options,
                "_stop": threading.Event(),
            }
        threading.Thread(target=self._run, args=(jid,), daemon=True).start()
        return jid

    def _make_ctx(self, options: dict, stop_event=None) -> Context:
        timeout = int(options.get("timeout") or 15)
        threads = int(options.get("threads") or 10)
        proxy = options.get("proxy") or None
        if options.get("tor"):
            proxy = "socks5h://127.0.0.1:9050"
        verify = not options.get("insecure")
        session = build_session(user_agent=options.get("user_agent") or None,
                                proxy=proxy, verify_tls=verify, timeout=timeout)
        extra = _parse_headers(options.get("headers"))
        if extra:
            session.headers.update(extra)
        rate = float(options.get("rate") or 0)
        cache_dir = ".ghosteye_cache" if options.get("cache") else None
        session = workflow.wrap_session(session, rate=rate, cache_dir=cache_dir,
                                        cache_ttl=int(options.get("cache_ttl") or 300),
                                        rate_per_host=float(options.get("rate_per_host") or 0))
        if stop_event is not None:
            inner = session.request
            def _guarded(method, url, **kw):
                if stop_event.is_set():
                    raise RuntimeError("scan stopped")
                return inner(method, url, **kw)
            session.request = _guarded
        return Context(config=self.cfg, session=session, threads=threads,
                       timeout=timeout, verbose=False)

    def _run(self, jid: str) -> None:
        job = self.jobs[jid]
        ctx = self._make_ctx(job["options"], job["_stop"])
        mods = job["_modules"]
        parallel = max(1, int(job["options"].get("parallel") or 3))
        target = job["target"]
        ex = ThreadPoolExecutor(max_workers=parallel)
        try:
            futures = {ex.submit(self._run_one, m, target, ctx): m for m in mods}
            pending = set(futures)
            while pending:
                if job["cancel"]:
                    break
                # wake up at least every 0.4s so cancel is honoured promptly,
                # even while modules are blocked on slow network timeouts
                done, pending = wait(pending, timeout=0.4,
                                     return_when=FIRST_COMPLETED)
                for fut in done:
                    m = futures[fut]
                    try:
                        res = fut.result()
                    except Exception as exc:  # noqa: BLE001
                        res = Result(module=getattr(m, "id", "?"), target=target,
                                     status="error", data={}, error=str(exc))
                    with self.lock:
                        job["_results_obj"].append(res)
                        job["results"].append(res.as_dict())
                        job["done"] += 1
                        job["current"] = getattr(m, "name", "")
                        try:
                            job["risk"] = reporting_ext.score_findings(job["_results_obj"])
                        except Exception:
                            pass
            # deep / recursive expansion to discovered subdomains + IPs
            if not job["cancel"] and job["options"].get("deep"):
                try:
                    from . import workflow
                    plan, assets = workflow.deep_plan(
                        job["_results_obj"], target, getattr(self, "scope", None),
                        int(job["options"].get("deep_max") or 25))
                    with self.lock:
                        job["total"] += sum(len(ms) for _a, ms in plan)
                        job["current"] = (f"deep: {len(assets['hosts'])} hosts + "
                                          f"{len(assets['ips'])} IPs")
                    for asset, ms in plan:
                        if job["cancel"]:
                            break
                        afut = {ex.submit(self._run_one, m, asset, ctx): m for m in ms}
                        apending = set(afut)
                        while apending and not job["cancel"]:
                            adone, apending = wait(apending, timeout=0.4,
                                                   return_when=FIRST_COMPLETED)
                            for fut in adone:
                                m = afut[fut]
                                try:
                                    res = fut.result()
                                except Exception as exc:  # noqa: BLE001
                                    res = Result(module=getattr(m, "id", "?"),
                                                 target=asset, status="error",
                                                 data={}, error=str(exc))
                                with self.lock:
                                    job["_results_obj"].append(res)
                                    job["results"].append(res.as_dict())
                                    job["done"] += 1
                                    try:
                                        job["risk"] = reporting_ext.score_findings(job["_results_obj"])
                                    except Exception:
                                        pass
                except Exception:
                    pass
            job["status"] = "cancelled" if job["cancel"] else "done"
        except Exception as exc:  # noqa: BLE001
            job["status"] = "error"
            job["error"] = str(exc)
        finally:
            # don't block on in-flight modules; drop anything still queued.
            # workers already running finish in the background within their
            # own socket timeout - we just stop collecting their results.
            try:
                ex.shutdown(wait=False, cancel_futures=True)
            except TypeError:                       # Python < 3.9
                ex.shutdown(wait=False)
            job["finished"] = time.time()
            self._persist(job)

    @staticmethod
    def _run_one(module, target: str, ctx: Context) -> Result:
        try:
            return module.run(target, ctx)
        except Exception as exc:  # noqa: BLE001
            return Result(module=getattr(module, "id", "?"), target=target,
                          status="error", data={}, error=str(exc))

    def _persist(self, job: dict) -> None:
        try:
            store = reporting.Store(self.cfg.get("db", "ghosteye.db"))
            risk = job.get("risk") or {}
            store.save_scan(job["id"], job["target"], job["_results_obj"],
                            risk.get("risk_level", ""), int(risk.get("risk_score", 0)))
            for r in job["_results_obj"]:
                store.save(r)        # per-module rows power the CLI --diff
            store.close()
        except Exception:
            pass

    def snapshot(self, jid: str) -> Optional[dict]:
        with self.lock:
            job = self.jobs.get(jid)
            if not job:
                return None
            return {k: v for k, v in job.items() if not k.startswith("_")}

    def results_obj(self, jid: str) -> Optional[List[Result]]:
        with self.lock:
            job = self.jobs.get(jid)
            return list(job["_results_obj"]) if job else None

    def cancel(self, jid: str) -> bool:
        with self.lock:
            job = self.jobs.get(jid)
            if job and job["status"] == "running":
                job["cancel"] = True
                job["_stop"].set()       # abort in-flight HTTP requests at once
                return True
        return False


def _parse_headers(raw) -> dict:
    """Accept a dict, or a 'Key: Value' string (one per line)."""
    if not raw:
        return {}
    if isinstance(raw, dict):
        return {str(k): str(v) for k, v in raw.items()}
    out = {}
    for line in str(raw).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            if k.strip():
                out[k.strip()] = v.strip()
    return out


# --------------------------------------------------------------------------- #
#  Module / profile metadata
# --------------------------------------------------------------------------- #
def _meta() -> dict:
    import ghost_eye
    mods = [{"id": m.id, "name": m.name, "category": m.category,
             "target_kind": getattr(m, "target_kind", "host")}
            for m in REGISTRY.values()]
    mods.sort(key=lambda x: (x["category"], x["name"]))
    cats = {c: [m.id for m in ms]
            for c, ms in sorted(modules_by_category().items())}
    recipes = workflow.load_recipes("recipes.yaml")
    return {"version": ghost_eye.__version__, "modules": mods,
            "categories": cats, "profiles": recipes}


def _select(payload: dict) -> List:
    mode = payload.get("mode", "all")
    val = payload.get("value")
    if mode == "all":
        return list(REGISTRY.values())
    if mode == "modules":
        ids = val if isinstance(val, list) else [val]
        return [REGISTRY[i] for i in ids if i in REGISTRY]
    if mode == "category":
        return modules_by_category().get(val, [])
    if mode == "profile":
        ids = workflow.load_recipes("recipes.yaml").get(val, [])
        return [REGISTRY[i] for i in ids if i in REGISTRY]
    return []


# --------------------------------------------------------------------------- #
#  HTTP handler
# --------------------------------------------------------------------------- #
class Handler(BaseHTTPRequestHandler):
    server_version = "GhostEye-web"

    # silence default logging; route through our logger only on errors
    def log_message(self, fmt, *args):  # noqa: D401
        pass

    # ---- helpers ---------------------------------------------------------- #
    def _json(self, obj, code=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _bytes(self, data: bytes, ctype: str, code=200, filename=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        if filename:
            self.send_header("Content-Disposition",
                             f'attachment; filename="{filename}"')
        self.end_headers()
        self.wfile.write(data)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    # ---- routing ---------------------------------------------------------- #
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path in ("/", "/index.html"):
            return self._serve_index()
        if path == "/api/meta":
            return self._json(_meta())
        if path == "/api/history":
            return self._json({"history": self._history()})
        if path == "/api/compare":
            return self._compare_scans(parsed)
        if path == "/api/schedules":
            return self._list_schedules()
        if path.startswith("/api/scan/"):
            return self._saved_scan(path.split("/")[3] if len(path.split("/")) > 3 else "")
        if path.startswith("/api/job/"):
            return self._job_get(path, parsed)
        if path.startswith("/static/"):
            return self._serve_static(path)
        return self._json({"error": "not found"}, 404)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path == "/api/scan":
            return self._scan_start()
        if path == "/api/schedule":
            return self._schedule_create()
        if path.startswith("/api/job/") and path.endswith("/cancel"):
            jid = path.split("/")[3]
            ok = self.server.jobs.cancel(jid)
            return self._json({"cancelled": ok})
        return self._json({"error": "not found"}, 404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if path.startswith("/api/schedule/"):
            sid = path.split("/")[3] if len(path.split("/")) > 3 else ""
            ok = self.server.scheduler.remove(sid)
            return self._json({"deleted": ok})
        return self._json({"error": "not found"}, 404)

    # ---- endpoints -------------------------------------------------------- #
    def _scan_start(self):
        payload = self._body()
        target = (payload.get("target") or "").strip()
        if not target:
            return self._json({"error": "target required"}, 400)
        scope = getattr(self.server, "scope", None)
        if scope is not None and not scope.empty:
            allowed, reason = scope.allows(target)
            if not allowed:
                return self._json({"error": f"out of scope: {reason}"}, 403)
        modules = _select(payload.get("selection") or {"mode": "all"})
        if not modules:
            return self._json({"error": "no modules matched selection"}, 400)
        options = payload.get("options") or {}
        jid = self.server.jobs.create(target, modules, options)
        return self._json({"job_id": jid, "total": len(modules)})

    def _job_get(self, path: str, parsed):
        parts = path.split("/")          # ['', 'api', 'job', '<id>', maybe 'report']
        jid = parts[3] if len(parts) > 3 else ""
        sub = parts[4] if len(parts) > 4 else ""
        if sub == "report":
            return self._job_report(jid, parsed)
        if sub == "inventory":
            return self._job_inventory(jid)
        if sub == "rollup":
            return self._job_rollup(jid)
        if sub == "diff":
            return self._job_diff(jid, parsed)
        snap = self.server.jobs.snapshot(jid)
        if snap is None:
            return self._json({"error": "unknown job"}, 404)
        return self._json(snap)

    def _job_inventory(self, jid: str):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        return self._json(reporting_ext.build_inventory(results, target))

    def _job_rollup(self, jid: str):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        return self._json({"hosts": reporting_ext.build_host_rollup(results, target)})

    def _job_diff(self, jid: str, parsed):
        against = parse_qs(parsed.query).get("against", [""])[0]
        cur = self.server.jobs.results_obj(jid)
        if not cur:
            return self._json({"error": "no results yet"}, 404)
        try:
            store = reporting.Store(self.server.jobs.cfg.get("db", "ghosteye.db"))
            saved = store.load_scan(against)
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"history unavailable: {exc}"}, 500)
        if not saved:
            return self._json({"error": "comparison scan not found"}, 404)
        now = {r.module: r.data for r in cur}
        old = {x["module"]: x.get("data", {}) for x in saved["results"]}
        added = sorted(set(now) - set(old))
        removed = sorted(set(old) - set(now))
        changed = sorted(m for m in (set(now) & set(old)) if now[m] != old[m])
        return self._json({"against": against, "added_modules": added,
                           "removed_modules": removed, "changed_modules": changed,
                           "note": "modules whose findings differ from the saved run"})

    def _job_report(self, jid: str, parsed):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        fmt = (parse_qs(parsed.query).get("format", ["html"])[0]).lower()
        import tempfile
        tmp = Path(tempfile.mkdtemp()) / f"report.{fmt}"
        try:
            if fmt in _EXT_FORMATS:
                reporting_ext.export_ext(results, str(tmp), fmt, target)
            else:
                reporting.export(results, str(tmp), fmt, target)
        except RuntimeError:
            pass  # pdf->html fallback already wrote a file at tmp's sibling
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"export failed: {exc}"}, 500)
        if not tmp.exists():
            # reporting may have changed the suffix on fallback; find sibling
            sib = next(iter(tmp.parent.glob("report.*")), None)
            if sib:
                tmp = sib
            else:
                return self._json({"error": "export produced no file"}, 500)
        data = tmp.read_bytes()
        ctype = _CONTENT_TYPES.get(fmt, "application/octet-stream")
        dl = fmt not in ("html", "dashboard")   # view html inline, download rest
        safe = "".join(c for c in target if c.isalnum() or c in ".-_") or "report"
        fname = f"ghosteye_{safe}.{tmp.suffix.lstrip('.')}" if dl else None
        return self._bytes(data, ctype, filename=fname)

    def _saved_scan(self, scan_id: str):
        try:
            store = reporting.Store(self.server.jobs.cfg.get("db", "ghosteye.db"))
            saved = store.load_scan(scan_id)
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"history unavailable: {exc}"}, 500)
        if not saved:
            return self._json({"error": "scan not found"}, 404)
        return self._json({"id": scan_id, "target": saved["target"],
                           "results": saved["results"], "saved": True})

    def _history(self):
        rows = []
        try:
            store = reporting.Store(self.server.jobs.cfg.get("db", "ghosteye.db"))
            rows = store.recent_scans(40)
            store.close()
        except Exception:
            pass
        # include jobs from this session that are still running / unsaved
        jm = self.server.jobs
        seen = {r["id"] for r in rows}
        with jm.lock:
            for j in jm.jobs.values():
                if j["id"] not in seen:
                    rows.append({"id": j["id"], "target": j["target"],
                                 "ts": None, "modules": j["total"],
                                 "risk": (j["risk"] or {}).get("risk_level") if j["risk"] else None,
                                 "score": (j["risk"] or {}).get("risk_score") if j["risk"] else None,
                                 "status": j["status"]})
        return rows

    # ---- compare --------------------------------------------------------- #
    def _compare_scans(self, parsed):
        qs = parse_qs(parsed.query)
        scan_a = qs.get("a", [""])[0]
        scan_b = qs.get("b", [""])[0]
        if not scan_a or not scan_b:
            return self._json({"error": "provide ?a=<scan_id>&b=<scan_id>"}, 400)
        try:
            store = reporting.Store(self.server.jobs.cfg.get("db", "ghosteye.db"))
            a = store.load_scan(scan_a)
            b = store.load_scan(scan_b)
            store.close()
        except Exception as exc:
            return self._json({"error": f"history unavailable: {exc}"}, 500)
        if not a:
            return self._json({"error": f"scan {scan_a} not found"}, 404)
        if not b:
            return self._json({"error": f"scan {scan_b} not found"}, 404)
        a_mods = {r["module"]: r.get("data", {}) for r in a["results"]}
        b_mods = {r["module"]: r.get("data", {}) for r in b["results"]}
        added = sorted(set(b_mods) - set(a_mods))
        removed = sorted(set(a_mods) - set(b_mods))
        changed = []
        unchanged = []
        for m in sorted(set(a_mods) & set(b_mods)):
            if a_mods[m] != b_mods[m]:
                changed.append({"module": m, "old": a_mods[m], "new": b_mods[m]})
            else:
                unchanged.append(m)
        return self._json({
            "scan_a": {"id": scan_a, "target": a["target"]},
            "scan_b": {"id": scan_b, "target": b["target"]},
            "added": added, "removed": removed,
            "changed": changed, "unchanged": unchanged,
            "summary": {
                "added_count": len(added), "removed_count": len(removed),
                "changed_count": len(changed), "unchanged_count": len(unchanged),
            }
        })

    # ---- schedules ------------------------------------------------------- #
    def _schedule_create(self):
        payload = self._body()
        target = (payload.get("target") or "").strip()
        if not target:
            return self._json({"error": "target required"}, 400)
        interval = int(payload.get("interval_minutes", 60))
        selection = payload.get("selection") or {"mode": "all"}
        options = payload.get("options") or {}
        sid = self.server.scheduler.add(target, interval, selection, options)
        return self._json({"schedule_id": sid, "interval_minutes": interval})

    def _list_schedules(self):
        return self._json({"schedules": self.server.scheduler.list_all()})

    # ---- static ----------------------------------------------------------- #
    def _serve_index(self):
        idx = STATIC_DIR / "index.html"
        if not idx.exists():
            return self._json({"error": "index.html missing"}, 500)
        self._bytes(idx.read_bytes(), "text/html")

    def _serve_static(self, path: str):
        name = path[len("/static/"):]
        f = (STATIC_DIR / name).resolve()
        if STATIC_DIR.resolve() not in f.parents or not f.exists():
            return self._json({"error": "not found"}, 404)
        import mimetypes
        ctype = mimetypes.guess_type(str(f))[0] or "application/octet-stream"
        self._bytes(f.read_bytes(), ctype)


# --------------------------------------------------------------------------- #
#  Scheduled recurring scans
# --------------------------------------------------------------------------- #
class Scheduler:
    def __init__(self, jobs: JobManager) -> None:
        self.jobs = jobs
        self._schedules: Dict[str, dict] = {}
        self._timers: Dict[str, threading.Timer] = {}
        self.lock = threading.Lock()

    def add(self, target: str, interval_minutes: int, selection: dict,
            options: dict) -> str:
        sid = uuid.uuid4().hex[:10]
        with self.lock:
            self._schedules[sid] = {
                "id": sid, "target": target,
                "interval_minutes": max(1, interval_minutes),
                "selection": selection, "options": options,
                "last_job": None, "run_count": 0, "active": True,
            }
        self._arm(sid)
        return sid

    def _arm(self, sid: str) -> None:
        sched = self._schedules.get(sid)
        if not sched or not sched["active"]:
            return
        interval = sched["interval_minutes"] * 60
        t = threading.Timer(interval, self._fire, args=(sid,))
        t.daemon = True
        t.start()
        self._timers[sid] = t

    def _fire(self, sid: str) -> None:
        with self.lock:
            sched = self._schedules.get(sid)
            if not sched or not sched["active"]:
                return
        modules = _select(sched["selection"])
        if modules:
            jid = self.jobs.create(sched["target"], modules, sched["options"])
            with self.lock:
                sched["last_job"] = jid
                sched["run_count"] += 1
        self._arm(sid)

    def remove(self, sid: str) -> bool:
        with self.lock:
            sched = self._schedules.get(sid)
            if not sched:
                return False
            sched["active"] = False
            timer = self._timers.pop(sid, None)
            if timer:
                timer.cancel()
            del self._schedules[sid]
            return True

    def list_all(self) -> List[dict]:
        with self.lock:
            return [{k: v for k, v in s.items()} for s in self._schedules.values()]


# --------------------------------------------------------------------------- #
#  Server
# --------------------------------------------------------------------------- #
def serve(host: str = "127.0.0.1", port: int = 8777,
          db: str = "ghosteye.db", scope_file: str = "") -> None:
    from .scope import Scope
    cfg = Config()
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.jobs = JobManager(cfg)        # type: ignore[attr-defined]
    httpd.scheduler = Scheduler(httpd.jobs)  # type: ignore[attr-defined]
    httpd.scope = Scope.from_file(scope_file)   # type: ignore[attr-defined]
    httpd.jobs.scope = httpd.scope      # deep scans honour the same scope
    url = f"http://{host if host != '0.0.0.0' else '127.0.0.1'}:{port}"
    print(f"{Colors.CYAN}{Colors.BOLD}Ghost Eye dashboard{Colors.RESET} "
          f"-> {Colors.GREEN}{url}{Colors.RESET}")
    if host == "0.0.0.0":
        Console.warn("bound to 0.0.0.0 - reachable by anything on your network")
    if not httpd.scope.empty:                   # type: ignore[attr-defined]
        Console.info(f"scope guard active ({scope_file})")
    print(f"{Colors.GREY}Authorised security testing only. Ctrl-C to stop.{Colors.RESET}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print()
        httpd.shutdown()


def main(argv=None) -> int:
    import argparse
    import os
    import shutil
    import subprocess
    import threading
    p = argparse.ArgumentParser(description="Ghost Eye web dashboard")
    p.add_argument("--host", default="127.0.0.1",
                   help="bind address (default 127.0.0.1; use 0.0.0.0 to expose)")
    p.add_argument("--port", type=int, default=8777, help="port (default 8777)")
    p.add_argument("--db", default="ghosteye.db", help="SQLite history path")
    p.add_argument("--scope", default="", help="scope file: only these hosts/CIDRs")
    p.add_argument("--open", action="store_true", help="open the dashboard in a browser")
    args = p.parse_args(argv)

    if args.open:
        host = "127.0.0.1" if args.host == "0.0.0.0" else args.host
        url = f"http://{host}:{args.port}"

        def _open():
            if ("com.termux" in os.environ.get("PREFIX", "")
                    or os.path.isdir("/data/data/com.termux")):
                exe = shutil.which("termux-open-url")
                if exe:
                    try:
                        subprocess.run([exe, url], check=False)
                        return
                    except Exception:
                        pass
                print(f"open this in your browser: {url}")
                return
            try:
                import webbrowser
                webbrowser.open(url)
            except Exception:
                print(f"open this in your browser: {url}")
        threading.Timer(1.0, _open).start()
    serve(host=args.host, port=args.port, db=args.db, scope_file=args.scope)
    return 0
