"""Telegram bot — drive Ghost Eye from your phone.

`--notify` already *pushes* a summary to a Telegram webhook. This is the other
direction: a bot that takes commands, runs real scans, and answers with the
result. Start a scan on the train, read the findings when it lands.

    /scan example.com quick     run a profile against a target
    /status                     how the running scan is doing
    /findings                   the last scan's findings, worst first
    /fixorder                   what to fix first (KEV × reachability)
    /ports example.com          a port sweep on its own
    /stop                       cancel the running scan
    /scans                      recent scans from the history
    /help

Security — read this before enabling it
---------------------------------------
A bot that runs scans is a remote-command channel into the machine hosting it.
Two rules are therefore not optional and are enforced here rather than
documented as advice:

* **Default deny.** Only chat IDs on the allow-list may command the bot. An
  empty allow-list authorises *nobody*, because anyone who finds a bot token
  could otherwise point your host at any target on the internet — turning your
  machine into someone else's scanning proxy. The first unauthorised chat is
  reported so you can add your own ID deliberately, and its command is refused.
* **Scope still applies.** Every target goes through the same `Scope` guard the
  CLI and dashboard use. Remote convenience does not widen what you are allowed
  to scan.

Beyond that: one scan at a time per bot, a cooldown between commands, replies
truncated to Telegram's limit, and the token is never echoed back into a chat.

Long-polling (`getUpdates`) — no inbound port, no webhook to expose, works from
behind NAT. FOR AUTHORISED USE ONLY.
"""

from __future__ import annotations

import html
import threading
import time
from typing import Any, Callable, Dict, List, Optional

API = "https://api.telegram.org/bot{token}/{method}"

# Telegram hard-limits a message to 4096 characters.
MAX_MESSAGE = 3900

# Minimum seconds between accepted commands from one chat. A bot that will
# happily start a scan per second is a denial-of-service tool aimed at whatever
# it is pointed at, and at the host running it.
COOLDOWN = 3.0

HELP = (
    "<b>Ghost Eye</b>\n"
    "/scan &lt;target&gt; [profile]  — run a scan\n"
    "/ports &lt;target&gt; [spec]    — TCP port sweep (default top100)\n"
    "/status                    — progress of the running scan\n"
    "/findings                  — last scan's findings, worst first\n"
    "/fixorder                  — what to fix first\n"
    "/stop                      — cancel the running scan\n"
    "/scans                     — recent scans\n"
    "/profiles                  — available profiles\n"
    "/whoami                    — your chat id (to allow-list it)\n"
    "/help"
)


class Unauthorised(Exception):
    """A chat that is not on the allow-list tried to command the bot."""


