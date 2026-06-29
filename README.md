# 👁 Ghost Eye v3.5

A modular **information-gathering / reconnaissance / OSINT** toolkit — a
ground-up rewrite of the original single-file *Ghost Eye* by
[Jolanda de Koff (BullsEye0)](https://github.com/BullsEye0).

The original was one 400-line `fun()` loop. This version is a small package
where **every feature is a self-registering module** and the menu builds
itself from the registry — so adding a feature is just dropping a class into
a file. **210+ modules** ship in this release across 11 categories, plus a
workflow layer (profiles, batch scanning, watch mode, plugins, risk scoring,
reports in JSON/CSV/HTML/PDF/Markdown/SARIF/Prometheus/dashboard) and a
**browser dashboard** that drives all of it (`ghost_eye_web.py`).

Everything is **reconnaissance / detection only** — no exploitation, payloads,
brute-forcing, or DoS.

> ## ⚠️ Authorised use only
> Ghost Eye performs active reconnaissance (port scans, directory probing,
> subdomain brute-forcing, etc.). Run it **only** against systems you own or
> have **explicit written permission** to assess. Unauthorised scanning is
> illegal in most jurisdictions. You are responsible for how you use it.

---

## Install

```bash
pip install -r requirements.txt        # all features
# …or install nothing — the package loads anyway and each module
#   tells you what it needs when you run it.
python3 ghost_eye.py --list            # see every module
```

Python 3.9+. Some modules shell out to external binaries (`nmap`, `mtr`,
`whois`, `dig`, `masscan`, `exiftool`, `dot`); they degrade gracefully and
say so if a binary is missing.

### Termux (Android)

Ghost Eye runs on a phone under [Termux](https://termux.dev) — including the
web dashboard, which you open in your phone's browser.

```bash
pkg update && pkg install python nmap dnsutils whois openssl graphviz exiftool
pip install -r requirements-termux.txt        # pure-Python, no compiler needed
python3 ghost_eye_web.py --open               # opens the dashboard in your browser
```

`requirements-termux.txt` deliberately leaves out the few packages that need a
C compiler or Rust (`mmh3`, `cryptography`, `Pillow`, `reportlab`, `pysnmp`);
those modules simply print "requires …" and the other ~120 keep working. To
add them later: `pkg install clang python-cryptography python-pillow`. Note
that SYN scans, `masscan`, and raw ping need root, which stock Android lacks —
those modules skip themselves. Run `python3 ghost_eye.py --doctor` to see
exactly what's available on your device.

---

## New in v3.5

**80 new modules and features** — the biggest expansion yet.

### DNS (12 new modules)
`dnssecchain` full DNSSEC chain validation · `dnswildcard` advanced wildcard detection · `domainage` WHOIS creation date & registrar history · `subtakeover` dangling CNAME detection for 21 services · `dnsprop` propagation check across 6 public resolvers · `emailauth` SPF/DMARC/DKIM alignment audit with grading · `pdns` passive DNS history via OTX/HackerTarget · `dnsrebind` low-TTL + private IP rebinding detection · `typosquat` homoglyph/typosquat domain generation · `nsdelegation` NS consistency & SOA serial comparison · `domexpiry` domain expiration monitoring · `glue` in-bailiwick NS glue record verification

### TLS (10 new modules)
`ctdiff` CT log diff with 7-day new cert count · `ja3` server-side JA3S fingerprint · `deprecatedca` detect distrusted CAs (Symantec, WoSign, StartCom) · `certpin` HPKP/Expect-CT header detection · `mixedcontent` deep HTML parse for HTTP resources on HTTPS pages · `wildcertscope` wildcard cert coverage mapping · `tlsresume` session ticket/PSK detection · `caaaudit` CAA records vs actual cert issuer comparison · `mtls` mutual TLS detection · `scts` embedded SCT extraction

### Web (18 new modules)
`rediradv` extended open redirect testing (24 params, 6 payloads) · `corsadv` CORS misconfiguration with 5 origin tests · `cookieaudit` cookie security audit (__Host-/__Secure- prefix, SameSite) · `clickjack` X-Frame-Options + frame-ancestors analysis · `methodenum` full HTTP method enumeration with TRACE/XST detection · `apidisco` API endpoint discovery (24 paths) · `ratelimit` rate limit detection via burst requests · `waffp` WAF fingerprinting (10 signatures) · `cmsdetect` CMS detection (8 types) · `wpscan` WordPress vulnerability scan · `sourcemap` .js.map file detection · `adminfinder` admin panel finder (30 paths) · `smuggle` request smuggling indicators · `favhash` favicon fingerprint with Shodan query · `metatags` meta tag intelligence extraction · `formaction` form action analysis & CSRF detection · `exposedfiles` sensitive file detection (18 paths)

### Network (15 new modules)
`tcptrace` TCP traceroute · `fwinfer` firewall rule inference · `v4v6parity` IPv4/IPv6 parity check · `bgphijack` BGP hijack detection via RIPE RIS · `svcver` service version probing · `sshaudit` SSH algorithm audit · `dohdot` DoH/DoT support detection · `mqtt` MQTT broker detection · `dockerapi` Docker API exposure · `k8sadv` extended Kubernetes exposure · `ntp` NTP amplification check · `ldap` LDAP exposure · `smb` SMB enumeration · `ftpanon` anonymous FTP detection · `grpc` gRPC reflection detection

### Email (7 new modules)
`bimicheck` BIMI record validation · `mtastsval` MTA-STS policy validation · `tlsrptcheck` TLS-RPT record check · `spoofcheck` email spoofability assessment · `smtprelay` open relay detection · `dispdetect` disposable email provider detection · `catchalldetect` catch-all mailbox detection

### Cloud (8 new modules)
`s3enum` public S3 bucket enumeration · `azureblob` Azure blob storage exposure · `gcsbucket` GCP storage bucket check · `metassrf` cloud metadata SSRF surface (10 providers) · `firebase` exposed Firebase database · `gitrecon` GitHub/GitLab org reconnaissance · `dnshost` DNS hosting provider detection · `cdngeo` CDN node geolocation

### OSINT (8 new modules)
`breachcheck` breach exposure check · `social` social media profile finder · `waybackadv` advanced Wayback Machine diff · `pastebin` paste site monitor · `gdork` Google dork generator · `techstack` technology stack profiling · `threatfeed` threat feed aggregation · `jsdeps` JavaScript dependency tree analysis

### Dashboard & Workflow (2 new features)
- **Scan comparison** — side-by-side diff of two saved scans with added/removed/changed/unchanged breakdown (dashboard **Compare** button, `GET /api/compare?a=<id>&b=<id>`)
- **Scheduled recurring scans** — create, list, and delete scheduled scans from the dashboard (dashboard **Schedules** button, `POST /api/schedule`, `GET /api/schedules`, `DELETE /api/schedule/<id>`)
- Two new scan recipes: `dns` (all DNS modules) and `network` (all network modules)
- All existing recipes updated with new module IDs

---

## New in v3.4

- **Deep / recursive scan** (`--deep`, or the dashboard toggle): after the first pass it fans out to every discovered subdomain and IP and runs a lightweight per-asset profile (DNS, tech, headers, TLS grade, takeover, cookies, CSP, CORS for hosts; InternetDB, RIPEstat, GeoIP, port-scan for IPs). `--deep-max` bounds the fan-out; the scope guard is honoured. Turns 130 single-target modules into a real attack-surface sweep.
- **Per-host rollup** (`--rollup`, the dashboard **Rollup** button, `GET /api/job/<id>/rollup`): groups every finding by host with its open ports, detected tech, CVEs and a per-host severity — so a deep sweep is actually readable.
- **Load past scans** from the dashboard history (**View ↗**, `GET /api/scan/<id>`).
- **DNS reliability on mobile/Termux**: the `dns` and `axfr` modules now fall back to DNS-over-HTTPS when the system resolver is unavailable (blocked port 53 / no resolv.conf).
- **Resilient dashboard polling**: a transient network blip no longer kills a running scan (it retries before giving up).
- **CI**: GitHub Actions runs the test suite (now 16 tests) on Python 3.9–3.12, plus ruff lint.

## New in v3.3

- **100% free / no paid keys.** Removed the integrations that require a paid plan (Shodan host API, Censys, Have I Been Pwned, SecurityTrails). Their value is covered by free, no-key modules.
- **Added `ripestat`** — RIPE NCC's RIPEstat Data API (free, no key, no signup): the announcing ASN, covering BGP prefix, AS holder and the network's abuse-contact. The free Shodan **InternetDB** (`internetdb`) lookup was already present and stays.
- The only keys left are *optional free-tier* ones (`virustotal`, `abuseipdb`) and the free `GITHUB_TOKEN`; skip them and everything still runs.

## New in v3.2

- **`portscan`** — pure-Python TCP connect scan (no root, no nmap; works on stock Termux), curated common-ports list with optional banner/TLS grab.
- **`doh`** — DNS-over-HTTPS resolver (Cloudflare/Google) that bypasses the local resolver — useful on mobile/captive networks.
- **`cve`** — CVE correlation: reads the product+version a target discloses (Server / X-Powered-By) and cross-references the public NVD database (keyword search, no API key) to surface prioritised advisories.
- **Unified asset inventory** — merges hosts, IPs, open services, emails, URLs and tech from every module into one deduplicated attack surface. CLI: `--inventory`; dashboard: the **Inventory** button; API: `GET /api/job/<id>/inventory`.
- **Scope guard** — restrict an engagement to authorised hosts/CIDRs. Pass `--scope scope.txt` to the CLI or `ghost_eye_web.py`; out-of-scope targets are refused (the dashboard returns HTTP 403).
- **Persistent history + diff** — every dashboard scan is saved to SQLite; the **History / Diff** button lists past runs and diffs the current scan against any of them (`GET /api/history`, `GET /api/job/<id>/diff?against=<scan_id>`).
- **Multi-target batches in the dashboard** — enter several targets (newline/comma/space separated) and they run sequentially; review each via History.
- **Per-host rate limiting** — `--rate-per-host` (CLI) or the **Per-host (req/s)** option in the dashboard, in addition to the global rate.
- **Installable package** — `pip install .` / `pipx install .` exposes the `ghost-eye` and `ghost-eye-web` commands (see `pyproject.toml`). Core deps are pure-Python so it installs on Termux too.
- **Test suite** — `tests/test_core.py` covers validators, the JA3S/JA4S parser, multi-source subdomain merging, the inventory/scope/portscan/CVE/DoH logic and the history store. Run with `pytest` (or `python3 tests/test_core.py` style runners).

## Web dashboard

A browser console for driving every scan — no CLI needed. The web layer uses
only the Python **standard library** (no Flask/extra installs), so it runs
anywhere the package does.

```bash
python3 ghost_eye_web.py                 # http://127.0.0.1:8777
python3 ghost_eye_web.py --open          # …and open the browser
python3 ghost_eye_web.py --host 0.0.0.0 --port 9000   # expose on your LAN
```

From the dashboard you can:

- pick a **target** and a **scope** — a profile, a category, hand-picked
  modules (searchable, grouped), or everything;
- set options (timeout, parallelism, threads, rate limit, response cache,
  TLS-verify, Tor) and **Run scan** / **Stop**;
- watch findings **stream in live** with a progress bar and a risk HUD
  (severity counts + score), severity-highlighted rows, and filter/search;
- **export** the run as HTML, an interactive dashboard, JSON, Markdown, SARIF,
  CSV, or Prometheus, straight from the toolbar.

> The server binds to `127.0.0.1` (localhost only) by default — just you, on
> your machine, with no authentication. `--host 0.0.0.0` exposes it on your
> network, so only do that on a network you trust. The dashboard can scan any
> target you type — **authorised testing only**.

The dashboard talks to a small JSON API you can also use directly:
`GET /api/meta`, `POST /api/scan`, `GET /api/job/<id>`,
`POST /api/job/<id>/cancel`, `GET /api/job/<id>/report?format=<fmt>`.

---

## Usage

### Interactive (like the original, but auto-generated)
```bash
python3 ghost_eye.py
```

### Non-interactive CLI
```bash
# specific modules
python3 ghost_eye.py -t example.com -m headers,cert,subs,waf

# a whole category
python3 ghost_eye.py -t example.com --category SSL/TLS

# everything, write an HTML report, keep history
python3 ghost_eye.py -t example.com --all --output report.html --save-db

# show what changed since the last stored run
python3 ghost_eye.py -t example.com -m cert --save-db --diff
```

### Stealth / routing
```bash
python3 ghost_eye.py -t example.com -m subs --proxy http://127.0.0.1:8080
python3 ghost_eye.py -t example.com -m subs --tor          # socks5h://127.0.0.1:9050
python3 ghost_eye.py -t example.com --all --rotate-ua      # rotate User-Agent per module
```

### Reports & notifications
```bash
-o report.json        # JSON
-o report.csv         # CSV
-o report.html        # styled dark-theme HTML
-o report.pdf         # PDF (needs reportlab; falls back to HTML)
-o report.md          # Markdown (with prioritised findings table)
-o report.sarif       # SARIF 2.1.0 for CI security gates
-o report.prom        # Prometheus metrics (-f prometheus)
-o dash.html -f dashboard   # interactive dashboard (client-side filter/search)
--risk                # print a prioritised risk summary to the console
--notify <webhook>    # Slack / Discord / Telegram summary (auto-detected)
--siem <url> --siem-mode elasticsearch|splunk|webhook [--siem-token <hec>]
```

### Profiles, batch, watch, plugins
```bash
# named scan recipes (see recipes.yaml)
python3 ghost_eye.py --list-profiles
python3 ghost_eye.py -t example.com -p perimeter -o out.html
python3 ghost_eye.py --recipes my_recipes.yaml -t example.com -p custom

# batch: scan a file of targets, resumable if interrupted
python3 ghost_eye.py -T targets.txt -p quick -o report.json
python3 ghost_eye.py -T targets.txt -p quick --resume

# watch mode: re-run on an interval, alert on change (pairs with --notify)
python3 ghost_eye.py -t example.com -p tls --watch 3600 --notify <webhook>

# politeness + caching
python3 ghost_eye.py -t example.com --all --rate 5 --cache --cache-ttl 600

# drop-in plugins (any @register module under a directory)
python3 ghost_eye.py -t example.com -m httpdate --plugins ./plugins

# environment / dependency check, and Hebrew UI
python3 ghost_eye.py --doctor
python3 ghost_eye.py --lang he
```

### Scheduling (feature #75)
Use cron — e.g. a daily 3 a.m. scan with a dated report:
```cron
0 3 * * * /usr/bin/python3 /opt/ghost_eye/ghost_eye.py -t example.com \
          --all --save-db --diff --output /reports/$(date +\%F).html \
          --notify https://hooks.slack.com/services/XXX
```

---

## Configuration & API keys

**Ghost Eye runs fully with no API keys and no paid subscriptions.** The paid
Shodan, Censys, HIBP and SecurityTrails integrations were removed in v3.3; their
capability is covered by free, no-key modules (`internetdb`, `ripestat`,
`geoip`, `proxytype`, `threatfeed`, `subs`, `cve`, …).

Two **free-tier** keys are still *optional* — supply them only if you want the
extra reputation lookups; everything else works without them:

```bash
python3 ghost_eye.py --config-init      # writes ~/.ghosteye/config.ini
```

```ini
[settings]
threads = 20
timeout = 15
proxy =
verify_tls = true
wordlist =                 ; optional path for dnsbrute / dirs

[api_keys]
virustotal =               ; optional, free tier (4 req/min)
abuseipdb =                ; optional, free tier (1000 checks/day)
```

These can also be supplied as environment variables (`VT_API_KEY`,
`ABUSEIPDB_API_KEY`, and the free `GITHUB_TOKEN` for the `github` dork module).
**Modules without a key are skipped with a clear message — nothing crashes.**

---

## Modules

**DNS** — `dns` all record types · `rdns` reverse PTR · `axfr` zone transfer ·
`dnssec` · `caa` · `wildcard` · `dnsbrute` · `passivedns` · `whois` · `age`
domain age · `rdap`

**Assets** — `subs` crt.sh subdomains · `takeover` subdomain takeover ·
`revip` reverse IP · `asn` · `favicon` hash→Shodan pivot · `wayback` ·
`jsendpoints` · `apidisc` API discovery

**Network** — `nmap` (fast/full/syn/udp/version/os/aggressive profiles) ·
`banner` · `pingsweep` · `masscan` · `traceroute` (+per-hop geo) · `uptime` ·
`netmap` (Graphviz DOT)

**SSL/TLS** — `cert` full analysis · `certexpiry` · `tlsversions` ·
`ciphers` · `chain` validation · `tlsgrade` (SSL-Labs-style A+…F) ·
`ocspstaple` · `ocsprevoke` · `hstspreload` · `keyaudit` key/sig strength ·
`weakdh` Logjam · `tlscve` CVE exposure surface · `sanpivot` sibling domains ·
`ctmonitor` CT-log new-cert monitor · `cipherorder` forward secrecy · `ja4` JA3S/JA4S server fingerprint

**Web** — `headers` (+clickjacking) · `cors` · `cookies` · `methods` ·
`waf` · `cdn` · `tech` fingerprint/CMS · `dirs` · `sitemap` · `forms` ·
`redirect` open-redirect · `httpscheck` mixed content · `robots` · `crawl` ·
`httpversions` h2/h3 · `securitytxt` · `txtfiles` humans/ads · `wellknown` ·
`graphql` introspection · `websocket` · `jslibs` lib versions · `cspgrade` ·
`sri` integrity coverage · `vhost` virtual-host enum · `errorpage` fingerprint

**Email** — `mtasts` · `tlsrpt` · `bimi` · `dkim` selector discovery ·
`starttls` MX encryption · `catchall` · `disposable` · `dmarcrua`

**Cloud** — `cloudprov` provider ID · `k8s` exposed API · `docker`
registry/API · `tfstate` · `cicd` configs · `metadata` SSRF surface ·
`regtags` registry tags · `serverless` function URLs · `dangling` CNAME takeover

**OSINT** — `emails` · `emailauth` SPF/DKIM/DMARC ·
`username` public-profile search · `dorks` Google-dork generator · `exif`
metadata · `validate` email/phone · `github` exposed-secret search ·
`whoispivot` · `analytics` ID pivot · `gravatar` · `emailperm` permutator ·
`dorkext` Shodan/Censys/FOFA · `revimage` · `waybackdiff` · `oldrobots` ·
`related` correlation

**Network** — `nmap` (fast/full/syn/udp/version/os/aggressive profiles) ·
`banner` · `pingsweep` · `masscan` · `traceroute` (+per-hop geo) · `uptime` ·
`netmap` (Graphviz DOT) · `ipv6` · `revnet` reverse-DNS netblock ·
`asnassets` · `origin` IP behind CDN · `exposeddb` (Redis/Memcached/Elastic,
detection-only) · `rdpvnc` · `snmp` default community · `openresolver` ·
`vpnike` · `tlsports` TLS on non-standard ports

**Passive Intel** — `internetdb` Shodan free · `torexit` · `proxytype`
VPN/proxy/hosting · `threatfeed` abuse.ch · `geoip` enrichment · `urlscan` ·
`reputation` domain age + Tranco rank

**Threat Intel** — `internetdb` Shodan InternetDB (free, no key) · `ripestat`
RIPE NCC ASN/prefix/abuse (free, no key) · `cve` NVD correlation ·
`virustotal` *(optional free key)* · `abuseipdb` *(optional free key)* ·
`rbl` blacklists · `typosquat` phishing-lookalike monitor

**Exposure** — `vcs` exposed `.git`/`.svn` · `backups` · `buckets`
S3/GCS/Azure · `dirlisting` · `admin` login panels · `dashboards`
unauthenticated services

> All exposure/misconfig modules are **detection-only** — they report that
> something is publicly reachable; they never download, modify, or exploit it.

---

## What was fixed from the original

- **Loop exit bug** — the original `while choice != "12"` exited on
  *Traceroute*, not *Exit*, and `fun()` recursed into itself. Replaced with a
  clean menu/dispatch loop.
- **Command injection** — the original passed raw `input()` straight into
  `os.system()` / `os.popen()` (`whois `, `dig `, `nmap `, `mtr `…). Every
  external call now uses `subprocess.run([...])` with an **argument list (no
  shell)**, and every target is validated by `clean_host()`, which rejects
  anything that isn't a bare domain/IP (`example.com; rm -rf /` → refused).
- **Silent failures** — pervasive `except: pass` hid every error. Now there's
  real error handling, a `Result` object per module, and `--verbose` logging.
- **No structure** — one giant function → a registry of `Module` subclasses,
  structured results, and JSON/CSV/HTML/PDF export.

---

## Architecture / extending

```
ghost_eye.py            launcher
ghost_eye/
  core.py               Console, validators, run_cmd, Result, Module, REGISTRY
  config.py             settings + API-key resolution (file + env)
  reporting.py          JSON/CSV/HTML/PDF, SQLite history, diff, notifications
  cli.py                argparse + interactive menu (both built from REGISTRY)
  modules/
    dns_recon.py  whois_recon.py  subdomains.py  network.py  tls.py
    web.py  osint.py  intel.py  exposure.py
```

Add a feature:

```python
from ..core import Module, Context, Result, register, clean_host

@register
class MyCheck(Module):
    id, name, category = "mycheck", "My new check", "Web"
    target_kind = "url"          # domain | ip | url | host

    def run(self, target: str, ctx: Context) -> Result:
        host = clean_host(target)          # always validate first
        # ...use ctx.session (requests) / ctx.threads / ctx.timeout...
        return self.ok(host, {"finding": "value"})
```

It now appears in the menu, in `--list`, and is runnable with
`-m mycheck`. No other file needs editing.

---

*Rewrite of BullsEye0/Ghost-Eye. Use responsibly and legally.*
