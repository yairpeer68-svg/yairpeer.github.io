# 👁 Ghost Eye

**Modular reconnaissance / OSINT / exposure-detection toolkit** — a Personal
Cyber Intelligence Platform with a browser dashboard, a typed Knowledge Graph,
an intelligence timeline, a rule-based AI analyst, exploit-intelligence
correlation, executive reporting and a CI/CD security gate.

A ground-up rewrite of the original single-file *Ghost Eye* by
[Jolanda de Koff (BullsEye0)](https://github.com/BullsEye0). The original was
one 400-line loop; this is a small Python package where **every feature is a
self-registering module** and the menu, CLI and dashboard build themselves from
the registry. Adding a capability is just dropping a class into a file.

- **499 modules** across 19 categories
- Everything is **reconnaissance / detection only** — no exploitation, payloads,
  brute-forcing, or DoS
- Loads with **zero third-party dependencies** installed (each module lazily
  imports what it needs and degrades gracefully)
- **708 automated tests** (unit + smoke + behavioural + engine + intelligence + integration), CI on
  Python 3.9 / 3.11 / 3.12

> ## ⚠️ Authorised use only
> Ghost Eye performs active reconnaissance (port scans, directory probing,
> subdomain enumeration, service fingerprinting, etc.). Run it **only** against
> systems you own or have **explicit written permission** to assess.
> Unauthorised scanning is illegal in most jurisdictions. You are responsible
> for how you use it.

---

## Table of contents

- [Install](#install)
- [Quick start](#quick-start)
- [Command-line reference](#command-line-reference)
- [Scan profiles](#scan-profiles-recipes)
- [Exploit / zero-day intelligence](#exploit--zero-day-intelligence)
- [Reporting](#reporting)
- [CI/CD security gate](#cicd-security-gate)
- [Notifications](#notifications)
- [Deep scan & asset inventory](#deep-scan--asset-inventory)
- [API keys](#api-keys)
- [Web dashboard](#web-dashboard)
- [Error log](#error-log)
- [Module catalogue](#module-catalogue)
- [Architecture](#architecture--adding-a-module)
- [Testing & quality](#testing--quality)
- [Configuration reference](#configuration-reference)
- [Project layout](#project-layout)

---

## Install

```bash
# from a checkout
pip install -e .                 # installs the `ghost-eye` / `ghost-eye-web` commands
# …or just run it in place:
python3 ghost_eye.py             # interactive menu
```

Dependencies are **optional** — the package imports even with none installed and
each module tells you what it needs when you run it. Install everything for full
coverage:

```bash
pip install -r requirements.txt          # or requirements-termux.txt on Android/Termux
```

Requires **Python 3.9+**. Optional external binaries (`nmap`, `masscan`,
`ping`, `traceroute`, `mtr`) are used when present and skipped gracefully when
not.

---

## Quick start

```bash
# interactive menu (pick a module, enter a target)
python3 ghost_eye.py

# a single target, specific modules
python3 ghost_eye.py -t example.com -m headers,cert,subs

# a ready-made profile, save an executive HTML report
python3 ghost_eye.py -t example.com -p quick --exec-report report.html

# everything, plus exploit-intelligence correlation
python3 ghost_eye.py -t example.com --all --exploit-intel

# a batch of targets from a file
python3 ghost_eye.py -T targets.txt -p perimeter

# the browser dashboard
python3 ghost_eye_web.py --open
```

---

## Command-line reference

**Selection**

| Flag | Meaning |
|------|---------|
| `-t, --target <t>` | single target (domain / IP / URL) |
| `-T, --targets <file>` | batch: one target per line |
| `-m, --modules <ids>` | comma-separated module ids |
| `-p, --profile <name>` | a scan profile (see below) |
| `--category <name>` | every module in a category |
| `--all` | every module |
| `--list`, `--list-profiles` | enumerate modules / profiles |

**Output & reporting**

| Flag | Meaning |
|------|---------|
| `-o, --output <file>` | write a report (format inferred from extension) |
| `--format <fmt>` | `json`, `csv`, `html`, `md`, `sarif`, `prometheus`, `dashboard`, `exec`, `graphml`, `gexf` |
| `--exec-report <file>` | polished executive HTML report (grade + graph + exploits); honours `--lang he` |
| `--intel-report <file>` | unified **intelligence report** (assets, org profile, attack-surface graph, tech, cloud, leaks) |
| `--intel` | print an intelligence summary after the scan |
| `--screenshots [N]` | screenshot the target + N discovered subdomains into the gallery |
| `--risk` | print a prioritised risk summary |
| `--inventory`, `--rollup` | asset inventory / per-host rollup |
| `--exploit-intel` | check every discovered CVE against the public exploit DBs |
| `--ci`, `--fail-on <sev>` | CI mode: non-zero exit if findings breach the severity gate |
| `--siem <url>` | push results to Elasticsearch / Splunk / webhook |
| `--notify <webhook>` | Slack / Discord / Telegram summary |
| `--save-db`, `--db <path>`, `--diff` | SQLite history + diff vs previous run |

**Workflow**

| Flag | Meaning |
|------|---------|
| `--deep`, `--deep-max <n>` | fan out to discovered subdomains/IPs |
| `--watch <seconds>` | re-run on an interval, alert on change |
| `--resume` | skip already-done targets (batch) |
| `--scope <file>` | refuse targets outside an allow-list |
| `--osint-deep [depth]` | advanced OSINT: automated multi-hop pivot from `-t` (default depth 1) |
| `--passive-only` | run only passive modules (no traffic to the target) |
| `--adaptive-rate` | self-tuning throttle: back off when the target errors / rate-limits |
| `--queue <db>` `--enqueue` `--worker` | distributed scanning: share a job queue across many worker hosts |
| `--lang {en,he}` | interface / report language (Hebrew = RTL) |
| `--plugins <dir>` | load extra module files from a directory |

**Keys, config & diagnostics**

| Flag | Meaning |
|------|---------|
| `--set-keys` | interactively enter & save optional API keys |
| `--no-keys` | never prompt for API keys during a scan |
| `--config-init` | write a config template to `~/.ghosteye/config.ini` |
| `--errors` | print the persistent error log |
| `--module-report` | per-module quality/capability report |
| `--trend` | security trend for `-t <target>` from `--db` history |
| `--doctor` | check installed dependencies + binaries |

---

## Scan profiles (recipes)

Ready-made module bundles, runnable with `-p <name>`:

`quick`, `perimeter`, `dns`, `web`, `tls`, `email`, `network`, `cloud`,
`exposure`, `osint`, `passive`, `api`, `auth`, `privacy`, `supply_chain`,
`iot`, `crypto`, `ai`, `exploit`, `mobile`.

```bash
python3 ghost_eye.py -t example.com -p exploit --exploit-intel
```

Define your own in a YAML/JSON file and load with `--recipes myrecipes.yaml`.

---

## Intelligence layer — a Personal Cyber Intelligence Platform

Ghost Eye doesn't just list findings — the `intelligence/` layer **correlates
the output of every module that ran into one ASM-style picture**, without any
extra scanning:

- **Assets** — subdomains, related domains, IPs, services, emails (de-duplicated).
- **Technologies** classified into CMS / framework / server / CDN / WAF.
- **Cloud footprint** — AWS / Azure / GCP / Cloudflare / … detection.
- **Email posture** — an SPF/DKIM/DMARC/MTA-STS score out of 100.
- **Certificates** — issuers, SAN domains, wildcard/related domains.
- **Leak indicators** — public breach/leak signals surfaced by OSINT modules.
- **Organization profile** — a plain-language "uses …" + "main risks …" summary.
- **Attack-surface graph** — a self-contained SVG of the target and everything
  connected to it.
- **Visual recon** — screenshots of the target and its subdomains (Shodan/Censys
  style thumbnails) embedded in the report and the dashboard gallery.

On top of that correlation, four layers turn it from a *scanner* into a
**Personal Cyber Intelligence Platform** — all **rule-based, deterministic and
offline (no LLM, no external API)**:

- **🕸 Knowledge Graph** — a full **typed entity/relationship** graph (not just a
  star): `subdomain_of`, `resolves_to`, `in_netblock`, `hosted_on`, `uses`,
  `issued_for`, `mx_for`, `ns_for`, `affected_by`, `exposes`, `registered_to`.
  Entities are typed (target, subdomain, ip, asn, cloud, tech, cert-issuer,
  email, org, cve, leak, mail/name-server) and rendered as an interactive SVG
  with real edges between them.
- **🔗 Smart entity correlation** — **pivot points** (the most connected
  entities an attacker/analyst moves through), **shared infrastructure** (one IP
  / netblock / cloud that ties many hosts together) and **clusters** (assets that
  provably belong together, via connected components of the graph).
- **🔥 Risk heat-map** — every graph node is scored **0-100** and banded
  (low/medium/high/critical); a host inherits danger from the leaks, CVEs and
  exposures attached to it. Toggle **🔥 Heat** on the dashboard graph to colour
  the whole map by risk.
- **🛤 Attack-path scoring** — the shortest chain from each exposure / leak /
  CVE to the target, scored by the average danger of the nodes it crosses ("how
  an attacker actually gets in").
- **🧩 tech → CVE correlation** — draws `tech --affected_by--> cve` edges by
  matching a CVE's context to a fingerprinted technology, so the graph shows
  *which* component a vulnerability belongs to.
- **🔗 Supply-chain mapping** — external hosts loaded at runtime (CDNs, JS
  libraries, analytics, payment/widget providers) are mapped as `dependency`
  entities linked to the target — the third parties in your software supply chain.
- **🕓 Intelligence Timeline** — dated events extracted from WHOIS, certificates,
  breach data and HTTP, ordered chronologically, with insights (upcoming/expired
  certs, domain age, most-recent breach, last infrastructure change).
- **🧠 AI analyst** — a rule-based analyst write-up: a **headline, executive
  summary, prioritised assessment, an attack narrative** ("how this surface would
  be approached"), **recommendations** and a stated **confidence** — the shape of
  a human analyst's report, fully deterministic and private.

```bash
python3 ghost_eye.py -t example.com --all --intel                 # console summary
python3 ghost_eye.py -t example.com --all --intel-report intel.html
python3 ghost_eye.py -t example.com -p perimeter --screenshots 15 --intel-report intel.html
# dashboard: GET /api/job/<id>/intel   ·   GET /api/job/<id>/risk
# the /intel payload now carries knowledge_graph (nodes scored with risk/band),
# correlation, risk_heatmap, attack_paths, supply_chain, timeline & analysis
```

Produces a single **Ghost Eye Intelligence Report** HTML page: the analyst
assessment, attack-surface graph, **knowledge graph**, pivot points & shared
infrastructure, the **intelligence timeline**, visual gallery, org profile,
tech, cloud, email score, certificates and leaks.

### Advanced OSINT — automated multi-hop deep-dive

`--osint-deep [depth]` (CLI) and the dashboard's **🌐 Deep OSINT** tool
(`POST /api/osint-deep`) turn the OSINT modules into an **autonomous
investigation**: from a single seed domain Ghost Eye runs OSINT sources,
extracts every entity they reveal (related domains, e-mails, IPs) and **pivots
onto each of them** — running the right modules per entity kind — up to the
chosen depth. Everything merges into one Knowledge Graph with **provenance**
(which hop, and which parent, discovered each entity) and a **confidence** that
decays with distance from the seed.

```bash
python3 ghost_eye.py -t example.com --osint-deep 2 -o osint.json
```

**Many free, keyless sources per data type** feed the correlator so its
confidence scoring has plenty to corroborate — no Shodan/Censys key required:
subdomains/CT from `certspotter`, `bufferover`, `hackertarget`, `subdomaincenter`
(+ existing `waybackcdx`, `commoncrawl`, `rapiddns`, `riddler`, passive-DNS
sources); passive DNS/reputation from `otxrep` (AlienVault OTX) and `robtex`;
ASN/netblock from `bgpview`, `ipapi`, `cymruasn`; malware/abuse from abuse.ch
`threatfox` and `urlhaus`; breach/infostealer exposure from `hudsonrock` and
`emailrep`; public-code mentions from `grepapp` and `searchcode`.

Two power modules feed the pivot: **`emailpattern`** harvests a company's public
e-mails, infers its address format (`first.last` / `flast` / …) and **generates
the most likely addresses** for people it finds on the site (even execs whose
address was never published); **`certpivot`** reads the TLS certificate and turns
every `subjectAltName` into a **sibling domain on the same cert** — a strong
infrastructure link with no third-party service. Every graph entity is scored by
**source corroboration** (`annotate_confidence`): confirmed by ≥3 independent
modules → high, 2 → medium, 1 → low, so the OSINT picture is ranked, not a flat
pile of hits.

### Graph-first dashboard & platform tooling

The default dashboard (`/`, served by `ghost-eye-web`) is a **graph-first OSINT
investigator** built around the typed Knowledge Graph. Beyond click-to-pivot,
kind filters, cluster-by-type and the risk heat-map, it now includes:

- **🗺 Mini-map** — a live overview of the whole graph in the corner; click to
  jump the camera anywhere.
- **🛤 Path-finding** — **shift-click** a second node to trace and highlight the
  shortest path between any two entities.
- **🕸 Unified multi-target graph** — merge several targets' graphs into one and
  surface the infrastructure they **share** (`GET /api/unified?targets=a,b`).
- **🔎 Full-text search** — search every finding of a scan at once
  (`GET /api/job/<id>/search?q=`).
- **🧩 Module / profile / schedule editor** — pick exactly the modules to run,
  save the set as a named profile (kept in the browser), run it now or schedule
  it to re-run on an interval.
- **🛡 Scope-guard editor**, **passive-only** and **adaptive rate-limit**
  toggles, and one-click **GraphML / GEXF** graph export (yEd / Gephi /
  Cytoscape / NetworkX).
- **🎫 Ticketing** — file a finding as a **Jira** or **ServiceNow** ticket
  (`POST /api/job/<id>/ticket`; credentials from `JIRA_*` / `SERVICENOW_*` env,
  with a safe dry-run preview).
- **🌐 EN / עברית** interface toggle and a **🌓 light / dark theme**.
- **⌘K command palette** — jump to any action or graph entity from the keyboard.
- **▦ Findings table** (sortable/filterable) and an **🕓 interactive timeline**.
- **🎯 Focus mode** (isolate a node's cluster), **per-node notes & tags**
  (kept in the browser), and **📊 Metrics / ⬇ Backup / ⬆ Restore** of saved scans.

### Enterprise / infrastructure

- **🔔 Custom alert rules** (`/api/alert-rules`) — change-monitoring fires only
  when it matters: set a minimum severity, a minimum number of new items, ignore
  event types (e.g. new subdomains), or restrict to specific targets.
- **🔐 Encryption at rest** — set `GHOSTEYE_SECRET` and stored API keys (and any
  JSON blob via `secure_store`) are sealed with PBKDF2 + Fernet (AES). Transparent
  and backward-compatible; nothing changes until you set the passphrase.
- **📥 Offline CVE mirror** — every exploit-intel lookup is cached in a local
  SQLite mirror; seed the whole **CISA KEV** catalogue or import an NVD JSON feed,
  then run fully offline with `GHOSTEYE_OFFLINE=1`.
- **🖧 Distributed scanning** — a shared SQLite job queue lets many workers
  (`--queue jobs.db --worker`, run on as many hosts as you like) cooperate on a
  target list with an atomic claim, so no target is ever scanned twice.

### Install anywhere (Docker / Termux, feature 80)

```bash
# one-command installer (Linux / macOS / Termux) + an `update.sh` auto-updater
bash install.sh

# or Docker — same image runs the dashboard or a one-off CLI scan
docker build -t ghost-eye .
docker run --rm -p 8777:8777 ghost-eye            # dashboard
docker run --rm ghost-eye -t example.com -p quick # CLI
```

**Screenshots** (`--screenshots [N]`, and the `screenshot` module) render each
asset headless. Backends, in order: **Playwright/Chromium** (desktop/server),
then a **system Chromium CLI** — the Android/**Termux** path: `pkg install
x11-repo tur-repo && pkg install chromium` and the CLI backend is used
automatically, no Playwright needed. Degrades gracefully when no browser exists.

---

## Exploit / zero-day intelligence

After a scan, Ghost Eye can take every CVE and product+version it discovered and
ask the **public exploit databases** whether a working public exploit already
exists — turning `nginx 1.18.0` or `CVE-2021-23017` into an actionable verdict.

Sources (all free, **no API key**):

| Source | What it contributes |
|--------|--------------------|
| **NVD** | CVSS + references tagged *Exploit* |
| **Exploit-DB** | CVE search → EDB-IDs of published exploits |
| **GitHub Advisories** | public advisories, often with PoC links |
| **Metasploit / Rapid7** | Metasploit-module presence (via CIRCL) |
| **CIRCL** | aggregated exploit-db / metasploit references |
| **PacketStorm** | published exploit files mentioning the CVE |
| **CISA KEV** | is the CVE *actively exploited in the wild*? (+ ransomware flag) |
| **FIRST.org EPSS** | probability & percentile of exploitation in the next 30 days |
| **OpenCVE / MITRE** | link-outs for manual review |

Each CVE gets a verdict — `ACTIVELY EXPLOITED (CISA KEV)` → `EXPLOIT PUBLIC` →
`advisory only` → `no public exploit found` — plus a `weaponised` flag (Metasploit
module or Exploit-DB PoC), an `epss` score and a `known_exploited` flag. Findings
are ranked KEV-first, then public exploit, then EPSS, then CVSS.

```bash
python3 ghost_eye.py -t example.com --all --exploit-intel
python3 ghost_eye.py -t example.com -m exploitdb        # standalone module
# dashboard: GET /api/job/<id>/exploits
```

Detection/correlation only — nothing is ever exploited.

---

## Reporting

`-o report.<ext>` picks the format by extension, or use `--format`:

- **JSON / CSV** — machine-readable
- **HTML / dashboard** — interactive, self-contained, filterable
- **Markdown** — prioritised findings table
- **SARIF** — CI security gates / code-scanning
- **Prometheus** — metrics exposition
- **exec** — the **executive report**: a single self-contained HTML file with a
  risk grade (A–F), severity tiles, an inline SVG **attack-surface graph**, an
  exploit-intelligence table, prioritised findings and the asset inventory.
  Full **RTL + Hebrew** with `--lang he`; light/dark aware.

```bash
python3 ghost_eye.py -t example.com --all --exploit-intel --exec-report report.html --lang he
```

---

## CI/CD security gate

Fail a pipeline when findings cross a severity threshold:

```bash
python3 ghost_eye.py -t staging.example.com -p perimeter --ci --fail-on high
# exit code 0 = pass, 1 = findings at or above `high`
```

Pair it with `--format sarif -o results.sarif` to upload to code-scanning.

---

## Notifications

Push a scan summary (grade, top findings, exploitable CVEs) to a webhook — the
service is auto-detected from the URL:

```bash
python3 ghost_eye.py -t example.com --all \
  --notify https://hooks.slack.com/services/XXX          # Slack
  # or a Discord webhook, or api.telegram.org/bot<TOKEN>/sendMessage?chat_id=<ID>
```

**🔔 Change-alert monitoring** — turn periodic re-scans into an active monitor.
Set a **change-alert webhook** in the dashboard's scan options, and when a re-scan
**adds new exposure** — a new subdomain, IP, open service/port, CVE, technology or
leak indicator — Ghost Eye diffs the attack surface against the previous scan and
fires an alert with the exact diff to Slack / Discord / Telegram. Growth-only,
rule-based; a first scan (no history) never alerts. The dashboard also shows a
live banner and the full diff in the Trend panel.

**Continuous monitoring** — add the same webhook to a **schedule** (dashboard →
Schedules) and Ghost Eye re-scans the target every N minutes and pings you *only
when the surface changes* — hands-off drift monitoring. Scheduled runs carry the
alert webhook automatically, so no manual re-runs are needed.

---

## Deep scan & asset inventory

`--deep` fans out from the assets discovered in the first pass to the target's
**real subdomains and IPs** (off-target references are never scanned) and runs a
profile against each. `--inventory` prints a de-duplicated attack-surface view
(hosts, IPs, services, emails, URLs, tech); `--rollup` groups findings per host
with ports, tech, CVEs and a per-host severity.

```bash
python3 ghost_eye.py -t example.com -p perimeter --deep --deep-max 30 --rollup
```

---

## API keys

Almost every module works with **no key**. A few optional ones
(VirusTotal, AbuseIPDB, DeepSeek) can use one. Ghost Eye asks for the key it
needs and remembers it:

```bash
python3 ghost_eye.py --set-keys          # enter & save all keys up front
```

When a scan includes a module that needs a key and none is set, it prompts and
saves your answer. Resolution order is **env var → OS keyring → config file**:

- **OS keyring** — if the optional [`keyring`](https://pypi.org/project/keyring/)
  package is installed, keys go to the OS secret store (Secret Service / macOS
  Keychain / Windows Credential Manager) and **nothing is written to disk**.
  Force the file backend with `GHOSTEYE_NO_KEYRING=1`.
- **Config file** — otherwise `~/.ghosteye/config.ini` under `[api_keys]`,
  written `0600` (owner-only).
- **Env vars** — `VT_API_KEY`, `ABUSEIPDB_API_KEY`, `DEEPSEEK_API_KEY` always win.

Keys stay local and are never committed. Use `--no-keys` for unattended runs.

---

## Security trend / history

Save scans with `--save-db`, then track how a target's exposure changes over
time — re-scored risk per scan, and the modules that appeared or disappeared
between scans:

```bash
python3 ghost_eye.py -t example.com -p perimeter --save-db    # build history
python3 ghost_eye.py -t example.com --trend                   # show the timeline
# dashboard: history/diff via the /api/job/<id>/diff endpoint
```

---

## Web dashboard

```bash
python3 ghost_eye_web.py                 # localhost only (default)
python3 ghost_eye_web.py --open          # open a browser
python3 ghost_eye_web.py --host 0.0.0.0 --port 9000 --scope scope.txt
```

Two dashboards, one server — the **graph-first OSINT investigator is the home page**:

- **`/`** (and `/osint`) — the **OSINT investigator** (Maltego / BloodHound style):
  type a target, hit *Investigate*, and Ghost Eye correlates its footprint into a
  live **force-directed entity graph** (typed nodes — target, subdomain, IP, ASN,
  cloud, tech, cert, email, org, CVE, leak — with icons and colours). Pan, zoom,
  drag nodes, **click any node to pivot** to its typed relationships, and filter
  by entity kind. The right rail is a **unified OSINT profile** (subdomains, IPs,
  emails, WHOIS/ASN, tech, cloud, leaks, email posture, timeline, screenshots and
  the analyst headline). The recon console (advanced scans / schedules / exports)
  lives at **`/console`**, cross-linked from the OSINT bar. The graph supports
  a **⚙ Tools** menu brings full CLI parity into the dashboard — screenshot
  every subdomain, exploit intel, risk, compliance, inventory, host rollup,
  trend and one-click report downloads (Exec/Intel/HTML/JSON/MD/SARIF/CSV).
    **search/highlight**, natural-language **Ask**, **cluster-by-type**, **PNG
  export**, and only ever shows the target's *own* related hosts (OSINT-reference
  sites like github/pastebin and URL-encode artifacts are filtered out).

**🔑 API keys in the browser** — a **Keys** panel (in both dashboards) lets you paste
optional API keys (VirusTotal / AbuseIPDB / DeepSeek) and **save them** straight
from the dashboard; they persist to the OS keyring or the `0600` config file
(`GET/POST /api/keys`). Keys are never shown back or returned by the API.

More platform features, all in the browser:

- **📱 Installable PWA** — a web-app manifest + service worker (offline shell) and
  app icons; "Add to home screen" on Android/Termux for an app-like launch.
- **▦ Portfolio** — a multi-target board (`/api/portfolio`): the latest scan per
  target with risk + asset counts, sorted by risk; click a row to open it.
- **💬 Ask (natural language)** — ask the graph in plain English ("how many
  subdomains?", "any leaks?", "what tech?", "who owns it?"); a rule-based engine
  answers from the loaded intel and highlights the matching nodes (no LLM).
- **📊 Scheduled reports** — give a schedule a *report webhook* and it pushes a
  full summary every run; give it an *alert webhook* and it pings only on change.
- **🔕 Triage / acknowledge** — mark a surfaced item (subdomain/IP/CVE/leak) as
  *known* from the entity panel; acknowledged items are muted from change-alerts
  (`GET/POST /api/acks`, stored 0600 locally).

A polished single-page console (mobile-first, dark, RTL-aware): configure a scan
(profile / category / modules / all), watch findings stream in live with a
running risk score, filter by severity/module, and export to any format. The
toolbar is grouped into **Views** and **Reports**; on a phone the controls become
a **slide-in drawer** and the toolbar scrolls horizontally, so it's fully usable
from **Termux/Android**. A failed module auto-expands to show its reason. Includes
asset inventory, per-host rollup, scan history/diff, and a **🌐 Hebrew / RTL
toggle** (the drawer opens from the right in RTL).

The **Intelligence** panel renders the whole Personal Cyber Intelligence
Platform right in the browser: the **🧠 AI-analyst assessment** (headline,
summary, prioritised assessment, attack narrative, recommendations, confidence),
the attack-surface graph, the **🕸 typed Knowledge Graph** — **interactive**:
**filter by entity kind** (toggle target/subdomain/ip/tech/cloud/… on and off)
and **click any node to focus its relationships** (its edges light up, the rest
dims) — **🔗 entity correlation** (pivot points, shared infrastructure,
clusters), the **🕓 intelligence timeline**, the screenshot gallery, org
profile, tech, cloud, email score and leak indicators — all from the single
`/api/job/<id>/intel` payload.

**Logs & errors:** the server prints a concise **request log** to the terminal
(every API call + its status; `--quiet` to silence), and any handler crash is
caught, printed with a full traceback, recorded to the error log, and returned as
a 500 — never a silent dead connection. In the dashboard, a module that fails
shows an **ERROR** badge and **auto-expands to show the reason** (e.g. "no PTR
record (NXDOMAIN)") — these per-module errors are usually benign (the target
simply doesn't offer that record/service), not a bug in Ghost Eye.

**Auth:** localhost-only by default (no friction). The moment you bind
off-localhost (`--host 0.0.0.0`) an **API token is auto-generated** and required
for every `/api/*` route — it is printed in the dashboard URL (`?token=…`) and
the frontend sends it automatically. Set your own with `--auth-token` or
`GHOSTEYE_TOKEN`. Without the token, `/api/*` returns `401`.

Every capability is reachable from the dashboard — no need to drop to the CLI.
Alongside the scan and exports, one-click panels cover **Exploit intel**
(CVE → public-exploit correlation), **Risk** (prioritised, context-scored),
**Compliance** (OWASP Top-10 coverage bars), an on-demand **Screenshots** sweep
(target + discovered subdomains, merged into the gallery), a **📈 Trend** panel,
plus **Intelligence**, Inventory, Rollup, History/Diff, Compare and Schedules.
Reports download straight from the bar (HTML, **Exec**, **Intel**, dashboard,
JSON, Markdown, SARIF, CSV, Prometheus).

**📈 Intelligence trend** — every dashboard scan is saved automatically, so the
Trend panel shows how the **attack surface itself evolves** across the history:
a multi-line chart of risk / assets / entities / subdomains over time, the
first→latest deltas, an overall direction (improving / worsening / stable), and
per-scan **knowledge-graph churn** — exactly which subdomains, IPs, technologies
or leaks **appeared or disappeared** between scans. Re-correlated per scan,
rule-based, offline.

REST endpoints per job: `/api/job/<id>/{score,compliance,exploits,risk,intel,inventory,rollup,report,diff,search,ticket}`
plus **POST** `/api/job/<id>/screenshots` (visual-recon sweep),
`/api/trend?target=<t>` (intelligence trend), `/api/unified?targets=…` (merged
multi-target graph) and `/api/scope` (scope-guard editor).

**Compliance frameworks** (`/api/job/<id>/compliance?framework=`): `owasp_top10`,
`nist_csf`, `iso27001`, `pci_dss`, `soc2`.

**New detection modules** in this line: `jssecrets` (leaked keys in the site's
JavaScript), `sigscan` (a nuclei-style signature engine — bring your own
`GHOSTEYE_SIGNATURES` YAML/JSON rules), `iamexpose` (exposed cloud IAM /
credential files), `originhunt` (**reveal the real server IP behind a
CDN/WAF** — classifies A-records against published Cloudflare/Fastly/CloudFront/
Akamai ranges and pivots via SPF, MX and origin-revealing subdomains).

**Advisory layer** (folded into the `/intel` report): `remediation` (a concrete,
prioritised fix per finding), `asset_sensitivity` (hosts classified critical→low
by name), `management_brief` (a plain-language executive summary), plus
`anomaly_detection` (flag metrics that deviate from the historical baseline).
Dashboard **⚙ Tools** adds 🧠 AI summary (`/api/job/<id>/summary` — LLM when a
DeepSeek key is set, deterministic otherwise), 🩹 Remediation, 👔 Exec brief, and
`/api/job/<id>/ask?q=` for offline Q&A over the findings.

---

## Error log

Every module crash or failed lookup is appended to a persistent error log with a
timestamp, location, target and full traceback — nothing is silently lost.

```bash
python3 ghost_eye.py --errors            # view it
# location: $GHOSTEYE_ERRORLOG or ~/.ghosteye/errors.log
```

---

## Module catalogue

307 modules across 19 categories:

| Category | # | Examples |
|----------|---|----------|
| Web | 56 | headers, cors, csp, graphql, smuggle, protopollute, cspbypass, lfisurface |
| Network | 40 | nmap, portscan, sshaudit, quicdetect, wgdetect, osfp, ipmi |
| OSINT | 32 | subs, github, wayback, social, related, threatagg, favsimilar |
| SSL/TLS | 27 | cert, tlsgrade, ciphers, ctmonitor, mtls, zerortt |
| DNS | 25 | dns, dnssecchain, subtakeover, nsecwalk, nsmxtakeover |
| Cloud | 24 | s3enum, k8s, docker, metassrf, tfstate, gcpenum |
| Email | 17 | spf/dkim/dmarc, mtasts, mxfingerprint, dkimstrength |
| AI/LLM | 13 | aiapi, aikeyleak, vectordb, modelserve, promptinject |
| API Security | 10 | gqlaudit, jwt surfaces, idorsurface, wsaudit |
| Assets | 8 | subs, asn, favicon, jsendpoints, wayback |
| Auth & Session | 8 | oauthaudit, jwtaudit, samldetect, sessionaudit |
| Threat Intel | 7 | cve, exploitdb, rbl, ripestat, virustotal |
| Passive Intel | 7 | internetdb, geoip, urlscan, torexit |
| Privacy | 7 | gdpraudit, trackerinv, piiscan, ccpacheck |
| Supply Chain | 7 | npmscan, pipscan, sbomextract, depconfuse |
| IoT | 7 | upnpscan, rtspscan, coapscan, icsscan, snmpv3 |
| Exposure | 6 | vcs, backups, buckets, admin, dirlisting |
| Crypto | 5 | web3rpc, smartcontract, ipfsgw, ensscan |
| Mobile | 1 | mobileapp (APK/IPA static analysis) |

Run `python3 ghost_eye.py --list` for the full list with ids.

---

## Architecture / adding a module

Everything hangs off a tiny contract in `ghost_eye/core.py`: subclass `Module`,
set four attributes, implement `run()`, and decorate with `@register`. The CLI,
interactive menu, dashboard and recipes all build from the registry.

```python
from ..core import Module, Context, Result, register, clean_host

@register
class MyCheck(Module):
    id, name, category = "mycheck", "My new check", "Web"
    target_kind = "url"                 # domain | ip | url | host

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)          # always validate first
        except ValueError as e:
            return self.fail(target, str(e))
        r = ctx.session.get(f"https://{host}", timeout=ctx.timeout)
        return self.ok(host, {"server": r.headers.get("Server", "?")})
```

Add the file to `ghost_eye/modules/__init__.py`; it now appears in the menu, in
`--list`, and runs with `-m mycheck`. Module ids must be unique (a duplicate
raises at import). `clean_host()` strictly validates input, so user-supplied
targets never reach a subprocess or a crafted URL.

**Key helpers in `core`:** `clean_host` / `is_ip` / `is_domain` (validation),
`ensure_scheme`, `build_session` (configured `requests.Session`), `run_cmd`
(safe, no-shell subprocess that degrades to "" when a binary is missing),
`dns_resolver(ctx)` (shared dnspython resolver), `record_error` (persistent log).

---

## Testing & quality

```bash
pip install pytest && python3 -m pytest -q
```

- **Unit tests** — validators, inventory, rollup, deep-plan, workflow helpers.
- **All-module smoke test** — runs `run()` for every one of the 499 modules
  fully offline (network/DNS/sockets/subprocess stubbed) and asserts each
  returns a `Result` instead of crashing. This is the net that catches
  "module raises instead of failing gracefully" regressions.
- **Behavioural tests** — assert *correct* output: DER key-sizing against real
  RSA keys, exploit-intel verdict logic, deep-scan asset scoping, CI gate,
  attack score, notifications, executive-report structure, config round-trip.

- **Engine tests** — the shared `execute_module` / `run_scan` contract
  (crash → error Result + logged, non-Result coercion, order, parallelism, cancel).
- **Intelligence tests** — correlation, classifier precision (no false positives),
  organization profile, graph, screenshot pipeline, the **typed knowledge graph
  + entity correlation**, the **intelligence timeline**, and the **rule-based AI
  analyst** (asserting it composes a narrative offline, with no LLM).
- **Integration tests** — run the real `ghost_eye.py` as a subprocess against a
  local server and assert the JSON + intelligence HTML reports it produces.

**570 tests** pass in ~11s. A single **verification gate** runs the whole thing:

```bash
bash scripts/verify.sh     # compile · import · ruff · full tests · LIVE smoke
```

The live smoke actually starts the dashboard (checks the `401`→`200` auth gate)
and runs a real CLI report. CI (`.github/workflows/ci.yml`) runs the import
check, `compileall`, the full suite, the verification gate and advisory
ruff/mypy on Python 3.9/3.11/3.12.

---

## Performance

Measured with `scripts/benchmark.py` (synthetic modules, no network — isolates
the engine + result pipeline from remote latency), 100 targets × 15 modules =
1500 module-runs:

| scenario | runs/s | process RAM (peak RSS) |
|----------|-------:|-----------------------:|
| engine overhead, sequential (no I/O) | ~150,000 | ~25 MB |
| 20 ms simulated I/O, sequential | ~49 | ~26 MB |
| 20 ms simulated I/O, `parallel=3` | ~145 | ~26 MB |
| 20 ms simulated I/O, `parallel=10` | ~336 | ~26 MB |

Takeaways: orchestration overhead is negligible (~150k runs/s); thread-based
parallelism scales throughput ~3× at `parallel=3` and ~7× at `parallel=10`
**when there is real I/O latency to hide** (with zero latency, thread overhead
makes parallelism slower — as expected for a GIL-bound, I/O-oriented design);
and memory stays flat (~26 MB RSS holding all 1500 results). Real network scans
are dominated by remote latency, not by Ghost Eye.

```bash
python3 scripts/benchmark.py --targets 100 --modules 15 --latency-ms 20
```

---

## Configuration reference

`~/.ghosteye/config.ini` (override with `GHOSTEYE_CONFIG`):

```ini
[settings]
threads = 20
timeout = 15
user_agent =
proxy =
verify_tls = true

[api_keys]
virustotal =
abuseipdb =
deepseek =
```

Any setting is overridable via `GHOSTEYE_<OPTION>` environment variables
(e.g. `GHOSTEYE_THREADS=40`). Error-log path: `GHOSTEYE_ERRORLOG`.

---

## Project layout

```
ghost_eye/
  core.py          validators, Module/Result, session, run_cmd, error log
  config.py        settings + API-key management (env / OS keyring / 0600 file)
  engine.py        the single scan-execution path (used by CLI, dashboard, API)
  cli.py           argument parsing, scan orchestration, output
  webapp.py        browser dashboard + JSON API (token auth off-localhost)
  workflow.py      profiles, deep-plan, scoring, CI gate, notify, exploit-intel,
                   risk-intelligence, trend, module-report, screenshot sweep
  reporting.py     JSON/CSV/HTML/PDF exporters + SQLite history
  reporting_ext.py Markdown/SARIF/Prometheus/dashboard + executive report
  inventory.py     asset correlation, host rollup, deep-scan asset collection
  triage.py        acknowledgement store (mute known items from change-alerts)
  intelligence/    the Personal Cyber Intelligence Platform layer:
    correlation.py   assets, tech/cloud classification, email posture, certs
    entities.py      typed Knowledge Graph + smart entity correlation
    timeline.py      Intelligence Timeline (dated events + insights)
    analyst.py       rule-based AI analyst write-up (no LLM / no external API)
    graph.py         attack-surface + knowledge-graph SVG renderers
  modules/         ~49 files, 324 self-registering Module subclasses
  web_static/      the single-file dashboard (Hebrew/RTL + Intelligence panel)
tests/             unit + smoke + behavioural + engine + intelligence + integration
scripts/           verify.sh (release gate) + benchmark.py
.github/workflows/ CI (runs the verification gate)
```

---

## Credits & license

Rewrite of [BullsEye0/Ghost-Eye](https://github.com/BullsEye0) by
Jolanda de Koff. MIT licensed. **Use responsibly and legally** — authorised
security testing only.
