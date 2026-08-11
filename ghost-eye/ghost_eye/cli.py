"""
Ghost Eye command-line interface.

Two ways to drive it:

  Interactive   :  ghost_eye.py                  (menu built from the registry)
  Non-interactive (#77):
      ghost_eye.py -t example.com -m headers,cert,subs
      ghost_eye.py -t example.com --category Web
      ghost_eye.py -t example.com --all --output report.html
      ghost_eye.py --list
      ghost_eye.py --config-init

Covers feature requests #75 (scheduling note), #77 (argparse),
#78 (threads + progress), #79 (proxy/Tor + UA rotation), #80 (config/logging).
"""

from __future__ import annotations

import argparse
import random
import sys
import time
from typing import List, Optional

from .config import Config, MODULE_KEYS
from .core import (Colors, Console, Context, Module, REGISTRY, Result,
                   build_session, get_module, modules_by_category,
                   errorlog_path, setup_logging)
from . import engine
from . import reporting
from . import reporting_ext
from . import workflow
from .workflow import t

BANNER = r"""
 ('-. .-.               .-')    .-') _            ('-.                 ('-.
( OO )  /     Ghost    ( OO ). (  OO) )         _(  OO)      Eye     _(  OO)
,--. ,--. .-'),-----. (_)---\_)/     '._       (,------. ,--.   ,--.(,------.
|  | |  |( OO'  .-.  '/    _ | |'--...__)       |  .---'  \  `.'  /  |  .---'
|   .|  |/   |  | |  |\  :` `. '--.  .--'       |  |    .-')     /)  |  |
|       |\_) |  |\|  | '..`''.)   |  |         (|  '--.(OO  \   /.  (|  '--.
|  .-.  |  \ |  | |  |.-._)   \   |  |          |  .--' |   /  /     |  .--'
|  | |  |   `'  '-'  '\       /   |  |          |  `---.`-./  /      |  `---.
`--' `--'     `-----'  `-----'    `--' v3       `------'  `--'       `------'
"""

SUBTITLE = (
    "        Ghost Eye v3 - Information Gathering Toolkit\n"
    "        Modular rewrite - recon / OSINT / exposure detection\n"
    "        For AUTHORISED security testing only\n"
)

# User-Agent pool for rotation (#79)
_UA_POOL = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (X11; Linux x86_64; rv:125.0) Gecko/20100101 Firefox/125.0",
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1",
]


def print_banner() -> None:
    print(f"{Colors.BLUE}{BANNER}{Colors.RESET}")
    print(f"{Colors.CYAN}{SUBTITLE}{Colors.RESET}")


# --------------------------------------------------------------------------- #
#  Context / session construction
# --------------------------------------------------------------------------- #
def make_context(cfg: Config, args) -> Context:
    proxy = args.proxy or cfg.get("proxy") or None
    if args.tor:
        proxy = "socks5h://127.0.0.1:9050"
    ua = (None if args.rotate_ua else
          (args.user_agent or cfg.get("user_agent") or None))
    if args.rotate_ua:
        ua = random.choice(_UA_POOL)
    verify = not args.insecure and cfg.get_bool("verify_tls", True)
    timeout = args.timeout or cfg.get_int("timeout", 15)
    threads = args.threads or cfg.get_int("threads", 10)
    session = build_session(user_agent=ua, proxy=proxy,
                            verify_tls=verify, timeout=timeout)
    recorder = None
    if getattr(args, "opsec", False) or getattr(args, "opsec_strict", False):
        from . import opsec
        recorder = opsec.LeakRecorder(target=args.target or "",
                                      strict=getattr(args, "opsec_strict", False))
    wrap_kw = {
        "rate": getattr(args, "rate", 0) or 0,
        "cache_dir": ".ghosteye_cache" if getattr(args, "cache", False) else None,
        "cache_ttl": getattr(args, "cache_ttl", 300),
        "rate_per_host": getattr(args, "rate_per_host", 0) or 0,
        "recorder": recorder,
    }
    session = workflow.wrap_session(session, **wrap_kw)
    ctx = Context(config=cfg, session=session, threads=threads,
                  timeout=timeout, verbose=args.verbose)
    # remembered so a rotated session can be re-wrapped identically
    ctx.session_wrap = wrap_kw            # type: ignore[attr-defined]
    ctx.opsec = recorder                  # type: ignore[attr-defined]
    return ctx


def _maybe_rotate(ctx: Context, args) -> None:
    """If UA rotation is on, give each module a fresh session/UA."""
    if not args.rotate_ua:
        return
    proxy = ctx.session.proxies.get("https") if ctx.session else None
    session = build_session(user_agent=random.choice(_UA_POOL),
                            proxy=proxy, verify_tls=ctx.session.verify,
                            timeout=ctx.timeout)
    # re-apply throttling/caching: a plain build_session() drops the wrapper,
    # so --rotate-ua used to silently switch off --rate and --cache
    ctx.session = workflow.wrap_session(
        session, **getattr(ctx, "session_wrap", {}) or {})


# --------------------------------------------------------------------------- #
#  Running modules
# --------------------------------------------------------------------------- #
def run_modules(mods: List[Module], target: str, ctx: Context,
                args) -> List[Result]:
    results: List[Result] = []
    store = reporting.Store(args.db) if args.save_db else None
    total = len(mods)
    rate = (engine.AdaptiveRateLimiter()
            if getattr(args, "adaptive_rate", False) else None)
    for idx, mod in enumerate(mods, 1):
        _maybe_rotate(ctx, args)
        Console.rule(f"[{idx}/{total}] {mod.name}  ({mod.category})")
        if mod.needs:
            Console.kv("requires", ", ".join(mod.needs))
        t0 = time.time()
        if rate:
            rate.wait()
        try:
            res = engine.execute_module(mod, target, ctx)
        except KeyboardInterrupt:
            Console.warn("interrupted by user")
            break
        if rate:
            rate.observe(res)
        res.render()
        Console.kv("took", f"{time.time() - t0:.1f}s")

        # scan-diff against the previous run (#72)
        if store:
            history = store.last_two(mod.name, target)
            store.save(res)
            if args.diff and history:
                d = reporting.diff_results(history[0]["data"], res.data)
                if any(d.values()):
                    Console.warn("changes since last run:")
                    for kind, items in d.items():
                        if items:
                            Console.kv(kind, items)
        results.append(res)

    if store:
        store.close()
    return results


