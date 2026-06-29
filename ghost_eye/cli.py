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

from .config import Config
from .core import (Colors, Console, Context, Module, REGISTRY, Result,
                   build_session, modules_by_category, setup_logging)
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
    rate = getattr(args, "rate", 0) or 0
    cache_dir = ".ghosteye_cache" if getattr(args, "cache", False) else None
    cache_ttl = getattr(args, "cache_ttl", 300)
    session = workflow.wrap_session(session, rate=rate,
                                    cache_dir=cache_dir, cache_ttl=cache_ttl,
                                    rate_per_host=getattr(args, "rate_per_host", 0) or 0)
    return Context(config=cfg, session=session, threads=threads,
                   timeout=timeout, verbose=args.verbose)


def _maybe_rotate(ctx: Context, args) -> None:
    """If UA rotation is on, give each module a fresh session/UA."""
    if args.rotate_ua:
        proxy = ctx.session.proxies.get("https") if ctx.session else None
        ctx.session = build_session(user_agent=random.choice(_UA_POOL),
                                    proxy=proxy, verify_tls=ctx.session.verify,
                                    timeout=ctx.timeout)


# --------------------------------------------------------------------------- #
#  Running modules
# --------------------------------------------------------------------------- #
def run_modules(mods: List[Module], target: str, ctx: Context,
                args) -> List[Result]:
    results: List[Result] = []
    store = reporting.Store(args.db) if args.save_db else None
    total = len(mods)
    for idx, mod in enumerate(mods, 1):
        _maybe_rotate(ctx, args)
        Console.rule(f"[{idx}/{total}] {mod.name}  ({mod.category})")
        if mod.needs:
            Console.kv("requires", ", ".join(mod.needs))
        t0 = time.time()
        try:
            res = mod.run(target, ctx)
        except KeyboardInterrupt:
            Console.warn("interrupted by user")
            break
        except Exception as exc:  # noqa: BLE001 - never let one module kill the run
            res = Result(mod.name, target, status="error", error=str(exc))
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


_EXT_FORMATS = {"md", "markdown", "sarif", "prom", "prometheus", "dashboard", "dash"}


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
                                     args.siem_token or "")
        Console.good("SIEM push ok") if ok else Console.warn("SIEM push failed")

    if args.notify:
        summary = _summary_text(results, target)
        ok = reporting.notify(args.notify, summary)
        Console.good("notification sent") if ok else Console.warn("notification failed")


def _summary_text(results: List[Result], target: str) -> str:
    lines = [f"Ghost Eye scan of {target}:"]
    for r in results:
        flag = {"ok": "+", "empty": "-", "error": "x"}.get(r.status, "?")
        lines.append(f"[{flag}] {r.module} ({r.status})")
    return "\n".join(lines)


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
        chosen = [REGISTRY[i] for i in ids if i in REGISTRY]
        miss = [i for i in ids if i not in REGISTRY]
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
            (chosen.append(REGISTRY[i]) if i in REGISTRY else missing.append(i))
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

    out = p.add_argument_group("output / reporting")
    out.add_argument("-o", "--output", help="write report to this file")
    out.add_argument("-f", "--format",
                     choices=["json", "csv", "html", "pdf", "md", "markdown",
                              "sarif", "prometheus", "prom", "dashboard"],
                     help="report format (default: infer from extension)")
    out.add_argument("--risk", action="store_true",
                     help="print a prioritised risk summary (#66)")
    out.add_argument("--inventory", action="store_true",
                     help="print a unified asset inventory across all modules")
    out.add_argument("--rollup", action="store_true",
                     help="print a per-host rollup (ports/tech/CVEs/severity per host)")
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

    flow = p.add_argument_group("workflow")
    flow.add_argument("--watch", type=int, metavar="SECONDS",
                      help="re-run on an interval and alert on change (#74)")
    flow.add_argument("--resume", action="store_true",
                      help="skip targets already done (batch mode, #76)")
    flow.add_argument("--doctor", action="store_true",
                      help="check installed dependencies + binaries (#79)")
    flow.add_argument("--lang", choices=["en", "he"], default="en",
                      help="interface language (#80)")
    flow.add_argument("--scope", default="",
                      help="scope file (hosts/CIDRs); refuse targets outside it")
    flow.add_argument("--deep", action="store_true",
                      help="recursive scan: fan out to discovered subdomains/IPs "
                           "and scan each (attack-surface sweep)")
    flow.add_argument("--deep-max", type=int, default=25,
                      help="max discovered hosts/IPs to expand to in --deep mode")

    misc = p.add_argument_group("misc")
    misc.add_argument("--config-init", action="store_true",
                      help="write a template config to ~/.ghosteye/config.ini")
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
    roll = inventory.build_host_rollup(results, target)
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
    return results


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
    store = reporting.Store(args.db)
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


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if args.no_color:
        Colors.disable()
    setup_logging(args.verbose, args.logfile)
    workflow.set_lang(args.lang)
    cfg = Config()

    if args.doctor:
        workflow.doctor()
        return 0

    if args.config_init:
        path = cfg.write_template()
        Console.good(f"wrote config template: {path}")
        Console.info("fill in your API keys, then re-run")
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
            _run_once(mods, args.target, cfg, args)
        except KeyboardInterrupt:
            Console.warn("\ninterrupted")
            return 130
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