class TelegramBot:
    """A long-polling Telegram bot wired to the real scan engine."""

    def __init__(self, token: str, allowed_chats: Optional[List[int]] = None,
                 cfg: Any = None, scope: Any = None, db: str = "ghosteye.db",
                 session: Any = None, runner: Optional[Callable] = None) -> None:
        if not token or ":" not in token:
            raise ValueError("a Telegram bot token looks like '123456:ABC-DEF…'")
        self.token = token
        self.allowed = {int(c) for c in (allowed_chats or []) if str(c).strip()}
        self.cfg = cfg
        self.scope = scope
        self.db = db
        self.session = session
        self._runner = runner            # injected for tests; real one below
        self._offset = 0
        self._last_cmd: Dict[int, float] = {}
        self._job: Optional[Dict[str, Any]] = None
        self._last: Optional[Dict[str, Any]] = None
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self.seen_unauthorised: List[int] = []

    # -- transport --------------------------------------------------------- #
    def _call(self, method: str, **params) -> Dict[str, Any]:
        session = self.session
        if session is None:
            from .core import build_session
            session = self.session = build_session(timeout=65)
        resp = session.get(API.format(token=self.token, method=method),
                           params=params, timeout=65)
        try:
            return resp.json() or {}
        except Exception:  # noqa: BLE001
            return {}

    def send(self, chat_id: int, text: str) -> None:
        """Reply, truncated to Telegram's limit rather than silently dropped."""
        body = text if len(text) <= MAX_MESSAGE else (
            text[:MAX_MESSAGE] + "\n…(truncated — open the dashboard for the rest)")
        try:
            self._call("sendMessage", chat_id=chat_id, text=body,
                       parse_mode="HTML", disable_web_page_preview=True)
        except Exception:  # noqa: BLE001 - a failed reply must not kill the bot
            pass

    # -- authorisation ----------------------------------------------------- #
    def authorised(self, chat_id: int) -> bool:
        """Default deny. An empty allow-list authorises nobody, not everybody."""
        return int(chat_id) in self.allowed

    def _guard(self, chat_id: int, command: str) -> None:
        if not self.authorised(chat_id):
            if chat_id not in self.seen_unauthorised:
                self.seen_unauthorised.append(chat_id)
            # /whoami is answered for anyone, because you need your own id to
            # put yourself on the allow-list in the first place. It reveals
            # nothing the caller does not already know about themselves.
            if command != "/whoami":
                raise Unauthorised(str(chat_id))
        now = time.time()
        last = self._last_cmd.get(chat_id, 0.0)
        if command.startswith(("/scan", "/ports")) and now - last < COOLDOWN:
            raise RuntimeError(f"slow down — {COOLDOWN:.0f}s between scans")
        self._last_cmd[chat_id] = now

    # -- scanning ---------------------------------------------------------- #
    def _in_scope(self, target: str) -> None:
        if self.scope is not None and not getattr(self.scope, "empty", True):
            ok, why = self.scope.allows(target)
            if not ok:
                raise PermissionError(f"out of scope: {why}")

    def run_scan(self, target: str, selection: Dict[str, Any],
                 options: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Run a scan synchronously and keep the result for later commands."""
        self._in_scope(target)
        if self._runner is not None:                    # test seam
            results = self._runner(target, selection, options or {})
        else:
            results = self._real_scan(target, selection, options or {})
        with self._lock:
            self._last = {"target": target, "results": results,
                          "when": time.time()}
        return self._last

    def _real_scan(self, target: str, selection: Dict[str, Any],
                   options: Dict[str, Any]):
        from . import engine, workflow
        from .config import Config
        from .core import Context, REGISTRY, build_session, get_module, \
            modules_by_category
        cfg = self.cfg or Config()
        mode = selection.get("mode", "profile")
        if mode == "profile":
            ids = workflow.load_recipes("recipes.yaml").get(selection.get("value"), [])
            mods = [get_module(i) for i in ids if get_module(i)]
        elif mode == "category":
            mods = modules_by_category().get(selection.get("value"), [])
        elif mode == "modules":
            mods = [get_module(i) for i in (selection.get("value") or []) if get_module(i)]
        else:
            mods = list(REGISTRY.values())
        if not mods:
            raise ValueError("that selection matched no modules")
        session = build_session(timeout=int(options.get("timeout") or 15))
        ctx = Context(config=cfg, session=session,
                      timeout=int(options.get("timeout") or 15), threads=10)
        for key in ("ports", "scan_retries", "scan_rate"):
            if options.get(key) not in (None, ""):
                setattr(ctx, key, options[key])
        with self._lock:
            self._job = {"target": target, "total": len(mods), "done": 0}
        out = []
        for mod in mods:
            if self._stop.is_set():
                break
            out.append(engine.execute_module(mod, target, ctx))
            with self._lock:
                self._job["done"] = len(out)
        with self._lock:
            self._job = None
        return out

    # -- command handling -------------------------------------------------- #
    def handle(self, chat_id: int, text: str) -> str:
        """Turn one message into a reply. Pure enough to test without a network."""
        text = (text or "").strip()
        command = text.split()[0].lower() if text else ""
        args = text.split()[1:]
        try:
            self._guard(chat_id, command)
        except Unauthorised:
            return ("⛔ This bot is not authorised for this chat.\n"
                    f"Your chat id is <code>{chat_id}</code> — add it to the "
                    "allow-list (<code>--telegram-allow</code>) if it is yours.")
        except RuntimeError as exc:
            return f"⏳ {html.escape(str(exc))}"

        if command in ("/start", "/help", ""):
            return HELP
        if command == "/whoami":
            return (f"chat id <code>{chat_id}</code> — "
                    + ("authorised ✅" if self.authorised(chat_id)
                       else "not on the allow-list ⛔"))
        if command == "/profiles":
            from . import workflow
            names = sorted(workflow.load_recipes("recipes.yaml"))
            return "<b>Profiles</b>\n" + "\n".join(f"· {n}" for n in names)
        if command == "/status":
            with self._lock:
                job = dict(self._job) if self._job else None
            if not job:
                return "Idle — nothing running."
            return (f"⏳ <b>{html.escape(job['target'])}</b> — "
                    f"{job['done']}/{job['total']} modules")
        if command == "/stop":
            self._stop.set()
            return "🛑 Stopping after the current module."
        if command == "/scans":
            return self._recent_scans()
        if command in ("/findings", "/fixorder"):
            return self._report(command)
        if command in ("/scan", "/ports"):
            if not args:
                return f"Usage: {command} &lt;target&gt;"
            target = args[0]
            try:
                if command == "/ports":
                    result = self.run_scan(
                        target, {"mode": "modules", "value": ["portscan"]},
                        {"ports": args[1] if len(args) > 1 else "top100",
                         "timeout": 5})
                else:
                    profile = args[1] if len(args) > 1 else "quick"
                    self._stop.clear()
                    result = self.run_scan(target, {"mode": "profile",
                                                    "value": profile})
            except PermissionError as exc:
                return f"⛔ {html.escape(str(exc))}"
            except Exception as exc:  # noqa: BLE001
                return f"❌ {html.escape(str(exc)[:200])}"
            return self._summary(result)
        return f"Unknown command {html.escape(command)}. /help"

    # -- formatting -------------------------------------------------------- #
    def _summary(self, scan: Dict[str, Any]) -> str:
        from . import reporting_ext
        results = scan["results"]
        try:
            scored = reporting_ext.score_findings(results)
        except Exception:  # noqa: BLE001
            scored = {"counts": {}, "risk_level": "?", "findings": []}
        counts = scored.get("counts", {})
        errors = sum(1 for r in results if getattr(r, "status", "") == "error")
        lines = [f"✅ <b>{html.escape(scan['target'])}</b> — "
                 f"{len(results)} modules, risk {scored.get('risk_level','?')}"]
        if any(counts.values()):
            lines.append("  ".join(f"{k}:{v}" for k, v in counts.items() if v))
        if errors:
            lines.append(f"⚠️ {errors} module(s) errored")
        for f in scored.get("findings", [])[:8]:
            lines.append(f"• <b>{html.escape(f['severity'])}</b> "
                         f"{html.escape(f['module'])}: "
                         f"{html.escape(str(f['detail'])[:110])}")
        if len(scored.get("findings", [])) > 8:
            lines.append(f"…and {len(scored['findings'])-8} more — /findings")
        return "\n".join(lines)

    def _report(self, command: str) -> str:
        with self._lock:
            last = dict(self._last) if self._last else None
        if not last:
            return "No scan yet — run /scan &lt;target&gt; first."
        if command == "/findings":
            from . import reporting_ext, verdicts
            scored = reporting_ext.score_findings(last["results"])
            ruled = verdicts.apply_verdicts(scored["findings"], db=self.db)
            rows = ruled["findings"][:15]
            if not rows:
                return "No findings crossed the severity threshold."
            out = [f"<b>Findings — {html.escape(last['target'])}</b>"]
            for f in rows:
                out.append(f"• <b>{html.escape(f['severity'])}</b> "
                           f"<code>{html.escape(f['id'])}</code> "
                           f"{html.escape(f['module'])}: "
                           f"{html.escape(str(f['detail'])[:100])}")
            if ruled["suppressed_count"]:
                out.append(f"\n({ruled['suppressed_count']} withheld by your verdicts)")
            return "\n".join(out)
        from . import prioritise
        rep = prioritise.prioritise(last["results"])
        if not rep["fix_order"]:
            return "No CVEs found in the last scan."
        out = [f"<b>Fix order — {html.escape(last['target'])}</b>"]
        if rep["act_now"]:
            out.append("🔥 <b>Act now:</b> " +
                       ", ".join(html.escape(a["cve"]) for a in rep["act_now"]))
        for i, f in enumerate(rep["fix_order"][:10], 1):
            out.append(f"{i}. <code>{html.escape(f['cve'])}</code> "
                       f"p{f['priority']} — {html.escape(f['reachability'])}")
        return "\n".join(out)

    def _recent_scans(self) -> str:
        try:
            from . import reporting
            store = reporting.Store(self.db)
            rows = store.recent_scans(10)
            store.close()
        except Exception as exc:  # noqa: BLE001
            return f"History unavailable: {html.escape(str(exc)[:120])}"
        if not rows:
            return "No stored scans (start the scanner with --db to keep them)."
        return "<b>Recent scans</b>\n" + "\n".join(
            f"· {html.escape(r['target'])} — {html.escape(str(r['risk']))} "
            f"({html.escape(str(r['ts'])[:16])})" for r in rows)

    # -- the loop ---------------------------------------------------------- #
    def poll_once(self, timeout: int = 25) -> int:
        """Fetch and answer one batch of updates. Returns how many were handled."""
        data = self._call("getUpdates", offset=self._offset, timeout=timeout)
        updates = data.get("result", []) or []
        for upd in updates:
            self._offset = max(self._offset, int(upd.get("update_id", 0)) + 1)
            msg = upd.get("message") or upd.get("edited_message") or {}
            chat = (msg.get("chat") or {}).get("id")
            text = msg.get("text") or ""
            if chat is None or not text:
                continue
            try:
                self.send(int(chat), self.handle(int(chat), text))
            except Exception as exc:  # noqa: BLE001 - one bad message, not the bot
                self.send(int(chat), f"❌ {html.escape(str(exc)[:160])}")
        return len(updates)

    def run_forever(self, on_event=None) -> None:
        me = self._call("getMe").get("result", {}) or {}
        if on_event:
            on_event(f"connected as @{me.get('username','?')}; "
                     f"{len(self.allowed)} chat(s) allow-listed")
        if not self.allowed and on_event:
            on_event("WARNING: the allow-list is empty, so every command will be "
                     "refused. Message the bot /whoami and add the id it reports.")
        while not self._stop.is_set():
            try:
                self.poll_once()
            except Exception as exc:  # noqa: BLE001
                if on_event:
                    on_event(f"poll failed: {str(exc)[:120]}")
                time.sleep(5)

    def shutdown(self) -> None:
        self._stop.set()
