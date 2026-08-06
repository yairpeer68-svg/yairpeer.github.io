"""Workflow / architecture features (new features #72-#80)."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import pickle
import threading
import time
from pathlib import Path
from typing import Dict, List, Optional

from .core import Colors, Console, have_binary, REGISTRY

# --------------------------------------------------------------------------- #
#  Passive-only classification (feature 71)
# --------------------------------------------------------------------------- #
# Categories that never actively touch the target — they read third-party data
# sources, archives and DNS. Anything else (Network, Web, Exposure, Auth, TLS
# handshakes, …) sends traffic to the target and is treated as active.
PASSIVE_CATEGORIES = {
    "osint", "threat intel", "passive", "reputation", "geo", "intel",
    "supply chain",
}
# individual ids that are passive even though their category reads as active
_PASSIVE_IDS = {
    "internetdb", "geoip", "proxytype", "torexit", "threatfeed", "reputation",
    "urlscan", "whoispivot", "related", "analytics", "homoglyph", "typosquat",
    "cve", "tlscve",
}
# ids that actively probe even though their category reads as passive
_ACTIVE_IDS: set = set()


def is_passive(module) -> bool:
    """True if a module only reads third-party / archived data and never sends
    traffic to the target itself (feature 71)."""
    mid = getattr(module, "id", "")
    if mid in _ACTIVE_IDS:
        return False
    if mid in _PASSIVE_IDS:
        return True
    return getattr(module, "category", "").strip().lower() in PASSIVE_CATEGORIES


def passive_only(modules):
    """Filter a module list down to the passive subset."""
    return [m for m in modules if is_passive(m)]


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
              "spoofcheck", "dispdetect", "catchalldetect",
              "mxfingerprint", "dkimstrength"],
    "tls": ["cert", "certexpiry", "tlsversions", "ciphers", "chain", "tlsgrade",
            "ocspstaple", "keyaudit", "weakdh", "cipherorder", "hstspreload", "ja4",
            "ctdiff", "ja3", "deprecatedca", "certpin", "mixedcontent",
            "wildcertscope", "tlsresume", "caaaudit", "mtls", "scts", "zerortt"],
    "web": ["headers", "cors", "cookies", "methods", "waf", "cdn", "tech",
            "httpversions", "securitytxt", "cspgrade", "sri", "graphql",
            "rediradv", "corsadv", "cookieaudit", "clickjack", "methodenum",
            "apidisco", "ratelimit", "waffp", "cmsdetect", "wpscan",
            "sourcemap", "adminfinder", "smuggle", "favhash", "metatags",
            "formaction", "exposedfiles",
            "wasmdetect", "swaudit", "pwacheck", "http3check", "permspolicy",
            "referrerpol", "coopcoep", "cachpoison", "hostheader", "httpdesync",
            "mimesniff", "protopollute", "cspbypass", "lfisurface"],
    "cloud": ["cloudprov", "k8s", "docker", "tfstate", "cicd", "buckets",
              "dangling", "serverless", "metadata", "s3enum", "azureblob",
              "gcsbucket", "metassrf", "firebase", "gitrecon", "dnshost", "cdngeo",
              "cfdnmisconfig", "azureadtenant", "gcpenum", "tfcloud",
              "vaultdetect", "consuldetect", "etcddetect"],
    "exposure": ["vcs", "backups", "buckets", "dirlisting", "admin", "dashboards",
                 "exposeddb", "rdpvnc", "snmp", "exposedfiles", "adminfinder",
                 "dockerapi", "k8sadv", "ldap", "smb", "ftpanon",
                 "jssecrets", "sigscan", "iamexpose"],
    "osint": ["emails", "emailauth", "username", "dorks", "github",
              "whoispivot", "analytics", "related", "breachcheck", "social",
              "waybackadv", "pastebin", "gdork", "techstack", "threatfeed",
              "threatagg", "jsdeps",
              "jobstech", "feedfind", "sitemapintel", "robotsdiff", "orgprofile",
              "commitauthors", "whoistimeline", "favsimilar",
              # feature batch B — new passive-OSINT sources
              "subs", "pdnsotx", "pdnsht", "pdnsanubis", "pdnstm", "rapiddns",
              "riddler", "waybackcdx", "commoncrawl", "urlscanio", "bucketscan",
              "faviconhash", "socialrecon", "ghleak", "hibpbreach", "sectxt",
              "robotsmap", "certpivot", "emailpattern",
              # free/keyless multi-source breadth (many sources per data type)
              "certspotter", "bufferover", "hackertarget", "subdomaincenter",
              "otxrep", "hudsonrock", "grepapp", "searchcode", "threatfox",
              "urlhaus", "spamhausdbl", "psbdmp", "keybase", "certdetails",
              "sitedossier", "favicmmh3", "anubisjldc", "phishstats", "waybackparams", "wikidata", "commoncrawlmine", "waybacksecrets", "phoneharvest", "extdomains", "otxpulse", "merklemap", "uriblock", "npmsearch", "dockerhub", "cratesio", "rubygems", "packagist", "nuget", "artifacthub", "gitlabsearch", "hackernews", "reddit", "gdelt", "stackexchange", "secedgar", "wikipedia", "codeberg", "pdnsmnemonic", "swheritage", "columbus", "crtsh", "bitbucket", "sourcegraph", "dohcloudflare", "dohgoogle", "openphish", "bgpviewsearch", "ripedb", "gleif", "githuborg", "leakix", "sucuri", "xposeddomain", "crtshorg", "tranco", "otxmalware", "otxurls", "digitalside", "dnsseccaa", "dnslytics", "lookalike", "spfdmarc", "certissuers", "mxintel", "nsintel", "txtsaas", "spfvendors", "wildcarddns", "htdns", "dkimscan", "soaintel", "danetlsa", "pagelinks", "phisharmy", "httpsrr", "srvscan", "domainptr", "mozillaobs", "stevenblack", "certemails"],
    "passive": ["internetdb", "geoip", "proxytype", "torexit", "threatfeed",
                "reputation", "urlscan", "breachcheck", "waybackadv", "pastebin"],
    "perimeter": ["dns", "subs", "nmap", "headers", "cert", "tlsgrade", "waf",
                  "cdn", "origin", "originhunt", "exposeddb", "dashboards", "vcs",
                  "subtakeover", "dnssecchain", "fwinfer", "svcver", "sshaudit"],
    "dns": ["dns", "whois", "subs", "dnssecchain", "dnswildcard", "domainage",
            "subtakeover", "dnsprop", "emailauth", "dnsrebind", "nsdelegation",
            "domexpiry", "glue", "typosquat", "dmarcalign", "homoglyph",
            "nsecwalk", "nsmxtakeover"],
    "network": ["nmap", "tcptrace", "fwinfer", "v4v6parity", "bgphijack",
                "svcver", "sshaudit", "dohdot", "mqtt", "ntp", "grpc",
                "dockerapi", "k8sadv", "ldap", "smb", "ftpanon",
                "quicdetect", "wgdetect", "meshdetect", "ipv6only", "rebindguard",
                "osfp", "ipmi"],
    "ai": ["deepseek", "aiapi", "dsapi", "aikeyleak", "aidash", "vectordb",
            "aiapp", "modelserve", "aiorch", "jupyter", "hfrecon", "promptleak",
            "promptinject"],
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
    "exploit": ["tech", "cve", "exploitdb"],
    "mobile": ["mobileapp", "jsendpoints", "exposedfiles"],
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
    "critical": 10.0, "high": 5.0, "medium": 2.0, "low": 0.5,
    "info": 0.0,
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
#  v3.8  Risk intelligence — composite, context-aware prioritisation
# --------------------------------------------------------------------------- #
import re as _re

_RISK_CVE = _re.compile(r"CVE-\d{4}-\d{4,7}", _re.I)
# CVSS-like base weight per severity bucket
_RISK_BASE = {"critical": 9.0, "high": 7.0, "medium": 4.5, "low": 1.5, "info": 0.5}
# words that imply the finding is actually reachable / unauthenticated
_EXPOSURE_WORDS = ("open", "public", "no auth", "unauth", "exposed", "anonymous",
                   "world-readable", "reflects origin", "without credentials",
                   "no password", "default cred", "listing", "disclosed")


def capture_surface(results, target: str = "", max_shots: int = 10,
                    timeout: int = 15, parallel: int = 2):
    """Screenshot the target and every discovered subdomain, returning a list of
    'screenshot' Result objects that merge straight into the intelligence
    gallery — a full visual sweep of the attack surface. Uses whatever headless
    Chromium backend is available (Playwright, or the Termux `chromium` CLI)."""
    from .core import Result
    from .inventory import build_inventory
    from .modules.screenshot import capture_many

    inv = build_inventory(results, target)
    tgt = (target or inv.get("target", "")).lower().rstrip(".")
    suffix = "." + tgt if tgt else ""
    hosts = [h for h in inv["hosts"] if tgt and (h == tgt or h.endswith(suffix))]
    if tgt and tgt not in hosts:
        hosts.insert(0, tgt)
    hosts = hosts[:max(1, max_shots)]
    if not hosts:
        return []

    def sweep(scheme):
        by_url = {f"{scheme}://{h}": h for h in hosts_todo}
        res = capture_many(list(by_url), timeout=timeout)
        return {by_url[u]: r for u, r in res.items()}

    hosts_todo = hosts
    got = sweep("https")                     # one browser for all HTTPS
    hosts_todo = [h for h in hosts
                  if not got.get(h, {}).get("screenshot", "").startswith("data:")]
    if hosts_todo:                           # retry the failures over plain HTTP
        for host, res in sweep("http").items():
            if res.get("screenshot", "").startswith("data:"):
                got[host] = res

    shots = []
    for host in hosts:
        res = got.get(host, {})
        if res.get("screenshot", "").startswith("data:"):
            shots.append(Result(
                "Website screenshot (visual recon)", host, "ok",
                {"final_url": res.get("final_url"),
                 "title": res.get("title"),
                 "backend": res.get("backend"),
                 "screenshot": res["screenshot"]}))
    return shots


def intelligence_report(results, target: str = "",
                        exploit: Optional[dict] = None) -> dict:
    """The unified ASM-style intelligence picture: correlated assets, classified
    technologies, cloud footprint, email posture, certificates, leak indicators,
    an organization profile and the attack-surface graph — assembled from the
    output of every module that ran."""
    from .intelligence import (analyze, asset_sensitivity, attack_paths,
                               build_graph, build_timeline, correlate,
                               enrich_tech_cve, entity_correlation,
                               knowledge_graph, management_translation,
                               organization_profile, remediation, risk_heatmap,
                               supply_chain)
    from .reporting_ext import score_findings
    intel = correlate(results, target)
    profile = organization_profile(intel, results)
    kg = knowledge_graph(results, intel["target"], intel)
    # enrich the graph before scoring so new nodes/edges feed the heat-map:
    #  - tech->CVE correlation (feature 19)
    #  - external supply-chain dependencies (feature 24)
    enrich_tech_cve(kg, results)
    supply = supply_chain(kg, results, intel["target"])
    # source-corroboration confidence per entity (advanced OSINT trust scoring)
    from .intelligence import annotate_confidence
    annotate_confidence(kg)
    # per-entity risk heat-map (feature 17) — writes risk/band into node attrs
    heat = risk_heatmap(kg)
    # scored attack paths from exposure/leak/CVE to the target (feature 18)
    apaths = attack_paths(kg)
    corr = entity_correlation(kg)
    # advisory layer: classify host sensitivity (70), map fixes (69) and a
    # plain-language management brief (72)
    sensitivity = asset_sensitivity(kg)
    scored = score_findings(results)
    remedy = remediation({}, scored.get("findings", []))
    from .intelligence import identity_graph, source_matrix
    identity = identity_graph(results, intel["target"])
    srcmatrix = source_matrix(results, intel["target"])
    tline = build_timeline(results, intel["target"])
    a = attack_score(results)
    out = {
        "target": intel["target"],
        "grade": a["grade"],
        "risk_level": a["risk_level"],
        "score": a["normalized"],
        "counts": intel["counts"],
        "intelligence": intel,
        "organization": profile,
        "graph": build_graph(intel),
        "knowledge_graph": kg,
        "correlation": corr,
        "risk_heatmap": heat,
        "attack_paths": apaths,
        "supply_chain": supply,
        "asset_sensitivity": sensitivity,
        "remediation": remedy,
        "identity_graph": identity,
        "source_matrix": srcmatrix,
        "timeline": tline,
    }
    # the rule-based AI analyst reasons over the fully assembled picture
    out["analysis"] = analyze(out)
    out["management_brief"] = management_translation(out)
    if exploit is not None:
        out["exploitable_cves"] = exploit.get("exploitable", [])
    return out


def osint_deepdive(seed: str, cfg=None, depth: int = 1,
                   max_per_hop: int = 12) -> dict:
    """Advanced OSINT: an automated multi-hop investigation from a single seed.
    Runs OSINT sources, pivots onto every entity they reveal (related domains,
    e-mails, IPs) up to ``depth``, and merges everything into one Knowledge
    Graph with provenance. Reconnaissance/detection only."""
    from .intelligence import deep_dive
    return deep_dive(seed, cfg=cfg, depth=max(0, min(3, int(depth))),
                     max_per_hop=max_per_hop)


def platform_report(results, target: str = "",
                    exploit: Optional[dict] = None) -> dict:
    """Personal Cyber Intelligence Platform view — the full intelligence
    picture plus the typed Knowledge Graph, smart entity correlation, the
    Intelligence Timeline and the rule-based AI-analyst write-up. Alias of the
    enriched intelligence_report(); kept as a named entry point for the CLI/API
    and reports."""
    return intelligence_report(results, target, exploit=exploit)


def trend(store, target: str) -> dict:
    """Build a security trend for a target from the SQLite scan history: per
    saved scan the re-scored risk + finding counts, the modules that appeared
    or disappeared since the previous scan, and the overall direction. Turns the
    tool from a point-in-time scanner into a change tracker."""
    from .core import Result
    from .reporting_ext import score_findings

    scans = store.scans_for(target)
    timeline = []
    prev_mods = None
    prev_score = None
    for s in scans:
        results = [Result(x.get("module", ""), x.get("target", target),
                          x.get("status", "ok"), x.get("data", {}) or {})
                   for x in s["results"]]
        scored = score_findings(results)
        mods = {x.get("module", "") for x in s["results"]}
        entry = {
            "scan_id": s["id"], "ts": s["ts"],
            "risk_level": scored["risk_level"],
            "risk_score": scored["risk_score"],
            "counts": scored["counts"],
            "modules": len(mods),
        }
        if prev_mods is not None:
            entry["new_modules"] = sorted(mods - prev_mods)
            entry["gone_modules"] = sorted(prev_mods - mods)
            entry["score_delta"] = scored["risk_score"] - prev_score
        timeline.append(entry)
        prev_mods, prev_score = mods, scored["risk_score"]

    direction = "n/a"
    if len(timeline) >= 2:
        delta = timeline[-1]["risk_score"] - timeline[0]["risk_score"]
        direction = ("worsening" if delta > 0 else
                     "improving" if delta < 0 else "stable")
    return {
        "target": target,
        "scans": len(timeline),
        "direction": direction,
        "first_ts": timeline[0]["ts"] if timeline else None,
        "last_ts": timeline[-1]["ts"] if timeline else None,
        "timeline": timeline,
        "note": "risk re-scored from stored findings; 'direction' compares the "
                "first and latest scan. Save scans with --save-db to build history.",
    }


def intelligence_trend(store, target: str) -> dict:
    """Intelligence trend — how the *attack surface itself* evolves across the
    saved scan history, not just the risk score. For every stored scan it
    re-correlates the intelligence picture (assets, subdomains, IPs, tech, cloud,
    leaks) and diffs the knowledge-graph entities against the previous scan, so
    you can see exactly which subdomains/IPs/technologies appeared or disappeared
    over time. Rule-based, offline. Pairs the Timeline + Knowledge Graph with
    history to turn Ghost Eye into a change-tracking intelligence platform."""
    from .core import Result
    from .intelligence import correlate, knowledge_graph
    from .reporting_ext import score_findings

    scans = store.scans_for(target)
    series = []
    prev_ent = None
    for s in scans:
        results = [Result(x.get("module", ""), x.get("target", target),
                          x.get("status", "ok"), x.get("data", {}) or {})
                   for x in s["results"]]
        intel = correlate(results, target)
        kg = knowledge_graph(results, target, intel)
        scored = score_findings(results)
        c = intel["counts"]
        # notable entities we track over time (the surface the analyst watches)
        ent = {f'{e["kind"]}:{e["label"]}' for e in kg["entities"]
               if e["kind"] in ("subdomain", "ip", "tech", "cloud", "cve",
                                "leak", "email", "org")}
        entry = {
            "scan_id": s["id"], "ts": s["ts"],
            "risk_level": scored["risk_level"],
            "risk_score": scored["risk_score"],
            "assets": c["assets"], "subdomains": c["subdomains"],
            "ips": c["ips"], "technologies": c["technologies"],
            "emails": c["emails"], "leaks": c["leak_indicators"],
            "cloud": len([x for x in intel["cloud"] if "unknown" not in x.lower()]),
            "entities": kg["counts"]["entities"],
        }
        if prev_ent is not None:
            added = sorted(ent - prev_ent)
            gone = sorted(prev_ent - ent)
            entry["new_entities"] = [e.split(":", 1)[1] for e in added][:20]
            entry["gone_entities"] = [e.split(":", 1)[1] for e in gone][:20]
            entry["new_count"] = len(added)
            entry["gone_count"] = len(gone)
        prev_ent = ent
        series.append(entry)

    direction = "n/a"
    deltas = {}
    if len(series) >= 2:
        f, l = series[0], series[-1]
        deltas = {k: l[k] - f[k] for k in ("risk_score", "assets", "subdomains",
                                           "ips", "technologies", "entities",
                                           "leaks")}
        rd = deltas["risk_score"]
        direction = ("worsening" if rd > 0 else
                     "improving" if rd < 0 else "stable")
    return {
        "target": target,
        "scans": len(series),
        "direction": direction,
        "first_ts": series[0]["ts"] if series else None,
        "last_ts": series[-1]["ts"] if series else None,
        "deltas": deltas,
        "series": series,
        "note": "attack surface re-correlated per saved scan; new/gone entities "
                "diff the knowledge graph vs the previous scan. Save scans "
                "(dashboard auto-saves; CLI --save-db) to build history.",
    }


def portfolio(store, max_targets: int = 60) -> dict:
    """A portfolio / multi-target ASM overview: for every distinct target in the
    saved-scan history, the latest scan's re-correlated asset counts and risk —
    one board for all monitored assets. Rule-based, offline."""
    from .core import Result
    from .intelligence import correlate
    from .reporting_ext import score_findings

    rows = store.recent_scans(500)          # newest first
    latest = {}
    for s in rows:                          # keep the newest scan per target
        t = (s.get("target") or "").strip()
        if t and t not in latest:
            latest[t] = s
    items = []
    for t, s in list(latest.items())[:max_targets]:
        try:
            full = store.load_scan(s["id"]) or {}
            results = [Result(x.get("module", ""), x.get("target", t),
                              x.get("status", "ok"), x.get("data", {}) or {})
                       for x in full.get("results", [])]
            intel = correlate(results, t)
            scored = score_findings(results)
            c = intel["counts"]
            items.append({
                "target": t, "scan_id": s["id"], "ts": s.get("ts"),
                "risk_level": scored["risk_level"], "risk_score": scored["risk_score"],
                "assets": c["assets"], "subdomains": c["subdomains"],
                "ips": c["ips"], "emails": c["emails"],
                "technologies": c["technologies"], "leaks": c["leak_indicators"],
            })
        except Exception:  # noqa: BLE001 - a broken row must not sink the board
            continue
    order = {"CRITICAL": 0, "HIGH": 1, "MEDIUM": 2, "LOW": 3, "INFO": 4, "": 5}
    items.sort(key=lambda x: (order.get(x["risk_level"], 5), -x["assets"]))
    totals = {
        "targets": len(items),
        "assets": sum(i["assets"] for i in items),
        "subdomains": sum(i["subdomains"] for i in items),
        "leaks": sum(i["leaks"] for i in items),
        "at_risk": sum(1 for i in items if i["risk_level"] in ("CRITICAL", "HIGH")),
    }
    return {"targets": items, "totals": totals,
            "note": "latest saved scan per target; dashboard scans auto-save, so "
                    "the board fills as you investigate more targets."}


def module_report() -> dict:
    """A quality / capability report for every registered module: category,
    target kind, declared dependencies (needs), whether it is documented, and
    whether it is covered by the all-module smoke test (every registered module
    is). Turns the plugin registry into inspectable metadata."""
    import sys
    from collections import Counter

    from .core import REGISTRY
    mods = []
    for mid, m in sorted(REGISTRY.items()):
        cls = type(m)
        file_doc = getattr(sys.modules.get(cls.__module__, None), "__doc__", "")
        documented = bool((file_doc or "").strip())
        needs = list(getattr(m, "needs", []) or [])
        mods.append({
            "id": mid,
            "name": getattr(m, "name", mid),
            "category": getattr(m, "category", "Misc"),
            "target_kind": getattr(m, "target_kind", "host"),
            "needs": needs,
            "documented": documented,
            "smoke_covered": True,      # every REGISTRY entry is smoke-tested
        })
    cats = Counter(x["category"] for x in mods)
    documented = sum(1 for x in mods if x["documented"])
    with_needs = sum(1 for x in mods if x["needs"])
    return {
        "total": len(mods),
        "categories": len(cats),
        "by_category": dict(sorted(cats.items(), key=lambda kv: -kv[1])),
        "documented": documented,
        "documented_pct": round(100 * documented / max(1, len(mods)), 1),
        "declare_dependencies": with_needs,
        "smoke_covered": len(mods),
        "modules": mods,
    }


def risk_intelligence(results, exploit: Optional[dict] = None) -> dict:
    """Prioritise findings with a composite score, not just a severity label:

        risk = base(severity) × exposure × exploit_availability × confidence

    `exploit` is an optional exploit_intel() report; findings whose CVE has a
    public exploit are amplified. Returns findings ranked most-actionable first.
    """
    from .reporting_ext import score_findings
    scored = score_findings(results)
    exploitable = {c.upper() for c in (exploit or {}).get("exploitable", [])}

    ranked = []
    for f in scored.get("findings", []):
        sev = f.get("severity", "low")
        base = _RISK_BASE.get(sev, 1.0)
        blob = f"{f.get('field','')} {f.get('detail','')}".lower()
        exposure = 1.4 if any(w in blob for w in _EXPOSURE_WORDS) else 1.0
        cves = [c.upper() for c in _RISK_CVE.findall(blob)]
        has_exploit = any(c in exploitable for c in cves)
        exploit_mult = 1.6 if has_exploit else 1.0
        # detection is pattern-based; treat as high-but-not-certain confidence
        confidence = 0.9
        composite = round(base * exposure * exploit_mult * confidence, 2)
        ranked.append({
            "module": f.get("module"),
            "field": f.get("field"),
            "detail": str(f.get("detail", ""))[:160],
            "severity": sev,
            "exposure_boost": exposure > 1.0,
            "public_exploit": has_exploit,
            "cves": cves or None,
            "risk_score": composite,
        })
    ranked.sort(key=lambda r: r["risk_score"], reverse=True)
    top = ranked[0]["risk_score"] if ranked else 0.0
    # normalise the overall to 0-100 against the worst possible single finding
    worst_possible = _RISK_BASE["critical"] * 1.4 * 1.6 * 0.9
    overall = min(100, int(round(top / worst_possible * 100))) if ranked else 0
    return {
        "overall_risk": overall,
        "top_risk_score": top,
        "formula": "base(severity) × exposure × exploit_availability × confidence",
        "prioritised": ranked[:30],
        "count": len(ranked),
        "note": "context-aware ranking: an exposed, publicly-exploitable finding "
                "outranks a higher-severity but unreachable one",
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
    "pci_dss": {
        "Req 1 Network Security": ["nmap", "portscan", "waf", "origin", "cdn"],
        "Req 2 Secure Config": ["headers", "cspgrade", "dirlisting", "backups",
                                 "sigscan", "phpinfo"],
        "Req 3 Protect Stored Data": ["s3enum", "buckets", "bucketscan",
                                       "tfstate", "iamexpose"],
        "Req 4 Encrypt Transmission": ["cert", "tlsgrade", "ciphers", "weakdh",
                                        "starttls", "mixedcontent"],
        "Req 6 Secure Systems": ["tech", "cve", "wpscan", "cmsdetect",
                                  "depconfuse", "sigscan"],
        "Req 8 Access Control": ["loginsurface", "jwtaudit", "admin",
                                  "adminfinder", "defaultcreds"],
        "Req 10 Monitor Access": ["securitytxt", "ratelimit", "waf"],
    },
    "soc2": {
        "CC6.1 Logical Access": ["loginsurface", "jwtaudit", "admin",
                                  "adminfinder", "cors", "iamexpose"],
        "CC6.6 Boundary Protection": ["nmap", "portscan", "waf", "origin",
                                       "smuggle"],
        "CC6.7 Data in Transit": ["cert", "tlsgrade", "ciphers", "weakdh",
                                   "mixedcontent"],
        "CC7.1 Vuln Detection": ["tech", "cve", "wpscan", "sigscan",
                                  "jssecrets", "depconfuse"],
        "CC7.2 Anomaly Monitoring": ["securitytxt", "ratelimit", "waf"],
        "C1.1 Confidentiality": ["s3enum", "buckets", "bucketscan", "backups",
                                  "iamexpose", "jssecrets"],
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


# --------------------------------------------------------------------------- #
#  v3.8  Notifications — Slack / Discord / Telegram / generic webhook
# --------------------------------------------------------------------------- #
_SEV_RANK = {"critical": 0, "high": 1, "medium": 2, "low": 3, "info": 4}


def _summary_text(results, target: str, exploit: Optional[dict] = None) -> str:
    """A compact, human-readable scan summary used by notifications."""
    from .reporting_ext import score_findings
    scored = score_findings(results)
    a = attack_score(results)
    c = scored.get("counts", {})
    lines = [
        f"👁 Ghost Eye — {target or 'scan'}",
        f"Grade {a['grade']} ({a['normalized']}/100) · risk {a['risk_level']}",
        f"critical {c.get('critical',0)} · high {c.get('high',0)} · "
        f"medium {c.get('medium',0)} · low {c.get('low',0)} "
        f"({len(results)} modules)",
    ]
    if exploit and exploit.get("exploitable"):
        lines.append(f"⚠ public exploits for: {', '.join(exploit['exploitable'][:8])}")
    top = scored.get("findings", [])[:5]
    if top:
        lines.append("top:")
        for f in top:
            lines.append(f"  [{f['severity']}] {f['module']}: {f['detail'][:70]}")
    return "\n".join(lines)


def _detect_service(url: str) -> str:
    if "hooks.slack.com" in url:
        return "slack"
    if "discord" in url:
        return "discord"
    if "api.telegram.org" in url:
        return "telegram"
    return "webhook"


def _send_webhook(url: str, text: str, service: str = "auto",
                  session=None, extra: Optional[dict] = None) -> bool:
    """Post `text` to Slack / Discord / Telegram / a generic webhook. Shared
    transport for scan-summary and surface-change alerts."""
    if not url:
        return False
    svc = _detect_service(url) if service == "auto" else service
    try:
        import requests as _req
        s = session or _req.Session()
        if svc == "slack":
            r = s.post(url, json={"text": text}, timeout=15)
        elif svc == "discord":
            r = s.post(url, json={"content": text[:1900]}, timeout=15)
        elif svc == "telegram":
            # chat_id is expected in the URL query; we add the message body
            r = s.post(url, data={"text": text, "parse_mode": "HTML"}, timeout=15)
        else:  # generic webhook — send both text and a structured payload
            r = s.post(url, json={"text": text, **(extra or {})}, timeout=15)
        return getattr(r, "status_code", 500) < 300
    except Exception as exc:  # noqa: BLE001
        from .core import record_error
        record_error(f"notify {svc}", "", exc)
        return False


def notify(results, target: str = "", url: str = "", service: str = "auto",
           exploit: Optional[dict] = None, session=None) -> bool:
    """Push a scan summary to Slack / Discord / Telegram / a generic webhook.

    `service` auto-detects from the URL host; override with slack/discord/
    telegram/webhook. Telegram URLs must already carry ?chat_id=…"""
    if not url:
        return False
    text = _summary_text(results, target, exploit)
    return _send_webhook(url, text, service, session,
                         extra={"target": target,
                                "grade": attack_score(results)})


def _entity_sets(results, target: str) -> dict:
    """Map the notable knowledge-graph entities of a scan by kind, plus open
    services — the surface an active monitor watches for growth."""
    from .intelligence import correlate, knowledge_graph
    from .inventory import build_inventory
    intel = correlate(results, target)
    kg = knowledge_graph(results, target, intel)
    by_kind: dict = {}
    for e in kg["entities"]:
        by_kind.setdefault(e["kind"], set()).add(e["label"])
    by_kind["service"] = set(build_inventory(results, target).get("services", []))
    return by_kind


def surface_diff(prev_results, curr_results, target: str = "") -> dict:
    """What NEW exposure appeared on the attack surface between two scans:
    new subdomains, IPs, technologies, CVEs, leaks, emails and open services.
    Growth only — the signal an active monitor should alert on. Rule-based."""
    prev = _entity_sets(prev_results, target) if prev_results else {}
    curr = _entity_sets(curr_results, target)

    def added(kind):
        return sorted(curr.get(kind, set()) - prev.get(kind, set()))

    diff = {
        "target": target,
        "new_subdomains": added("subdomain"),
        "new_ips": added("ip"),
        "new_technologies": added("tech"),
        "new_cves": added("cve"),
        "new_leaks": added("leak"),
        "new_emails": added("email"),
        "new_services": added("service"),
        "new_cloud": added("cloud"),
    }
    diff["total_new"] = sum(len(v) for k, v in diff.items()
                            if k.startswith("new_"))
    diff["changed"] = diff["total_new"] > 0
    diff["first_scan"] = not prev_results
    return diff


def _change_text(diff: dict) -> str:
    """Human-readable surface-change alert."""
    tgt = diff.get("target") or "target"
    lines = [f"🚨 Ghost Eye — attack surface changed on {tgt}",
             f"{diff['total_new']} new item(s) since the last scan:"]
    labels = [
        ("new_subdomains", "subdomains"), ("new_ips", "IPs"),
        ("new_services", "open services"), ("new_cves", "CVEs"),
        ("new_technologies", "technologies"), ("new_leaks", "leak indicators"),
        ("new_cloud", "cloud"), ("new_emails", "emails"),
    ]
    for key, label in labels:
        vals = diff.get(key) or []
        if vals:
            lines.append(f"  + {label}: {', '.join(str(v) for v in vals[:12])}"
                         + (f" (+{len(vals) - 12} more)" if len(vals) > 12 else ""))
    return "\n".join(lines)


def notify_change(diff: dict, url: str = "", service: str = "auto",
                  session=None) -> bool:
    """Send a surface-change alert (only when something new appeared)."""
    if not url or not diff.get("changed"):
        return False
    return _send_webhook(url, _change_text(diff), service, session,
                         extra={"target": diff.get("target"),
                                "change": diff})


# --------------------------------------------------------------------------- #
#  v3.8  CI/CD security gate — exit non-zero when findings breach a threshold
# --------------------------------------------------------------------------- #
def ci_gate(results, fail_on: str = "high") -> dict:
    """Decide a CI pass/fail. Fails if any finding is at or above `fail_on`."""
    from .reporting_ext import score_findings
    scored = score_findings(results)
    counts = scored.get("counts", {})
    threshold = _SEV_RANK.get(fail_on.lower(), 1)
    breaching = {sev: n for sev, n in counts.items()
                 if n and _SEV_RANK.get(sev, 9) <= threshold}
    offending = sum(breaching.values())
    passed = offending == 0
    return {
        "passed": passed,
        "exit_code": 0 if passed else 1,
        "fail_on": fail_on.lower(),
        "breaching_counts": breaching,
        "offending_total": offending,
        "grade": attack_score(results)["grade"],
        "message": ("PASS — no findings at or above %s" % fail_on if passed
                    else "FAIL — %d finding(s) at or above %s" % (offending, fail_on)),
    }


# --------------------------------------------------------------------------- #
#  v3.8  Exploit / zero-day intelligence over a whole scan
# --------------------------------------------------------------------------- #
def exploit_intel(results, session=None, timeout: int = 20,
                  max_cves: int = 20) -> dict:
    """Correlate every CVE found in a scan against the public exploit databases
    (Exploit-DB, Metasploit/Rapid7, NVD, GitHub advisories, PacketStorm, CIRCL,
    with OpenCVE/MITRE link-outs) and report which findings already have a
    public exploit / PoC — i.e. what an attacker can weaponise today.

    Detection/correlation only; nothing is ever exploited."""
    from .modules.exploit_intel import check_cve, extract_cves
    cves = extract_cves(results)[:max_cves]
    if session is None:
        try:
            import requests
            session = requests.Session()
            session.headers.update({"User-Agent": "GhostEye-ExploitIntel"})
        except Exception:  # noqa: BLE001
            return {"error": "requests not available", "cves_found": cves}

    findings = []
    for i, cve in enumerate(cves):
        findings.append(check_cve(cve, session, timeout,
                                  throttle=0.4 if i else 0.0))
    exploitable = [f for f in findings if f["exploit_available"]]
    weaponised = [f for f in findings if f["weaponised"]]
    kev = [f for f in findings if f.get("known_exploited")]
    # rank: actively-exploited (KEV) first, then public exploit, then EPSS, CVSS
    findings.sort(key=lambda f: (f.get("known_exploited", False),
                                 f["exploit_available"], f["weaponised"],
                                 f.get("epss") or 0, f["cvss"] or 0), reverse=True)
    return {
        "cves_found": len(cves),
        "exploitable_count": len(exploitable),
        "weaponised_count": len(weaponised),
        "kev_count": len(kev),
        "kev": [f["cve"] for f in kev],
        "exploitable": [f["cve"] for f in exploitable],
        "verdict": ("ACTIVELY EXPLOITED CVEs PRESENT (CISA KEV)" if kev
                    else "PUBLIC EXPLOITS AVAILABLE" if exploitable
                    else "no public exploits found for detected CVEs"),
        "findings": findings,
        "note": "sourced from Exploit-DB, Metasploit, NVD, GitHub, PacketStorm, "
                "CIRCL, plus CISA KEV (actively exploited) and FIRST.org EPSS "
                "(exploitation probability). 'weaponised' = a Metasploit module "
                "or Exploit-DB PoC exists. Verify the exact version before acting.",
    }
