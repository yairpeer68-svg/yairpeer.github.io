"""Privacy & compliance modules (features #19-#25). Detection only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class GdprAudit(Module):
    id, name, category = "gdpraudit", "GDPR cookie consent audit", "Privacy"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:80_000].lower()
            cookies_set = list(r.cookies)

            findings["cookies_on_first_visit"] = len(cookies_set)
            tracking = [c for c in cookies_set
                        if any(kw in c.name.lower() for kw in
                               ["_ga", "_gid", "_fbp", "_fbc", "hubspot",
                                "mixpanel", "hotjar", "amplitude", "_gcl"])]
            findings["tracking_cookies_before_consent"] = [c.name for c in tracking]

            consent_indicators = ["cookie-consent", "cookie-banner", "cookie-notice",
                                   "gdpr", "consent-manager", "cookiebot", "onetrust",
                                   "trustarc", "quantcast", "osano", "cookieconsent",
                                   "cookie-law", "cc-banner"]
            detected = [ind for ind in consent_indicators if ind in body]
            findings["consent_banner"] = detected or "none detected"

            if "prechecked" in body or "pre-checked" in body:
                findings["pre_checked_boxes"] = True
            if "legitimate interest" in body:
                findings["legitimate_interest_basis"] = True

        except Exception as e:
            return self.fail(host, str(e)[:80])

        risk = "informational"
        if findings.get("tracking_cookies_before_consent"):
            risk = "HIGH"
        elif not detected:
            risk = "MEDIUM"

        findings["risk"] = risk
        return self.ok(host, findings)


@register
class TrackerInventory(Module):
    id, name, category = "trackerinv", "Third-party tracker inventory", "Privacy"
    target_kind = "url"

    _TRACKERS = {
        "Google Analytics": [r"google-analytics\.com", r"googletagmanager\.com",
                              r"gtag\(", r"ga\(.*send"],
        "Facebook Pixel": [r"connect\.facebook\.net", r"fbq\(", r"fbevents\.js"],
        "Hotjar": [r"hotjar\.com", r"hj\("],
        "Mixpanel": [r"mixpanel\.com", r"mixpanel\.track"],
        "Amplitude": [r"amplitude\.com", r"amplitude\.getInstance"],
        "Segment": [r"segment\.com", r"analytics\.track"],
        "Heap": [r"heap-\d+\.js", r"heapanalytics\.com"],
        "FullStory": [r"fullstory\.com", r"_fs_namespace"],
        "Intercom": [r"intercom\.com", r"Intercom\("],
        "Hubspot": [r"hubspot\.com", r"hs-analytics"],
        "Clarity": [r"clarity\.ms"],
        "Matomo/Piwik": [r"matomo\.js", r"piwik\.js"],
        "Plausible": [r"plausible\.io"],
        "PostHog": [r"posthog\.com", r"posthog\.init"],
        "Sentry": [r"sentry\.io", r"Sentry\.init"],
        "Datadog RUM": [r"datadoghq\.com", r"DD_RUM"],
        "New Relic": [r"newrelic\.com", r"NREUM"],
        "LinkedIn Insight": [r"snap\.licdn\.com"],
        "Twitter Pixel": [r"static\.ads-twitter\.com", r"twq\("],
        "TikTok Pixel": [r"analytics\.tiktok\.com", r"ttq\.track"],
        "Pinterest Tag": [r"pintrk\(", r"ct\.pinterest\.com"],
        "Crisp": [r"crisp\.chat"],
        "Drift": [r"drift\.com"],
        "Lucky Orange": [r"luckyorange\.com"],
        "Mouseflow": [r"mouseflow\.com"],
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        detected = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:150_000]
            for tracker, patterns in self._TRACKERS.items():
                for pat in patterns:
                    if re.search(pat, body, re.I):
                        detected[tracker] = True
                        break
        except Exception as e:
            return self.fail(host, str(e)[:80])

        return self.ok(host, {
            "trackers": sorted(detected.keys()) or "none detected",
            "count": len(detected),
            "risk": "MEDIUM" if len(detected) > 5 else
                    "LOW" if detected else "informational",
        })


@register
class PrivacyPolicy(Module):
    id, name, category = "privacypol", "Privacy policy / ToS detection", "Privacy"
    target_kind = "url"

    _PATHS = ["/privacy", "/privacy-policy", "/legal/privacy",
              "/terms", "/tos", "/terms-of-service", "/legal/terms",
              "/cookie-policy", "/cookies", "/legal/cookies",
              "/data-processing", "/dpa", "/legal",
              "/imprint", "/impressum"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 200:
                    body = r.text[:20_000].lower()
                    if any(kw in body for kw in ["privacy", "data", "cookie",
                                                  "terms", "legal", "policy"]):
                        # try to find last updated date
                        date_match = re.search(
                            r'(?:updated|effective|last modified)[:\s]*'
                            r'(\d{1,2}[/\-\.]\d{1,2}[/\-\.]\d{2,4}|\w+ \d{1,2},? \d{4})',
                            body)
                        return path, {
                            "status": 200,
                            "size": len(r.content),
                            "last_updated": date_match.group(1) if date_match else "unknown",
                        }
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info

        # check footer links on homepage
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[-5000:].lower()
            for kw in ["privacy policy", "terms of service", "cookie policy"]:
                if kw in body:
                    found.setdefault("_footer_links", []).append(kw)
        except Exception:
            pass

        return self.ok(host, {
            "policies": found or "none found",
            "risk": "MEDIUM" if not found else "informational",
            "note": "missing privacy policy may indicate GDPR/CCPA non-compliance"
        })


@register
class PiiScan(Module):
    id, name, category = "piiscan", "PII exposure scan", "Privacy"
    target_kind = "url"

    _PII_PATTERNS = {
        "email": re.compile(r'[a-zA-Z0-9._%+\-]{3,}@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}'),
        "phone_us": re.compile(r'(?:\+1[\s\-]?)?\(?\d{3}\)?[\s\-]?\d{3}[\s\-]?\d{4}'),
        "phone_intl": re.compile(r'\+\d{1,3}[\s\-]?\d{4,14}'),
        "ssn": re.compile(r'\b\d{3}[\-\s]\d{2}[\-\s]\d{4}\b'),
        "credit_card": re.compile(r'\b(?:4\d{3}|5[1-5]\d{2}|3[47]\d{2}|6(?:011|5\d{2}))[\s\-]?\d{4}[\s\-]?\d{4}[\s\-]?\d{4}\b'),
        "ip_address": re.compile(r'\b(?:(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(?:25[0-5]|2[0-4]\d|[01]?\d\d?)\b'),
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        pages = [base, base + "/about", base + "/contact",
                 base + "/team", base + "/staff"]

        for url in pages:
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:50_000]
                for pii_type, pattern in self._PII_PATTERNS.items():
                    matches = pattern.findall(body)
                    if matches:
                        path = url.replace(base, "") or "/"
                        findings.setdefault(pii_type, []).extend(
                            [{"page": path, "count": len(matches)}])
            except Exception:
                continue

        # also check API responses
        for path in ["/api/users", "/api/v1/users", "/api/contacts"]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and "json" in r.headers.get("Content-Type", ""):
                    body = r.text[:30_000]
                    for pii_type, pattern in self._PII_PATTERNS.items():
                        if pattern.search(body):
                            findings.setdefault(pii_type, []).append(
                                {"page": path, "source": "API"})
            except Exception:
                continue

        risk = "informational"
        if "ssn" in findings or "credit_card" in findings:
            risk = "CRITICAL"
        elif "email" in findings and len(findings.get("email", [])) > 3:
            risk = "MEDIUM"

        return self.ok(host, {
            "pii_found": findings or "none detected",
            "risk": risk,
        })


@register
class CcpaCheck(Module):
    id, name, category = "ccpacheck", "CCPA Do-Not-Sell detection", "Privacy"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # GPC header support
        try:
            r = ctx.session.get(base, timeout=ctx.timeout,
                                headers={"Sec-GPC": "1"})
            findings["gpc_header_sent"] = True
            if "gpc" in r.text.lower()[:30_000]:
                findings["gpc_acknowledged"] = True
        except Exception:
            pass

        # Do Not Sell link
        dns_paths = ["/do-not-sell", "/privacy/do-not-sell",
                     "/ccpa", "/opt-out", "/privacy/opt-out",
                     "/do-not-sell-my-info", "/your-privacy-choices"]
        for path in dns_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 100:
                    findings["do_not_sell_page"] = path
                    break
            except Exception:
                continue

        # check footer
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[-8000:].lower()
            for phrase in ["do not sell", "do not share", "your privacy choices",
                           "opt-out", "ccpa"]:
                if phrase in body:
                    findings.setdefault("footer_mentions", []).append(phrase)
        except Exception:
            pass

        # .well-known/gpc.json
        try:
            r = ctx.session.get(base + "/.well-known/gpc.json", timeout=ctx.timeout)
            if r.status_code == 200:
                findings["gpc_json"] = r.json()
        except Exception:
            pass

        return self.ok(host, findings or {"ccpa": "no CCPA indicators found"})


@register
class DataResidency(Module):
    id, name, category = "dataresidency", "Data residency check", "Privacy"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}

        # geolocate server
        import socket
        try:
            ip = socket.gethostbyname(host)
            findings["ip"] = ip
            r = ctx.session.get(f"http://ip-api.com/json/{ip}?fields=country,countryCode,city,isp,org,hosting",
                                timeout=ctx.timeout)
            if r.status_code == 200:
                findings["server_location"] = r.json()
        except Exception:
            pass

        # check CDN/cloud provider headers
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
            hdrs = {k.lower(): v for k, v in r.headers.items()}
            if "cf-ray" in hdrs:
                loc = hdrs["cf-ray"].split("-")[-1] if "-" in hdrs["cf-ray"] else ""
                findings["cdn"] = {"provider": "Cloudflare", "pop": loc}
            elif "x-amz-cf-pop" in hdrs:
                findings["cdn"] = {"provider": "CloudFront",
                                   "pop": hdrs["x-amz-cf-pop"]}
            elif "x-served-by" in hdrs:
                findings["cdn"] = {"provider": "Fastly/Varnish",
                                   "node": hdrs["x-served-by"][:40]}
        except Exception:
            pass

        # EU adequacy check
        country = (findings.get("server_location", {}).get("countryCode", "") or "").upper()
        eu_eea = {"AT","BE","BG","HR","CY","CZ","DK","EE","FI","FR","DE","GR",
                  "HU","IE","IT","LV","LT","LU","MT","NL","PL","PT","RO","SK",
                  "SI","ES","SE","IS","LI","NO"}
        adequate = {"AD","AR","CA","FO","GG","IL","IM","JP","JE","NZ","CH","UY",
                    "KR","GB","US"}
        if country in eu_eea:
            findings["gdpr_jurisdiction"] = "EU/EEA (GDPR applies)"
        elif country in adequate:
            findings["gdpr_jurisdiction"] = f"Adequate ({country})"
        elif country:
            findings["gdpr_jurisdiction"] = f"Non-adequate ({country}) — transfers need SCCs/BCRs"

        return self.ok(host, findings)


@register
class ConsentAnalysis(Module):
    id, name, category = "consentlog", "Consent mechanism analysis", "Privacy"
    target_kind = "url"

    _CMPS = {
        "OneTrust": ["onetrust", "optanon"],
        "Cookiebot": ["cookiebot", "cybot"],
        "TrustArc": ["trustarc", "truste"],
        "Quantcast": ["quantcast"],
        "Osano": ["osano"],
        "CookieYes": ["cookieyes"],
        "Termly": ["termly"],
        "Iubenda": ["iubenda"],
        "Didomi": ["didomi"],
        "Usercentrics": ["usercentrics"],
        "CookieFirst": ["cookiefirst"],
        "Complianz": ["complianz"],
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:100_000].lower()

            # detect CMP platform
            for cmp, indicators in self._CMPS.items():
                if any(ind in body for ind in indicators):
                    findings["cmp_platform"] = cmp
                    break

            # TCF v2 check
            if "tcfapi" in body or "__tcfapi" in body or "iabtcf" in body:
                findings["tcf_v2"] = True
                # check consent string
                tcf_match = re.search(r'(?:tcString|euconsent-v2)["\s:=]+([A-Za-z0-9+/=_-]{20,})', body)
                if tcf_match:
                    findings["consent_string_exposed"] = True

            # IAB USP (CCPA)
            if "uspapi" in body or "__uspapi" in body:
                findings["iab_usp"] = True

            # GPP
            if "gppapi" in body or "__gpp" in body:
                findings["iab_gpp"] = True

        except Exception as e:
            return self.fail(host, str(e)[:80])

        return self.ok(host, findings or {"consent": "no CMP detected"})
