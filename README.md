# 👁 Ghost Eye

**Modular reconnaissance / OSINT / exposure-detection toolkit** with a browser
dashboard, exploit-intelligence correlation, executive reporting and a CI/CD
security gate.

A ground-up rewrite of the original single-file *Ghost Eye* by
[Jolanda de Koff (BullsEye0)](https://github.com/BullsEye0). The original was
one 400-line loop; this is a small Python package where **every feature is a
self-registering module** and the menu, CLI and dashboard build themselves from
the registry. Adding a capability is just dropping a class into a file.

- **307 modules** across 19 categories
- Everything is **reconnaissance / detection only** — no exploitation, payloads,
  brute-forcing, or DoS
- Loads with **zero third-party dependencies** installed (each module lazily
  imports what it needs and degrades gracefully)
- **354 automated tests** (unit + all-module smoke + behavioural), CI on
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
| `--format <fmt>` | `json`, `csv`, `html`, `md`, `sarif`, `prometheus`, `dashboard`, `exec` |
| `--exec-report <file>` | polished executive HTML report (grade + graph + exploits); honours `--lang he` |
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
| `--lang {en,he}` | interface / report language (Hebrew = RTL) |
| `--plugins <dir>` | load extra module files from a directory |

**Keys, config & diagnostics**

| Flag | Meaning |
|------|---------|
| `--set-keys` | interactively enter & save optional API keys |
| `--no-keys` | never prompt for API keys during a scan |
| `--config-init` | write a config template to `~/.ghosteye/config.ini` |
| `--errors` | print the persistent error log |
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
| **OpenCVE / MITRE** | link-outs for manual review |

Each CVE gets a verdict — `EXPLOIT PUBLIC`, `advisory only`, or
`no public exploit found` — and a `weaponised` flag when a Metasploit module or
Exploit-DB PoC exists.

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
saves your answer to `~/.ghosteye/config.ini` under `[api_keys]`. Keys can also
come from environment variables (`VT_API_KEY`, `ABUSEIPDB_API_KEY`,
`DEEPSEEK_API_KEY`). The key file stays local and is never committed. Use
`--no-keys` for unattended runs.

---

## Web dashboard

```bash
python3 ghost_eye_web.py                 # localhost only (default)
python3 ghost_eye_web.py --open          # open a browser
python3 ghost_eye_web.py --host 0.0.0.0 --port 9000 --scope scope.txt
```

A single-page console: configure a scan (profile / category / modules / all),
watch findings stream in live with a running risk score, filter by
severity/module, and export to any format. Includes asset inventory, per-host
rollup, scan history/diff, and a **🌐 Hebrew / RTL toggle**. Localhost-only by
default; bind wider only on trusted networks.

REST endpoints per job: `/api/job/<id>/{score,compliance,exploits,inventory,rollup,report,diff}`.

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
- **All-module smoke test** — runs `run()` for every one of the 307 modules
  fully offline (network/DNS/sockets/subprocess stubbed) and asserts each
  returns a `Result` instead of crashing. This is the net that catches
  "module raises instead of failing gracefully" regressions.
- **Behavioural tests** — assert *correct* output: DER key-sizing against real
  RSA keys, exploit-intel verdict logic, deep-scan asset scoping, CI gate,
  attack score, notifications, executive-report structure, config round-trip.

**354 tests** pass. CI (`.github/workflows/ci.yml`) runs an import check,
`compileall`, the full suite and a ruff bug-rule lint on Python 3.9/3.11/3.12.

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
  config.py        settings + optional API-key management
  cli.py           argument parsing, scan orchestration, output
  webapp.py        localhost browser dashboard + JSON API
  workflow.py      profiles, deep-plan, scoring, CI gate, notify, exploit-intel
  reporting.py     JSON/CSV/HTML/PDF exporters + SQLite history
  reporting_ext.py Markdown/SARIF/Prometheus/dashboard + executive report
  inventory.py     asset correlation, host rollup, deep-scan asset collection
  modules/         ~48 files, 307 self-registering Module subclasses
  web_static/      the single-file dashboard (with Hebrew/RTL)
tests/             unit + smoke + behavioural
.github/workflows/ CI
```

---

## Credits & license

Rewrite of [BullsEye0/Ghost-Eye](https://github.com/BullsEye0) by
Jolanda de Koff. MIT licensed. **Use responsibly and legally** — authorised
security testing only.
