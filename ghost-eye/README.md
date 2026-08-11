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

- **553 modules** across 19 categories
- Everything is **reconnaissance / detection only** — no exploitation, payloads,
  brute-forcing, or DoS
- Loads with **zero third-party dependencies** installed (each module lazily
  imports what it needs and degrades gracefully)
- **1465 automated tests**, CI on Python 3.9 / 3.11 / 3.12. 553 of those are the
  per-module smoke test (one assertion each: "returns a `Result`, never
  raises"); the other 739 are behavioural — see
  [Testing & quality](#testing--quality)
- **`--check-health`** probes modules against known-good targets to report which
  actually still work today — catching *silent failure*, the way a module keeps
  returning stale output long after its source changed

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
- [Trust layer — confidence, provenance & OPSEC](#trust-layer--confidence-provenance--opsec)
- [CDN/WAF filtering — finding the real IPs](#cdnwaf-filtering--finding-the-real-ips)
- [Infrastructure attribution](#infrastructure-attribution)
- [Corpus baseline — what is unusual here](#corpus-baseline--what-is-unusual-here)
- [Analyst verdicts — tell it once](#analyst-verdicts--tell-it-once)
- [Fix order — what to do first](#fix-order--what-to-do-first)
- [CSP as an asset source](#csp-as-an-asset-source)
- [Port scanning](#port-scanning)
- [Reporting](#reporting)
- [CI/CD security gate](#cicd-security-gate)
- [Notifications](#notifications)
- [Deep scan & asset inventory](#deep-scan--asset-inventory)
- [API keys](#api-keys)
- [Web dashboard](#web-dashboard)
- [Ownership, audit and email](#ownership-audit-and-email)
- [Telegram bot](#telegram-bot)
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
| `-t, --target <t>` | single target — `example.com`, `1.2.3.4`, `example.com:8080`, `http://example.com:8080/path`. A scheme and a port are honoured (an https attempt on a port that isn't running TLS falls back to http; a *certificate* error never does) |
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
| `--filter-cdn` | classify every IP found: CDN/WAF edge vs cloud vs candidate origin |
| `--attribute` | infrastructure attribution: cluster hosts into operator estates with weighted evidence |
| `--anomalies` | score the scan against every host you have scanned before — report only what is unusual |
| `--mark <id>:<verdict>` | rule on a finding: `false_positive`, `accepted_risk`, `confirmed` — applied to every later scan |
| `--unmark <id>`, `--verdicts` | drop a ruling / list every standing verdict |
| `--baseline-learn` | teach the corpus baseline from this scan (scoring always runs first) |
| `--inventory`, `--rollup` | asset inventory / per-host rollup |
| `--exploit-intel` | check every discovered CVE against the public exploit DBs |
| `--fix-order` | rank every CVE by exploitation pressure × observed reachability — what to fix first |
| `--csp-assets` | mine the target's Content-Security-Policy for hosts and report the ones enumeration missed |
| `--telegram-bot` | run the Telegram bot — drive scans from your phone |
| `--telegram-token`, `--telegram-allow` | bot token, and the chat ids permitted to command it (**empty = nobody**) |
| `--ports <spec>` | ports for `portscan`: `80,443`, `1-1024`, `top100`, `all`, or a combination |
| `--scan-retries <n>` | extra probes before calling a silent port filtered (default 1) |
| `--scan-rate <n>` | max connection attempts/second — pacing is more *accurate*, not just politer |
| `--scan-all-addresses` | scan every resolved address, not just the first (IPv4 vs IPv6) |
| `--ci`, `--fail-on <sev>` | CI mode: non-zero exit if findings breach the severity gate |
| `--siem <url>` | push results to Elasticsearch / Splunk / webhook (TLS verified; `--siem-insecure` to opt out for a lab collector) |
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
| `--investigate <seed>` | entity investigation of a username / e-mail: canary-checked profiles + identity + OPSEC dossier |
| `--passive-only` | run only passive modules (no traffic to the target) |
| `--opsec` | OPSEC audit: report which third parties the scan disclosed the target to |
| `--opsec-strict` | OPSEC enforce: contact **only** the target, refuse every third-party request |
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
| `--check-health [ids\|cat]` | probe modules against known-good targets; report which actually work today (network) |

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

**It self-tests.** This is the highest-stakes module in the tool — if one of its
sources breaks, it reports a weaponised CVE as "no exploit available", the most
dangerous false negative a security tool can produce, and nothing would notice.
So it carries a health self-test: `--check-health exploitdb` probes it against
CVEs known to be weaponised and on CISA KEV (Log4Shell, EternalBlue, Heartbleed,
ProxyLogon, Struts) and **fails if any comes back clean** — telling you the
zero-day intelligence is degraded instead of letting you trust a false "nothing
found". (It tolerates a single source hiccup; a total miss is broken.)

### Emerging / freshly-disclosed vulnerabilities (`freshvulns`)

`exploitdb` above queries databases of *known* vulnerabilities. A true zero-day
— unknown to everyone — is by definition in **no** database, and finding one is
vulnerability research (fuzzing, code/binary analysis), which a passive recon
tool doesn't and shouldn't do. `freshvulns` is the achievable, defensive
counterpart: **early warning for vulnerabilities disclosed so recently the
settled databases (NVD) haven't caught up** — the window where you're exposed
but the CVE hasn't propagated.

```bash
python3 ghost_eye.py -t example.com -m freshvulns   # fresh disclosures, last ~21d
```

It reads sources that lead NVD by days-to-weeks — **GitHub Security Advisories**
(often published first), **CISA KEV recent additions** (a CVE added this
fortnight is being exploited *right now*, whatever its age), and the **newest
Nuclei detection templates** (the community ships a template within hours of a
vuln being weaponised, routinely before the CVE record settles) — and
**cross-references them against the products the target advertises**, so the
headline is "a vuln just dropped for the nginx you run", not a firehose.

A CVE that picked up *both* a KEV listing and a fresh detection template inside
the window is reported separately as `exploited_and_detectable` — being
exploited in the wild and trivially scannable is the sharpest signal this
module can produce. Window is `--config` `fresh_days` (default 21). This is
fresh *known* intelligence, explicitly **not** literal zero-day discovery.

---

## Trust layer — confidence, provenance & OPSEC

A recon tool's value is trust in its output, so Ghost Eye is explicit about
**how sure** a finding is and **what it cost you** to obtain it.

**Confidence + provenance on every finding.** Each scored finding is tagged —
with no change to the modules themselves — by how it was obtained:

| provenance | meaning | default confidence |
|------------|---------|--------------------|
| `direct` | we contacted the asset (headers, cert, DNS, ports) | high |
| `third_party` | a service reported it (reputation feed, Wayback, GitHub) | medium |
| `heuristic` | a keyword/pattern matched, unverified (`*surface`, `*indicators`) | low |

Findings sort by severity **then** confidence, so a confirmed critical outranks
a heuristic one. A module can override (`data["_confidence"]`,
`data["_provenance"]`), and the data-driven OSINT modules carry per-hit
confidence from their canary check. Reports include a confidence roll-up
(`verified_fraction`, counts by level); the dashboard's findings view exposes it
at `GET /api/job/<id>/findings`.

**OPSEC leak-awareness.** An OSINT scan hands the target's name to many third
parties (Gravatar, ip-api, DoH resolvers, threat feeds) — each then knows what
you're investigating. `--opsec` reports exactly who saw the target:

```bash
python3 ghost_eye.py -t example.com -m dns,geoip --opsec
#   OPSEC — low
#     third parties that saw the target: 2
#       cloudflare-dns.com: 9 request(s)
#       dns.google: 9 request(s)

# refuse to touch anything but the target — nothing leaks to third parties:
python3 ghost_eye.py -t example.com --all --opsec-strict
```

In the dashboard: `GET /api/job/<id>/opsec`. Pair `--opsec-strict` with `--tor`
to hide your source IP as well.

**Source-registry health.** The data-driven engine can audit its own sources:
`sourcehealth` sends a random canary to every site in the username registry and
flags the ones that answer `200` for *anyone* (silently broken / false-positive
prone) so you know which sources to trust before acting on a sweep.

```bash
python3 ghost_eye.py -m sourcehealth -t x
```

---

## CDN/WAF filtering — finding the real IPs

Most addresses a scan returns are **not the target's servers**. They are
Cloudflare/Akamai/Fastly edge nodes that thousands of unrelated sites also
answer from. Counting them as the target's assets inflates the inventory,
poisons attribution, and buries the handful of addresses that actually matter.

Every IP is classified into one of four kinds:

| kind | meaning |
|------|---------|
| `cdn` | a published CDN/WAF edge range (Cloudflare, Akamai, Fastly, Imperva, Sucuri, …) |
| `cloud` | a known hosting provider (AWS/GCP/Azure/DO/Hetzner/…) — context, **not** a reason to discard |
| `private` | RFC1918, loopback, link-local, and the RFC5737 documentation ranges |
| `origin` | none of the above — a candidate **real** server |

```bash
# one target: is it fronted, and what is the real IP?
python3 ghost_eye.py -t example.com -m cdnfilter

# filter every IP a whole scan produced
python3 ghost_eye.py -t example.com --all --filter-cdn
# dashboard: GET /api/job/<id>/ipfilter
```

```
IP filter — 5 address(es), 2 origin candidate(s)
[+] origin candidates (outside every known CDN/WAF range):
    93.184.216.34: candidate origin
    91.198.174.192: candidate origin
    Cloudflare edge (filtered out): 2 IP(s): 104.16.1.1, 172.67.9.9
```

The classification is applied in three places, so edge noise is subtracted
everywhere and not just in one report:

- **`cdnfilter` module** — resolves a target and reports `behind_cdn`,
  the provider, and whether the origin is exposed or `FULLY FRONTED`.
- **Asset inventory** — gains `origin_ips`, `cdn_ips` and `cdn_providers`, so
  the asset count reflects the target's own infrastructure.
- **Attribution** — a shared CDN address is demoted to near-zero evidence.
  Two unrelated sites answering from the same Cloudflare node are *not*
  the same operator, and the engine will no longer say they are.

Ranges are bundled (so classification works fully offline) and can be updated
from the providers' own published lists with `netclass.refresh_ranges()`. A
failed or suspiciously short refresh is rejected rather than silently replacing
the bundled ranges. An address outside every known range is a **candidate**
origin, not proof — until it is *verified*.

### Origin verification — candidate → proven

Finding a candidate is the easy half. The `originhunt` module now does the half
that matters: it **asks each candidate directly for the target's site** — an
HTTP request to the IP carrying `Host: target` — and compares the response with
what the CDN serves.

```bash
python3 ghost_eye.py -t example.com -m originhunt
# dashboard: POST /api/verify-origin {host, candidates:[ip,...]}
```

- If the candidate returns the **same page**, it is hosting that site — that is
  the origin, **confirmed**, and the CDN/WAF can be bypassed by talking to it
  directly (a real finding: restrict the origin to accept only CDN traffic).
- If it returns a default page, someone else's site, or an error, it is
  **rejected**.

Comparison is deliberately fuzzy — pages carry CSRF tokens, timestamps and
rotating content, so those are stripped before scoring body similarity, title
and `Server` header together. A single leftover token can't reject a real
origin, and a shared "It works!" page can't confirm a fake one. Candidate IPs
come from `originhunt`'s passive channels (origin-revealing subdomains, SPF, MX)
and are now classified against all 14 CDN providers — fixing a bug where an
Imperva or Sucuri edge address was reported as a true origin.

This step **does contact the candidate address** (unlike the rest of Ghost
Eye's passive OSINT), so it runs only when you select `originhunt`. Authorised
use only.

---

## Infrastructure attribution

*Which of these assets are run by the same operator?*

Naive tooling answers that with "they share a nameserver" — and is almost
always wrong, because a million sites share Cloudflare's. What makes shared
evidence meaningful is its **selectivity**: how few things in the world carry
that exact value.

```bash
python3 ghost_eye.py -t example.com --deep --attribute
# dashboard: GET /api/job/<id>/attribution
```

The engine works in four steps:

1. **Extraction** — pulls pivotable identifiers out of whatever the 550 modules
   produced (analytics/tag IDs, certificate serials and public-key hashes,
   favicon hashes, JARM/JA3, MX/NS sets, ASN, S3 buckets…), reading the
   *flattened* result data so it works regardless of which module emitted a
   value or what it called the field.
2. **Selectivity weighting** — every evidence *type* has a prior (a shared
   certificate serial is near-proof; a shared ASN is nearly meaningless), and
   every *value* is then re-weighted by its inverse frequency in the observed
   corpus. A value present on every host is driven to zero automatically, so
   shared-infrastructure noise is demoted **by the data**, not by a hard-coded
   blocklist that could never be complete.
3. **Fusion** — independent evidence is combined with noisy-OR
   (`P = 1 - Π(1 - wᵢ)`), so several weak signals can accumulate while no
   single weak signal can carry a link alone.
4. **Clustering** — hosts linked above the confidence threshold are grouped
   into *estates* by union-find.

A worked example — six hosts, all behind the same CDN, ASN and CA, where only
two share an identity:

```
estate: ['x.com', 'y.com']  confidence 0.950
  x.com <-> y.com  0.950   cert_serial 0.769 · ga_id 0.754 ·
                           asn 0.080 · cert_org 0.035 · ns_set 0.016
  a.com <-> b.com  0.126   asn 0.080 · cert_org 0.035 · ns_set 0.016   (rejected)
```

The shared Cloudflare nameservers, Let's Encrypt issuer and hosting ASN
contribute almost nothing; the shared certificate and analytics property carry
the link. **Every link is explainable** — the report lists each shared value,
its weight and how many hosts carry it.

Correlation only, no scanning. Attribution is evidence, not proof: verify
before attributing ownership to a real party.

---

## Corpus baseline — what is unusual here

A 552-module scan returns thousands of fields, and almost all of them are what
*every* host looks like: an nginx `Server` header, a Let's Encrypt issuer, port
443 open. Reading that output means already knowing what normal is — and the
tool never tells you, so the one genuinely odd field sits in the same flat list
as the four hundred that are not.

`--anomalies` learns normal from **every host you have ever scanned** and
reports only what this host does differently.

```bash
# teach the baseline while you scan (scoring always runs before learning)
python3 ghost_eye.py -T targets.txt -p perimeter --baseline-learn --db estate.db

# then ask what is unusual about one host
python3 ghost_eye.py -t suspect.example -p perimeter --anomalies --db estate.db
```

```
== Anomalies — 6 unusual value(s) vs a corpus of 74 host(s) ==
  [ONLY THIS HOST]  headers.x-powered-by = JBoss-EAP/7
  [2/74 hosts]      tls.issuer = Internal Corp CA
  [1/74 hosts]      web.exposed_path = /jmx-console
```

This is the mirror image of [infrastructure attribution](#infrastructure-attribution).
There, a rare shared value is strong evidence two hosts share an operator; here,
a rare value is a reason to look. Both rest on the same measurement, so both
inherit the same correction: **frequency over a handful of hosts is not
knowledge**. Below 8 hosts the engine says so instead of inventing confident
numbers.

Two guards keep it from becoming a firehose:

- **Identifier suppression.** A field whose distinct-value count tracks its host
  count is an identifier, not an observation — every host has its own IP,
  certificate serial and response time, so without this every host would be
  maximally "anomalous" in those fields forever. Fields above 0.9
  distinct-values-per-host are dropped automatically, so there is no
  hand-maintained blocklist to keep current.
- **Idempotent learning.** Observations are keyed `(host, field, value)`, so
  re-scanning one host ten times does not make its values look ten times more
  normal — and a host is excluded from its own prevalence, so it can never
  teach the baseline a value and then hide behind it.

Dashboard: `GET /api/job/<id>/anomalies` (read-only — opening the panel never
learns, so it cannot quietly normalise the host you are looking at).


---

## Analyst verdicts — tell it once

Every recon tool produces findings that are not findings: a header flagged as
sensitive that is deliberate, an "exposed" path that is a public API, a CVE that
does not apply to this build. Without a way to say so, the same false positive
costs attention on every scan — and a list people learn to skim is how a real
finding gets missed.

Rule on it once and the ruling is applied from then on:

```bash
python3 ghost_eye.py -t example.com -p quick --risk --db estate.db
#   [medium] 7f2a91c4be03  headers: x-powered-by = JBoss-EAP/7

python3 ghost_eye.py --db estate.db \
    --mark 7f2a91c4be03:false_positive --mark-reason "deliberate, behind SSO"

python3 ghost_eye.py --db estate.db --verdicts       # review what you have ruled
python3 ghost_eye.py --db estate.db --unmark 7f2a91c4be03
```

Verdicts are `false_positive`, `accepted_risk` (both withhold the finding) and
`confirmed` (labels it, never hides it).

The whole design turns on one hazard: **a suppression that outlives the thing it
was about is worse than the noise it removed.** Mark `server = nginx/1.18` as a
false positive, and two years later the host runs a different build with a real
problem in the same field — a naive suppression list hides it silently, forever.
Three rules prevent that, each pinned by a test:

- **The value is part of the identity.** A verdict fingerprints
  `scope + module + field + value`, so any change to the value is a new finding
  that comes back for judgement. You ruled on what you saw, not on that field
  for all time.
- **Verdicts expire** (`--mark-ttl`, default 180 days). An expired ruling stops
  suppressing and is reported as expired rather than silently dropped.
- **Suppression is never invisible.** Every run prints how many findings your
  verdicts withheld and under which rulings, and the withheld findings are kept,
  not deleted. A count you can see is the difference between a filter and a
  blindfold.

Scope defaults to the host the finding came from, so ruling on one box does not
quietly speak for the estate; `--mark-scope '*'` is available as an explicit,
recorded choice.

Dashboard: `POST /api/verdict` `{id, verdict, reason?, scope?, ttl_days?}`.


---

## Fix order — what to do first

A scan that reports "47 critical" has not finished the job. CVSS scores a
vulnerability in the abstract — how bad it *would* be, for anyone, if reachable
and if exploited. It says nothing about whether anyone is exploiting it, or
whether your instance can be reached at all, so sorting by CVSS produces a list
nobody can act on and the real emergency sits at number 31.

```bash
python3 ghost_eye.py -t example.com -p perimeter --fix-order
```

```
== Fix order — 41 CVE(s), 2 exploited AND reachable ==
ACT NOW  CVE-2021-44228  (confirmed exposed) — on CISA KEV — exploited in the wild
 1. CVE-2021-44228   priority 94.0  [confirmed exposed, EPSS 97%, CVSS 10.0]
      why  on CISA KEV — exploited in the wild
      on   api.example.com
 2. CVE-2023-38545   priority 61.2  [exposed behind CDN/WAF, EPSS 71%, CVSS 9.8]
…
 31 CVE(s) are on hosts this scan never observed exposed — ranked lower, NOT ruled safe
```

Ranking combines three things instead of one:

- **Is it being exploited?** CISA KEV means *in the wild, right now*. EPSS
  (FIRST.org) gives the probability of exploitation within 30 days — an
  empirical forecast, not a severity opinion. EPSS can raise a quiet CVE, but a
  low forecast never lowers a KEV one: "not predicted" is not evidence against
  something already in use.
- **Can it be reached?** Derived from the scan itself — a live response or an
  open port is confirmation, a CDN/WAF range means reachable-but-filtered, a
  private address means not reachable from outside.
- **How bad if it lands?** CVSS, kept — but as one term among three rather than
  the whole ranking. The result: a reachable, actively-exploited *medium*
  outranks an unreachable critical nobody is touching.

**On the honesty of reachability.** The tempting move is to score an unreachable
finding to zero and drop it. Ghost Eye does not: a scan that did not observe a
service exposed has not *established* that it is unreachable — it may sit behind
auth, on an odd port, or on a host the scan never touched. Unobserved exposure
lowers priority, is labelled `not observed exposed`, and is counted in the
output. It never removes a finding and never claims safety.

EPSS lookups are batched (one request per 100 CVEs rather than one per CVE), so
a 240-CVE estate costs three requests instead of 240.


---

## CSP as an asset source

A Content-Security-Policy is a list, written by the target themselves, of every
host their pages are allowed to talk to. Nobody publishes their infrastructure
more accurately: it is maintained by the people who know, it breaks the site
when it is wrong, and it costs one HTTP request to read. Subdomain
brute-forcing guesses — a CSP simply tells you.

```bash
python3 ghost_eye.py -t example.com -p perimeter --csp-assets
```

```
== CSP assets — 4 host(s) not found by any other module ==
NEW  checkout.example.com
NEW  csp-collector.internal.example.com
  connect-src            api.example.com, metrics.vendor.io
      =                  an API/websocket the front end talks to
  form-action            checkout.example.com
      =                  where this site is allowed to POST forms — credentials go here
  frame-ancestors        partner.example.net
  report sinks           https://csp-collector.internal.example.com/report
  staged in Report-Only  next-gen.example.com
```

Most tooling treats CSP purely as a hardening check ("is `unsafe-inline` set?")
and throws the host list away. Ghost Eye mines it as intelligence:

- **Per-directive meaning is kept.** A host in `connect-src` is an API the front
  end calls; in `form-action` it is where credentials may be posted; in
  `frame-ancestors` it is a named partner. Flattening them into one "domains"
  list discards the part that says *what each host is*.
- **Report sinks.** `report-uri` / `report-to` name whoever collects violation
  reports — very often an internal or vendor hostname that appears nowhere in
  public DNS.
- **Report-Only is a preview.** Sites stage the *next* policy there before
  enforcing it, so it routinely names infrastructure that is not live yet. A
  weakness that is only staged is not reported as live.
- **The delta is the finding.** Cross-referenced against the hosts the scan
  already discovered, the headline becomes "CSP names 4 hosts your enumeration
  missed".

Hardening findings come along for free (`unsafe-inline`, `unsafe-eval`, `data:`,
wildcard and `http:` sources), each with the reason it matters.

**On registrable domains.** Splitting a hostname on the last two labels reduces
`shop.example.co.uk` to `co.uk`, which files every unrelated `.co.uk` host in
the policy as the target's own infrastructure — a bug the older `cspdomains`
module had, now fixed and pinned by a test. `cspmap.registrable_domain()`
carries a compact multi-label suffix table (including vendor suffixes like
`github.io`, so two tenants of one platform are never treated as the same org).


---

## Port scanning

```bash
python3 ghost_eye.py -t example.com -m portscan                      # top 100
python3 ghost_eye.py -t example.com -m portscan --ports 1-1024
python3 ghost_eye.py -t example.com -m portscan --ports all --scan-rate 200
python3 ghost_eye.py -t example.com -m portscan --scan-all-addresses  # v4 + v6
```

Connect-scan only: a full TCP handshake, closed immediately. No SYN/stealth
scanning (that needs root and is a different legal posture), no UDP, and
nothing sent beyond the minimal nudge needed to make a service announce
itself — a port is reported open, never opened further.

Three things separate a scan you can act on from one that merely looks
plausible, and all three are pinned by tests:

**`closed` is not `filtered`.** A connection refused (RST) *proves* the host is
up and nothing is listening there. A timeout proves nothing at all — a firewall
dropped it, the packet was lost, or you are being rate-limited. Collapsing both
into "not open" throws away the most useful thing a scan produces: whether
there is a firewall, and which ports it guards.

```
== TCP port scan — 93.184.216.34 ==
  scanned            1024
  open_count         3
  closed_count       1019
  filtered_count     2
  open_ports         22/ssh: SSH-2.0-OpenSSH_9.6p1
                     443/https: TLS TLSv1.3 / TLS_AES_256_GCM_SHA384
  firewall_posture   2 port(s) silently dropped while 1019 were refused —
                     a firewall is selectively filtering
```

**One dropped packet is not a firewall.** A scanner that concludes "filtered"
from a single timeout invents firewalls on any lossy path and gives different
answers run to run. Every non-answer is retried (`--scan-retries`) before it is
believed, and each verdict reports how many probes it rests on. A refusal is
conclusive on the first try and is never retried.

**Scanning a CDN edge is not scanning the target.** If the name resolves into
Cloudflare, a "port scan of example.com" is a port scan of Cloudflare, and the
open ports belong to someone else entirely. That is stated as a `WARNING` with
`scanned_the_target: false` rather than presented as the target's attack
surface — find the origin first (`--filter-cdn`, `originhunt`) and scan that.

A port spec that cannot be honoured (`443-80`, `70000`, `top0`) is an error,
not an empty scan — scanning nothing and reporting "no open ports" reads
exactly like a clean host.

`--scan-rate` is about accuracy as much as courtesy: a host that rate-limits
you turns real open ports into timeouts, which the scanner then has to report
as filtered. Slower is more correct.


---

## Ownership, audit and email

Three things a single-analyst tool can skip and a team cannot.

**Ownership.** A finding nobody owns is a finding nobody fixes. Each one can
carry an assignee and a workflow status, keyed on the same fingerprint a
verdict uses — so ownership survives a re-scan instead of being lost with the
job. Status is a deliberately closed set (`open`, `investigating`,
`remediating`, `resolved`, `wont_fix`) and anything outside it is **refused**,
not stored: a typo'd status silently drops the finding out of every filtered
view, which is worse than no status at all.

**Audit.** Ghost Eye can start scans, rewrite scope, delete stored findings and
hold API keys, so *"who deleted last quarter's scans?"* has to have an answer
that is not a shrug. Every state-changing call is recorded — actor, action,
target, time, and whether it succeeded — by a wrapper around the POST router
rather than by a line per endpoint, so a new endpoint is audited whether or not
anyone remembers. The log is append-only from the API's point of view: there is
a route that reads it and none that edits or deletes it, and a test asserts
`AuditLog` has no `delete`/`edit`/`clear` method at all.

The Audit workspace also answers *"is anyone else in here right now?"* — the
question worth asking before you delete a quarter of stored scans. It is
derived from recent actions rather than from a presence protocol, and says so:
the console is reached with a shared token, so there is no user identity to
build a real one on. What can be stated honestly is who acted and when, and an
actor whose last action was four seconds ago is someone you are sharing the
console with.

Nothing secret reaches the log. A redaction pass runs over every free-text
detail before it is written, and it is deliberately over-eager — a labelled
credential loses **everything after the label** (`Authorization: Basic <blob>`
would otherwise keep the blob and lose only the word "Basic"), bearer tokens
are stripped, and any bare 28-plus-character key-shaped blob is replaced. A
shortened audit entry beats a leaked one.

**Email.** `--notify` and the webhook sink cover the push case; this is for the
teams who read email and will not stand up a receiver just to be told a
certificate expires in nine days. It is configured from the console under
**Reports → Email this report**.

- **TLS by default.** STARTTLS unless the port is 465 (implicit TLS), and
  turning it off is reported as a problem unless the host is loopback. A report
  is a list of your own weaknesses — exactly the message you do not want on the
  wire in clear.
- **The password is write-only.** It is stored with the API keys (OS keyring
  when available, otherwise the 0600 config file, encrypted at rest when
  `GHOSTEYE_SECRET` is set), never returned by the API, and absent from the
  mailer's `__repr__` so it cannot be echoed into a log or an audit entry by
  accident.
- **Every configuration problem is reported at once**, not one per round trip.
  One round trip per mistake is how a settings page gets abandoned.
- **Nothing sends itself.** There is no "email me every scan" switch: a message
  goes out because an operator pressed a button. A tool that mails findings
  unprompted is a tool that eventually mails them to the wrong list.

---

## Telegram bot

`--notify` *pushes* a summary to a Telegram webhook. This is the other
direction: a bot that takes commands, runs real scans, and answers with the
result. Start a scan on the train, read the findings when it lands.

```bash
python3 ghost_eye.py --telegram-bot \
    --telegram-token 123456:ABC-DEF… \
    --telegram-allow 987654321 \
    --scope scope.txt --db estate.db
```

```
/scan example.com quick     run a profile against a target
/ports example.com 1-1024   a port sweep on its own
/status                     how the running scan is doing
/findings                   the last scan's findings, worst first
/fixorder                   what to fix first (KEV × reachability)
/stop  /scans  /profiles  /whoami  /help
```

It can also be started, stopped and allow-listed from the console's **Settings**
workspace — the token is stored by the backend and never returned to the browser.

**Read this before enabling it.** A bot that runs scans is a remote-command
channel into the machine hosting it. Two rules are enforced in code rather than
offered as advice:

- **Default deny.** Only allow-listed chat ids may command the bot, and an empty
  allow-list authorises **nobody** — not everybody. Anyone who obtained the token
  could otherwise point your host at any target on the internet, turning your
  machine into someone else's scanning proxy. Message the bot `/whoami` (answered
  for anyone, since you need your own id to allow-list yourself) and pass what it
  reports. Unauthorised chats that try are recorded and shown to you.
- **Scope still applies.** Every target goes through the same `Scope` guard the
  CLI and dashboard use. Remote convenience does not widen what you may scan.

Also: one scan at a time, a cooldown between scan commands, replies truncated to
Telegram's 4096-character limit rather than silently rejected, and the token is
never echoed into a chat. Long-polling — no inbound port, no webhook to expose,
works from behind NAT.


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

Almost every module works with **no key**. Some optional ones can use one:
**VirusTotal** and **AbuseIPDB** (threat intel), and an **LLM-provider account
recon** set that audits a key you own — DeepSeek, OpenAI, Anthropic, Google
Gemini, Groq, Mistral, OpenRouter, Cohere, Together, Perplexity, xAI and
Replicate. Ghost Eye asks for the key a module needs and remembers it:

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
- **Env vars** — `VT_API_KEY`, `ABUSEIPDB_API_KEY`, `DEEPSEEK_API_KEY`,
  `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `GROQ_API_KEY`,
  `MISTRAL_API_KEY`, `OPENROUTER_API_KEY`, `COHERE_API_KEY`, `TOGETHER_API_KEY`,
  `PERPLEXITY_API_KEY`, `XAI_API_KEY`, `REPLICATE_API_TOKEN` always win.

Keys stay local and are never committed. Use `--no-keys` for unattended runs.

### LLM-provider account recon

The `*acct` modules (`openaiacct`, `anthropicacct`, `geminiacct`, `groqacct`,
`mistralacct`, `openrouteracct`, `cohereacct`, `togetheracct`, `pplxacct`,
`xaiacct`, `replicateacct`) answer the questions a responder asks about a key —
one they found in a leak, or one they own:

- is it still **live**, or was it revoked? (`200` vs `401`)
- which **models** and how much **quota / credit** does it unlock?
- what **rate-limit / org** context does the provider report back?

They are **read-only** — they list models and read account metadata; no
generation is billed. Run them only against keys you own or are authorised to
assess.

```bash
# audit a single provider key
OPENAI_API_KEY=sk-… python3 ghost_eye.py -t example.com -m openaiacct

# every provider you have a key for
python3 ghost_eye.py -t example.com --category AI/LLM
```

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
python3 ghost_eye_web.py --open                 # console at /
python3 ghost_eye_web.py --auth-token SECRET    # require a token
```

The console is the home page and reaches **every one of the 51 endpoints the
API serves** — a number worth stating precisely, because the previous console
reached 11 of them, so most of what Ghost Eye could do was invisible from a
browser and only usable from the CLI. A test measures the coverage on every
run, so the gap cannot silently reopen as the API grows.

A rail of 30 workspaces, a persistent command bar, one content region:

| Group | Workspaces |
|-------|-----------|
| Operate | **Scan** (selection, engine, port-scan, batch, baseline) · **Live results** · **Search** · **Search all scans** |
| Analyse | **Findings** · **Fix order** · **Anomalies** · **Risk model** · **Intelligence** · **Ask the scan** · **Exploit intel** |
| Assets | **Inventory** · **By host** · **Ports** · **CDN / origin** · **Verify origin** · **CSP assets** · **Attribution** · **Entity investigation** |
| Trust | **Verdicts** · **OPSEC** · **Compliance** · **Owners & status** |
| Manage | **History & trend** · **Portfolio** · **Reports** · **Schedules** · **Scope, rules & backup** · **Audit trail** · **Settings** |

Details that decide whether it is usable rather than merely complete:

- **Command palette** (`Ctrl`/`⌘ K`) — jump to any of the 30 workspaces, any of
  the 553 modules, or an action. 30 workspaces is more than a rail can make fast.
- **Keyboard triage**: `j`/`k` to move, `f`/`a`/`c` to rule, `x` to select,
  `Enter` for the evidence, `/` to search, `?` for the list. Two hundred findings
  is mouse work otherwise.
- **Bulk verdicts** — select twelve identical false positives and rule once, with
  an **Undo** toast; each ruling still locks onto its own value.
- **Evidence drawer** — the finding's provenance and *everything the producing
  module returned*, not just the flattened value.
- **Shareable deep links** — the workspace, the job and the filter state live in
  the URL, *and are read back on load*, so the view you are looking at can be
  sent to someone else and they land on it. If the link names a scan that is
  still running, the recipient's page keeps updating instead of freezing on
  whichever frame was current when they opened it.
- **Force-directed entity graph** with drag, wheel-zoom, highlight and
  neighbourhood focus. A ring layout says nothing about structure, which is the
  only reason to draw a graph. The simulation runs **in a Worker** — 180 steps
  of an O(n²) repulsion loop is ~950ms of frozen page at 400 nodes — and its
  result is cached on the node and link set, so highlighting a node reuses the
  positions instead of re-running the physics to draw the same picture in a
  different colour. The worker is built from `layoutBody.toString()`, so there
  is exactly one copy of the algorithm and the threaded and inline paths cannot
  drift apart; the Blob URL is same-origin, so the page still loads nothing
  remote.

  That it is a Worker at all was a measurement, not a preference. The findings
  filter costs **0.02ms** per pass while a structured clone of the same array
  costs **0.08ms** — moving *that* off the main thread would have made the
  console four times slower, so it stays where it is.
- **Several scans open at once.** Comparing two hosts otherwise means loading
  one, writing things down, and loading the other. Tabs hold the snapshots this
  session already has, so switching is free and costs no request, and any two
  can be diffed side by side. One scan shows no tab bar — a tab bar with one
  tab is chrome that teaches you nothing.
- **Scan presets** (selection + every option, named) and two different previews
  of what a run will cost. The **dry run** gives a worst-case bound from the
  timeout; the **estimate** asks the backend what these modules have actually
  taken *on this machine* — the engine records `elapsed_ms` per module, so with
  history the number is measured rather than guessed, and without it the console
  says so instead of inventing one. Both name the modules that spend a paid API
  quota, and both state plainly that nothing was sent.
- **Backend self-check** — which endpoints answer and how fast, from the browser.
- **Themes** (dark / light / high-contrast), **density**, and
  `prefers-reduced-motion` respected.

- **Findings are actionable in place.** Filter by severity or free text, then
  rule with FP / Accept / Confirm — a real dialog, with scope and expiry, not a
  browser `prompt()`. The count of withheld findings is always shown, because a
  filter you cannot see is a blindfold.
- **The live stream patches, it does not rebuild.** Re-rendering 553 rows every
  second destroys your scroll position and any text selection *while the scan is
  still running*; only modules whose output changed are touched.
- **Verdicts are optimistic, with a rollback.** The ruling paints immediately
  and reconciles after; a write the backend refuses is put back and said out
  loud. Waiting a round trip per finding makes triaging fifty of them feel
  broken — but an optimistic UI without a rollback is just one that lies when
  the network fails.
- **Re-run a single module.** A module that timed out should not cost you all
  553 again, and one whose answer you doubt is worth a cheap second opinion. The
  current results stay open until the new job returns, so you can compare the
  two rather than losing the one you doubted.
- **Search across every stored scan**, not just the one on screen — the question
  you actually have six months later is "where have I *ever* seen this IP?", and
  no per-scan search can answer it. If the string matches a scan's target but no
  finding value, it says that too, rather than "nothing matches".
- **Owners & status.** A finding nobody owns is a finding nobody fixes.
  Ownership is keyed on the same fingerprint a verdict uses, so it follows the
  finding into the next scan. Status is a closed set — free text turns into six
  spellings of "in progress" and none of them can be counted.
- **Audit trail.** Every state-changing call is recorded: who asked, what
  changed, when. The log is append-only — there is no endpoint that edits or
  removes an entry — and a redaction pass strips anything token-shaped before
  a detail string reaches disk.
- **Email delivery** alongside the webhook sink: STARTTLS by default, the
  password write-only (stored with the API keys, never returned to the page),
  and nothing that sends itself.
- **Batch scanning**: extra targets, one per line — the queue runs them in turn
  and stops with the scan.
- **Trend and compare**: sparklines per numeric series across a target's
  history, and an added/removed/changed diff between any two stored scans.
- **Switching language keeps your scan.** The reload restores the job and the
  workspace you were in.
- **Keyboard and screen readers**: skip-to-content, a visible focus ring, and an
  `aria-live` scan status.
- **Genuinely offline.** The last finished scan is kept in IndexedDB, so with
  the backend dead the console still opens on those findings — not on a page
  that can only list which modules exist. A persistent banner says what you are
  looking at and when it was captured, because "the backend is gone" is a
  condition and a toast that fades leaves you reading stale findings believing
  they are live.
- **Self-contained.** No CDN, no external fonts, no analytics — a recon console
  that phones out tells someone else which install is running, and breaks in an
  air-gapped environment. A test asserts the page loads nothing remote.
- **Installable PWA**, Hebrew/RTL throughout including body copy, and a slide-in
  rail with a tap-outside backdrop on phones.

The graph-first **OSINT view** remains at `/osint` and is linked from the rail —
a specialist lens on the same data, not a second dashboard.

### Dashboard security model


The dashboard drives scans and stores API keys, so the API is guarded even on
localhost. "Local" is not a trust boundary in a browser: any page you happen to
be visiting can POST to `127.0.0.1`, and a hostname an attacker controls can be
pointed at `127.0.0.1` to read the replies (DNS rebinding).

- **A token is always required for `/api/*`.** The startup banner prints the
  URL with `?token=…` in it — open that and the dashboard works as normal.
  Set your own with `--auth-token` or `GHOSTEYE_TOKEN`. Comparisons are
  constant-time; the token is never written to the access log.
- **Cross-origin writes are refused.** Any `POST`/`DELETE` carrying an `Origin`
  that isn't the dashboard's own gets a `403`. Requests with no `Origin` at all
  (curl, scripts, CI) are unaffected.
- **The `Host` header is checked** against the address the server bound to.
  Behind a reverse proxy, add your hostname with
  `GHOSTEYE_ALLOWED_HOSTS=recon.internal` (comma-separated; `*` disables the
  check).
- **Response headers**: a strict `Content-Security-Policy`
  (`frame-ancestors 'none'`, `connect-src 'self'`), `X-Frame-Options: DENY`,
  `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`.

Findings from a scanned host are attacker-influenced text, so everything the
dashboard renders is HTML-escaped (quotes included) and inline images must be
`data:image/…` URIs we produced ourselves.

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
optional API keys (VirusTotal / AbuseIPDB / DeepSeek + the LLM providers) and **save them** straight
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

553 modules across 19 categories:

| Category | # | Examples |
|----------|---|----------|
| OSINT | 243 | subs, github, wayback, usernamescan, emailfootprint, sourcehealth |
| Web | 56 | headers, cors, graphql, smuggle, protopollute, cspbypass, lfisurface |
| Network | 42 | nmap, portscan, sshaudit, quicdetect, wgdetect, osfp, ipmi |
| SSL/TLS | 27 | cert, tlsgrade, ciphers, ctmonitor, mtls, zerortt |
| DNS | 26 | dns, dnssecchain, subtakeover, nsecwalk, nsmxtakeover |
| Cloud | 25 | s3enum, k8s, docker, metassrf, tfstate, gcpenum |
| AI/LLM | 24 | aiapi, aikeyleak, modelserve, promptinject, openaiacct, anthropicacct |
| Threat Intel | 19 | cve, exploitdb, rbl, ripestat, virustotal, threatfox |
| Email | 17 | spf/dkim/dmarc, mtasts, mxfingerprint, dkimstrength, bimi |
| API Security | 10 | gqlaudit, idorsurface, wsaudit, restfuzz, webhookfind |
| Assets | 9 | subs, asn, favicon, jsendpoints, revip, screenshot |
| Auth & Session | 8 | oauthaudit, jwtaudit, samldetect, sessionaudit, mfacheck |
| Exposure | 8 | vcs, backups, buckets, admin, dirlisting, jssecrets |
| IoT | 7 | upnpscan, rtspscan, coapscan, icsscan, snmpv3 |
| Passive Intel | 7 | internetdb, geoip, urlscan, torexit, reputation |
| Privacy | 7 | gdpraudit, trackerinv, piiscan, ccpacheck, privacypol |
| Supply Chain | 7 | npmscan, pipscan, sbomextract, depconfuse, actionleak |
| Crypto | 5 | web3rpc, smartcontract, ipfsgw, ensscan, cryptoaddr |
| Mobile | 5 | mobileapp, applinks, assetlinks, appadstxt, deeplinks |

Run `python3 ghost_eye.py --list` for the full list with ids.

### Merged modules (retired ids still work)

A few modules turned out to query the *same source for the same purpose*. They
were merged into one, and the surviving module emits the union of both result
shapes so nothing was lost:

| retired id | now served by | why |
|------------|---------------|-----|
| `reverseip` | `revip` | same HackerTarget reverse-IP endpoint; the merged one also accepts a hostname, not just an IP |
| `urlscanio` | `urlscan` | same `urlscan.io` search; merged result keeps the scan metadata *and* the url/subdomain extraction |
| `pdnsanubis` | `anubisjldc` | identical AnubisDB request and parsing |
| `commoncrawl` | `commoncrawlmine` | the retired one queried a **hard-coded** `CC-MAIN-2024-10` index and silently went stale; the survivor resolves the newest crawl at run time |

**Nothing breaks.** `-m reverseip`, saved recipes and scan profiles keep working
— retired ids resolve through `core.ALIASES` to the surviving module. They no
longer appear in `--list`, and a genuinely unknown id is still an error. A
module declares what it replaced with `absorbed = ["old_id"]`; `register()`
refuses to absorb an id that still exists, and `get_module()` is the
alias-aware lookup (plain `REGISTRY[...]` does not follow aliases).

The newest additions: the **data-driven OSINT engine** (see below), **11
LLM-provider account-recon modules** (`*acct` — validate a key you own, list the
models and quota it unlocks) and **4 mobile association modules** (`applinks`,
`assetlinks`, `appadstxt`, `deeplinks` — the public app-association files that
map an org's mobile portfolio).

### Data-driven OSINT at scale

Most modules are one Python class per source. The **scale lever** is the
opposite: a *source is a row in a JSON registry*, so one module checks hundreds
— or, with an external dataset, thousands — of sites in a pass.

```bash
# username across the built-in ~140-site registry
python3 ghost_eye.py --username somehandle

# an email's public footprint (Gravatar/Libravatar, non-intrusive)
python3 ghost_eye.py --email someone@example.com

# point at a Sherlock or WhatsMyName dataset and search thousands of sites
GHOSTEYE_USERNAME_SITES=/path/to/sherlock/data.json \
    python3 ghost_eye.py -m usernamescan -t somehandle
```

- `usernamescan` — checks the username against the whole registry concurrently.
  Every "found" verdict is **re-checked against a random canary username** to
  drop sites that answer `200` for *any* name, and each hit carries a
  confidence (`high`/`medium`). The loader reads the native format **and** the
  community [Sherlock](https://github.com/sherlock-project/sherlock) `data.json`
  and [WhatsMyName](https://github.com/WebBreacher/WhatsMyName) schemas, so you
  can drop in their thousands of sites unchanged.
- `usernamevariants` — generates plausible handle variants (separator swaps,
  leetspeak, common suffixes) and checks the popular sites for each.
- `emailfootprint` — non-intrusive email OSINT by hash (Gravatar/Libravatar
  avatar + profile, self-declared linked accounts). No message is sent.

Discovered profiles/accounts flow into the **entity graph** as their own node
kind, so an investigation shows the *person* side (usernames, linked accounts)
next to the domain side. Ship your own registry at
`ghost_eye/data/username_sites.json` or override with `GHOSTEYE_USERNAME_SITES`.

### Entity investigation (the capstone)

`--investigate` ties the whole engine together into one person-focused report:
give it a username or an e-mail and it runs the right modules for that kind,
correlates the discovered profiles + linked accounts into an **identity graph**,
scores every finding's **confidence**, pivots a username's discovered e-mails
one hop further, and reports the **OPSEC** exposure the lookup itself created.

```bash
python3 ghost_eye.py --investigate somehandle            # prints a dossier
python3 ghost_eye.py --investigate someone@example.com -o dossier.md
```

It prints (and, with `-o file.md`, writes) an **entity dossier**: confirmed
accounts first (each with its confidence), linked identities, discovered
e-mails, a confidence roll-up, and the third parties the investigation
disclosed the seed to. In the dashboard: `POST /api/investigate {seed}`
(add `"dossier": true` for the Markdown). Reconnaissance / OSINT only —
authorised use.

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

### Health checks — catching silent failure

The offline test suite proves a module *returns a `Result`*; it can't prove the
module still *works*, because the sources behind 553 modules and 190 external
services change without warning. The worst failure isn't a crash — it's a
module that keeps returning stale output after its source moved (`commoncrawl`
queried a hard-coded crawl index and quietly went stale for months).

`--check-health` closes that gap. It probes modules against stable, known-good
targets over the real network and classifies each: **healthy** (ran, returned
data, and — if the module declares an `expect` shape — the shape matched),
**degraded** (ran but empty), **broken** (errored or *wrong-shaped output*),
**no_key** (needs an unconfigured API key), **skipped**.

```bash
python3 ghost_eye.py --check-health                 # all modules
python3 ghost_eye.py --check-health DNS             # one category
python3 ghost_eye.py --check-health cert,headers,dns  # specific ids
python3 ghost_eye.py --check-health -o health.json  # machine-readable
```

It is **network-bound and deliberately never part of CI** — the offline tests
cover the harness's classification logic with mock modules, not the live health
of the internet. It exits non-zero when anything is broken, so it can gate a
release check.

A module opts into real shape-checking by declaring `expect` (a list of keys a
healthy result must carry, or a predicate). This is what catches silent
staleness a "returns a Result" check never could — and it already paid off: on
its first live run it caught the `cert` module returning all-`None` certificate
fields (it disabled verification with `CERT_NONE`, which makes Python's
`getpeercert()` return an empty dict; now it parses the DER form and reports the
real certificate). That bug had been silent since the module was written.

- **Unit tests** — validators, inventory, rollup, deep-plan, workflow helpers.
- **All-module smoke test** — runs `run()` for every one of the 553 modules
  fully offline (network/DNS/sockets/subprocess stubbed) and asserts each
  returns a `Result` instead of crashing. This is the net that catches
  "module raises instead of failing gracefully" regressions. Note what it does
  *not* do: it makes one assertion per module and a module that returned
  `fail()` for everything would still pass it. Depth comes from the
  behavioural tests below, not from this count.
- **Hardening tests** (`tests/test_hardening.py`) — pin the security and
  correctness fixes: dashboard CSRF/DNS-rebinding/auth gates (against a real
  server on a real socket), target port+scheme parsing, the http-fallback
  downgrade guards, the JSON response cache, scope IPv6 handling, and unique
  module names.
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

**1465 tests** pass in ~20s. A single **verification gate** runs the whole thing:

```bash
bash scripts/verify.sh     # compile · import · ruff · full tests · LIVE smoke
```

The live smoke starts the real dashboard and checks the `401`→`200` auth gate,
that a cross-origin `POST` is refused, and that a forged `Host` header is
refused; then it runs a real CLI scan against a local site and asserts the
report contains an actual `200` finding — not merely that a file was written.
CI (`.github/workflows/ci.yml`) runs the import check, `compileall`, the full
suite, the verification gate and advisory ruff/mypy on Python 3.9/3.11/3.12.

### Hostile-response fuzzing

The smoke test feeds every module a *stubbed* network. A separate audit harness
feeds all 553 modules five kinds of **hostile-but-plausible** HTTP response —
empty body, binary garbage, `null` JSON, unexpected HTML, and `404` with no
headers — and fails on any `TypeError` / `AttributeError` / `KeyError` /
`IndexError` / `UnboundLocalError` raised from Ghost Eye's own frames. 2760
module-runs currently produce **zero** such crashes: a source that changes shape
degrades into an error `Result`, it never takes the scan down.

```bash
python3 scripts/audit_hostile_responses.py       # all five profiles, minutes
python3 scripts/audit_hostile_responses.py garbage
```

That harness found nothing, which is the point of running it. What the manual
audit behind v4.2.1 *did* find was a quieter class of bug — code that ran
cleanly while silently producing less than it should:

- four modules computed a `CRITICAL`/`HIGH` severity and returned without it,
  so the rating never reached the operator
- one malformed row in an imported Sherlock/WhatsMyName registry threw away the
  **entire** file, and the sweep reported "registry is empty"
- `sourcehealth` read `username_max = 0` as "audit nothing" where every other
  consumer reads it as "no cap"
- `refresh_ranges()` documented a refresh from "the providers" but could only
  parse Cloudflare's plaintext list, silently skipping every JSON publisher
- origin verification discarded the error explaining *why* a candidate failed

Each is pinned by a regression test in `tests/test_bugfixes.py`.

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
scripts/           verify.sh (release gate), benchmark.py,
                   audit_hostile_responses.py (hostile-response fuzz audit)
.github/workflows/ CI (runs the verification gate)
```

---

## Credits & license

Rewrite of [BullsEye0/Ghost-Eye](https://github.com/BullsEye0) by
Jolanda de Koff. MIT licensed. **Use responsibly and legally** — authorised
security testing only.
