"""Ghost Eye web dashboard backend (standard library only).

Exposes the existing module registry over a tiny HTTP API and serves a
single-page dashboard. No third-party web framework required.

    python3 ghost_eye_web.py                 # http://127.0.0.1:8777
    python3 ghost_eye_web.py --host 0.0.0.0 --port 9000

Binds to 127.0.0.1 (localhost only) by default. Authorised use only.
"""

from __future__ import annotations

import hmac
import json
import os
import shutil
import sys
import threading
import time
import traceback
import uuid
from concurrent.futures import (FIRST_COMPLETED, ThreadPoolExecutor,
                                wait)
from datetime import datetime, timedelta, timezone
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, List, Optional
from urllib.parse import parse_qs, urlparse

from .config import Config
from .core import (Colors, Console, Context, REGISTRY, Result, build_session,
                   get_module, modules_by_category, record_error)
from . import engine, reporting, reporting_ext, workflow

STATIC_DIR = Path(__file__).parent / "web_static"

# Finished jobs are kept in memory so the dashboard can still open them after a
# scan ends. Cap the backlog so a long-lived dashboard doesn't grow without
# bound — everything worth keeping is already persisted to the history DB.
MAX_FINISHED_JOBS = 50

# The dashboard is a local tool, but the browser will happily let *any* site the
# user is visiting send it requests. Content-Security-Policy keeps an injected
# script from phoning home, and frame-ancestors blocks clickjacking. The pages
# are single-file (inline <style>/<script>), hence 'unsafe-inline'.
_CSP = ("default-src 'self'; "
        "script-src 'self' 'unsafe-inline'; "
        "style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data:; "
        "connect-src 'self'; "
        # The entity-graph force layout runs in a Worker built from a same-origin
        # Blob, so it does not freeze the page for a second on a large graph.
        # This permits blob: workers ONLY — script-src is untouched, so it
        # cannot be used to pull in remote code.
        "worker-src 'self' blob:; "
        "object-src 'none'; "
        "base-uri 'none'; "
        "form-action 'none'; "
        "frame-ancestors 'none'")

_CONTENT_TYPES = {
    "json": "application/json", "sarif": "application/json",
    "csv": "text/csv", "html": "text/html", "dashboard": "text/html",
    "pdf": "application/pdf", "md": "text/markdown", "markdown": "text/markdown",
    "prometheus": "text/plain", "prom": "text/plain",
    "exec": "text/html", "execreport": "text/html", "executive": "text/html",
    "intel": "text/html", "intelligence": "text/html",
}
_EXT_FORMATS = {"md", "markdown", "sarif", "prometheus", "prom", "dashboard",
                "exec", "execreport", "executive", "intel", "intelligence",
                "graphml", "gexf", "osint", "dossier"}


# --------------------------------------------------------------------------- #
#  Job manager - one background scan per job id
# --------------------------------------------------------------------------- #
class JobManager:
    def __init__(self, cfg: Config, db: str = "") -> None:
        self.cfg = cfg
        # An explicit --db wins over the configured/default path. Every store
        # in the app goes through self.db_path so the flag actually takes
        # effect instead of being silently ignored.
        self.db_path = db or cfg.get("db", "ghosteye.db") or "ghosteye.db"
        self.jobs: Dict[str, dict] = {}
        self.lock = threading.Lock()
        self.scope = None        # set by serve(); used to bound deep scans

    def store(self):
        """Open a history store on the configured DB. Callers must close it."""
        return reporting.Store(self.db_path)

    def _prune(self) -> None:
        """Drop the oldest finished jobs once the backlog is over the cap.

        Caller holds self.lock. Results live on in the history DB; this only
        bounds the in-memory copy so a dashboard left running for weeks doesn't
        keep every scan it ever ran."""
        finished = sorted(
            (j for j in self.jobs.values() if j["status"] != "running"),
            key=lambda j: j.get("finished") or j.get("started") or 0)
        for job in finished[:max(0, len(finished) - MAX_FINISHED_JOBS)]:
            self.jobs.pop(job["id"], None)

    def create(self, target: str, modules: List, options: dict) -> str:
        jid = uuid.uuid4().hex[:12]
        with self.lock:
            self._prune()
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
        ctx = Context(config=self.cfg, session=session, threads=threads,
                      timeout=timeout, verbose=False)
        # scan-tuning options travel on the Context, exactly as the CLI sets
        # them, so the dashboard drives the port scanner identically
        for opt in ("ports", "scan_retries", "scan_rate", "scan_all_addresses"):
            value = options.get(opt)
            if value not in (None, ""):
                setattr(ctx, opt, value)
        return ctx

    def _run(self, jid: str) -> None:
        job = self.jobs[jid]
        ctx = self._make_ctx(job["options"], job["_stop"])
        mods = job["_modules"]
        parallel = max(1, int(job["options"].get("parallel") or 3))
        target = job["target"]
        # feature 66: adaptive throttle that widens delay when the target/network
        # starts erroring or rate-limiting us (opt-in via options.adaptive_rate)
        rl = (engine.AdaptiveRateLimiter(ceiling=float(job["options"].get("rate_ceiling") or 5))
              if job["options"].get("adaptive_rate") else None)
        job["_rate"] = rl
        ex = ThreadPoolExecutor(max_workers=parallel)
        try:
            futures = {ex.submit(self._run_one, m, target, ctx, rl): m for m in mods}
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
                        record_error(f"module {getattr(m, 'id', '?')}", target, exc)
                        # label with .name, exactly like engine.execute_module —
                        # every downstream view keys results by module label, so
                        # mixing ids and names here would split one module in two
                        res = Result(module=getattr(m, "name", getattr(m, "id", "?")),
                                     target=target, status="error", data={},
                                     error=str(exc))
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
                        afut = {ex.submit(self._run_one, m, asset, ctx, rl): m for m in ms}
                        apending = set(afut)
                        while apending and not job["cancel"]:
                            adone, apending = wait(apending, timeout=0.4,
                                                   return_when=FIRST_COMPLETED)
                            for fut in adone:
                                m = afut[fut]
                                try:
                                    res = fut.result()
                                except Exception as exc:  # noqa: BLE001
                                    record_error(f"module {getattr(m, 'id', '?')}",
                                                 asset, exc)
                                    res = Result(
                                        module=getattr(m, "name",
                                                       getattr(m, "id", "?")),
                                        target=asset, status="error",
                                        data={}, error=str(exc))
                                with self.lock:
                                    job["_results_obj"].append(res)
                                    job["results"].append(res.as_dict())
                                    job["done"] += 1
                                    try:
                                        job["risk"] = reporting_ext.score_findings(job["_results_obj"])
                                    except Exception as exc:  # noqa: BLE001
                                        record_error("risk scoring", asset, exc)
                except Exception as exc:  # noqa: BLE001
                    record_error("deep scan", target, exc)
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
    def _run_one(module, target: str, ctx: Context, rate=None) -> Result:
        # single shared execution path (crash -> error Result + error-log)
        if rate is not None:
            rate.wait()
        res = engine.execute_module(module, target, ctx)
        if rate is not None:
            rate.observe(res)
        return res

    def _persist(self, job: dict) -> None:
        try:
            store = self.store()
            # active monitoring: if an alert webhook is configured, diff the new
            # surface against the previous saved scan BEFORE persisting this one,
            # and fire an alert when new exposure appeared (new subdomain / IP /
            # port / CVE / leak). Turns periodic re-scans into a change monitor.
            self._maybe_alert(job, store)
            self._maybe_report(job)
            risk = job.get("risk") or {}
            store.save_scan(job["id"], job["target"], job["_results_obj"],
                            risk.get("risk_level", ""), int(risk.get("risk_score", 0)))
            for r in job["_results_obj"]:
                store.save(r)        # per-module rows power the CLI --diff
            store.close()
        except Exception as exc:  # noqa: BLE001
            # a scan that fails to save used to vanish without a trace; the
            # dashboard still works, but the reason now lands in the error log
            record_error("persist scan", job.get("target", ""), exc)

    def _maybe_alert(self, job: dict, store) -> None:
        url = (job.get("options") or {}).get("alert_webhook", "").strip()
        if not url or job.get("status") == "error":
            return
        try:
            from .core import Result as _R
            prev = store.scans_for(job["target"])   # oldest-first, pre-save
            prev_results = []
            if prev:
                prev_results = [_R(x.get("module", ""), x.get("target", ""),
                                   x.get("status", "ok"), x.get("data", {}) or {})
                                for x in prev[-1]["results"]]
            diff = workflow.surface_diff(prev_results, job["_results_obj"],
                                         job["target"])
            # triage: drop acknowledged ("known") items so they don't re-alert
            from . import triage
            for k in list(diff):
                if k.startswith("new_") and isinstance(diff[k], list):
                    diff[k] = triage.filter_new(job["target"], diff[k])
            diff["total_new"] = sum(len(v) for k, v in diff.items()
                                    if k.startswith("new_"))
            diff["changed"] = diff["total_new"] > 0
            # custom alert rules (feature 49): decide whether this change is
            # worth alerting on, and at what severity
            from . import alert_rules
            rules = (job.get("options") or {}).get("alert_rules") or alert_rules.load_rules()
            verdict = alert_rules.evaluate(diff, rules, job["target"])
            diff["alert"] = verdict
            job["surface_change"] = diff        # surfaced via the job snapshot
            if (diff.get("changed") and not diff.get("first_scan")
                    and verdict.get("fire")):
                workflow.notify_change(diff, url)
        except Exception as exc:  # noqa: BLE001
            record_error("surface alert", job.get("target", ""), exc)

    def _maybe_report(self, job: dict) -> None:
        """Scheduled report delivery: if a report_webhook is set, push a full
        scan summary (grade + top findings) on every run — periodic reporting."""
        url = (job.get("options") or {}).get("report_webhook", "").strip()
        if not url or job.get("status") == "error":
            return
        try:
            exploit = None
            if job["options"].get("exploit_intel"):
                exploit = workflow.exploit_intel(job["_results_obj"])
            workflow.notify(job["_results_obj"], job["target"], url,
                            exploit=exploit)
        except Exception as exc:  # noqa: BLE001
            record_error("scheduled report", job.get("target", ""), exc)

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

    def extend_results(self, jid: str, extra: List[Result]) -> int:
        """Merge additional Result objects (e.g. an on-demand screenshot sweep)
        into a job so every downstream view — intelligence gallery, inventory —
        picks them up."""
        with self.lock:
            job = self.jobs.get(jid)
            if not job:
                return 0
            for r in extra:
                job["_results_obj"].append(r)
                job["results"].append(r.as_dict())
            job["total"] = len(job["_results_obj"])
            job["done"] = job["total"]
            return len(extra)

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


