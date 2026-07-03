"""Advanced OSINT modules v2 (v3.5 features #71-#78). Detection only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List
from urllib.parse import quote

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class BreachCheck(Module):
    id, name, category = "breachcheck", "Leaked credentials check", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        # HIBP domain search (free, no API key needed for domain)
        try:
            r = ctx.session.get(
                f"https://haveibeenpwned.com/api/v3/breaches",
                timeout=ctx.timeout,
                headers={"User-Agent": "GhostEye-OSINT"})
            if r.status_code == 200:
                breaches = r.json()
                domain_breaches = [b for b in breaches
                                   if host in (b.get("Domain") or "").lower()]
                results["direct_breaches"] = [{
                    "name": b.get("Name"),
                    "date": b.get("BreachDate"),
                    "count": b.get("PwnCount"),
                    "data_types": b.get("DataClasses", [])[:5],
                } for b in domain_breaches[:10]]
        except Exception:
            pass
        # IntelX (free tier)
        try:
            r = ctx.session.get(
                f"https://2.intelx.io/phonebook/search?term={host}&maxresults=5&media=0",
                timeout=ctx.timeout,
                headers={"x-key": "9df61df0-84f7-4dc7-b34c-8ccfb8646ace"})
            if r.status_code == 200:
                results["intelx_results"] = r.json().get("selectors", [])[:5]
        except Exception:
            pass
        # dehashed / leak-lookup reference
        results["lookup_links"] = {
            "haveibeenpwned": f"https://haveibeenpwned.com/DomainSearch/{host}",
            "intelx": f"https://intelx.io/?s={host}",
        }
        return self.ok(host, {"breach_data": results,
                              "note": "check these sources for leaked credentials"})


@register
class SocialProfileFinder(Module):
    id, name, category = "social", "Social media profile finder", "OSINT"
    target_kind = "host"

    _PLATFORMS = {
        "GitHub": "https://github.com/{u}",
        "Twitter/X": "https://twitter.com/{u}",
        "LinkedIn": "https://www.linkedin.com/company/{u}",
        "Instagram": "https://www.instagram.com/{u}",
        "Facebook": "https://www.facebook.com/{u}",
        "YouTube": "https://www.youtube.com/@{u}",
        "Reddit": "https://www.reddit.com/user/{u}",
        "TikTok": "https://www.tiktok.com/@{u}",
        "Medium": "https://medium.com/@{u}",
        "Pinterest": "https://www.pinterest.com/{u}",
        "Telegram": "https://t.me/{u}",
        "Keybase": "https://keybase.io/{u}",
    }

    def run(self, target, ctx):
        username = target.strip().split(".")[0].lower()
        username = re.sub(r"[^a-z0-9_\-]", "", username)
        if not username:
            return self.fail(target, "provide a username or domain")
        found = {}
        not_found = []

        def check(item):
            platform, url_template = item
            url = url_template.format(u=username)
            try:
                r = ctx.session.get(url, timeout=ctx.timeout, allow_redirects=True)
                if r.status_code == 200:
                    if ("page not found" not in r.text.lower() and
                            "doesn't exist" not in r.text.lower() and
                            "user not found" not in r.text.lower() and
                            len(r.text) > 500):
                        return platform, url
            except Exception:
                pass
            return platform, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for platform, url in ex.map(check, self._PLATFORMS.items()):
                if url:
                    found[platform] = url
                else:
                    not_found.append(platform)
        return self.ok(username, {"profiles_found": found or "none",
                                   "not_found": not_found,
                                   "username": username})


@register
class WaybackDiffAdv(Module):
    id, name, category = "waybackadv", "Wayback Machine diff (extended)", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        url = ensure_scheme(host)
        data = {}
        try:
            # get latest snapshot
            r = ctx.session.get(
                f"https://archive.org/wayback/available?url={host}",
                timeout=ctx.timeout + 10)
            if r.status_code == 200:
                snap = r.json().get("archived_snapshots", {}).get("closest", {})
                data["latest_snapshot"] = snap.get("url")
                data["snapshot_timestamp"] = snap.get("timestamp")
        except Exception:
            pass
        # CDX API for history
        try:
            cdx = ctx.session.get(
                f"http://web.archive.org/cdx/search/cdx?url={host}"
                "&output=json&fl=timestamp,statuscode,digest&collapse=digest&limit=100",
                timeout=ctx.timeout + 15)
            if cdx.status_code == 200:
                rows = cdx.json()[1:]
                data["total_snapshots"] = len(rows)
                data["earliest"] = rows[0][0] if rows else None
                data["latest"] = rows[-1][0] if rows else None
                # find status code changes
                changes = []
                for i in range(1, len(rows)):
                    if rows[i][1] != rows[i - 1][1]:
                        changes.append({"from": rows[i - 1][1], "to": rows[i][1],
                                        "at": rows[i][0]})
                data["status_changes"] = changes[:10] or "none"
                if len(rows) >= 2:
                    data["diff_url"] = (
                        f"https://web.archive.org/web/diff/{rows[0][0]}/{rows[-1][0]}/{url}")
        except Exception:
            pass
        # also grab technologies from old snapshots
        try:
            if data.get("latest_snapshot"):
                snap_r = ctx.session.get(data["latest_snapshot"], timeout=ctx.timeout)
                old_tech = []
                for sig, tech in [("wp-content", "WordPress"), ("drupal", "Drupal"),
                                  ("joomla", "Joomla"), ("shopify", "Shopify"),
                                  ("react", "React"), ("angular", "Angular")]:
                    if sig in snap_r.text.lower():
                        old_tech.append(tech)
                data["historical_tech"] = old_tech or "none detected"
        except Exception:
            pass
        return self.ok(host, data or {"note": "Wayback Machine data unavailable"})


@register
class PastebinMonitor(Module):
    id, name, category = "pastebin", "Pastebin / paste site search", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        # Google Custom Search for paste sites
        dorks = [
            f'site:pastebin.com "{host}"',
            f'site:ghostbin.com "{host}"',
            f'site:hastebin.com "{host}"',
            f'site:paste.ee "{host}"',
            f'site:gist.github.com "{host}"',
        ]
        results["search_dorks"] = dorks
        results["search_urls"] = {
            "google": "https://www.google.com/search?q=" + quote('intext:"' + host + '" site:pastebin.com'),
            "pastebin_search": f"https://psbdmp.ws/api/v3/search/{host}",
        }
        # try psbdmp API (pastebin dump search)
        try:
            r = ctx.session.get(f"https://psbdmp.ws/api/v3/search/{host}",
                                timeout=ctx.timeout + 10)
            if r.status_code == 200:
                data = r.json()
                if isinstance(data, list):
                    results["psbdmp_results"] = data[:15]
                elif isinstance(data, dict):
                    results["psbdmp_results"] = data.get("data", [])[:15]
        except Exception:
            results["psbdmp"] = "API unavailable"
        return self.ok(host, results)


@register
class GoogleDorkGen(Module):
    id, name, category = "gdork", "Google dorking automation", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        dorks = {
            "login_pages": f'site:{host} inurl:login OR inurl:admin OR inurl:signin',
            "sensitive_files": f'site:{host} filetype:pdf OR filetype:doc OR filetype:xls OR filetype:csv',
            "config_files": f'site:{host} filetype:xml OR filetype:conf OR filetype:env OR filetype:ini',
            "sql_files": f'site:{host} filetype:sql OR filetype:bak OR filetype:log',
            "error_pages": f'site:{host} "error" OR "warning" OR "fatal" OR "exception"',
            "directory_listing": f'site:{host} intitle:"index of" OR intitle:"directory listing"',
            "exposed_docs": f'site:{host} filetype:pdf OR filetype:docx confidential OR internal OR private',
            "email_addresses": f'site:{host} "@{host}" filetype:pdf OR filetype:doc',
            "subdomains": f'site:*.{host} -www',
            "wordpress": f'site:{host} inurl:wp-content OR inurl:wp-includes',
            "api_endpoints": f'site:{host} inurl:api OR inurl:/v1/ OR inurl:/v2/',
            "open_redirects": f'site:{host} inurl:redirect OR inurl:url= OR inurl:next=',
            "password_files": f'site:{host} filetype:txt password OR username OR login',
            "backup_files": f'site:{host} filetype:bak OR filetype:old OR filetype:backup',
            "github_leaks": f'site:github.com "{host}" password OR secret OR api_key',
            "pastebin_leaks": f'site:pastebin.com "{host}"',
        }
        urls = {name: f"https://www.google.com/search?q={quote(dork)}"
                for name, dork in dorks.items()}
        return self.ok(host, {"dorks": dorks, "search_urls": urls,
                              "note": "use these dorks responsibly; respect rate limits"})


@register
class TechStackProfile(Module):
    id, name, category = "techstack", "Technology stack profiling", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        stack = {"server": {}, "frontend": {}, "security": {}, "analytics": {},
                 "cdn": {}, "dns": {}}
        url = ensure_scheme(host)
        try:
            r = ctx.session.get(url, timeout=ctx.timeout)
            html = r.text
            headers = {k.lower(): v for k, v in r.headers.items()}
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        # server
        stack["server"]["server"] = headers.get("server", "hidden")
        stack["server"]["x-powered-by"] = headers.get("x-powered-by", "hidden")
        # frontend frameworks
        for name, patterns in [
            ("React", [r"react", r"_reactRoot", r"__REACT"]),
            ("Vue.js", [r"data-v-", r"__vue__"]),
            ("Angular", [r"ng-version", r"ng-app"]),
            ("Next.js", [r"/_next/", r"__NEXT_DATA__"]),
            ("Nuxt.js", [r"/__nuxt/", r"__NUXT__"]),
            ("Svelte", [r"__svelte"]),
            ("jQuery", [r"jquery"]),
            ("Bootstrap", [r"bootstrap"]),
            ("Tailwind", [r"tailwind"]),
        ]:
            if any(re.search(p, html, re.I) for p in patterns):
                stack["frontend"][name] = "detected"
        # CMS
        for name, patterns in [
            ("WordPress", [r"/wp-content/", r"/wp-includes/"]),
            ("Drupal", [r"Drupal\.settings", r"/sites/default/"]),
            ("Joomla", [r"/components/com_"]),
            ("Shopify", [r"cdn\.shopify\.com"]),
            ("Wix", [r"wix\.com"]),
        ]:
            if any(re.search(p, html, re.I) for p in patterns):
                stack["server"][f"CMS:{name}"] = "detected"
        # security
        for h in ("strict-transport-security", "content-security-policy",
                  "x-frame-options", "x-content-type-options"):
            if h in headers:
                stack["security"][h] = headers[h][:60]
        # analytics
        for name, pattern in [
            ("Google Analytics", r"UA-\d+-\d+|G-[A-Z0-9]+"),
            ("Google Tag Manager", r"GTM-[A-Z0-9]+"),
            ("Facebook Pixel", r"fbq\("),
            ("Hotjar", r"hotjar"),
            ("Segment", r"analytics\.js"),
        ]:
            if re.search(pattern, html, re.I):
                stack["analytics"][name] = "detected"
        # CDN
        for name, header in [("Cloudflare", "cf-ray"), ("Fastly", "x-served-by"),
                             ("CloudFront", "x-amz-cf-id"), ("Akamai", "x-akamai-transformed")]:
            if header in headers:
                stack["cdn"][name] = "detected"
        return self.ok(host, {"stack": {k: v for k, v in stack.items() if v}})


@register
class ThreatFeedCheck(Module):
    id, name, category = "threatagg", "Threat feed cross-reference (aggregated)", "OSINT"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        import socket as _socket
        try:
            ip = host if re.match(r"\d+\.\d+\.\d+\.\d+", host) else _socket.gethostbyname(host)
        except OSError:
            ip = None
        results = {}
        # AbuseIPDB
        if ip:
            try:
                r = ctx.session.get(
                    f"https://api.abuseipdb.com/api/v2/check?ipAddress={ip}",
                    timeout=ctx.timeout,
                    headers={"Accept": "application/json", "Key": ""})
                if r.status_code == 200:
                    data = r.json().get("data", {})
                    results["abuseipdb"] = {
                        "score": data.get("abuseConfidenceScore"),
                        "reports": data.get("totalReports"),
                    }
            except Exception:
                pass
        # URLhaus
        try:
            r = ctx.session.post("https://urlhaus-api.abuse.ch/v1/host/",
                                 data={"host": host}, timeout=ctx.timeout)
            if r.status_code == 200:
                j = r.json()
                if j.get("query_status") == "no_results":
                    results["urlhaus"] = "clean"
                else:
                    results["urlhaus"] = {
                        "urls_count": j.get("urls_count", 0),
                        "status": j.get("query_status"),
                    }
        except Exception:
            pass
        # PhishTank (just link)
        results["phishtank"] = f"https://phishtank.org/phish_search.php?search={host}"
        # VirusTotal link
        results["virustotal"] = f"https://www.virustotal.com/gui/domain/{host}"
        # AlienVault OTX
        try:
            r = ctx.session.get(
                f"https://otx.alienvault.com/api/v1/indicators/domain/{host}/general",
                timeout=ctx.timeout)
            if r.status_code == 200:
                j = r.json()
                results["alienvault_otx"] = {
                    "pulse_count": j.get("pulse_info", {}).get("count", 0),
                    "reputation": j.get("reputation", 0),
                }
        except Exception:
            pass
        return self.ok(host, {"ip": ip, "threat_feeds": results,
                              "note": "check these feeds for malicious activity indicators"})


@register
class JsDependencyTree(Module):
    id, name, category = "jsdeps", "JavaScript dependency tree", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(r.text, "html.parser")
        scripts = []
        inline_count = 0
        external_count = 0
        cdns = set()
        for tag in soup.find_all("script"):
            src = tag.get("src", "")
            if src:
                external_count += 1
                integrity = tag.get("integrity", "")
                crossorigin = tag.get("crossorigin", "")
                entry = {"src": src[:120], "has_sri": bool(integrity),
                         "crossorigin": crossorigin or "none"}
                # detect CDN
                for cdn in ("cdnjs.cloudflare.com", "cdn.jsdelivr.net",
                            "unpkg.com", "ajax.googleapis.com",
                            "stackpath.bootstrapcdn.com", "code.jquery.com"):
                    if cdn in src:
                        entry["cdn"] = cdn
                        cdns.add(cdn)
                # extract library version
                ver = re.search(r"[/@](\d+\.\d+\.\d+)", src)
                if ver:
                    entry["version"] = ver.group(1)
                scripts.append(entry)
            else:
                inline_count += 1
        # check for npm-style imports in inline scripts
        inline_imports = set()
        for tag in soup.find_all("script"):
            if not tag.get("src"):
                imports = re.findall(r'''(?:import|require)\s*\(?['"]([@a-z0-9/\-]+)''',
                                     tag.string or "")
                inline_imports.update(imports)
        no_sri = [s["src"] for s in scripts if not s["has_sri"] and s["src"].startswith("http")]
        return self.ok(host, {
            "external_scripts": external_count,
            "inline_scripts": inline_count,
            "scripts": scripts[:30],
            "cdns_used": sorted(cdns) or "none",
            "missing_sri": no_sri[:15] or "all covered",
            "inline_imports": sorted(inline_imports)[:20] or "none",
        })
