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
            "formaction", "exposedfiles",
            "wasmdetect", "swaudit", "pwacheck", "http3check", "permspolicy",
            "referrerpol", "coopcoep", "cachpoison", "hostheader", "httpdesync",
            "mimesniff"],
    "cloud": ["cloudprov", "k8s", "docker", "tfstate", "cicd", "buckets",
              "dangling", "serverless", "metadata", "s3enum", "azureblob",
              "gcsbucket", "metassrf", "firebase", "gitrecon", "dnshost", "cdngeo",
              "cfdnmisconfig", "azureadtenant", "gcpenum", "tfcloud",
              "vaultdetect", "consuldetect", "etcddetect"],
    "exposure": ["vcs", "backups", "buckets", "dirlisting", "admin", "dashboards",
                 "exposeddb", "rdpvnc", "snmp", "exposedfiles", "adminfinder",
                 "dockerapi", "k8sadv", "ldap", "smb", "ftpanon"],
    "osint": ["emails", "emailauth", "username", "dorks", "github",
              "whoispivot", "analytics", "related", "breachcheck", "social",
              "waybackadv", "pastebin", "gdork", "techstack", "threatfeed",
              "threatagg", "jsdeps",
              "jobstech", "feedfind", "sitemapintel", "robotsdiff", "orgprofile",
              "commitauthors", "whoistimeline", "favsimilar"],
    "passive": ["internetdb", "geoip", "proxytype", "torexit", "threatfeed",
                "reputation", "urlscan", "breachcheck", "waybackadv", "pastebin"],
    "perimeter": ["dns", "subs", "nmap", "headers", "cert", "tlsgrade", "waf",
                  "cdn", "origin", "exposeddb", "dashboards", "vcs",
                  "subtakeover", "dnssecchain", "fwinfer", "svcver", "sshaudit"],
    "dns": ["dns", "whois", "subs", "dnssecchain", "dnswildcard", "domainage",
            "subtakeover", "dnsprop", "emailauth", "dnsrebind", "nsdelegation",
            "domexpiry", "glue", "typosquat", "dmarcalign", "homoglyph"],
    "network": ["nmap", "tcptrace", "fwinfer", "v4v6parity", "bgphijack",
                "svcver", "sshaudit", "dohdot", "mqtt", "ntp", "grpc",
                "dockerapi", "k8sadv", "ldap", "smb", "ftpanon",
                "quicdetect", "wgdetect", "meshdetect", "ipv6only", "rebindguard"],
    "ai": ["deepseek", "aiapi", "dsapi", "aikeyleak", "aidash", "vectordb",
            "aiapp", "modelserve", "aiorch", "jupyter", "hfrecon", "promptleak"],
    "api": ["gqlaudit", "restfuzz", "wsaudit", "ssedetect", "apiver",
            "preflightcheck", "contentneg", "hateoas", "webhookfind", "idorsurface"],
    "auth": ["oauthaudit", "jwtaudit", "samldetect", "sessionaudit",
             "loginsurface", "pwresetaudit", "mfacheck", "captchacheck"],
    "privacy": ["gdpraudit", "trackerinv", "privacypol", "piiscan",
                "ccpacheck", "dataresidency", "consentlog"],
    "supply_chain": ["npmscan", "pipscan", "dockertag", "actionleak",
                     "cicdscan", "sbomextract", "depconfuse"],
    "iot": ["upnpscan", "rtspscan", "coapscan", "icsscan",
            "telnetscan", "snmpv3", "mdnsscan"],
    "crypto": ["web3rpc", "cryptoaddr", "smartcontract", "ipfsgw", "ensscan"],
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


# --------------------------------------------------------------------------- #
#  #76  Composite attack score (weighted risk across all findings)
# --------------------------------------------------------------------------- #
_CATEGORY_WEIGHTS = {
    "CRITICAL": 10.0, "HIGH": 5.0, "MEDIUM": 2.0, "LOW": 0.5,
    "informational": 0.0,
}

