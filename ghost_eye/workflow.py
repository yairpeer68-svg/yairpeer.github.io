"""Workflow / architecture features (new features #72-#80)."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import pickle
import shutil
import threading
import time
from pathlib import Path
from typing import Callable, Dict, List, Optional

from .core import Colors, Console, have_binary, REGISTRY

# --------------------------------------------------------------------------- #
#  #72  Plugin system - drop .py files in a plugins/ dir
# --------------------------------------------------------------------------- #
def load_plugins(directory: str) -> List[str]:
    loaded = []
    d = Path(directory)
    if not d.is_dir():
        return loaded
    for f in sorted(d.glob("*.py")):
        if f.name.startswith("_"):
            continue
        try:
            spec = importlib.util.spec_from_file_location(f"ghosteye_plugin_{f.stem}", f)
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)   # @register runs on import
            loaded.append(f.name)
        except Exception as exc:  # noqa: BLE001
            Console.warn(f"plugin {f.name} failed to load: {exc}")
    return loaded


# --------------------------------------------------------------------------- #
#  #73  Scan recipes / profiles
# --------------------------------------------------------------------------- #
DEFAULT_RECIPES: Dict[str, List[str]] = {
    "quick": ["dns", "whois", "headers", "cert", "subs"],
    "email": ["emailauth", "mtasts", "tlsrpt", "bimi", "dkim", "starttls",
              "dmarcrua", "disposable", "bimicheck", "mtastsval", "tlsrptcheck",
              "spoofcheck", "dispdetect", "catchalldetect"],
    "tls": ["cert", "certexpiry", "tlsversions", "ciphers", "chain", "tlsgrade",
            "ocspstaple", "keyaudit", "weakdh", "cipherorder", "hstspreload", "ja4",
            "ctdiff", "ja3", "deprecatedca", "certpin", "mixedcontent",
            "wildcertscope", "tlsresume", "caaaudit", "mtls", "scts"],
    "web": ["headers", "cors", "cookies", "methods", "waf", "cdn", "tech",
            "httpversions", "securitytxt", "cspgrade", "sri", "graphql",
            "rediradv", "corsadv", "cookieaudit", "clickjack", "methodenum",
            "apidisco", "ratelimit", "waffp", "cmsdetect", "wpscan",
            "sourcemap", "adminfinder", "smuggle", "favhash", "metatags",
            "formaction", "exposedfiles"],
    "cloud": ["cloudprov", "k8s", "docker", "tfstate", "cicd", "buckets",
              "dangling", "serverless", "metadata", "s3enum", "azureblob",
              "gcsbucket", "metassrf", "firebase", "gitrecon", "dnshost", "cdngeo"],
    "exposure": ["vcs", "backups", "buckets", "dirlisting", "admin", "dashboards",
                 "exposeddb", "rdpvnc", "snmp", "exposedfiles", "adminfinder",
                 "dockerapi", "k8sadv", "ldap", "smb", "ftpanon"],
    "osint": ["emails", "emailauth", "username", "dorks", "github",
              "whoispivot", "analytics", "related", "breachcheck", "social",
              "waybackadv", "pastebin", "gdork", "techstack", "threatfeed", "jsdeps"],
    "passive": ["internetdb", "geoip", "proxytype", "torexit", "threatfeed",
                "reputation", "urlscan", "breachcheck", "waybackadv", "pastebin"],
    "perimeter": ["dns", "subs", "nmap", "headers", "cert", "tlsgrade", "waf",
                  "cdn", "origin", "exposeddb", "dashboards", "vcs",
                  "subtakeover", "dnssecchain", "fwinfer", "svcver", "sshaudit"],
    "dns": ["dns", "whois", "subs", "dnssecchain", "dnswildcard", "domainage",
            "subtakeover", "dnsprop", "emailauth", "dnsrebind", "nsdelegation",
            "domexpiry", "glue", "typosquat"],
    "network": ["nmap", "tcptrace", "fwinfer", "v4v6parity", "bgphijack",
                "svcver", "sshaudit", "dohdot", "mqtt", "ntp", "grpc",
                "dockerapi", "k8sadv", "ldap", "smb", "ftpanon"],
    "ai": ["deepseek", "aiapi", "dsapi", "aikeyleak", "aidash", "vectordb",
            "aiapp", "modelserve", "aiorch", "jupyter", "hfrecon", "promptleak"],
}


def load_recipes(path: Optional[str]) -> Dict[str, List[str]]:
    recipes = dict(DEFAULT_RECIPES)
    if not path or not os.path.exists(path):
        return recipes
    try:
        text = Path(path).read_text(encoding="utf-8")
        if path.endswith((".yaml", ".yml")):
            try:
                import yaml
                user = yaml.safe_load(text) or {}
            except ImportError:
                user = _mini_yaml(text)
        else:
            user = json.loads(text)
        for k, v in user.items():
            if isinstance(v, list):
                recipes[k] = [str(x).strip() for x in v]
    except Exception as exc:  # noqa: BLE001
        Console.warn(f"could not parse recipes ({exc}); using defaults")
    return recipes


def _mini_yaml(text: str) -> Dict[str, List[str]]:
    """Tiny fallback parser for 'name: [a, b, c]' or list-block YAML."""
    out: Dict[str, List[str]] = {}
    current = None
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if ":" in line and "[" in line:
            k, v = line.split(":", 1)
            out[k.strip()] = [x.strip(" []") for x in v.split(",") if x.strip(" []")]
        elif line.rstrip().endswith(":") and not line.startswith((" ", "\t")):
            current = line.strip()[:-1]
            out[current] = []
        elif line.startswith((" ", "\t")) and current and line.strip().startswith("-"):
            out[current].append(line.strip()[1:].strip())
    return out


# --------------------------------------------------------------------------- #
#  #75 rate limiting + #77 response caching - wrap a requests.Session in place
# --------------------------------------------------------------------------- #
def wrap_session(session, rate: float = 0.0,
                 cache_dir: Optional[str] = None, cache_ttl: int = 300,
                 rate_per_host: float = 0.0):
    if not rate and not cache_dir and not rate_per_host:
        return session
    if cache_dir:
        Path(cache_dir).mkdir(parents=True, exist_ok=True)
    original = session.request
    lock = threading.Lock()
    state = {"last": 0.0}
    host_last: Dict[str, float] = {}

    def request(method, url, **kw):
        cache_file = None
        if cache_dir and method.upper() == "GET":
            key = hashlib.sha256(
                (url + json.dumps(kw.get("params") or {}, sort_keys=True)).encode()
            ).hexdigest()
            cache_file = Path(cache_dir) / f"{key}.pkl"
            if cache_file.exists() and (time.time() - cache_file.stat().st_mtime) < cache_ttl:
                try:
                    return pickle.loads(cache_file.read_bytes())
                except Exception:
                    pass
        if rate and rate > 0:
            with lock:
                wait = (1.0 / rate) - (time.time() - state["last"])
                if wait > 0:
                    time.sleep(wait)
                state["last"] = time.time()
        if rate_per_host and rate_per_host > 0:
            from urllib.parse import urlparse as _up
            netloc = _up(url).netloc
            with lock:
                wait = (1.0 / rate_per_host) - (time.time() - host_last.get(netloc, 0.0))
                if wait > 0:
                    time.sleep(wait)
                host_last[netloc] = time.time()
        resp = original(method, url, **kw)
        if cache_file is not None:
            try:
                resp.content  # force body load before pickling
                cache_file.write_bytes(pickle.dumps(resp))
            except Exception:
                pass
        return resp

    session.request = request
    return session


# --------------------------------------------------------------------------- #
#  #76  Checkpoint / resume for multi-target runs
# --------------------------------------------------------------------------- #
class Checkpoint:
    def __init__(self, path: str = ".ghosteye_checkpoint.json") -> None:
        self.path = Path(path)
        self.done = set()
        if self.path.exists():
            try:
                self.done = set(json.loads(self.path.read_text()))
            except Exception:
                self.done = set()

    def is_done(self, target: str) -> bool:
        return target in self.done

    def mark(self, target: str) -> None:
        self.done.add(target)
        try:
            self.path.write_text(json.dumps(sorted(self.done)))
        except Exception:
            pass

    def clear(self) -> None:
        self.done.clear()
        if self.path.exists():
            self.path.unlink()


# --------------------------------------------------------------------------- #
#  #78  Lightweight progress
# --------------------------------------------------------------------------- #
class Progress:
    def __init__(self, total: int, label: str = "") -> None:
        self.total = max(total, 1)
        self.label = label
        self.n = 0
        self._tqdm = None
        try:
            from tqdm import tqdm
            self._tqdm = tqdm(total=self.total, desc=label, unit="mod")
        except Exception:
            self._tqdm = None

    def step(self, info: str = "") -> None:
        self.n += 1
        if self._tqdm:
            self._tqdm.set_postfix_str(info[:30])
            self._tqdm.update(1)
        else:
            pct = int(self.n / self.total * 100)
            bar = "#" * (pct // 5) + "-" * (20 - pct // 5)
            print(f"\r{Colors.GREY}[{bar}] {pct:3d}% {self.n}/{self.total} "
                  f"{info[:34]:<34}{Colors.RESET}", end="", flush=True)

    def close(self) -> None:
        if self._tqdm:
            self._tqdm.close()
        else:
            print()


# --------------------------------------------------------------------------- #
#  #79  doctor - check dependencies and external binaries
# --------------------------------------------------------------------------- #
_PY_DEPS = ["requests", "dns", "bs4", "nmap", "whois", "mmh3", "PIL",
            "phonenumbers", "reportlab", "cryptography", "yaml", "tqdm",
            "cloudscraper", "webtech", "pysnmp"]
_BINARIES = ["nmap", "masscan", "mtr", "traceroute", "dig", "whois",
             "exiftool", "dot", "ping", "openssl"]


def doctor() -> None:
    print(f"{Colors.BOLD}Ghost Eye doctor - environment check{Colors.RESET}\n")
    print(f"{Colors.BLUE}Python modules:{Colors.RESET}")
    for name in _PY_DEPS:
        try:
            __import__(name)
            mark, col = "OK ", Colors.GREEN
        except Exception:
            mark, col = "miss", Colors.YELLOW
        print(f"  {col}[{mark}]{Colors.RESET} {name}")
    print(f"\n{Colors.BLUE}External binaries:{Colors.RESET}")
    for b in _BINARIES:
        ok = have_binary(b)
        col = Colors.GREEN if ok else Colors.YELLOW
        print(f"  {col}[{'OK ' if ok else 'miss'}]{Colors.RESET} {b}"
              f"{'' if ok else '   (some modules will degrade)'}")
    print(f"\n{Colors.CYAN}Modules registered: {len(REGISTRY)}{Colors.RESET}")
    print(f"{Colors.GREY}Install Python deps: pip install -r requirements.txt{Colors.RESET}")
    if is_termux():
        print(f"\n{Colors.BLUE}Termux detected.{Colors.RESET}")
        print(f"{Colors.GREY}  binaries:  pkg install nmap dnsutils whois openssl "
              f"graphviz exiftool{Colors.RESET}")
        print(f"{Colors.GREY}  python:    pip install -r requirements-termux.txt"
              f"   (pure-python, no compiler needed){Colors.RESET}")
        print(f"{Colors.GREY}  note:      SYN scan, masscan and raw ping need root "
              f"(unavailable on stock Android) - those modules skip themselves.{Colors.RESET}")


def is_termux() -> bool:
    return ("com.termux" in os.environ.get("PREFIX", "")
            or os.path.isdir("/data/data/com.termux"))


# --------------------------------------------------------------------------- #
#  #80  i18n - minimal translation table
# --------------------------------------------------------------------------- #
_STRINGS = {
    "en": {
        "choose": "Choose a module by number (or 'a' = all, 'q' = quit):",
        "enter_choice": "Enter choice:",
        "enter_target": "Enter {kind}:",
        "no_target": "no target given",
        "invalid": "invalid option",
        "bye": "Like to See Ya, Hacking Anywhere..!",
        "report_written": "report written: {path}",
        "total_modules": "Total modules: {n}",
        "requires": "requires",
        "took": "took",
    },
    "he": {
        "choose": "בחר מודול לפי מספר (או 'a' = הכל, 'q' = יציאה):",
        "enter_choice": "הכנס בחירה:",
        "enter_target": "הכנס {kind}:",
        "no_target": "לא הוזן יעד",
        "invalid": "בחירה לא חוקית",
        "bye": "נתראה, פריצה בכל מקום..!",
        "report_written": "הדוח נכתב: {path}",
        "total_modules": "סך המודולים: {n}",
        "requires": "דורש",
        "took": "לקח",
    },
}
_LANG = {"current": "en"}


def set_lang(lang: str) -> None:
    if lang in _STRINGS:
        _LANG["current"] = lang


def t(key: str, **kw) -> str:
    s = _STRINGS.get(_LANG["current"], _STRINGS["en"]).get(key) \
        or _STRINGS["en"].get(key, key)
    return s.format(**kw) if kw else s


# --------------------------------------------------------------------------- #
#  Deep / recursive scan planning (shared by the CLI and the web dashboard)
# --------------------------------------------------------------------------- #
DEEP_HOST_MODULES = ["dns", "tech", "headers", "tlsgrade", "takeover",
                     "cookies", "cspgrade", "cors", "securitytxt"]
DEEP_IP_MODULES = ["internetdb", "ripestat", "geoip", "portscan"]


def deep_plan(results, target="", scope=None, max_hosts: int = 25):
    """Return (plan, assets): a list of (asset, [modules]) to scan next, derived
    from the hosts/IPs discovered in `results`."""
    from .inventory import collect_assets
    from .core import REGISTRY
    assets = collect_assets(results, target, scope, max_hosts)
    host_mods = [REGISTRY[i] for i in DEEP_HOST_MODULES if i in REGISTRY]
    ip_mods = [REGISTRY[i] for i in DEEP_IP_MODULES if i in REGISTRY]
    plan = [(h, host_mods) for h in assets["hosts"]] + \
           [(ip, ip_mods) for ip in assets["ips"]]
    return plan, assets
