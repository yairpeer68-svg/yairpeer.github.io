"""
Ghost Eye core framework.

Provides the shared building blocks every module relies on:
  * Colors / Console      - consistent, optionally-disabled ANSI output
  * validators            - strict domain / IP / URL validation (kills the
                            os.system command-injection class of bug)
  * run_cmd               - safe subprocess wrapper (NO shell=True)
  * build_session         - a configured requests.Session (UA, proxy, retries)
  * Result                - structured result object every module returns
  * Module / register     - base class + decorator that auto-populate the menu
"""

from __future__ import annotations

import ipaddress
import logging
import os
import re
import shutil
import subprocess
import sys
import textwrap
import traceback
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

log = logging.getLogger("ghosteye")


# --------------------------------------------------------------------------- #
#  Persistent error log — every crash/failure is appended here so nothing is
#  silently lost. Path: $GHOSTEYE_ERRORLOG or ~/.ghosteye/errors.log
# --------------------------------------------------------------------------- #
def errorlog_path() -> Path:
    env = os.environ.get("GHOSTEYE_ERRORLOG")
    return Path(env) if env else Path.home() / ".ghosteye" / "errors.log"


def record_error(where: str, target: str = "", exc: Any = None) -> None:
    """Append a crash/failure to the persistent error log.

    `exc` may be an exception (its traceback is captured) or any message.
    Never raises — error logging must not become a second failure."""
    try:
        path = errorlog_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        ts = datetime.now(timezone.utc).isoformat(timespec="seconds")
        head = f"[{ts}] {where}" + (f"  target={target}" if target else "")
        lines = [head]
        if isinstance(exc, BaseException):
            lines.append(f"    {type(exc).__name__}: {exc}")
            tb = "".join(traceback.format_exception(
                type(exc), exc, exc.__traceback__)).rstrip()
            if tb:
                lines.append(textwrap.indent(tb, "    "))
        elif exc:
            lines.append(f"    {exc}")
        with open(path, "a", encoding="utf-8") as fh:
            fh.write("\n".join(lines) + "\n")
        log.debug("recorded error to %s (%s)", path, where)
    except Exception:  # noqa: BLE001 - logging must never crash the run
        pass


# --------------------------------------------------------------------------- #
#  Colours / console
# --------------------------------------------------------------------------- #
class Colors:
    """ANSI colour codes. Call Colors.disable() for no-colour / piped output."""

    BLUE = "\033[1;34m"
    CYAN = "\033[1;36m"
    GREEN = "\033[1;32m"
    YELLOW = "\033[1;33m"
    RED = "\033[1;91m"
    GREY = "\033[1;90m"
    BOLD = "\033[1m"
    RESET = "\033[0m"

    @classmethod
    def disable(cls) -> None:
        for name in ("BLUE", "CYAN", "GREEN", "YELLOW", "RED", "GREY", "BOLD", "RESET"):
            setattr(cls, name, "")


class Console:
    """Tiny print helper so every module looks the same."""

    @staticmethod
    def info(msg: str) -> None:
        print(f"{Colors.BLUE}[~]{Colors.RESET} {msg}")

    @staticmethod
    def good(msg: str) -> None:
        print(f"{Colors.GREEN}[+]{Colors.RESET} {msg}")

    @staticmethod
    def warn(msg: str) -> None:
        print(f"{Colors.YELLOW}[!]{Colors.RESET} {msg}")

    @staticmethod
    def err(msg: str) -> None:
        print(f"{Colors.RED}[-]{Colors.RESET} {msg}")

    @staticmethod
    def kv(key: str, value: Any, indent: int = 2) -> None:
        pad = " " * indent
        print(f"{pad}{Colors.CYAN}{key}:{Colors.RESET} {value}")

    @staticmethod
    def rule(title: str = "") -> None:
        line = "-" * 60
        if title:
            print(f"\n{Colors.GREY}{line}{Colors.RESET}\n{Colors.BOLD}{title}{Colors.RESET}")
        else:
            print(f"{Colors.GREY}{line}{Colors.RESET}")


# --------------------------------------------------------------------------- #
#  Validation  (defends every module that shells out or builds a URL)
# --------------------------------------------------------------------------- #
_DOMAIN_RE = re.compile(
    r"^(?=.{1,253}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)"
    r"(?:\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))+$"
)


def is_ip(value: str) -> bool:
    try:
        ipaddress.ip_address(value)
        return True
    except ValueError:
        return False


def is_domain(value: str) -> bool:
    return bool(_DOMAIN_RE.match(value or ""))