def attack_score(results) -> dict:
    """Compute a composite attack-surface score from scan results."""
    from .reporting_ext import score_findings
    scored = score_findings(results)
    counts = scored.get("counts", {})
    raw = sum(counts.get(sev, 0) * w for sev, w in _CATEGORY_WEIGHTS.items())
    # normalize to 0-100
    total_findings = sum(counts.values()) or 1
    normalized = min(100, int(raw / total_findings * 10))
    grade = ("A+" if normalized < 5 else "A" if normalized < 15
             else "B" if normalized < 30 else "C" if normalized < 50
             else "D" if normalized < 70 else "F")
    return {
        "raw_score": round(raw, 1),
        "normalized": normalized,
        "grade": grade,
        "finding_counts": counts,
        "risk_level": scored.get("risk_level", "LOW"),
    }


# --------------------------------------------------------------------------- #
#  #77  Executive summary report (text-based, PDF via reportlab if available)
# --------------------------------------------------------------------------- #
def exec_report(results, target: str = "", out_path: str = "") -> str:
    """Generate an executive summary report. Returns the output path."""
    from .reporting_ext import score_findings
    scored = score_findings(results)
    ascore = attack_score(results)
    ts = time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())

    lines = [
        "=" * 60,
        "GHOST EYE — Executive Summary Report",
        "=" * 60,
        f"Target:     {target}",
        f"Date:       {ts}",
        f"Modules:    {len(results)}",
        f"Grade:      {ascore['grade']} (score: {ascore['normalized']}/100)",
        f"Risk Level: {ascore['risk_level']}",
        "",
        "--- Finding Summary ---",
        f"  Critical: {scored['counts'].get('critical', 0)}",
        f"  High:     {scored['counts'].get('high', 0)}",
        f"  Medium:   {scored['counts'].get('medium', 0)}",
        f"  Low:      {scored['counts'].get('low', 0)}",
        "",
        "--- Top Findings ---",
    ]
    for f in scored.get("findings", [])[:15]:
        lines.append(f"  [{f['severity'].upper():8s}] {f['module']:16s} {f['detail'][:60]}")
    lines.append("")
    lines.append("=" * 60)
    text = "\n".join(lines)

    if not out_path:
        safe = "".join(c for c in target if c.isalnum() or c in ".-_") or "report"
        out_path = f"ghosteye_exec_{safe}.txt"

    # try PDF via reportlab
    if out_path.endswith(".pdf"):
        try:
            from reportlab.lib.pagesizes import A4
            from reportlab.pdfgen import canvas as _canvas
            c = _canvas.Canvas(out_path, pagesize=A4)
            c.setFont("Courier", 10)
            y = 780
            for line in lines:
                if y < 40:
                    c.showPage()
                    c.setFont("Courier", 10)
                    y = 780
                c.drawString(40, y, line[:90])
                y -= 14
            c.save()
            return out_path
        except ImportError:
            out_path = out_path.replace(".pdf", ".txt")

    Path(out_path).write_text(text, encoding="utf-8")
    return out_path


# --------------------------------------------------------------------------- #
#  #78  Compliance mapping (NIST CSF / ISO 27001 / OWASP)
# --------------------------------------------------------------------------- #
_COMPLIANCE_MAP = {
    "nist_csf": {
        "ID.AM": ["dns", "whois", "subs", "tech", "nmap", "portscan"],
        "PR.AC": ["headers", "cors", "cookies", "oauthaudit", "jwtaudit",
                   "sessionaudit", "loginsurface", "mfacheck"],
        "PR.DS": ["cert", "tlsgrade", "ciphers", "mixedcontent", "weakdh"],
        "PR.IP": ["securitytxt", "cspgrade", "sri", "clickjack", "permspolicy"],
        "DE.CM": ["waf", "waffp", "ratelimit", "ids"],
        "RS.AN": ["gdpraudit", "trackerinv", "piiscan", "ccpacheck"],
    },
    "iso27001": {
        "A.8 Asset Management": ["dns", "whois", "subs", "tech", "nmap"],
        "A.10 Cryptography": ["cert", "tlsgrade", "ciphers", "chain", "weakdh"],
        "A.13 Communications": ["headers", "cors", "cookies", "httpversions"],
        "A.14 System Security": ["cspgrade", "sri", "clickjack", "methods",
                                  "ratelimit", "smuggle"],
        "A.18 Compliance": ["gdpraudit", "privacypol", "ccpacheck", "consentlog"],
    },
    "owasp_top10": {
        "A01 Broken Access Control": ["cors", "corsadv", "methods", "methodenum",
                                       "idorsurface", "preflightcheck"],
        "A02 Cryptographic Failures": ["cert", "tlsgrade", "ciphers", "weakdh",
                                        "mixedcontent", "starttls"],
        "A03 Injection": ["graphql", "gqlaudit", "smuggle", "hostheader"],
        "A05 Security Misconfiguration": ["headers", "securitytxt", "cspgrade",
                                           "cookies", "clickjack", "permspolicy"],
        "A06 Vulnerable Components": ["tech", "npmscan", "pipscan", "sbomextract",
                                       "depconfuse", "cmsdetect"],
        "A07 Auth Failures": ["oauthaudit", "jwtaudit", "sessionaudit",
                               "loginsurface", "pwresetaudit", "captchacheck"],
        "A09 Logging & Monitoring": ["securitytxt", "ratelimit", "waf"],
        "A10 SSRF": ["metassrf", "hostheader", "cachpoison"],
    },
}


