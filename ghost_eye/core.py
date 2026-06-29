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
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Callable, Dict, List, Optional

log = logging.getLogger("ghosteye")


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


def clean_host(value: str) -> str:
    """
    Normalise user input to a bare host and REJECT anything that is not a
    plain domain/IP. Strips scheme, path, port, whitespace. Raises ValueError
    on anything suspicious so a malicious string never reaches a subprocess.
    """
    if not value:
        raise ValueError("empty target")
    v = value.strip().lower()
    v = re.sub(r"^[a-z]+://", "", v)        # drop scheme
    v = v.split("/")[0]                      # drop path
    v = v.split("?")[0]
    v = v.split("#")[0]
    if v.count(":") == 1 and not is_ip(v):   # host:port (but keep IPv6)
        v = v.split(":")[0]
    if not is_host(v):
        raise ValueError(f"refusing unsafe / invalid target: {value!r}")
    return v


def ensure_scheme(target: str, default: str = "https") -> str:
    if re.match(r"^[a-z]+://", target):
        return target
    return f"{default}://{target}"


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
        raise FileNotFoundError(f"required binary not found: {args[0]}")
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


# --------------------------------------------------------------------------- #
#  HTTP session
# --------------------------------------------------------------------------- #
DEFAULT_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
)


def build_session(
    user_agent: Optional[str] = None,
    proxy: Optional[str] = None,
    verify_tls: bool = True,
    timeout: int = 15,
):
    """Return a configured requests.Session. Imported lazily so the package
    still loads if requests is missing."""
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

    def as_dict(self) -> Dict[str, Any]:
        return {
            "module": self.module,
            "target": self.target,
            "status": self.status,
            "data": self.data,
            "error": self.error,
            "timestamp": self.started,
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
@dataclass
class Context:
    config: Any
    session: Any = None
    threads: int = 10
    timeout: int = 15
    verbose: bool = False


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

    def run(self, target: str, ctx: Context) -> Result:  # pragma: no cover
        raise NotImplementedError

    # convenience: build a Result bound to this module
    def ok(self, target: str, data: Dict[str, Any]) -> Result:
        status = "ok" if data else "empty"
        return Result(self.name, target, status=status, data=data)

    def fail(self, target: str, err: str) -> Result:
        return Result(self.name, target, status="error", error=err)


REGISTRY: Dict[str, Module] = {}


def register(cls):
    """Class decorator: instantiate the module and add it to the registry."""
    inst = cls()
    if not inst.id:
        raise ValueError(f"module {cls.__name__} has no id")
    if inst.id in REGISTRY:
        raise ValueError(f"duplicate module id: {inst.id}")
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