def is_host(value: str) -> bool:
    """Accept either a bare domain or an IP. Used before anything is executed."""
    return is_ip(value) or is_domain(value)


class Host(str):
    """A validated bare host that *remembers* the scheme and port of the target
    it was parsed from.

    It is a plain ``str`` holding only the host, so the hundreds of call sites
    that hand it to DNS resolvers, sockets and subprocesses keep working
    unchanged. ``ensure_scheme`` reads the remembered parts back, which is what
    stops ``-t example.com:8080`` from silently becoming a scan of port 443.
    """

    # NB: no __slots__ — CPython forbids nonempty __slots__ on a str subclass.

    def __new__(cls, host: str, port: Optional[int] = None,
                scheme: Optional[str] = None) -> "Host":
        obj = super().__new__(cls, host)
        obj.port = port
        obj.scheme = scheme
        return obj


def _parse_port(raw: str) -> int:
    if not raw.isdigit() or not 1 <= int(raw) <= 65535:
        raise ValueError(f"refusing unsafe / invalid port: {raw!r}")
    return int(raw)


def clean_host(value: str) -> Host:
    """
    Normalise user input to a bare host and REJECT anything that is not a
    plain domain/IP. Strips scheme, path, port, whitespace. Raises ValueError
    on anything suspicious so a malicious string never reaches a subprocess.

    The returned :class:`Host` still compares and behaves as the bare host, but
    carries the original scheme/port so URL builders can restore them.
    """
    if not value:
        raise ValueError("empty target")
    v = value.strip().lower()
    scheme_match = re.match(r"^([a-z][a-z0-9+.-]*)://", v)
    scheme = scheme_match.group(1) if scheme_match else None
    if scheme_match:
        v = v[scheme_match.end():]          # drop scheme
    v = v.split("/")[0]                      # drop path
    v = v.split("?")[0]
    v = v.split("#")[0]
    port: Optional[int] = None
    if v.startswith("["):                    # [IPv6]:port
        host_part, _, rest = v.partition("]")
        v = host_part.lstrip("[")
        if rest.startswith(":") and rest[1:]:
            port = _parse_port(rest[1:])
    elif v.count(":") == 1 and not is_ip(v):  # host:port (bare IPv6 has >1)
        v, _, raw_port = v.partition(":")
        if raw_port:
            port = _parse_port(raw_port)
    if not is_host(v):
        raise ValueError(f"refusing unsafe / invalid target: {value!r}")
    return Host(v, port=port, scheme=scheme)


def ensure_scheme(target: str, default: str = "https") -> str:
    """Build a base URL for `target`.

    When `target` is a :class:`Host` produced by :func:`clean_host`, the scheme
    and port of the original input are restored — so a module that was handed
    ``http://example.com:8080`` probes that, not ``https://example.com``.
    """
    if re.match(r"^[a-z][a-z0-9+.-]*://", target):
        return target
    scheme = getattr(target, "scheme", None) or default
    port = getattr(target, "port", None)
    host = f"[{target}]" if is_ip(target) and ":" in target else str(target)
    return f"{scheme}://{host}:{port}" if port else f"{scheme}://{host}"


# --------------------------------------------------------------------------- #
#  Safe external-command runner  (replaces os.system / os.popen)
# --------------------------------------------------------------------------- #
def have_binary(name: str) -> bool:
    return shutil.which(name) is not None


def run_cmd(args: List[str], timeout: int = 60) -> str:
    """
    Run an external binary safely.

    args MUST be a list (argv) -> no shell, so user input cannot be
    interpreted as shell metacharacters. Returns combined stdout/stderr text.
    """
    if not args:
        return ""
    if not have_binary(args[0]):
        # degrade gracefully: a missing optional binary yields no output rather
        # than crashing the module (callers treat empty output as "no data").
        return ""
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return f"[timeout after {timeout}s running {args[0]}]"
    out = proc.stdout or ""
    if proc.stderr:
        out += ("\n" + proc.stderr) if out else proc.stderr
    return out.strip()


def dns_resolver(ctx: "Context"):
    """A dnspython resolver honouring the scan timeout. Imported lazily so the
    package loads even when dnspython is not installed (the caller then fails
    gracefully). Shared by every DNS/email module."""
    import dns.resolver
    r = dns.resolver.Resolver()
    r.lifetime = ctx.timeout
    r.timeout = ctx.timeout
    return r


# --------------------------------------------------------------------------- #
#  HTTP session
# --------------------------------------------------------------------------- #
DEFAULT_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)