def _paid_modules() -> set:
    """Modules that consume a third-party API key.

    Those are the ones with a quota behind them, so an estimate that ignores
    them will happily tell you a scan is free right before it burns a month of
    your VirusTotal allowance.
    """
    from .config import MODULE_KEYS
    return set(MODULE_KEYS)


def _select(payload: dict) -> List:
    mode = payload.get("mode", "all")
    val = payload.get("value")
    if mode == "all":
        mods = list(REGISTRY.values())
    elif mode == "modules":
        ids = val if isinstance(val, list) else [val]
        mods = [get_module(i) for i in ids if get_module(i)]
    elif mode == "category":
        mods = modules_by_category().get(val, [])
    elif mode == "profile":
        ids = workflow.load_recipes("recipes.yaml").get(val, [])
        mods = [get_module(i) for i in ids if get_module(i)]
    else:
        mods = []
    # passive-only mode (feature 71): drop anything that touches the target
    if payload.get("passive_only"):
        mods = workflow.passive_only(mods)
    return mods


# --------------------------------------------------------------------------- #
#  HTTP handler
# --------------------------------------------------------------------------- #
class Handler(BaseHTTPRequestHandler):
    server_version = "GhostEye-web"

    # silence the noisy default logging; we emit our own concise access log
    def log_message(self, fmt, *args):  # noqa: D401
        pass

    # ---- logging ---------------------------------------------------------- #
    def _access_log(self, code: int) -> None:
        """Concise per-request line to the terminal (unless --quiet). This is the
        'log' that shows what the dashboard is doing and surfaces failures."""
        if getattr(self.server, "quiet", False):
            return
        path = self.path.split("?", 1)[0]        # never log the ?token=
        ts = time.strftime("%H:%M:%S")
        col = (Colors.RED if code >= 500 else "\033[33m" if code >= 400
               else Colors.GREY)
        try:
            sys.stderr.write(f"{col}{ts}  {self.command:6} {path} -> {code}"
                             f"{Colors.RESET}\n")
            sys.stderr.flush()
        except Exception:  # noqa: BLE001
            pass

    def _oops(self, exc: BaseException):
        """A handler crashed — record it, print a traceback to the terminal, and
        return a 500 so the browser sees the reason instead of a dead socket."""
        path = self.path.split("?", 1)[0]
        record_error("webapp", path, exc)
        try:
            sys.stderr.write(f"{Colors.RED}[server error] {self.command} {path}: "
                             f"{exc}\n{traceback.format_exc()}{Colors.RESET}\n")
            sys.stderr.flush()
        except Exception:  # noqa: BLE001
            pass
        try:
            return self._json({"error": f"server error: {exc}"}, 500)
        except Exception:  # noqa: BLE001 - client already gone
            return None

    # ---- helpers ---------------------------------------------------------- #
    def _security_headers(self) -> None:
        self.send_header("Content-Security-Policy", _CSP)
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cache-Control", "no-store")

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self._security_headers()
        self.end_headers()
        self.wfile.write(body)
        self._access_log(code)

    def _bytes(self, data: bytes, ctype: str, code=200, filename=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        if filename:
            self.send_header("Content-Disposition",
                             f'attachment; filename="{filename}"')
        self._security_headers()
        self.end_headers()
        self.wfile.write(data)
        self._access_log(code)

    def _body(self) -> dict:
        length = int(self.headers.get("Content-Length") or 0)
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            return {}

    # ---- routing ---------------------------------------------------------- #
    def _authed(self, parsed) -> bool:
        """True unless a token is required and not supplied. The token may come
        from an X-Ghost-Token header, an Authorization: Bearer header, a
        ?token= query param, or a ge_token cookie.

        Every comparison is constant-time so the token can't be recovered a
        byte at a time by timing the 401s."""
        tok = getattr(self.server, "auth_token", "")
        if not tok:
            return True

        def same(candidate: str) -> bool:
            return bool(candidate) and hmac.compare_digest(candidate, tok)

        if same(self.headers.get("X-Ghost-Token", "")):
            return True
        auth = self.headers.get("Authorization", "")
        if auth.startswith("Bearer ") and same(auth[7:]):
            return True
        if same(parse_qs(parsed.query).get("token", [""])[0]):
            return True
        try:
            cookie = SimpleCookie(self.headers.get("Cookie", ""))
        except Exception:  # noqa: BLE001 - a malformed Cookie header is a miss
            return False
        morsel = cookie.get("ge_token")
        return same(morsel.value) if morsel else False

    def _allowed_hosts(self) -> set:
        return getattr(self.server, "allowed_hosts", set())

    def _host_ok(self) -> bool:
        """Reject requests whose Host header we don't recognise.

        Without this, a page on the public internet can point a hostname it
        controls at 127.0.0.1 (DNS rebinding) and then read every API response
        — same-origin as far as the browser is concerned. Comparing the Host
        header against the addresses we actually bound to closes that."""
        allowed = self._allowed_hosts()
        if not allowed:                      # explicitly disabled
            return True
        raw = self.headers.get("Host", "")
        host = raw.rsplit(":", 1)[0] if raw.count(":") == 1 else raw
        return host.strip("[]").lower() in allowed

    def _origin_ok(self) -> bool:
        """Reject cross-origin state-changing requests.

        A browser sends `Origin` on every POST/DELETE, including the
        "simple requests" (text/plain bodies) that dodge a CORS preflight. Any
        page the user happens to be visiting could otherwise drive the API —
        start scans, overwrite stored API keys, restore the history DB."""
        origin = self.headers.get("Origin", "")
        if not origin or origin == "null":
            # No Origin at all means a non-browser client (curl, a script).
            # Those can't be a confused deputy for someone else's page.
            return True
        allowed = self._allowed_hosts()
        if not allowed:
            return True
        try:
            host = (urlparse(origin).hostname or "").lower()
        except ValueError:
            return False
        return host in allowed

    def _guard(self, parsed, *, state_changing: bool):
        """Shared pre-flight for every route. Returns True when the request may
        proceed; otherwise it has already written the error response."""
        if not self._host_ok():
            self._json({"error": "host not allowed — reach the dashboard on "
                                 "the address it is bound to"}, 403)
            return False
        if state_changing and not self._origin_ok():
            self._json({"error": "cross-origin request refused"}, 403)
            return False
        return True

    # each verb is wrapped so a handler crash is logged + returned as 500,
    # instead of silently killing the connection with no trace anywhere.
    def do_GET(self):
        try:
            return self._do_get()
        except Exception as exc:  # noqa: BLE001
            return self._oops(exc)

    def do_POST(self):
        try:
            return self._do_post()
        except Exception as exc:  # noqa: BLE001
            return self._oops(exc)

    def do_DELETE(self):
        try:
            return self._do_delete()
        except Exception as exc:  # noqa: BLE001
            return self._oops(exc)

    def _do_get(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if not self._guard(parsed, state_changing=False):
            return None
        # The console is the home: it is the only page that reaches every
        # capability the API exposes. The graph-first OSINT view is a
        # specialist lens onto the same data and is linked from the rail.
        if path in ("/", "/console", "/console.html", "/index.html"):
            return self._serve_page("index.html")
        if path in ("/osint", "/osint.html"):
            return self._serve_page("osint.html")
        if path == "/manifest.webmanifest":
            return self._serve_asset("manifest.webmanifest", "application/manifest+json")
        if path == "/sw.js":
            return self._serve_asset("sw.js", "application/javascript")
        if path.startswith("/static/"):
            return self._serve_static(path)
        if not self._authed(parsed):               # gate every /api/* route
            return self._json({"error": "unauthorized — append ?token=… "
                               "from the dashboard URL"}, 401)
        if path == "/api/meta":
            return self._json(_meta())
        if path == "/api/history":
            return self._json({"history": self._history()})
        if path == "/api/keys":
            return self._keys_get()
        if path == "/api/portfolio":
            return self._portfolio()
        if path == "/api/acks":
            from . import triage
            target = (parse_qs(parsed.query).get("target", [""])[0]).strip()
            return self._json({"target": target,
                               "acks": triage.list_acks(target)})
        if path == "/api/trend":
            return self._trend(parsed)
        if path == "/api/compare":
            return self._compare_scans(parsed)
        if path == "/api/schedules":
            return self._list_schedules()
        if path == "/api/unified":
            return self._unified(parsed)
        if path == "/api/scope":
            return self._scope_get()
        if path == "/api/metrics":
            return self._metrics()
        if path == "/api/alert-rules":
            return self._alert_rules_get()
        if path.startswith("/api/job/") and path.endswith("/stream"):
            return self._job_stream(path.split("/")[3])
        if path == "/api/notes":
            return self._notes_get(parsed)
        if path == "/api/watchlist":
            return self._watchlist_get(parsed)
        if path == "/api/telegram":
            return self._telegram_status()
        if path == "/api/assign":
            return self._assign_get(parsed)
        if path == "/api/audit":
            return self._audit_get(parsed)
        if path == "/api/email":
            return self._email_get()
        if path == "/api/search-all":
            return self._search_all(parsed)
        if path == "/api/estimate":
            return self._estimate(parsed)
        if path == "/api/verdicts":
            return self._all_verdicts()
        if path == "/api/baseline":
            return self._baseline_summary()
        if path == "/api/backup":
            return self._backup()
        if path.startswith("/api/scan/"):
            return self._saved_scan(path.split("/")[3] if len(path.split("/")) > 3 else "")
        if path.startswith("/api/job/"):
            return self._job_get(path, parsed)
        return self._json({"error": "not found"}, 404)

    # Routes that write their own audit entry with real detail. Everything else
    # gets a generic one from the wrapper below, so nothing that changes state
    # can go unrecorded just because someone forgot to add a line.
    _SELF_AUDITED = ("/api/assign", "/api/email", "/api/scan")

    def _do_post(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if not self._guard(parsed, state_changing=True):
            return None
        if not self._authed(parsed):
            return self._json({"error": "unauthorized"}, 401)
        out = self._post_route(path, parsed)
        if path.startswith("/api/") and path not in self._SELF_AUDITED:
            self._record(path[len("/api/"):])
        return out

    def _post_route(self, path, parsed):
        if path == "/api/scan":
            return self._scan_start()
        if path == "/api/scope":
            return self._scope_set()
        if path == "/api/restore":
            return self._restore()
        if path == "/api/osint-deep":
            return self._osint_deep()
        if path == "/api/investigate":
            return self._investigate()
        if path == "/api/notes":
            return self._notes_set()
        if path == "/api/watchlist":
            return self._watchlist_set()
        if path == "/api/retention":
            return self._retention()
        if path == "/api/telegram":
            return self._telegram_set()
        if path == "/api/assign":
            return self._assign_set()
        if path == "/api/email":
            return self._email_set()
        if path == "/api/verdict":
            return self._set_verdict()
        if path == "/api/verify-origin":
            return self._verify_origin()
        if path == "/api/alert-rules":
            return self._alert_rules_set()
        if path == "/api/schedule":
            return self._schedule_create()
        if path == "/api/keys":
            return self._keys_set()
        if path == "/api/acks":
            from . import triage
            p = self._body()
            target = (p.get("target") or "").strip()
            item = (p.get("item") or "").strip()
            if not target or not item:
                return self._json({"error": "target and item required"}, 400)
            acks = triage.ack(target, item, add=not p.get("remove"))
            return self._json({"target": target, "acks": acks,
                               "acked": item in acks})
        if path.startswith("/api/job/") and path.endswith("/cancel"):
            jid = path.split("/")[3]
            ok = self.server.jobs.cancel(jid)
            return self._json({"cancelled": ok})
        if path.startswith("/api/job/") and path.endswith("/screenshots"):
            return self._job_screenshots(path.split("/")[3], parsed)
        if path.startswith("/api/job/") and path.endswith("/ticket"):
            return self._job_ticket(path.split("/")[3], parsed)
        return self._json({"error": "not found"}, 404)

    def _do_delete(self):
        parsed = urlparse(self.path)
        path = parsed.path
        if not self._guard(parsed, state_changing=True):
            return None
        if not self._authed(parsed):
            return self._json({"error": "unauthorized"}, 401)
        if path.startswith("/api/schedule/"):
            sid = path.split("/")[3] if len(path.split("/")) > 3 else ""
            ok = self.server.scheduler.remove(sid)
            self._record("schedule-delete", detail=sid, ok=ok)
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
        # scans are the one action worth naming its target: "who pointed this
        # at production?" is the question an audit log exists to answer.
        self._record("scan", detail=f"{len(modules)} modules", target=target)
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
        if sub == "score":
            return self._job_score(jid)
        if sub == "compliance":
            return self._job_compliance(jid, parsed)
        if sub == "exploits":
            return self._job_exploits(jid, parsed)
        if sub == "risk":
            return self._job_risk(jid)
        if sub == "intel":
            return self._job_intel(jid)
        if sub == "search":
            return self._job_search(jid, parsed)
        if sub == "findings":
            return self._job_findings(jid)
        if sub == "opsec":
            return self._job_opsec(jid)
        if sub == "attribution":
            return self._job_attribution(jid)
        if sub == "ipfilter":
            return self._job_ipfilter(jid)
        if sub == "anomalies":
            return self._job_anomalies(jid)
        if sub == "fixorder":
            return self._job_fixorder(jid)
        if sub == "cspassets":
            return self._job_cspassets(jid)
        if sub == "verdicts":
            return self._job_verdicts(jid)
        if sub == "ask":
            return self._job_ask(jid, parsed)
        if sub == "summary":
            return self._job_summary(jid)
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
            store = self.server.jobs.store()
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

    def _job_score(self, jid: str):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        try:
            score = workflow.attack_score(results)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"score failed: {exc}"}, 500)
        return self._json(score)

    def _job_compliance(self, jid: str, parsed):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        framework = parse_qs(parsed.query).get("framework", ["owasp_top10"])[0]
        try:
            report = workflow.compliance_check(results, framework)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"compliance check failed: {exc}"}, 500)
        return self._json(report)

    def _job_risk(self, jid: str):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        try:
            report = workflow.risk_intelligence(results)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"risk intel failed: {exc}"}, 500)
        return self._json(report)

    def _job_screenshots(self, jid: str, parsed):
        """On-demand visual-recon sweep: screenshot the target + N discovered
        subdomains and merge the thumbnails into the job's results so the
        Intelligence gallery shows them. POST /api/job/<id>/screenshots?max=N."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        try:
            mx = int(parse_qs(parsed.query).get("max", ["10"])[0])
        except (TypeError, ValueError):
            mx = 10
        try:
            shots = workflow.capture_surface(results, target,
                                             max_shots=max(1, min(mx, 25)))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"screenshot sweep failed: {exc}"}, 500)
        self.server.jobs.extend_results(jid, shots)
        out = [{"host": s.target,
                "url": s.data.get("final_url", ""),
                "title": s.data.get("title", ""),
                "image": s.data.get("screenshot", "")}
               for s in shots
               if str(s.data.get("screenshot", "")).startswith("data:image")]
        return self._json({"target": target, "count": len(out),
                           "screenshots": out,
                           "note": "merged into the job — open Intelligence to "
                                   "see them in the gallery"})

    def _job_intel(self, jid: str):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        try:
            report = workflow.intelligence_report(results, target)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"intelligence failed: {exc}"}, 500)
        return self._json(report)

    def _job_search(self, jid: str, parsed):
        """Full-text search across every finding of a job (feature 48).
        GET /api/job/<id>/search?q=<query>."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        q = (parse_qs(parsed.query).get("q", [""])[0]).strip()
        from .search import full_text_search
        try:
            return self._json(full_text_search(results, q))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"search failed: {exc}"}, 500)

    def _job_findings(self, jid: str):
        """All severity-tagged findings of a job as a flat table (feature 42)."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        try:
            scored = reporting_ext.score_findings(results)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"findings failed: {exc}"}, 500)
        return self._json({"findings": scored.get("findings", []),
                           "counts": scored.get("counts", {}),
                           "risk_level": scored.get("risk_level", ""),
                           "confidence": scored.get("confidence", {})})

    def _job_ipfilter(self, jid: str):
        """Classify every IP the job saw: CDN/WAF edge vs cloud vs candidate
        origin, so edge noise can be filtered out of the asset picture."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        try:
            return self._json(workflow.ip_filter_report(results, target))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"ip filter failed: {exc}"}, 500)

    # ---- live stream ------------------------------------------------------ #
    def _job_stream(self, jid: str):
        """Server-sent events for a running job.

        Polling once a second is a correct but wasteful way to watch a scan: it
        costs a request per second per open tab whether or not anything
        happened, and it puts a one-second floor on how fresh the view can be.
        This pushes a frame only when the job actually changes, and the client
        falls back to polling if the stream cannot be established — a dashboard
        that goes blank because SSE was blocked by a proxy is worse than a
        chatty one.
        """
        snap = self.server.jobs.snapshot(jid)
        if snap is None:
            return self._json({"error": "unknown job"}, 404)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self._security_headers()
        self.end_headers()
        last = None
        deadline = time.time() + 900          # a stream is not immortal
        try:
            while time.time() < deadline:
                snap = self.server.jobs.snapshot(jid)
                if snap is None:
                    break
                stamp = (snap.get("done"), snap.get("status"), len(snap.get("results") or []))
                if stamp != last:
                    last = stamp
                    payload = json.dumps(snap)
                    self.wfile.write(b"event: job\ndata: " + payload.encode() + b"\n\n")
                    self.wfile.flush()
                if snap.get("status") != "running":
                    break
                time.sleep(0.4)
            # a comment frame keeps intermediaries from closing an idle stream
            self.wfile.write(b": done\n\n")
            self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass          # the tab was closed; nothing to report
        return None

    # ---- notes / watchlist / retention ------------------------------------ #
    def _state_path(self, name: str):
        base = Path(os.environ.get("GHOSTEYE_STATE", "")
                    or (Path.home() / ".ghosteye"))
        base.mkdir(parents=True, exist_ok=True)
        return base / name

    def _read_state(self, name: str) -> dict:
        try:
            return json.loads(self._state_path(name).read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            return {}

    def _write_state(self, name: str, obj: dict) -> None:
        self._state_path(name).write_text(json.dumps(obj, indent=1),
                                          encoding="utf-8")

    def _notes_get(self, parsed):
        """Analyst notes, keyed by finding id or by target."""
        key = (parse_qs(parsed.query).get("key", [""])[0]).strip()
        notes = self._read_state("notes.json")
        return self._json({"key": key, "note": notes.get(key, ""),
                           "count": len(notes)} if key
                          else {"notes": notes, "count": len(notes)})

    def _notes_set(self):
        body = self._body()
        key = str(body.get("key") or "").strip()
        if not key:
            return self._json({"error": "key required"}, 400)
        notes = self._read_state("notes.json")
        text = str(body.get("note") or "").strip()
        if text:
            notes[key] = text[:4000]
        else:
            notes.pop(key, None)
        self._write_state("notes.json", notes)
        return self._json({"key": key, "saved": bool(text), "count": len(notes)})

    def _watchlist_get(self, parsed):
        """Values you pinned; a scan that changes one of them is worth a look."""
        wl = self._read_state("watchlist.json")
        return self._json({"watchlist": wl, "count": len(wl),
                           "note": "a watched value is compared on every scan of "
                                   "its target; a change is reported rather than "
                                   "buried in the diff."})

    def _watchlist_set(self):
        body = self._body()
        key = str(body.get("key") or "").strip()
        if not key:
            return self._json({"error": "key required"}, 400)
        wl = self._read_state("watchlist.json")
        if body.get("remove"):
            wl.pop(key, None)
        else:
            wl[key] = {"value": str(body.get("value") or "")[:400],
                       "target": str(body.get("target") or ""),
                       "added": datetime.now(timezone.utc).isoformat()}
        self._write_state("watchlist.json", wl)
        return self._json({"watchlist": wl, "count": len(wl)})

    def _retention(self):
        """Prune stored scans older than N days, or beyond N per target."""
        body = self._body()
        days = int(body.get("days") or 0)
        keep = int(body.get("keep_per_target") or 0)
        if days <= 0 and keep <= 0:
            return self._json({"error": "pass days and/or keep_per_target"}, 400)
        try:
            store = self.server.jobs.store()
            rows = store.recent_scans(100000)
            removed, by_target = 0, {}
            cutoff = (datetime.now(timezone.utc)
                      - timedelta(days=days)).isoformat() if days > 0 else None
            for row in rows:            # recent_scans is newest-first
                target = row.get("target", "")
                by_target[target] = by_target.get(target, 0) + 1
                too_old = bool(cutoff and str(row.get("ts", "")) < cutoff)
                too_many = bool(keep > 0 and by_target[target] > keep)
                if too_old or too_many:
                    store.conn.execute("DELETE FROM scans WHERE id=?", (row["id"],))
                    removed += 1
            store.conn.commit()
            remaining = store.count_scans()
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"retention failed: {exc}"}, 500)
        return self._json({"removed": removed, "remaining": remaining,
                           "note": "findings are deleted permanently; take a "
                                   "backup first if you might want them."})

    # ---- ownership, audit, email, cross-scan search ------------------------ #
    def _assignments(self):
        """Shared across requests, created on first use so a handler built by a
        test without going through serve() still works."""
        store = getattr(self.server, "assignments", None)
        if store is None:
            from .collab import Assignments
            store = Assignments(self._state_path("assignments.json"))
            self.server.assignments = store       # type: ignore[attr-defined]
        return store

    def _audit(self):
        log = getattr(self.server, "audit", None)
        if log is None:
            from .collab import AuditLog
            log = AuditLog(self._state_path("audit.jsonl"))
            self.server.audit = log               # type: ignore[attr-defined]
        return log

    def _record(self, action: str, detail: str = "", target: str = "",
                ok: bool = True) -> None:
        """Audit one state-changing call. Never raises: an unwritable log must
        not turn a working API into a broken one."""
        try:
            self._audit().record(action, detail=detail, target=target, ok=ok,
                                 actor=self.headers.get("X-Ghost-Actor", "")
                                 or self.client_address[0])
        except Exception:  # noqa: BLE001
            pass

    def _assign_get(self, parsed):
        from .collab import STATUSES
        store = self._assignments()
        key = (parse_qs(parsed.query).get("key", [""])[0]).strip()
        if key:
            return self._json({"key": key, "assignment": store.get(key),
                               "statuses": list(STATUSES)})
        return self._json({"assignments": store.all(),
                           "summary": store.summary(),
                           "statuses": list(STATUSES)})

    def _assign_set(self):
        body = self._body()
        key = str(body.get("key") or "").strip()
        if not key:
            return self._json({"error": "key required"}, 400)
        store = self._assignments()
        if body.get("remove"):
            gone = store.unassign(key)
            self._record("unassign", detail=key, ok=gone)
            return self._json({"key": key, "removed": gone,
                               "summary": store.summary()})
        try:
            entry = store.assign(key,
                                 assignee=body.get("assignee") or "",
                                 status=body.get("status") or "open",
                                 target=body.get("target") or "",
                                 note=body.get("note") or "")
        except ValueError as exc:
            return self._json({"error": str(exc)}, 400)
        self._record("assign", detail=f"{key} -> {entry['status']}",
                     target=entry.get("target", ""))
        return self._json({"assignment": entry, "summary": store.summary()})

    def _audit_get(self, parsed):
        q = parse_qs(parsed.query)
        try:
            limit = int(q.get("limit", ["200"])[0])
        except ValueError:
            limit = 200
        try:
            window = int(q.get("active_minutes", ["30"])[0])
        except ValueError:
            window = 30
        log = self._audit()
        return self._json({"entries": log.tail(min(limit, 1000),
                                               action=q.get("action", [""])[0]),
                           "summary": log.summary(),
                           "active": log.active(window),
                           "active_minutes": window,
                           "note": "append-only: there is no API that edits or "
                                   "deletes an entry. 'active' is derived from "
                                   "recent actions, not from a presence "
                                   "protocol — a shared token gives no user "
                                   "identity to build one on."})

    def _mailer(self):
        from .mailer import Mailer
        cfg = self.server.jobs.cfg
        return Mailer(host=cfg.get("smtp_host", "") or "",
                      port=int(cfg.get("smtp_port", 587) or 587),
                      username=cfg.get("smtp_user", "") or "",
                      password=cfg.api_key("smtp_password") or "",
                      sender=cfg.get("smtp_from", "") or "",
                      use_tls=str(cfg.get("smtp_tls", "1")) not in ("0", "false"))

    def _email_get(self):
        m = self._mailer()
        cfg = self.server.jobs.cfg
        return self._json({"smtp": m.config(), "problems": m.problems(),
                           "recipients": cfg.get("smtp_to", "") or "",
                           "note": "the password is write-only — it is stored "
                                   "with the API keys and never returned."})

    def _email_set(self):
        """Save SMTP settings, or send one message. Sending is always an
        explicit act: there is no 'email me every scan' switch here."""
        body = self._body()
        cfg = self.server.jobs.cfg
        if body.get("action") == "send":
            return self._email_send(body)
        for field, option in (("host", "smtp_host"), ("port", "smtp_port"),
                              ("username", "smtp_user"), ("sender", "smtp_from"),
                              ("recipients", "smtp_to"), ("use_tls", "smtp_tls")):
            if field in body:
                value = body[field]
                if field == "use_tls":
                    value = "1" if value else "0"
                cfg.set(option, str(value))
        if body.get("password"):
            cfg.set_api_key("smtp_password", str(body["password"]))
        m = self._mailer()
        self._record("email-config", detail=f"host={m.host} port={m.port}")
        return self._json({"saved": True, "smtp": m.config(),
                           "problems": m.problems()})

    def _email_send(self, body):
        from .mailer import MailError, report_email
        m = self._mailer()
        cfg = self.server.jobs.cfg
        to = body.get("to") or cfg.get("smtp_to", "") or ""
        jid = str(body.get("job_id") or "").strip()
        subject = str(body.get("subject") or "Ghost Eye test message")
        text = str(body.get("body") or
                   "This is a test message from Ghost Eye. "
                   "If you are reading it, delivery works.")
        if jid:
            snap = self.server.jobs.snapshot(jid)
            results = self.server.jobs.results_obj(jid)
            if snap is None or not results:
                return self._json({"error": "job not found or has no results"}, 404)
            try:
                scored = reporting_ext.score_findings(results)
            except Exception as exc:  # noqa: BLE001
                return self._json({"error": f"findings failed: {exc}"}, 500)
            subject, text = report_email(snap["target"], snap.get("risk") or {},
                                         scored.get("findings", []))
        try:
            out = m.send(to, subject, text)
        except MailError as exc:
            self._record("email-send", detail=str(exc), ok=False)
            return self._json({"error": str(exc)}, 400)
        self._record("email-send", detail=f"{len(out['recipients'])} recipient(s)")
        return self._json(out)

    def _search_all(self, parsed):
        """Search every stored scan, not just the one on screen."""
        from .search import search_scans
        q = parse_qs(parsed.query)
        query = (q.get("q", [""])[0]).strip()
        if not query:
            return self._json({"error": "q required"}, 400)
        try:
            limit = min(int(q.get("scans", ["60"])[0]), 300)
        except ValueError:
            limit = 60
        try:
            store = self.server.jobs.store()
            scans = store.search_all(query, limit=limit,
                                     target=(q.get("target", [""])[0]).strip())
            total = store.count_scans()
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"history unavailable: {exc}"}, 500)
        out = search_scans(scans, query)
        out["stored_scans"] = total
        return self._json(out)

    def _estimate(self, parsed):
        """How long a scan will take and what it will cost you, before you
        press the button.

        The time figure comes from what modules have actually taken on this
        machine, not from a guess: the engine records elapsed_ms per module and
        recent scans are the sample. With no history it says so rather than
        inventing a number.
        """
        q = parse_qs(parsed.query)
        modules = _select({"mode": q.get("mode", ["all"])[0],
                           "value": q.get("value", [""])[0]
                           if q.get("mode", ["all"])[0] != "modules"
                           else [m for m in (q.get("value", [""])[0]).split(",") if m]})
        samples, seen = {}, 0
        try:
            store = self.server.jobs.store()
            for row in store.recent_scans(15):
                scan = store.load_scan(row["id"])
                if not scan:
                    continue
                seen += 1
                for r in scan["results"]:
                    ms = int(r.get("elapsed_ms") or 0)
                    if ms > 0:
                        samples.setdefault(r.get("module", ""), []).append(ms)
            store.close()
        except Exception:  # noqa: BLE001 - an estimate is never worth an error
            pass
        per = {k: sorted(v)[len(v) // 2] for k, v in samples.items()}
        typical = sorted(per.values())[len(per) // 2] if per else 0
        names = [getattr(m, "name", str(m)) for m in modules]
        total_ms = sum(per.get(n, typical) for n in names)
        # modules run concurrently; the wall clock is roughly the serial time
        # divided by the worker count, floored by the slowest single module.
        workers = max(1, int(getattr(self.server.jobs, "workers", 10) or 10))
        slowest = max([per.get(n, typical) for n in names], default=0)
        wall = max(total_ms // workers, slowest)
        paid_ids = _paid_modules()
        paid = sorted({getattr(m, "id", "") for m in modules
                       if getattr(m, "id", "") in paid_ids})
        return self._json({
            "modules": len(names),
            "sampled_scans": seen,
            "have_history": bool(per),
            "serial_ms": total_ms,
            "estimated_seconds": round(wall / 1000.0, 1) if wall else None,
            "slowest_module_ms": slowest,
            "requests_upper_bound": len(names) * 4,
            "paid_api_modules": paid,
            "note": ("estimated from this machine's own timings"
                     if per else
                     "no timing history yet — run one scan and this becomes real"),
        })

    # ---- Telegram bot ---------------------------------------------------- #
    def _telegram_status(self):
        """Whether the bot is configured and running. The token is never
        returned — only whether one is set."""
        bot = getattr(self.server, "telegram", None)
        cfg = self.server.jobs.cfg
        allow = str(cfg.get("telegram_allow", "") or "")
        return self._json({
            "running": bool(bot and not bot._stop.is_set()),
            "token_configured": bool(cfg.get("telegram_token")),
            "allowed_chats": [c for c in allow.replace(";", ",").split(",") if c.strip()],
            "unauthorised_seen": (bot.seen_unauthorised if bot else []),
            "note": "the allow-list is default-deny: empty means nobody may "
                    "command the bot. Message it /whoami to learn your chat id.",
        })

    def _telegram_set(self):
        """Configure and start/stop the bot. POST {token?, allow?, action}."""
        body = self._body()
        cfg = self.server.jobs.cfg
        if body.get("token"):
            cfg.set("telegram_token", str(body["token"]).strip())
        if body.get("allow") is not None:
            cfg.set("telegram_allow", str(body["allow"]).strip())
        action = str(body.get("action") or "").lower()
        bot = getattr(self.server, "telegram", None)
        if action == "stop":
            if bot:
                bot.shutdown()
            self.server.telegram = None
            return self._json({"running": False})
        if action == "start":
            if bot:
                bot.shutdown()
            token = str(cfg.get("telegram_token") or "").strip()
            if not token:
                return self._json({"error": "no bot token configured"}, 400)
            allow = str(cfg.get("telegram_allow", "") or "")
            try:
                from .telegram_bot import TelegramBot
                new = TelegramBot(
                    token=token,
                    allowed_chats=[c for c in allow.replace(";", ",").split(",") if c.strip()],
                    cfg=cfg, scope=getattr(self.server, "scope", None),
                    db=self.server.jobs.db_path)
            except ValueError as exc:
                return self._json({"error": str(exc)}, 400)
            self.server.telegram = new
            threading.Thread(target=new.run_forever, daemon=True).start()
            return self._json({"running": True,
                               "allowed": len(new.allowed),
                               "warning": ("the allow-list is empty, so every "
                                           "command will be refused")
                               if not new.allowed else ""})
        return self._telegram_status()

    def _all_verdicts(self):
        """Every standing ruling, so suppression is reviewable rather than
        something that quietly happens to a findings list."""
        from . import verdicts as v
        store = v.VerdictStore(self.server.jobs.db_path)
        try:
            rows = store.all()
            return self._json({
                "verdicts": rows,
                "count": len(rows),
                "expired": sum(1 for r in rows if r["expired"]),
                "note": "an expired verdict no longer suppresses anything; "
                        "re-judge it or re-mark it.",
            })
        finally:
            store.close()

    def _baseline_summary(self):
        """How much the corpus baseline knows, and whether it knows enough."""
        from . import baseline
        base = baseline.Baseline(self.server.jobs.db_path)
        try:
            return self._json(base.summary())
        finally:
            base.close()

    def _set_verdict(self):
        """Rule on a finding: {id, verdict, reason?, scope?, ttl_days?}.

        The id is the handle printed beside the finding; it resolves against the
        findings this installation has already shown, so the ruling can be made
        whenever the analyst gets to it rather than during the scan."""
        body = self._body()
        short = str(body.get("id", "")).strip()
        verdict = str(body.get("verdict", "")).strip()
        from . import verdicts as v
        if verdict not in v.VERDICTS:
            return self._json({"error": "verdict must be one of "
                                        + ", ".join(v.VERDICTS)}, 400)
        store = v.VerdictStore(self.server.jobs.db_path)
        try:
            finding = store.recall(short)
            if finding is None:
                return self._json({"error": f"unknown finding id {short!r}"}, 404)
            return self._json(store.record(
                finding, verdict, scope=body.get("scope", ""),
                reason=str(body.get("reason", ""))[:400],
                ttl_days=int(body.get("ttl_days") or v.DEFAULT_TTL_DAYS)))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"verdict failed: {exc}"}, 500)
        finally:
            store.close()

    def _job_fixorder(self, jid: str):
        """Rank the CVEs this job found by exploitation pressure x observed
        reachability — the answer to "what do I fix first"."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        try:
            from . import prioritise
            return self._json(prioritise.prioritise(results))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"fix order failed: {exc}"}, 500)

    def _job_cspassets(self, jid: str):
        """Hosts the target's own CSP declares, and which the scan missed."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        try:
            return self._json(workflow.csp_asset_report(results, target))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"csp assets failed: {exc}"}, 500)

    def _job_verdicts(self, jid: str):
        """This job's findings with the analyst's standing rulings applied."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        try:
            from . import reporting_ext, verdicts
            scored = reporting_ext.score_findings(results)
            out = verdicts.apply_verdicts(scored["findings"],
                                          db=self.server.jobs.db_path)
            out["counts"] = scored.get("counts", {})
            out["risk_level"] = scored.get("risk_level", "")
            return self._json(out)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"verdicts failed: {exc}"}, 500)

    def _job_anomalies(self, jid: str):
        """What is unusual about this job's target relative to every host this
        installation has ever scanned. Read-only: scoring never learns, so
        opening the panel cannot quietly normalise the host being looked at."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        try:
            from . import baseline
            return self._json(baseline.anomaly_report(
                results, db=self.server.jobs.db_path, target=target, learn=False))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"anomalies failed: {exc}"}, 500)

    def _job_attribution(self, jid: str):
        """Infrastructure attribution for a job: cluster the hosts it saw into
        operator estates with selectivity-weighted, explainable evidence."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        try:
            return self._json(workflow.attribution_report(results, target))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"attribution failed: {exc}"}, 500)

    def _job_opsec(self, jid: str):
        """OPSEC exposure for a job: which third parties the scan disclosed the
        target to, reconstructed from reported URLs (feature: leak-awareness)."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        from . import opsec
        try:
            return self._json(opsec.report_from_results(results, target))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"opsec report failed: {exc}"}, 500)

    def _job_ask(self, jid: str, parsed):
        """Deterministic Q&A over a scan's intelligence (feature 66).
        GET /api/job/<id>/ask?q=<question>."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        q = (parse_qs(parsed.query).get("q", [""])[0]).strip()
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        from .intelligence import question_answer
        try:
            report = workflow.intelligence_report(results, target)
            return self._json(question_answer(report, q))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"ask failed: {exc}"}, 500)

    def _job_summary(self, jid: str):
        """Natural-language summary of a scan (feature 65). Uses an LLM when a
        DeepSeek key is configured, else a deterministic summary."""
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        from .intelligence import ai_summary
        try:
            report = workflow.intelligence_report(results, target)
            key = ""
            try:
                key = self.server.jobs.cfg.api_key("deepseek") or ""
            except Exception:  # noqa: BLE001
                key = ""
            return self._json(ai_summary(report, api_key=key))
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"summary failed: {exc}"}, 500)

    def _unified(self, parsed):
        """Merge several targets' knowledge graphs into one unified graph
        (feature 4). GET /api/unified?targets=a.com,b.com — targets are taken
        from the most recent saved scan of each in the history DB."""
        raw = (parse_qs(parsed.query).get("targets", [""])[0]).strip()
        targets = [t.strip() for t in raw.split(",") if t.strip()]
        if len(targets) < 1:
            return self._json({"error": "pass ?targets=a.com,b.com"}, 400)
        from .core import Result
        from .intelligence import (correlate, knowledge_graph, risk_heatmap,
                                   unified_graph)
        graphs = []
        try:
            store = self.server.jobs.store()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"history unavailable: {exc}"}, 500)
        try:
            for t in targets[:8]:
                scans = store.scans_for(t)
                if not scans:
                    continue
                latest = scans[-1].get("results", [])
                res = [Result(x.get("module", ""), x.get("target", t),
                             x.get("status", "ok"), x.get("data", {}) or {})
                       for x in latest]
                if not res:
                    continue
                intel = correlate(res, t)
                kg = knowledge_graph(res, intel["target"], intel)
                risk_heatmap(kg)
                graphs.append((t, kg))
        finally:
            store.close()
        if not graphs:
            return self._json({"error": "no saved scans for those targets — "
                               "run & save them first"}, 404)
        return self._json(unified_graph(graphs))

    def _metrics(self):
        """System / health metrics for the dashboard (feature 80)."""
        import ghost_eye
        jobs = getattr(self.server, "jobs", None)
        if jobs is not None:
            with jobs.lock:
                all_jobs = list(jobs.jobs.values())
        else:
            all_jobs = []
        running = sum(1 for j in all_jobs if j.get("status") == "running")
        done = sum(1 for j in all_jobs if j.get("status") == "done")
        errored = sum(1 for j in all_jobs if j.get("status") == "error")
        scans = 0
        try:
            store = self.server.jobs.store()
            scans = store.count_scans()   # COUNT(*), not "load everything, len()"
            store.close()
        except Exception as exc:  # noqa: BLE001
            record_error("metrics: scan count", "", exc)
        err_log = 0
        try:
            from .core import errorlog_path
            p = errorlog_path()
            if p.exists():
                err_log = sum(1 for _ in p.open(encoding="utf-8", errors="ignore"))
        except Exception:  # noqa: BLE001
            pass
        up = int(time.time() - getattr(self.server, "started_at", time.time()))
        mirror = {}
        try:
            from . import cve_mirror
            m = cve_mirror.shared()
            mirror = {"cves": m.stats().get("cves", 0),
                      "kev": m.stats().get("kev", 0),
                      "offline": cve_mirror.offline()}
        except Exception:  # noqa: BLE001
            mirror = {}
        return self._json({
            "version": ghost_eye.__version__,
            "modules": len(REGISTRY),
            "cve_mirror": mirror,
            "jobs": {"total": len(all_jobs), "running": running,
                     "done": done, "error": errored},
            "saved_scans": scans,
            "error_log_lines": err_log,
            "uptime_seconds": up,
            "schedules": len(self.server.scheduler.list_all())
            if getattr(self.server, "scheduler", None) else 0,
        })

    def _osint_deep(self):
        """Advanced OSINT: automated multi-hop pivot from a seed (bounded so it
        returns in reasonable time). POST {target, depth}."""
        body = self._body()
        target = (body.get("target") or "").strip()
        if not target:
            return self._json({"error": "target required"}, 400)
        scope = getattr(self.server, "scope", None)
        if scope is not None and not scope.empty:
            allowed, reason = scope.allows(target)
            if not allowed:
                return self._json({"error": f"out of scope: {reason}"}, 403)
        try:
            depth = max(0, min(2, int(body.get("depth", 1))))
        except (TypeError, ValueError):
            depth = 1
        try:
            out = workflow.osint_deepdive(target, self.server.jobs.cfg,
                                          depth=depth, max_per_hop=8)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"osint deep-dive failed: {exc}"}, 500)
        return self._json(out)

    def _verify_origin(self):
        """Verify candidate origin IPs for a fronted host (active probe).
        POST {host, candidates:[ip,...]}."""
        body = self._body()
        host = (body.get("host") or body.get("target") or "").strip()
        cands = body.get("candidates") or []
        if not host or not isinstance(cands, list) or not cands:
            return self._json({"error": "host and a non-empty candidates list "
                                        "are required"}, 400)
        scope = getattr(self.server, "scope", None)
        if scope is not None and not scope.empty:
            allowed, reason = scope.allows(host)
            if not allowed:
                return self._json({"error": f"out of scope: {reason}"}, 403)
        try:
            out = workflow.verify_origin(host, cands, self.server.jobs.cfg)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"origin verification failed: {exc}"}, 500)
        return self._json(out)

    def _investigate(self):
        """Entity investigation: a username / e-mail seed -> canary-checked
        profiles + identity + OPSEC dossier. POST {seed}."""
        body = self._body()
        seed = (body.get("seed") or body.get("target") or "").strip()
        if not seed:
            return self._json({"error": "seed required (username or e-mail)"}, 400)
        try:
            out = workflow.entity_investigation(seed, self.server.jobs.cfg)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"investigation failed: {exc}"}, 500)
        if body.get("dossier"):
            from .intelligence import entity_dossier
            try:
                out["dossier_markdown"] = entity_dossier(out)
            except Exception:  # noqa: BLE001
                pass
        return self._json(out)

    def _alert_rules_path(self):
        p = os.environ.get("GHOSTEYE_ALERT_RULES", "")
        return Path(p) if p else Path.home() / ".ghosteye" / "alert_rules.json"

    def _alert_rules_get(self):
        from . import alert_rules
        return self._json({"rules": alert_rules.load_rules(),
                           "defaults": alert_rules.DEFAULT_RULES,
                           "event_severity": alert_rules._EVENT_SEVERITY})

    def _alert_rules_set(self):
        from . import alert_rules
        body = self._body()
        rules = {**alert_rules.DEFAULT_RULES,
                 **{k: v for k, v in body.items() if k in alert_rules.DEFAULT_RULES}}
        try:
            p = self._alert_rules_path()
            p.parent.mkdir(parents=True, exist_ok=True)
            with open(p, "w", encoding="utf-8") as fh:
                json.dump(rules, fh, indent=2)
            try:
                os.chmod(p, 0o600)
            except OSError:
                pass
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"save failed: {exc}"}, 500)
        return self._json({"ok": True, "rules": rules})

    def _backup(self):
        """Download every saved scan as a portable JSON backup (feature 77).
        Scan findings only — never API keys or secrets."""
        try:
            store = self.server.jobs.store()
            blob = store.export_all()
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"backup failed: {exc}"}, 500)
        body = json.dumps(blob, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Disposition",
                         'attachment; filename="ghosteye-backup.json"')
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _restore(self):
        """Restore saved scans from an uploaded backup JSON (feature 77)."""
        blob = self._body()
        try:
            store = self.server.jobs.store()
            n = store.import_all(blob)
            store.close()
        except ValueError as exc:
            return self._json({"error": str(exc)}, 400)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"restore failed: {exc}"}, 500)
        return self._json({"ok": True, "imported": n})

    def _scope_get(self):
        """Return the current scope-guard allow-list (feature 72)."""
        scope = getattr(self.server, "scope", None)
        lines = scope.to_lines() if scope and hasattr(scope, "to_lines") else []
        return self._json({"entries": lines, "empty": not lines,
                           "note": "one host / *.domain / CIDR / IP per entry; "
                                   "targets outside this list are refused."})

    def _scope_set(self):
        """Replace the scope-guard allow-list from the dashboard (feature 72).
        POST body: {entries: ["example.com", "10.0.0.0/8", ...]}."""
        from .scope import Scope
        body = self._body()
        entries = body.get("entries")
        if isinstance(entries, str):
            entries = entries.splitlines()
        if not isinstance(entries, list):
            return self._json({"error": "entries must be a list or newline text"}, 400)
        scope = Scope.from_lines([str(e) for e in entries])
        self.server.scope = scope
        if getattr(self.server, "jobs", None) is not None:
            self.server.jobs.scope = scope       # deep scans honour it too
        return self._json({"ok": True, "entries": scope.to_lines(),
                           "empty": scope.empty})

    def _job_ticket(self, jid: str, parsed):
        """File a Jira/ServiceNow ticket from a finding (feature 60).
        POST /api/job/<id>/ticket  body: {system, finding, dry_run}."""
        body = self._body()
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        from .ticketing import submit_ticket
        finding = body.get("finding") or {}
        system = body.get("system", "jira")
        dry = bool(body.get("dry_run", False))
        try:
            out = submit_ticket(finding, target, system, dry_run=dry)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"ticket failed: {exc}"}, 500)
        return self._json(out)

    def _job_exploits(self, jid: str, parsed):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        try:
            mx = int(parse_qs(parsed.query).get("max", ["20"])[0])
        except (TypeError, ValueError):
            mx = 20
        try:
            report = workflow.exploit_intel(results, max_cves=mx)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"exploit intel failed: {exc}"}, 500)
        return self._json(report)

    def _job_report(self, jid: str, parsed):
        results = self.server.jobs.results_obj(jid)
        if not results:
            return self._json({"error": "no results yet"}, 404)
        snap = self.server.jobs.snapshot(jid)
        target = snap["target"] if snap else ""
        fmt = (parse_qs(parsed.query).get("format", ["html"])[0]).lower()
        import tempfile
        # every download used to leave its scratch directory behind; build the
        # report, read it, then remove the whole directory
        tmpdir = Path(tempfile.mkdtemp(prefix="ghosteye-report-"))
        try:
            tmp = tmpdir / f"report.{fmt}"
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
                sib = next(iter(tmpdir.glob("report.*")), None)
                if sib:
                    tmp = sib
                else:
                    return self._json({"error": "export produced no file"}, 500)
            data = tmp.read_bytes()
            suffix = tmp.suffix.lstrip(".")
        finally:
            shutil.rmtree(tmpdir, ignore_errors=True)
        ctype = _CONTENT_TYPES.get(fmt, "application/octet-stream")
        dl = fmt not in ("html", "dashboard", "exec", "execreport", "executive",
                         "intel", "intelligence")
        safe = "".join(c for c in target if c.isalnum() or c in ".-_") or "report"
        fname = f"ghosteye_{safe}.{suffix}" if dl else None
        return self._bytes(data, ctype, filename=fname)

    def _saved_scan(self, scan_id: str):
        try:
            store = self.server.jobs.store()
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
            store = self.server.jobs.store()
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

    def _keys_get(self):
        """Which optional API keys are configured (never returns values)."""
        from .config import _KEY_LABELS, _SERVICE_KEYS
        cfg = self.server.jobs.cfg
        keys = [{"name": n, "label": _KEY_LABELS.get(n, n),
                 "set": bool(cfg.api_key(n))} for n in _SERVICE_KEYS]
        return self._json({"backend": cfg.key_backend(), "keys": keys})

    def _keys_set(self):
        """Save an API key from the dashboard (persists to OS keyring or the
        0600 config file, exactly like --set-keys)."""
        from .config import _ENV_MAP
        payload = self._body()
        name = (payload.get("name") or "").strip().lower()
        value = (payload.get("value") or "").strip()
        if name not in _ENV_MAP:
            return self._json({"error": f"unknown key '{name}'"}, 400)
        if not value:
            return self._json({"error": "empty value"}, 400)
        try:
            self.server.jobs.cfg.set_api_key(name, value)
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"save failed: {exc}"}, 500)
        return self._json({"ok": True, "name": name, "set": True,
                           "backend": self.server.jobs.cfg.key_backend()})

    def _portfolio(self):
        """Multi-target ASM overview from the saved-scan history."""
        try:
            store = self.server.jobs.store()
            report = workflow.portfolio(store)
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"portfolio failed: {exc}"}, 500)
        return self._json(report)

    def _trend(self, parsed):
        """Intelligence trend for a target across its saved-scan history:
        attack-surface/asset/risk series + knowledge-graph entity churn.
        GET /api/trend?target=<t>."""
        target = (parse_qs(parsed.query).get("target", [""])[0]).strip()
        if not target:
            return self._json({"error": "target required"}, 400)
        try:
            store = self.server.jobs.store()
            report = workflow.intelligence_trend(store, target)
            store.close()
        except Exception as exc:  # noqa: BLE001
            return self._json({"error": f"trend failed: {exc}"}, 500)
        return self._json(report)

    # ---- compare --------------------------------------------------------- #
    def _compare_scans(self, parsed):
        qs = parse_qs(parsed.query)
        scan_a = qs.get("a", [""])[0]
        scan_b = qs.get("b", [""])[0]
        if not scan_a or not scan_b:
            return self._json({"error": "provide ?a=<scan_id>&b=<scan_id>"}, 400)
        try:
            store = self.server.jobs.store()
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
        return self._serve_page("index.html")

    def _serve_page(self, name: str):
        page = STATIC_DIR / name
        if not page.exists():
            return self._json({"error": f"{name} missing"}, 500)
        self._bytes(page.read_bytes(), "text/html")

    def _serve_asset(self, name: str, ctype: str):
        f = (STATIC_DIR / name).resolve()
        if STATIC_DIR.resolve() not in f.parents or not f.exists():
            return self._json({"error": "not found"}, 404)
        self._bytes(f.read_bytes(), ctype)

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
def _local_addresses() -> set:
    """This machine's own IP literals, for the Host allow-list when bound to
    every interface. Best-effort — a failure just means a stricter list."""
    import socket
    found = {"127.0.0.1", "::1"}
    try:
        hostname = socket.gethostname()
        found.add(hostname.lower())
        for family, _t, _p, _c, sockaddr in socket.getaddrinfo(hostname, None):
            if family in (socket.AF_INET, socket.AF_INET6):
                found.add(str(sockaddr[0]).split("%")[0].lower())
    except Exception:  # noqa: BLE001
        pass
    try:                                     # the address used for egress
        probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        probe.settimeout(0.2)
        probe.connect(("192.0.2.1", 9))      # TEST-NET-1: no packet is sent
        found.add(probe.getsockname()[0])
        probe.close()
    except Exception:  # noqa: BLE001
        pass
    return found


def serve(host: str = "127.0.0.1", port: int = 8777,
          db: str = "ghosteye.db", scope_file: str = "",
          auth_token: str = "", quiet: bool = False) -> None:
    import os
    import secrets

    from .scope import Scope
    cfg = Config()
    httpd = ThreadingHTTPServer((host, port), Handler)
    httpd.jobs = JobManager(cfg, db=db)  # type: ignore[attr-defined]
    httpd.scheduler = Scheduler(httpd.jobs)  # type: ignore[attr-defined]
    httpd.scope = Scope.from_file(scope_file)   # type: ignore[attr-defined]
    httpd.jobs.scope = httpd.scope      # deep scans honour the same scope
    httpd.quiet = quiet                 # type: ignore[attr-defined]
    httpd.started_at = time.time()      # type: ignore[attr-defined]
    # A token is ALWAYS required. localhost used to be exempt, but "local" is
    # not a trust boundary in a browser: any page the user visits can post to
    # 127.0.0.1, and a rebound DNS name can read the replies. The URL printed
    # below carries the token, so the normal flow is unchanged.
    token = (auth_token or os.environ.get("GHOSTEYE_TOKEN", "")
             or secrets.token_urlsafe(16))
    httpd.auth_token = token             # type: ignore[attr-defined]
    # Host header allow-list (anti DNS-rebinding). GHOSTEYE_ALLOWED_HOSTS adds
    # names for reverse-proxy setups; setting it to "*" disables the check.
    allowed = {"127.0.0.1", "localhost", "::1"}
    if host not in ("0.0.0.0", "::", ""):
        allowed.add(host.strip("[]").lower())
    else:
        # Bound to every interface: allow this machine's own IP literals so
        # reaching it over the LAN works. Rebinding needs a *hostname* to point
        # at us, and an attacker's hostname still won't be on this list.
        allowed |= _local_addresses()
    extra = os.environ.get("GHOSTEYE_ALLOWED_HOSTS", "")
    if extra.strip() == "*":
        allowed = set()                  # opt out entirely
    elif extra:
        allowed |= {h.strip().lower() for h in extra.split(",") if h.strip()}
    httpd.allowed_hosts = allowed        # type: ignore[attr-defined]
    disp_host = host if host not in ("0.0.0.0", "::", "") else "127.0.0.1"
    url = f"http://{disp_host}:{port}/?token={token}"
    print(f"{Colors.CYAN}{Colors.BOLD}Ghost Eye — OSINT dashboard{Colors.RESET} "
          f"-> {Colors.GREEN}{url}{Colors.RESET}")
    Console.info("recon console (advanced scans/schedules) at /console")
    if host in ("0.0.0.0", "::"):
        Console.warn("bound to 0.0.0.0 - reachable by anything on your network")
        if allowed:
            Console.info("reach it on 127.0.0.1, or set GHOSTEYE_ALLOWED_HOSTS "
                         "to the hostname you will use")
    Console.info("auth token required for /api — open the URL above "
                 "(it carries ?token=…)")
    if not httpd.scope.empty:                   # type: ignore[attr-defined]
        Console.info(f"scope guard active ({scope_file})")
    if quiet:
        Console.info("request logging off (--quiet)")
    else:
        Console.info("request log below — every API call + errors are printed "
                     "here (module ERROR reasons also show in the dashboard)")
    print(f"{Colors.GREY}Authorised security testing only. Ctrl-C to stop.{Colors.RESET}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print()
        httpd.shutdown()


def main(argv=None) -> int:
    import argparse
    import secrets
    import subprocess
    p = argparse.ArgumentParser(description="Ghost Eye web dashboard")
    p.add_argument("--host", default="127.0.0.1",
                   help="bind address (default 127.0.0.1; use 0.0.0.0 to expose)")
    p.add_argument("--port", type=int, default=8777, help="port (default 8777)")
    p.add_argument("--db", default="ghosteye.db", help="SQLite history path")
    p.add_argument("--scope", default="", help="scope file: only these hosts/CIDRs")
    p.add_argument("--open", action="store_true", help="open the dashboard in a browser")
    p.add_argument("--auth-token", default="",
                   help="require this token for /api (auto-generated off-localhost)")
    p.add_argument("--quiet", action="store_true",
                   help="don't print the per-request access log to the terminal")
    args = p.parse_args(argv)

    # resolve the token here so --open can put it in the URL it launches;
    # serve() would otherwise mint one we have no way to hand the browser
    token = (args.auth_token or os.environ.get("GHOSTEYE_TOKEN", "")
             or secrets.token_urlsafe(16))

    if args.open:
        host = "127.0.0.1" if args.host in ("0.0.0.0", "::", "") else args.host
        url = f"http://{host}:{args.port}/?token={token}"

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
    serve(host=args.host, port=args.port, db=args.db, scope_file=args.scope,
          auth_token=token, quiet=args.quiet)
    return 0