def compliance_check(results, framework: str = "owasp_top10") -> dict:
    """Map scan results to a compliance framework and return coverage."""
    mapping = _COMPLIANCE_MAP.get(framework, {})
    if not mapping:
        return {"error": f"unknown framework: {framework}",
                "available": list(_COMPLIANCE_MAP.keys())}
    ran = {r.module for r in results}
    report = {}
    for control, modules in mapping.items():
        covered = [m for m in modules if m in ran]
        missing = [m for m in modules if m not in ran]
        pct = int(len(covered) / max(len(modules), 1) * 100)
        report[control] = {
            "coverage_pct": pct,
            "covered": covered,
            "missing": missing,
        }
    total_controls = len(mapping)
    fully_covered = sum(1 for v in report.values() if v["coverage_pct"] == 100)
    return {
        "framework": framework,
        "controls": report,
        "total_controls": total_controls,
        "fully_covered": fully_covered,
        "overall_pct": int(fully_covered / max(total_controls, 1) * 100),
    }


# --------------------------------------------------------------------------- #
#  #79  Scan template export / import
# --------------------------------------------------------------------------- #
def export_template(modules, options: dict, name: str = "",
                    out_path: str = "") -> str:
    """Export a scan configuration as a reusable JSON template."""
    template = {
        "name": name or "Ghost Eye Scan Template",
        "version": "1.0",
        "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "selection": {
            "mode": "modules",
            "value": [getattr(m, "id", str(m)) for m in modules],
        },
        "options": options,
    }
    if not out_path:
        safe = name.replace(" ", "_").lower() or "template"
        out_path = f"ghosteye_{safe}.json"
    Path(out_path).write_text(json.dumps(template, indent=2), encoding="utf-8")
    return out_path


def import_template(path: str) -> dict:
    """Load a scan template from JSON and return (selection, options)."""
    text = Path(path).read_text(encoding="utf-8")
    tmpl = json.loads(text)
    return {
        "name": tmpl.get("name", ""),
        "selection": tmpl.get("selection", {"mode": "all"}),
        "options": tmpl.get("options", {}),
    }


# --------------------------------------------------------------------------- #
#  #80  Live alert / webhook on per-finding basis
# --------------------------------------------------------------------------- #
class LiveAlerts:
    """Fire webhook calls when findings match severity thresholds."""

    def __init__(self, webhook_url: str = "", min_severity: str = "high") -> None:
        self.url = webhook_url
        self.min_severity = min_severity.lower()
        self._sev_order = {"critical": 0, "high": 1, "medium": 2,
                           "low": 3, "info": 4}
        self._threshold = self._sev_order.get(self.min_severity, 1)

    def check(self, result, session=None) -> bool:
        """Evaluate a single Result and fire webhook if above threshold."""
        if not self.url:
            return False
        data = result.data if hasattr(result, "data") else {}
        risk = str(data.get("risk", "")).lower()
        sev = self._sev_order.get(risk, 4)
        if sev > self._threshold:
            return False
        payload = {
            "module": getattr(result, "module", ""),
            "target": getattr(result, "target", ""),
            "severity": risk,
            "data": data,
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        }
        try:
            import requests as _req
            s = session or _req.Session()
            s.post(self.url, json=payload, timeout=10)
            return True
        except Exception:
            return False