# SSL errors that mean "this port simply isn't speaking TLS" — as opposed to a
# certificate problem, which is exactly what a man-in-the-middle looks like and
# must NEVER trigger a downgrade to plaintext.
_NOT_TLS_ERRORS = (
    "wrong_version_number", "unknown protocol", "record layer failure",
    "packet length too long", "http_request",
)
# Any of these on a request means it carries a secret. Never retry those in
# the clear, whatever the error.
_CREDENTIAL_HEADERS = ("authorization", "x-api-key", "apikey", "x-auth-token",
                       "cookie", "proxy-authorization")


def _plaintext_retry_ok(url: str, kw: dict, session, exc: Exception) -> bool:
    """Whether a failed https request may be retried over http."""
    if not url.lower().startswith("https://"):
        return False
    import requests
    if isinstance(exc, requests.exceptions.SSLError):
        # only when the far end is plainly not a TLS listener
        if not any(w in str(exc).lower() for w in _NOT_TLS_ERRORS):
            return False
    elif not isinstance(exc, requests.exceptions.ConnectionError):
        return False
    merged = {str(k).lower() for k in (session.headers or {})}
    merged |= {str(k).lower() for k in (kw.get("headers") or {})}
    if merged & set(_CREDENTIAL_HEADERS):
        return False
    return not (kw.get("auth") or kw.get("cert"))


def build_session(
    user_agent: Optional[str] = None,
    proxy: Optional[str] = None,
    verify_tls: bool = True,
    timeout: int = 15,
    http_fallback: bool = True,
):
    """Return a configured requests.Session. Imported lazily so the package
    still loads if requests is missing.

    With `http_fallback`, an https request that fails *because the port isn't
    running TLS at all* is retried once over http. That is what makes
    `-t example.com` reach an http-only site, and `-t host:8080` reach a
    plaintext service, instead of reporting a bare SSL error. Certificate
    failures never downgrade, and neither does any request carrying a
    credential — otherwise the fallback would be an attacker-triggerable way to
    resend secrets in the clear."""
    import requests
    from requests.adapters import HTTPAdapter

    try:
        from urllib3.util.retry import Retry
        retry = Retry(total=2, backoff_factor=0.4,
                      status_forcelist=(429, 500, 502, 503, 504))
        adapter = HTTPAdapter(max_retries=retry, pool_connections=20, pool_maxsize=20)
    except Exception:  # urllib3 version differences
        adapter = HTTPAdapter()

    s = requests.Session()
    s.mount("http://", adapter)
    s.mount("https://", adapter)
    s.headers.update({"User-Agent": user_agent or DEFAULT_UA})
    if proxy:
        s.proxies.update({"http": proxy, "https": proxy})
    s.verify = verify_tls
    # stash a default timeout the modules can read
    s.request_timeout = timeout  # type: ignore[attr-defined]
    if not verify_tls:
        try:
            import urllib3
            urllib3.disable_warnings()
        except Exception:
            pass
    if http_fallback:
        inner = s.request

        def _with_fallback(method, url, **kw):
            try:
                return inner(method, url, **kw)
            except Exception as exc:  # noqa: BLE001 - re-raised unless retryable
                if not _plaintext_retry_ok(str(url), kw, s, exc):
                    raise
                log.debug("https failed on %s (%s); retrying over http", url, exc)
                return inner(method, "http://" + str(url)[len("https://"):], **kw)

        s.request = _with_fallback  # type: ignore[method-assign]
    return s


# --------------------------------------------------------------------------- #
#  Result object
# --------------------------------------------------------------------------- #
@dataclass
class Result:
    module: str
    target: str
    status: str = "ok"                 # ok | error | empty
    data: Dict[str, Any] = field(default_factory=dict)
    error: Optional[str] = None
    started: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    # wall time the module took, filled in by engine.execute_module. Without it
    # a slow scan is a mystery: you can see that it took ten minutes but not
    # which module spent them.
    elapsed_ms: int = 0

    def as_dict(self) -> Dict[str, Any]:
        return {
            "module": self.module,
            "target": self.target,
            "status": self.status,
            "data": self.data,
            "error": self.error,
            "timestamp": self.started,
            "elapsed_ms": self.elapsed_ms,
        }

    def render(self) -> None:
        """Pretty-print to the console."""
        if self.status == "error":
            Console.err(f"{self.module}: {self.error}")
            return
        if self.status == "empty" or not self.data:
            Console.warn(f"{self.module}: no data found for {self.target}")
            return
        _render_value(self.data, indent=2)