_EXT_FORMATS = {"md", "markdown", "sarif", "prom", "prometheus", "dashboard",
                "dash", "exec", "execreport", "executive", "intel", "intelligence",
                "graphml", "gexf", "osint", "dossier"}


def handle_reports(results: List[Result], target: str, args) -> None:
    # risk summary (#66)
    if getattr(args, "risk", False) or args.output:
        score = reporting_ext.score_findings(results)
        Console.rule(f"Risk: {score['risk_level']} (score {score['risk_score']})")
        for sev in ("critical", "high", "medium", "low"):
            if score["counts"][sev]:
                Console.kv(sev, score["counts"][sev])
        for f in score["findings"][:12]:
            Console.warn(f"[{f['severity']}] {f['module']}: {f['field']} = {f['detail'][:80]}")

    if args.output:
        fmt = (args.format or args.output.rsplit(".", 1)[-1]).lower()
        try:
            if fmt in _EXT_FORMATS:
                path = reporting_ext.export_ext(results, args.output, fmt, target)
            else:
                path = reporting.export(results, args.output, args.format, target)
            Console.good(t("report_written", path=path))
        except RuntimeError as exc:   # e.g. reportlab missing -> wrote HTML
            Console.warn(str(exc))
        except Exception as exc:      # noqa: BLE001
            Console.err(f"report failed: {exc}")

    if getattr(args, "siem", None):
        ok = reporting_ext.push_siem(results, args.siem, args.siem_mode,
                                     args.siem_token or "",
                                     verify=not getattr(args, "siem_insecure", False))
        Console.good("SIEM push ok") if ok else Console.warn("SIEM push failed")

    if getattr(args, "exec_report", None):
        exploit = None
        if getattr(args, "exploit_intel", False):
            try:
                exploit = workflow.exploit_intel(results)
            except Exception:  # noqa: BLE001
                exploit = None
        try:
            p = reporting_ext.export_exec_report(results, args.exec_report, target,
                                                 exploit=exploit, lang=args.lang)
            Console.good(f"executive report: {p}")
        except Exception as exc:  # noqa: BLE001
            Console.err(f"exec report failed: {exc}")

    if getattr(args, "intel_report", None):
        try:
            p = reporting_ext.export_intel_report(results, args.intel_report,
                                                  target, lang=args.lang)
            Console.good(f"intelligence report: {p}")
        except Exception as exc:  # noqa: BLE001
            Console.err(f"intel report failed: {exc}")

    if args.notify:
        exploit = None
        if getattr(args, "exploit_intel", False):
            try:
                exploit = workflow.exploit_intel(results)
            except Exception:  # noqa: BLE001
                exploit = None
        ok = workflow.notify(results, target, args.notify, exploit=exploit)
        Console.good("notification sent") if ok else Console.warn("notification failed")


# --------------------------------------------------------------------------- #
#  Selection helpers
# --------------------------------------------------------------------------- #
def select_modules(args) -> Optional[List[Module]]:
    if args.all:
        return list(REGISTRY.values())
    if getattr(args, "profile", None):
        recipes = workflow.load_recipes(getattr(args, "recipes", None))
        if args.profile not in recipes:
            Console.err(f"unknown profile: {args.profile}")
            Console.kv("available", ", ".join(sorted(recipes)))
            return None
        ids = recipes[args.profile]
        chosen = [get_module(i) for i in ids if get_module(i)]
        miss = [i for i in ids if not get_module(i)]
        if miss:
            Console.warn(f"profile references unknown ids (skipped): {', '.join(miss)}")
        return chosen
    if args.category:
        cats = modules_by_category()
        want = args.category.lower()
        match = next((c for c in cats if c.lower() == want), None)
        if not match:
            Console.err(f"unknown category: {args.category}")
            Console.kv("available", ", ".join(sorted(cats)))
            return None
        return cats[match]
    if args.modules:
        ids = [m.strip() for m in args.modules.split(",") if m.strip()]
        chosen, missing = [], []
        for i in ids:
            mod = get_module(i)          # follows merge aliases
            (chosen.append(mod) if mod else missing.append(i))
        if missing:
            Console.err(f"unknown module id(s): {', '.join(missing)}")
            Console.kv("hint", "run --list to see all ids")
            return None
        return chosen
    return None


def print_profiles(args) -> None:
    recipes = workflow.load_recipes(getattr(args, "recipes", None))
    print_banner()
    for name, ids in sorted(recipes.items()):
        Console.rule(f"profile: {name}  ({len(ids)} modules)")
        print("  " + ", ".join(ids))
    print()


def print_module_list() -> None:
    print_banner()
    for category, mods in sorted(modules_by_category().items()):
        Console.rule(category)
        for m in mods:
            need = f"  {Colors.GREY}(needs: {', '.join(m.needs)}){Colors.RESET}" if m.needs else ""
            print(f"  {Colors.GREEN}{m.id:<14}{Colors.RESET} {m.name}{need}")
    print(f"\n{Colors.CYAN}Total modules: {len(REGISTRY)}{Colors.RESET}\n")