def _render_value(value: Any, indent: int = 0) -> None:
    pad = " " * indent
    if isinstance(value, dict):
        for k, v in value.items():
            if isinstance(v, (dict, list)) and v:
                print(f"{pad}{Colors.CYAN}{k}:{Colors.RESET}")
                _render_value(v, indent + 2)
            else:
                print(f"{pad}{Colors.CYAN}{k}:{Colors.RESET} {v}")
    elif isinstance(value, list):
        for item in value:
            if isinstance(item, (dict, list)):
                _render_value(item, indent)
                print()
            else:
                print(f"{pad}- {item}")
    else:
        print(f"{pad}{value}")


# --------------------------------------------------------------------------- #
#  Context passed to every module
# --------------------------------------------------------------------------- #

# Ceilings that belong to the Context because every execution path builds one.
MAX_MODULE_THREADS = 64      # workers inside a single module
DEFAULT_MODULE_THREADS = 10
MAX_TIMEOUT = 300            # seconds; longer pins a worker with nothing to show


def open_db(path, timeout: float = 30.0):
    """Open a SQLite connection with the settings a threaded server needs.

    Five call sites opened their own connection with five different sets of
    defaults, so parts of the product were hardened against concurrent access
    and parts were not — and "database is locked" surfaces only under the load
    nobody tests with.

    * **WAL** lets readers run while a writer holds the file. Without it, a
      background scan writing results blocks every dashboard read.
    * **busy_timeout** makes a contended write wait instead of raising
      immediately, which is what "database is locked" actually is.
    * **check_same_thread=False** because the connection is handed between the
      request thread and the worker that finishes a job. The caller still owns
      serialising its own writes.

    WAL is unavailable on some network filesystems; failing to set it is not a
    reason to refuse to open the database.
    """
    import sqlite3
    conn = sqlite3.connect(str(path), timeout=timeout, check_same_thread=False)
    try:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute(f"PRAGMA busy_timeout={int(timeout * 1000)}")
        conn.execute("PRAGMA synchronous=NORMAL")
    except Exception:  # noqa: BLE001 - e.g. WAL on a network filesystem
        pass
    return conn


@dataclass
class Context:
    config: Any = None
    session: Any = None
    threads: int = 10
    timeout: int = 15
    verbose: bool = False

    def __post_init__(self) -> None:
        # `threads` sizes the pool *inside* each module, and roughly sixty
        # modules pass it to ThreadPoolExecutor unmodified. Clamping only the
        # number of modules in flight therefore bounds nothing: 32 modules
        # each asking for 5000 workers is 160,000 threads, and every layer
        # above looks correctly limited while it happens.
        #
        # Clamped here, in the object every execution path must build, rather
        # than in the API — the CLI, the scheduler, the Telegram bot and the
        # distributed worker all construct a Context, and a limit enforced in
        # one caller is a limit with as many holes as there are callers.
        try:
            n = int(self.threads)
        except (TypeError, ValueError):
            n = DEFAULT_MODULE_THREADS
        if n != n:                       # NaN
            n = DEFAULT_MODULE_THREADS
        self.threads = max(1, min(MAX_MODULE_THREADS, n))
        try:
            t = float(self.timeout)
        except (TypeError, ValueError):
            t = 15.0
        # A timeout of zero means "never wait", which turns every module into
        # an instant failure; an unbounded one pins a worker for ever.
        self.timeout = type(self.timeout)(max(1, min(MAX_TIMEOUT, t))) \
            if isinstance(self.timeout, int) else max(1.0, min(MAX_TIMEOUT, t))
    # Set when the operator cancels. The engine checks it before starting each
    # module, and long-running modules may poll it. Without somewhere for a
    # cancel to *live*, "stop" can only ever mean "stop collecting results" —
    # the work carries on and the UI says it stopped, which is the lie that
    # matters.
    stop_event: Any = None

    def cancelled(self) -> bool:
        """True once the operator has asked to stop. Safe to call from a
        module in a loop; never raises, and answers False when unset."""
        ev = getattr(self, "stop_event", None)
        try:
            return bool(ev is not None and ev.is_set())
        except Exception:  # noqa: BLE001 - a broken flag must not break a scan
            return False


# --------------------------------------------------------------------------- #
#  Module base class + registry
# --------------------------------------------------------------------------- #
class Module:
    """Subclass this for every feature. The CLI builds its menu from REGISTRY."""

    id: str = ""                 # short slug, e.g. "dns"
    name: str = ""               # human label shown in the menu
    category: str = "Misc"
    target_kind: str = "host"    # host | domain | url | ip  (for the prompt)
    needs: List[str] = []        # required binaries / api-keys (informational)
    absorbed: List[str] = []     # ids this module replaced (kept as aliases)

    # ---- health contract (all optional; see ghost_eye.health) -------------- #
    # A stable, known-good target to probe this module against. None -> the
    # health harness derives one from target_kind. False -> skip health checks.
    health_target: Any = None
    # What a *healthy* result looks like, so a source that starts returning
    # 200-with-empty (the silent-failure case) is caught, not passed as "ok".
    # Either a list of keys that must be present in the (flattened) result data,
    # or a callable(data) -> bool. None -> generic "ran and returned data".
    expect: Any = None

    def run(self, target: str, ctx: Context) -> Result:  # pragma: no cover
        raise NotImplementedError

    def health_probe(self, ctx: "Context") -> Optional[Dict[str, Any]]:
        """Optional custom self-test for the health harness.

        Some modules can't be checked by "run against a canary target and see if
        data comes back" — the exploit-intelligence module against example.com
        finds no CVEs and looks empty, which says nothing about whether its
        sources still work. Such a module overrides this to probe a *known*
        answer (e.g. a CVE that is definitely weaponised) and returns
        ``{"ok": bool, "detail": str, ...}``. Returning None (the default) tells
        the harness to fall back to the generic canary-target probe."""
        return None

    # convenience: build a Result bound to this module
    def ok(self, target: str, data: Dict[str, Any]) -> Result:
        status = "ok" if data else "empty"
        return Result(self.name, target, status=status, data=data)

    def fail(self, target: str, err: str) -> Result:
        return Result(self.name, target, status="error", error=err)


REGISTRY: Dict[str, Module] = {}

# retired module id -> the canonical module that absorbed it. When two modules
# turned out to query the same source for the same purpose they were merged;
# the old id keeps working (recipes, saved scans and muscle memory don't break)
# and simply resolves to the survivor.
ALIASES: Dict[str, str] = {}


def resolve_id(module_id: str) -> str:
    """Map a possibly-retired module id onto the id that serves it today."""
    seen = set()
    mid = (module_id or "").strip()
    while mid in ALIASES and mid not in seen:
        seen.add(mid)
        mid = ALIASES[mid]
    return mid


def get_module(module_id: str) -> Optional[Module]:
    """Look a module up by id, transparently following merge aliases."""
    return REGISTRY.get(resolve_id(module_id))


def register(cls):
    """Class decorator: instantiate the module and add it to the registry."""
    inst = cls()
    if not inst.id:
        raise ValueError(f"module {cls.__name__} has no id")
    if inst.id in REGISTRY:
        raise ValueError(f"duplicate module id: {inst.id}")
    if inst.id in ALIASES:
        raise ValueError(f"module id {inst.id!r} is registered as an alias")
    for old in getattr(inst, "absorbed", []) or []:
        if old in REGISTRY:
            raise ValueError(
                f"{inst.id!r} claims to absorb {old!r}, which still exists")
        ALIASES[old] = inst.id
    # Results are labelled with .name, and every diff/compare/history view keys
    # them by that label — two modules sharing a name silently erase each other.
    clash = next((m for m in REGISTRY.values() if m.name == inst.name), None)
    if clash is not None:
        raise ValueError(
            f"duplicate module name {inst.name!r}: used by both "
            f"{clash.id!r} and {inst.id!r} — names must be unique because "
            f"results are keyed by them")
    REGISTRY[inst.id] = inst
    return cls


def modules_by_category() -> Dict[str, List[Module]]:
    out: Dict[str, List[Module]] = {}
    for mod in REGISTRY.values():
        out.setdefault(mod.category, []).append(mod)
    for mods in out.values():
        mods.sort(key=lambda m: m.name.lower())
    return out


# --------------------------------------------------------------------------- #
#  Logging
# --------------------------------------------------------------------------- #
def setup_logging(verbose: bool = False, logfile: Optional[str] = None) -> None:
    level = logging.DEBUG if verbose else logging.INFO
    handlers: List[logging.Handler] = []
    if logfile:
        handlers.append(logging.FileHandler(logfile, encoding="utf-8"))
    if verbose:
        sh = logging.StreamHandler(sys.stderr)
        handlers.append(sh)
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        handlers=handlers or [logging.NullHandler()],
    )