# --------------------------------------------------------------------------- #
#  Interactive menu (built from the registry)
# --------------------------------------------------------------------------- #
def interactive(cfg: Config, args) -> None:
    print_banner()
    cats = modules_by_category()
    index: List[Module] = []
    print(f"{Colors.BOLD}Choose a module by number "
          f"(or 'a' = all, 'q' = quit):{Colors.RESET}\n")
    n = 1
    for category in sorted(cats):
        print(f"{Colors.BLUE}-- {category} --{Colors.RESET}")
        for m in cats[category]:
            index.append(m)
            print(f"  {Colors.GREEN}{n:>2}.{Colors.RESET} {m.name}")
            n += 1
        print()

    try:
        choice = input(f"{Colors.BLUE}[+]{Colors.RESET} "
                       f"{Colors.RED}Enter choice:{Colors.RESET} ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        print()
        return
    if choice in ("q", "quit", "exit", "15"):
        Console.info("Like to See Ya, Hacking Anywhere..!")
        return

    if choice in ("a", "all"):
        mods = list(REGISTRY.values())
    else:
        try:
            sel = int(choice)
            assert 1 <= sel <= len(index)
            mods = [index[sel - 1]]
        except (ValueError, AssertionError):
            Console.err("invalid option")
            return

    kind = mods[0].target_kind if len(mods) == 1 else "host"
    prompt = {"domain": "domain", "ip": "IP address",
              "url": "domain or URL", "host": "domain or IP"}.get(kind, "target")
    try:
        target = input(f"{Colors.RED}[+] Enter {prompt}:{Colors.RESET} ").strip()
    except (EOFError, KeyboardInterrupt):
        print()
        return
    if not target:
        Console.err("no target given")
        return

    if not getattr(args, "no_keys", False):
        needed = sorted({MODULE_KEYS[m.id] for m in mods if m.id in MODULE_KEYS})
        if needed:
            saved = cfg.ensure_keys(needed)
            if saved:
                Console.good(f"saved keys: {', '.join(saved)} -> {cfg.key_backend()} backend")

    ctx = make_context(cfg, args)
    results = run_modules(mods, target, ctx, args)
    handle_reports(results, target, args)


# --------------------------------------------------------------------------- #
#  argparse
# --------------------------------------------------------------------------- #
def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="ghost_eye",
        description="Ghost Eye v3 - modular recon / OSINT toolkit "
                    "(authorised testing only).",
        epilog="Examples:\n"
               "  ghost_eye.py -t example.com -m headers,cert,subs\n"
               "  ghost_eye.py -t example.com --category SSL/TLS --output tls.html\n"
               "  ghost_eye.py -t example.com --all --output report.json\n"
               "  ghost_eye.py --list\n\n"
               "Schedule it (#75) with cron, e.g. daily at 3am:\n"
               "  0 3 * * * /usr/bin/python3 /path/ghost_eye.py -t example.com "
               "--all --output /reports/$(date +\\%F).html --save-db",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("-t", "--target", help="domain / IP / URL to assess")
    p.add_argument("-T", "--targets", metavar="FILE",
                   help="file with one target per line (batch mode, #67)")
    p.add_argument("-u", "--username", metavar="HANDLE",
                   help="OSINT a username across the site registry "
                        "(shortcut for -t HANDLE -m usernamescan,usernamevariants)")
    p.add_argument("--email", metavar="ADDRESS",
                   help="OSINT an email's public footprint "
                        "(shortcut for -t ADDRESS -m emailfootprint)")
    sel = p.add_argument_group("module selection")
    sel.add_argument("-m", "--modules",
                     help="comma-separated module ids (see --list)")
    sel.add_argument("--category", help="run every module in a category")
    sel.add_argument("-p", "--profile",
                     help="run a named scan recipe (see --list-profiles)")
    sel.add_argument("--all", action="store_true", help="run every module")
    sel.add_argument("--list", action="store_true", dest="list_modules",
                     help="list all modules and exit")
    sel.add_argument("--list-profiles", action="store_true",
                     help="list scan recipes/profiles and exit")
    sel.add_argument("--recipes", help="custom recipes file (yaml/json)")
    sel.add_argument("--plugins", metavar="DIR",
                     help="load extra modules from a plugins directory (#72)")

    net = p.add_argument_group("network / stealth")
    net.add_argument("--threads", type=int, help="worker threads (default 10)")
    net.add_argument("--timeout", type=int, help="per-request timeout seconds")
    net.add_argument("--rate", type=float, default=0,
                     help="global rate limit, requests/sec (politeness, #75)")
    net.add_argument("--cache", action="store_true",
                     help="cache HTTP GETs on disk (#77)")
    net.add_argument("--cache-ttl", type=int, default=300, help="cache TTL seconds")
    net.add_argument("--passive-only", action="store_true",
                     help="run only passive modules (no traffic to the target)")
    net.add_argument("--adaptive-rate", action="store_true",
                     help="self-tuning throttle: back off when the target errors/rate-limits")
    net.add_argument("--rate-per-host", type=float, default=0,
                     help="per-host rate limit, requests/sec")
    net.add_argument("--proxy", help="proxy URL, e.g. http://127.0.0.1:8080")
    net.add_argument("--tor", action="store_true",
                     help="route through Tor (socks5h://127.0.0.1:9050)")
    net.add_argument("--rotate-ua", action="store_true",
                     help="rotate User-Agent per module")
    net.add_argument("--user-agent", help="fixed custom User-Agent")
    net.add_argument("--insecure", action="store_true",
                     help="do not verify TLS certificates")
    net.add_argument("--opsec", action="store_true",
                     help="OPSEC audit: report which third parties the scan "
                          "disclosed the target to")
    net.add_argument("--opsec-strict", action="store_true",
                     help="OPSEC enforce: contact ONLY the target — refuse "
                          "every third-party request (implies --opsec)")

    out = p.add_argument_group("output / reporting")
    out.add_argument("-o", "--output", help="write report to this file")
    out.add_argument("-f", "--format",
                     choices=["json", "csv", "html", "pdf", "md", "markdown",
                              "sarif", "prometheus", "prom", "dashboard",
                              "graphml", "gexf"],
                     help="report format (default: infer from extension)")
    out.add_argument("--filter-cdn", action="store_true", dest="filter_cdn",
                     help="classify every IP found: CDN/WAF edge vs cloud vs "
                          "candidate origin (filters out edge noise)")
    out.add_argument("--attribute", action="store_true",
                     help="infrastructure attribution: cluster the hosts seen "
                          "into operator estates with weighted evidence")
    out.add_argument("--anomalies", action="store_true",
                     help="score this scan against everything you have scanned "
                          "before and report only what is unusual for your corpus")
    out.add_argument("--baseline-learn", action="store_true",
                     dest="baseline_learn",
                     help="teach the corpus baseline from this scan "
                          "(scoring always happens before learning)")
    out.add_argument("--risk", action="store_true",
                     help="print a prioritised risk summary (#66)")
    out.add_argument("--inventory", action="store_true",
                     help="print a unified asset inventory across all modules")
    out.add_argument("--rollup", action="store_true",
                     help="print a per-host rollup (ports/tech/CVEs/severity per host)")
    out.add_argument("--exploit-intel", action="store_true",
                     help="after the scan, check every discovered CVE against the "
                          "public exploit DBs (Exploit-DB, Metasploit, NVD, GitHub…) "
                          "and flag which ones have a public exploit")
    out.add_argument("--exec-report", metavar="FILE",
                     help="write a polished self-contained executive HTML report "
                          "(risk grade, attack-surface graph, exploit intel); "
                          "honours --lang he for a Hebrew RTL report")
    out.add_argument("--intel-report", metavar="FILE",
                     help="write the unified intelligence report (assets, org "
                          "profile, attack-surface graph, tech, cloud, leaks) as HTML")
    out.add_argument("--intel", action="store_true",
                     help="print a unified intelligence summary after the scan")
    out.add_argument("--screenshots", nargs="?", type=int, const=10,
                     metavar="N", help="after the scan, screenshot the target + up "
                     "to N discovered subdomains (default 10) into the visual gallery")
    out.add_argument("--ci", action="store_true",
                     help="CI/CD mode: exit non-zero if findings breach --fail-on")
    out.add_argument("--fail-on", choices=["critical", "high", "medium", "low"],
                     default="high", help="severity gate for --ci (default: high)")
    out.add_argument("--save-db", action="store_true",
                     help="store results in SQLite history")
    out.add_argument("--db", default="ghosteye.db", help="SQLite path")
    out.add_argument("--diff", action="store_true",
                     help="show changes vs the previous stored run")
    out.add_argument("--notify", metavar="WEBHOOK",
                     help="Slack/Discord/Telegram webhook for a summary")
    out.add_argument("--siem", metavar="URL",
                     help="push results to Elasticsearch/Splunk/webhook (#68)")
    out.add_argument("--siem-mode", choices=["webhook", "elasticsearch", "splunk"],
                     default="webhook")
    out.add_argument("--siem-token", help="Splunk HEC token")
    out.add_argument("--siem-insecure", action="store_true",
                     help="skip TLS verification when pushing to the SIEM "
                          "(lab collectors with a self-signed cert only — the "
                          "HEC token travels in the request)")

    flow = p.add_argument_group("workflow")
    flow.add_argument("--watch", type=int, metavar="SECONDS",
                      help="re-run on an interval and alert on change (#74)")
    flow.add_argument("--resume", action="store_true",
                      help="skip targets already done (batch mode, #76)")
    flow.add_argument("--doctor", action="store_true",
                      help="check installed dependencies + binaries (#79)")
    flow.add_argument("--check-health", nargs="?", const="__all__", default=None,
                      metavar="IDS|CATEGORY",
                      help="probe modules against known-good targets and report "
                           "which actually work today (network; catches silent "
                           "failure). Optional: a category or comma-separated ids")
    flow.add_argument("--lang", choices=["en", "he"], default="en",
                      help="interface language (#80)")
    flow.add_argument("--scope", default="",
                      help="scope file (hosts/CIDRs); refuse targets outside it")
    flow.add_argument("--osint-deep", nargs="?", type=int, const=1, default=None,
                      metavar="DEPTH",
                      help="advanced OSINT: automated multi-hop pivot from -t (default depth 1)")
    flow.add_argument("--investigate", metavar="SEED",
                      help="entity investigation of a username or e-mail: "
                           "canary-checked profiles + identity + OPSEC dossier")
    flow.add_argument("--queue", default="",
                      help="shared job-queue DB for distributed scanning")
    flow.add_argument("--enqueue", action="store_true",
                      help="add -t/-T targets to the --queue and exit")
    flow.add_argument("--worker", action="store_true",
                      help="process jobs from the --queue (run on many hosts)")
    flow.add_argument("--max-jobs", type=int, default=0,
                      help="worker: stop after N jobs (0 = until drained)")
    flow.add_argument("--deep", action="store_true",
                      help="recursive scan: fan out to discovered subdomains/IPs "
                           "and scan each (attack-surface sweep)")
    flow.add_argument("--deep-max", type=int, default=25,
                      help="max discovered hosts/IPs to expand to in --deep mode")

    misc = p.add_argument_group("misc")
    misc.add_argument("--config-init", action="store_true",
                      help="write a template config to ~/.ghosteye/config.ini")
    misc.add_argument("--set-keys", action="store_true",
                      help="interactively enter & save your API keys, then exit")
    misc.add_argument("--no-keys", action="store_true",
                      help="never prompt for API keys during a scan")
    misc.add_argument("--errors", action="store_true",
                      help="show the persistent error log (crashes/failures) and exit")
    misc.add_argument("--module-report", action="store_true",
                      help="print a quality/capability report for every module and exit")
    misc.add_argument("--trend", action="store_true",
                      help="show the security trend for -t <target> from --db history and exit")
    misc.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    misc.add_argument("--logfile", help="write logs to this file")
    misc.add_argument("--no-color", action="store_true", help="disable colours")
    return p


def _check_scope(target, args) -> bool:
    scope_file = getattr(args, "scope", "")
    if not scope_file:
        return True
    from .scope import Scope
    scope = Scope.from_file(scope_file)
    if scope.empty:
        return True
    allowed, reason = scope.allows(target)
    if not allowed:
        Console.err(f"out of scope ({reason}); skipping {target}")
    return allowed


def _run_deep(initial, target, cfg, args):
    """Fan out from the assets discovered in `initial` and scan each one."""
    from .scope import Scope
    from . import workflow
    scope = Scope.from_file(args.scope) if getattr(args, "scope", "") else None
    plan, assets = workflow.deep_plan(initial, target, scope,
                                      getattr(args, "deep_max", 25))
    if not plan:
        Console.info("deep scan: no new assets discovered to expand to")
        return []
    Console.rule(f"Deep scan: expanding to {len(assets['hosts'])} host(s) + "
                 f"{len(assets['ips'])} IP(s)")
    extra = []
    for asset, mods in plan:
        ctx = make_context(cfg, args)
        Console.info(f"↳ {asset}")
        extra.extend(run_modules(mods, asset, ctx, args))
    return extra


def _print_rollup(results, target):
    roll = reporting_ext.build_host_rollup(results, target)
    if not roll:
        return
    Console.rule("Per-host rollup")
    for host, info in roll.items():
        bits = []
        if info.get("ports"):
            bits.append(f"ports: {', '.join(map(str, info['ports'][:12]))}")
        if info.get("tech"):
            bits.append(f"tech: {', '.join(info['tech'][:6])}")
        if info.get("cves"):
            bits.append(f"CVEs: {len(info['cves'])}")
        sev = info.get("severity")
        Console.kv(f"{host}" + (f" [{sev}]" if sev else ""),
                   " · ".join(bits) or f"{info.get('findings', 0)} findings")


def _run_once(mods, target, cfg, args):
    if not _check_scope(target, args):
        return []
    ctx = make_context(cfg, args)
    results = run_modules(mods, target, ctx, args)
    if getattr(args, "deep", False):
        results = results + _run_deep(results, target, cfg, args)
    if getattr(args, "screenshots", None):
        n = args.screenshots
        Console.rule(f"Visual sweep — screenshotting up to {n} hosts")
        shots = workflow.capture_surface(results, target, max_shots=n,
                                         timeout=ctx.timeout)
        Console.kv("captured", f"{len(shots)} screenshot(s)")
        results = results + shots
    handle_reports(results, target, args)
    if getattr(args, "inventory", False):
        inv = reporting_ext.build_inventory(results, target)
        Console.rule("Asset inventory")
        for k, v in inv["counts"].items():
            Console.kv(k, v)
        for cat in ("hosts", "ips", "services", "emails"):
            if inv.get(cat):
                Console.kv(cat, ", ".join(map(str, inv[cat][:20]))
                           + (" …" if len(inv[cat]) > 20 else ""))
    if getattr(args, "rollup", False) or getattr(args, "deep", False):
        _print_rollup(results, target)
    if getattr(args, "exploit_intel", False):
        _print_exploit_intel(results)
    if getattr(args, "intel", False):
        _print_intel(results, target)
    if getattr(args, "filter_cdn", False):
        _print_ip_filter(workflow.ip_filter_report(results, target))
    if getattr(args, "attribute", False):
        _print_attribution(workflow.attribution_report(results, target))
    if getattr(args, "anomalies", False) or getattr(args, "baseline_learn", False):
        from . import baseline
        _print_anomalies(baseline.anomaly_report(
            results, db=args.db, target=target,
            learn=getattr(args, "baseline_learn", False)),
            scored=getattr(args, "anomalies", False))
    if getattr(ctx, "opsec", None) is not None:
        _print_opsec(ctx.opsec.report())
    return results


def _print_opsec(rep: dict) -> None:
    Console.rule(f"OPSEC — {rep['exposure']}")
    third = rep.get("third_parties_contacted", [])
    Console.kv("third parties that saw the target", rep["third_party_count"])
    for entry in third[:25]:
        Console.kv(entry["host"], f"{entry['requests']} request(s)", indent=4)
    if rep.get("strict_mode"):
        blocked = rep.get("blocked_in_strict_mode", [])
        Console.kv("blocked (strict mode)", len(blocked))
        for h in blocked[:25]:
            Console.kv(h, "refused", indent=4)
    Console.info(rep["note"])


def _run_health(args, cfg) -> int:
    """Probe modules against known-good targets and report what actually works
    today. Network-bound; a diagnostic, never part of a scan."""
    from . import health
    sel = getattr(args, "check_health", "__all__")
    cats = modules_by_category()
    if sel == "__all__":
        mods = list(REGISTRY.values())
        scope = "all modules"
    elif sel in cats or sel.lower() in {c.lower() for c in cats}:
        match = next(c for c in cats if c.lower() == sel.lower())
        mods = cats[match]
        scope = f"category {match}"
    else:
        ids = [i.strip() for i in sel.split(",") if i.strip()]
        mods = [get_module(i) for i in ids if get_module(i)]
        scope = f"{len(mods)} module(s)"
        if not mods:
            Console.err(f"no modules matched: {sel}")
            return 2
    print_banner()
    Console.rule(f"Health check — {scope} ({len(mods)} modules)")
    Console.info("probing live upstream sources; this contacts the network…")
    done = {"n": 0}

    def _tick(r):
        done["n"] += 1
        if r["status"] in ("broken",):
            Console.err(f"  [{done['n']}/{len(mods)}] {r['id']}: BROKEN — {r.get('detail','')[:70]}")
    rep = health.run_health_checks(mods, cfg=cfg, on_result=_tick)
    c = rep["counts"]
    Console.rule(f"Health: {rep['health_pct']}% "
                 f"(healthy {c['healthy']}, degraded {c['degraded']}, "
                 f"broken {c['broken']}, no-key {c['no_key']}, skipped {c['skipped']})")
    if rep["broken"]:
        Console.err("BROKEN — errored or wrong-shaped output (fix these):")
        for r in rep["broken"][:40]:
            Console.kv(r["id"], r.get("detail", "")[:90], indent=4)
    if rep["degraded"]:
        Console.warn(f"DEGRADED (ran but empty): {', '.join(r['id'] for r in rep['degraded'][:40])}")
    if args.output:
        import json as _json
        with open(args.output, "w", encoding="utf-8") as fh:
            _json.dump(rep, fh, ensure_ascii=False, indent=2)
        Console.good(f"wrote {args.output}")
    # exit non-zero when something is broken, so it can gate a release check
    return 1 if rep["broken"] else 0


def _print_ip_filter(rep: dict) -> None:
    Console.rule(f"IP filter — {rep['total_ips']} address(es), "
                 f"{rep['origin_count']} origin candidate(s)")
    if rep.get("origin_candidates"):
        Console.good("origin candidates (outside every known CDN/WAF range):")
        for ip in rep["origin_candidates"][:30]:
            Console.kv(ip, "candidate origin", indent=4)
    for provider, ips in (rep.get("cdn_providers") or {}).items():
        Console.kv(f"{provider} edge (filtered out)", f"{len(ips)} IP(s): "
                   + ", ".join(ips[:8]) + (" …" if len(ips) > 8 else ""))
    if rep.get("cloud_ips"):
        Console.kv("cloud-hosted", ", ".join(rep["cloud_ips"][:10]))
    if rep.get("fully_fronted"):
        Console.warn("every address is a CDN/WAF edge — origin not exposed")
    Console.info(rep.get("note", ""))


def _print_attribution(rep: dict) -> None:
    Console.rule(f"Infrastructure attribution — {rep['hosts_analysed']} host(s), "
                 f"{rep.get('estate_count', 0)} estate(s)")
    if not rep.get("estates"):
        Console.info(rep.get("note", "no estates above the confidence threshold"))
    for est in rep.get("estates", []):
        Console.kv("estate", f"{est['size']} hosts  (confidence {est['confidence']})")
        Console.kv("members", ", ".join(est["members"][:12]), indent=4)
        drivers = ", ".join(f"{k}={v}" for k, v in est["driving_evidence"].items())
        Console.kv("evidence", drivers, indent=4)
    demoted = rep.get("demoted_as_shared_infrastructure") or []
    if demoted:
        Console.kv("ignored as shared infrastructure", ", ".join(demoted[:6]))


def _print_anomalies(rep: dict, scored: bool = True) -> None:
    """What is unusual about this host relative to everything you've scanned."""
    if scored:
        Console.rule(f"Anomalies — {rep['anomaly_count']} unusual value(s) "
                     f"vs a corpus of {rep['corpus_hosts']} host(s)")
        if not rep.get("anomalies"):
            Console.info(rep.get("note", "nothing unusual"))
        for a in rep.get("anomalies", []):
            tag = "ONLY THIS HOST" if a["unique_to_this_host"] else \
                f"{a['seen_on_hosts']}/{a['of_hosts_with_field']} hosts"
            Console.warn(f"[{tag}] {a['module']}.{a['field']} = {a['value'][:90]}")
        if rep.get("by_module"):
            Console.kv("by module", ", ".join(f"{k}={v}" for k, v in
                                              list(rep["by_module"].items())[:8]))
    if "learned_observations" in rep:
        Console.good(f"baseline: learned {rep['learned_observations']} new "
                     f"observation(s); corpus now {rep['corpus_hosts']} host(s)")


def _print_intel(results, target):
    rep = workflow.intelligence_report(results, target)
    c = rep["counts"]
    Console.rule(f"Intelligence — {rep['target']}  (grade {rep['grade']}, "
                 f"risk {rep['risk_level']})")
    Console.kv("assets", f"{c['assets']}  (subdomains {c['subdomains']}, "
                         f"related {c['domains']}, IPs {c['ips']}, "
                         f"emails {c['emails']})")
    Console.kv("cloud", ", ".join(rep["intelligence"]["cloud"]))
    Console.kv("email security", f"{rep['intelligence']['email_security']['score']}"
                                 f"/100 ({rep['intelligence']['email_security']['grade']})")
    if rep["organization"]["uses"]:
        Console.kv("uses", ", ".join(rep["organization"]["uses"][:10]))
    if c["leak_indicators"]:
        Console.kv("leak indicators", c["leak_indicators"])

    kg = rep.get("knowledge_graph", {}).get("counts", {})
    if kg:
        Console.kv("knowledge graph", f"{kg.get('entities', 0)} entities · "
                                      f"{kg.get('relationships', 0)} relationships")
    corr = rep.get("correlation", {})
    for p in corr.get("pivot_points", [])[:3]:
        Console.kv("pivot", f"{p['entity']} ({p['kind']}, degree {p['degree']})")
    for s in corr.get("shared_infrastructure", [])[:2]:
        Console.kv("shared infra", f"{s['hub']} — {s['connects']} hosts")

    tl = rep.get("timeline", {})
    if tl.get("insights") and tl["insights"][0] != "no dated intelligence available in this scan":
        Console.rule("Timeline")
        for i in tl["insights"][:5]:
            Console.info(i)

    an = rep.get("analysis", {})
    if an:
        Console.rule(f"Analyst assessment  (confidence {an.get('confidence', '?')})")
        Console.info(an.get("headline", ""))
        for line in an.get("assessment", [])[:5]:
            Console.warn(f"• {line}")
        if an.get("recommendations"):
            Console.rule("Recommendations")
            for r in an["recommendations"][:5]:
                Console.good(f"→ {r}")

    Console.rule("Main risks")
    for r in rep["organization"]["main_risks"]:
        Console.warn(f"• {r}")


def _print_exploit_intel(results):
    Console.rule("Exploit / zero-day intelligence")
    rep = workflow.exploit_intel(results)
    if rep.get("error"):
        Console.err(rep["error"])
        return
    Console.kv("CVEs found", rep["cves_found"])
    Console.kv("with public exploit", rep["exploitable_count"])
    Console.kv("weaponised (Metasploit/PoC)", rep["weaponised_count"])
    if rep["cves_found"] == 0:
        Console.info("no CVEs were surfaced by this scan — "
                     "run tech/jslibs/cve modules to fingerprint versions first")
        return
    for f in rep["findings"]:
        tag = ("EXPLOIT PUBLIC" if f["exploit_available"] else f["verdict"])
        sev = f.get("severity") or "?"
        cvss = f.get("cvss")
        Console.kv(f"{f['cve']} [{sev}{'/' + str(cvss) if cvss else ''}]", tag)
        srcs = f["sources"]
        if f["exploit_available"]:
            edb = srcs.get("exploit_db")
            msf = srcs.get("metasploit")
            if edb and edb != "none":
                Console.kv("  Exploit-DB", edb)
            if msf and msf != "none":
                Console.kv("  Metasploit", msf)


def _run_batch(mods, targets, cfg, args):
    cp = workflow.Checkpoint() if args.resume else None
    if args.resume and not cp.done:
        pass
    if not args.resume:
        workflow.Checkpoint().clear()
        cp = workflow.Checkpoint()
    prog = workflow.Progress(len(targets), "targets")
    for tgt in targets:
        if cp.is_done(tgt):
            Console.info(f"skip (done): {tgt}")
            prog.step(tgt)
            continue
        Console.rule(f"target: {tgt}")
        try:
            _run_once(mods, tgt, cfg, args)
            cp.mark(tgt)
        except KeyboardInterrupt:
            Console.warn("\ninterrupted - rerun with --resume to continue")
            prog.close()
            return 130
        except Exception as exc:  # noqa: BLE001
            Console.err(f"{tgt}: {exc}")
        prog.step(tgt)
    prog.close()
    return 0


def _watch_loop(mods, target, cfg, args):
    Console.info(f"watch mode: re-running every {args.watch}s on {target} "
                 "(Ctrl-C to stop)")
    prev = None
    # persistence is run_modules' job (it honours --save-db); opening a second
    # Store here only leaked an sqlite connection per watch session
    while True:
        ctx = make_context(cfg, args)
        results = run_modules(mods, target, ctx, args)
        snap = {r.module: r.as_dict().get("data") for r in results}
        if prev is not None:
            changed = [m for m in snap if snap.get(m) != prev.get(m)]
            if changed:
                msg = f"Ghost Eye change on {target}: {', '.join(changed)}"
                Console.warn(msg)
                if args.notify:
                    reporting.notify(args.notify, msg)
            else:
                Console.good("no change")
        prev = snap
        handle_reports(results, target, args)
        try:
            time.sleep(max(args.watch, 5))
        except KeyboardInterrupt:
            print()
            return 0


def _collect_targets(args) -> List[str]:
    """Targets from -t and/or -T (one per line, # comments ignored)."""
    out: List[str] = []
    if getattr(args, "target", None):
        out.append(args.target.strip())
    if getattr(args, "targets", None):
        try:
            out += [l.strip() for l in open(args.targets, encoding="utf-8")
                    if l.strip() and not l.startswith("#")]
        except OSError:
            pass
    return out


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    # entity-OSINT shortcuts: --username / --email pick the target and the
    # right data-driven module so you don't have to remember module ids
    if getattr(args, "username", None):
        args.target = args.username
        if not args.modules:
            args.modules = "usernamescan,usernamevariants"
    if getattr(args, "email", None):
        args.target = args.email
        if not args.modules:
            args.modules = "emailfootprint"

    if args.no_color:
        Colors.disable()
    setup_logging(args.verbose, args.logfile)
    workflow.set_lang(args.lang)
    cfg = Config()

    if args.doctor:
        workflow.doctor()
        return 0

    if getattr(args, "check_health", None) is not None:
        return _run_health(args, cfg)

    # distributed scanning (feature 75): shared queue coordinator / worker
    if getattr(args, "queue", ""):
        from . import distributed
        if getattr(args, "enqueue", False):
            targets = _collect_targets(args)
            if not targets:
                Console.err("nothing to enqueue — pass -t/-T")
                return 2
            q = distributed.JobQueue(args.queue)
            n = q.enqueue_many(targets, args.profile or "quick")
            Console.good(f"enqueued {n} target(s) into {args.queue}")
            Console.kv("queue", q.stats())
            q.close()
            return 0
        if getattr(args, "worker", False):
            Console.rule(f"worker on {args.queue}")
            out = distributed.run_worker(args.queue, cfg,
                                         max_jobs=getattr(args, "max_jobs", 0) or 0)
            Console.good(f"worker {out['worker']} completed {out['completed']} job(s)")
            Console.kv("queue", out["queue"])
            return 0

    # advanced OSINT deep-dive (multi-hop auto-pivot from a single seed)
    if getattr(args, "osint_deep", None) is not None:
        if not args.target:
            Console.err("--osint-deep needs a seed: -t <domain>")
            return 2
        print_banner()
        Console.rule(f"OSINT deep-dive: {args.target} (depth {args.osint_deep})")
        out = workflow.osint_deepdive(args.target, cfg, depth=args.osint_deep)
        for h in out.get("hops", []):
            dc = h.get("discovered_counts", {})
            Console.kv(f"hop {h['hop']} ({h['confidence']})",
                       f"processed {h['processed']} · +{dc.get('domain',0)} domains "
                       f"+{dc.get('email',0)} emails +{dc.get('ip',0)} IPs")
        c = out.get("counts", {})
        Console.good(f"merged graph: {c.get('entities',0)} entities, "
                     f"{c.get('relationships',0)} relationships "
                     f"({out.get('entities_processed',0)} entities pivoted)")
        if args.output:
            import json as _json
            with open(args.output, "w", encoding="utf-8") as fh:
                _json.dump(out, fh, ensure_ascii=False, indent=2)
            Console.good(f"wrote {args.output}")
        return 0

    # entity investigation — the person-focused capstone over the data-driven
    # OSINT engine (username / e-mail seed -> profiles + identity + OPSEC).
    if getattr(args, "investigate", None):
        seed = args.investigate.strip()
        print_banner()
        Console.rule(f"Entity investigation — {seed}")
        out = workflow.entity_investigation(seed, cfg)
        if out.get("error"):
            Console.err(out["error"])
            return 2
        Console.kv("kind", out["kind"])
        Console.kv("profiles found", f"{out['profile_count']} "
                   f"({len(out['confirmed_profiles'])} high-confidence)")
        for p in out["confirmed_profiles"][:30]:
            Console.kv(p.get("site", "?"), p.get("url", ""), indent=4)
        if out.get("linked_emails"):
            Console.kv("linked e-mails", ", ".join(out["linked_emails"][:10]))
        _print_opsec(out.get("opsec", {}))
        from .intelligence import entity_dossier
        md = entity_dossier(out)
        if args.output:
            with open(args.output, "w", encoding="utf-8") as fh:
                if args.output.endswith((".md", ".markdown", ".txt")):
                    fh.write(md)
                else:
                    import json as _json
                    _json.dump(out, fh, ensure_ascii=False, indent=2)
            Console.good(f"wrote {args.output}")
        else:
            print("\n" + md)
        return 0

    if args.config_init:
        path = cfg.write_template()
        Console.good(f"wrote config template: {path}")
        Console.info("run with --set-keys to enter your API keys, then re-run")
        return 0

    if getattr(args, "trend", False):
        if not args.target:
            Console.err("--trend needs a target: -t <target>")
            return 2
        target = args.target
        try:
            store = reporting.Store(args.db)
            rep = workflow.trend(store, target)
            store.close()
        except Exception as exc:  # noqa: BLE001
            Console.err(f"trend unavailable: {exc}")
            return 2
        Console.rule(f"Security trend — {target}  ({rep['scans']} scans, "
                     f"{rep['direction']})")
        for e in rep["timeline"]:
            c = e["counts"]
            line = (f"{e['ts'][:19]}  risk {e['risk_level']:<8} "
                    f"score {e['risk_score']:<4} "
                    f"C{c.get('critical',0)} H{c.get('high',0)} "
                    f"M{c.get('medium',0)}")
            if "score_delta" in e:
                d = e["score_delta"]
                line += f"  Δ{'+' if d >= 0 else ''}{d}"
                if e.get("new_modules"):
                    line += f"  +{len(e['new_modules'])} new"
                if e.get("gone_modules"):
                    line += f"  -{len(e['gone_modules'])} gone"
            Console.kv("scan", line)
        if not rep["timeline"]:
            Console.info("no saved scans for this target — run with --save-db first")
        return 0

    if getattr(args, "module_report", False):
        rep = workflow.module_report()
        Console.rule(f"Module quality report — {rep['total']} modules, "
                     f"{rep['categories']} categories")
        Console.kv("documented", f"{rep['documented']}/{rep['total']} "
                                 f"({rep['documented_pct']}%)")
        Console.kv("smoke-test covered", f"{rep['smoke_covered']}/{rep['total']}")
        Console.kv("declare dependencies", rep["declare_dependencies"])
        for cat, n in rep["by_category"].items():
            Console.kv(cat, n)
        return 0

    if args.errors:
        path = errorlog_path()
        if not path.exists():
            Console.good(f"no errors logged — {path} does not exist yet")
            return 0
        Console.rule(f"Error log: {path}")
        try:
            print(path.read_text(encoding="utf-8"))
        except OSError as exc:
            Console.err(f"cannot read error log: {exc}")
            return 2
        return 0

    if args.set_keys:
        saved = cfg.interactive_setup()
        if saved:
            Console.good(f"saved keys: {', '.join(saved)} -> {cfg.key_backend()} backend")
        else:
            Console.info("no keys entered")
        return 0

    if args.plugins:
        loaded = workflow.load_plugins(args.plugins)
        if loaded:
            Console.good(f"loaded plugins: {', '.join(loaded)}")

    if args.list_profiles:
        print_profiles(args)
        return 0

    if args.list_modules:
        print_module_list()
        return 0

    mods = select_modules(args)

    # passive-only mode (feature 71): drop anything that touches the target
    if mods and getattr(args, "passive_only", False):
        before = len(mods)
        mods = workflow.passive_only(mods)
        Console.kv("passive-only", f"{len(mods)}/{before} modules (no active probing)")

    # ask for any API keys the selected modules need, and remember them
    if mods and not args.no_keys:
        needed = sorted({MODULE_KEYS[m.id] for m in mods if m.id in MODULE_KEYS})
        if needed:
            saved = cfg.ensure_keys(needed)
            if saved:
                Console.good(f"saved keys: {', '.join(saved)} -> {cfg.key_backend()} backend")

    # batch mode: -T / --targets file
    if args.targets:
        try:
            targets = [l.strip() for l in open(args.targets, encoding="utf-8")
                       if l.strip() and not l.startswith("#")]
        except OSError as exc:
            Console.err(f"cannot read targets file: {exc}")
            return 2
        if mods is None:
            Console.err("batch mode needs a selection: -m / --category / -p / --all")
            return 2
        print_banner()
        return _run_batch(mods, targets, cfg, args)

    # single target
    if args.target and mods is not None:
        print_banner()
        if args.watch:
            return _watch_loop(mods, args.target, cfg, args)
        try:
            results = _run_once(mods, args.target, cfg, args)
        except KeyboardInterrupt:
            Console.warn("\ninterrupted")
            return 130
        if getattr(args, "ci", False):
            gate = workflow.ci_gate(results, args.fail_on)
            Console.rule("CI/CD gate")
            Console.kv("result", gate["message"])
            Console.kv("grade", gate["grade"])
            if gate["breaching_counts"]:
                Console.kv("breaching", gate["breaching_counts"])
            return gate["exit_code"]
        return 0

    if args.target and mods is None:
        Console.err("pick what to run: -m <ids>, --category <name>, -p <profile>, or --all")
        Console.info("or run with no arguments for the interactive menu")
        return 2

    # no target -> interactive menu
    try:
        interactive(cfg, args)
    except KeyboardInterrupt:
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
