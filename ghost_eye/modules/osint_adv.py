"""Advanced OSINT / pivoting modules (new features #48-#56)."""

from __future__ import annotations

import hashlib
import re
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class WhoisPivot(Module):
    id, name, category = "whoispivot", "WHOIS registrant pivot", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import whois as pywhois
        except ImportError:
            return self.fail(target, "requires python-whois")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            w = pywhois.whois(host)
        except Exception as exc:
            return self.fail(host, f"whois failed: {exc}")
        email = w.emails[0] if isinstance(w.emails, list) and w.emails else w.emails
        org = w.org
        pivots = {}
        if email:
            pivots["reverse_whois_by_email"] = \
                f"https://viewdns.info/reversewhois/?q={email}"
        if org:
            pivots["reverse_whois_by_org"] = \
                f"https://viewdns.info/reversewhois/?q={org}"
        return self.ok(host, {"registrant_email": email or "(redacted)",
                              "org": org or "(redacted)", "pivot_links": pivots,
                              "note": "redacted WHOIS is common post-GDPR"})


@register
class AnalyticsPivot(Module):
    id, name, category = "analytics", "Analytics/AdSense ID pivot", "OSINT"
    target_kind = "url"

    _RX = {
        "Google Analytics (UA)": re.compile(r"UA-\d{4,10}-\d{1,4}"),
        "Google Analytics (GA4)": re.compile(r"G-[A-Z0-9]{8,12}"),
        "Google Tag Manager": re.compile(r"GTM-[A-Z0-9]{5,8}"),
        "AdSense": re.compile(r"ca-pub-\d{10,20}"),
        "Facebook Pixel": re.compile(r"fbq\(['\"]init['\"],\s*['\"](\d{10,20})"),
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            html = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout).text
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        ids = {}
        for label, rx in self._RX.items():
            m = rx.findall(html)
            if m:
                ids[label] = list(set(m))
        pivots = {}
        flat = [v for vals in ids.values() for v in (vals if isinstance(vals, list) else [vals])]
        for tid in flat[:5]:
            pivots[tid] = f"https://search.marketingdrunks.com/?q={tid}  (or hackertarget/spyonweb)"
        return self.ok(host, {"tracking_ids": ids or "none found",
                              "pivot_hint": "look up these IDs on spyonweb/publicwww "
                                            "to find sibling sites sharing them"})


@register
class GravatarLookup(Module):
    id, name, category = "gravatar", "Gravatar by email", "OSINT"
    target_kind = "host"

    def run(self, target, ctx):
        email = target.strip().lower()
        if "@" not in email:
            return self.fail(target, "provide an email address")
        h = hashlib.md5(email.encode()).hexdigest()
        url = f"https://www.gravatar.com/{h}.json"
        try:
            r = ctx.session.get(url, timeout=ctx.timeout,
                                headers={"User-Agent": "GhostEye"})
            if r.status_code == 200:
                entry = r.json().get("entry", [{}])[0]
                return self.ok(email, {
                    "exists": True,
                    "display_name": entry.get("displayName"),
                    "profile_url": entry.get("profileUrl"),
                    "accounts": [a.get("shortname") for a in entry.get("accounts", [])],
                    "image": f"https://www.gravatar.com/avatar/{h}"})
            return self.ok(email, {"exists": False, "hash": h})
        except Exception as exc:
            return self.fail(email, f"gravatar failed: {exc}")


@register
class EmailPermutator(Module):
    id, name, category = "emailperm", "Email/username permutator", "OSINT"
    target_kind = "host"

    def run(self, target, ctx):
        # input like "John Doe @example.com" or "John Doe example.com"
        raw = target.strip()
        m = re.search(r"@?([a-z0-9.\-]+\.[a-z]{2,})$", raw, re.I)
        domain = m.group(1) if m else "example.com"
        name = re.sub(r"@?[a-z0-9.\-]+\.[a-z]{2,}$", "", raw, flags=re.I).strip()
        parts = [p for p in re.split(r"\s+", name) if p]
        if len(parts) < 2:
            first, last = (parts + ["", ""])[:2]
        else:
            first, last = parts[0], parts[-1]
        f, l = first.lower(), last.lower()
        patterns = set()
        if f and l:
            patterns |= {f"{f}.{l}", f"{f}{l}", f"{f}_{l}", f"{f[0]}{l}",
                         f"{f}{l[0]}", f"{f[0]}.{l}", f"{l}.{f}", f"{l}{f}", f"{f}-{l}"}
        if f:
            patterns.add(f)
        emails = sorted(f"{p}@{domain}" for p in patterns if p)
        # validate domain MX once
        mx_ok = False
        try:
            import dns.resolver
            mx_ok = bool(dns.resolver.resolve(domain, "MX"))
        except Exception:
            pass
        return self.ok(domain, {"candidates": emails, "domain_has_mx": mx_ok,
                                "note": "candidate formats - verify with the SMTP catch-all "
                                        "check or a permission-based validator"})


@register
class DorkBuilderExt(Module):
    id, name, category = "dorkext", "Shodan/Censys/FOFA dorks", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        import base64
        fofa = f'domain="{host}"'
        return self.ok(host, {
            "shodan": [f"hostname:{host}", f"ssl.cert.subject.cn:{host}",
                       f"ssl:{host}", f'http.title:"{host}"'],
            "censys": [f"services.tls.certificates.leaf_data.subject.common_name:{host}",
                       f"names:{host}"],
            "fofa_query": fofa,
            "fofa_url": "https://fofa.info/result?qbase64=" +
                        base64.b64encode(fofa.encode()).decode(),
            "zoomeye": f'hostname:"{host}"'})


@register
class ReverseImageHelper(Module):
    id, name, category = "revimage", "Reverse image search links", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        url = target.strip()
        if not re.match(r"^https?://", url):
            return self.fail(target, "provide a full image URL")
        from urllib.parse import quote
        return self.ok(url, {"search_links": {
            "Google Lens": f"https://lens.google.com/uploadbyurl?url={quote(url)}",
            "Yandex": f"https://yandex.com/images/search?rpt=imageview&url={quote(url)}",
            "Bing": f"https://www.bing.com/images/search?q=imgurl:{quote(url)}&view=detailv2&iss=sbi",
            "TinEye": f"https://tineye.com/search?url={quote(url)}"}})


@register
class WaybackDiff(Module):
    id, name, category = "waybackdiff", "Wayback content diff", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            cdx = ctx.session.get(
                f"http://web.archive.org/cdx/search/cdx?url={host}"
                "&output=json&fl=timestamp,original&collapse=digest&limit=200",
                timeout=ctx.timeout + 15)
            rows = cdx.json()[1:] if cdx.status_code == 200 else []
            snaps = [r[0] for r in rows]
            return self.ok(host, {
                "snapshot_count": len(snaps),
                "earliest": snaps[0] if snaps else None,
                "latest": snaps[-1] if snaps else None,
                "compare_url": (f"https://web.archive.org/web/diff/{snaps[0]}/"
                                f"{snaps[-1]}/{ensure_scheme(host)}")
                if len(snaps) >= 2 else None})
        except Exception as exc:
            return self.fail(host, f"wayback failed: {exc}")


@register
class HistoricalRobots(Module):
    id, name, category = "oldrobots", "Historical robots.txt", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        paths = set()
        try:
            cdx = ctx.session.get(
                f"http://web.archive.org/cdx/search/cdx?url={host}/robots.txt"
                "&output=json&fl=timestamp&collapse=digest&limit=10",
                timeout=ctx.timeout + 10)
            stamps = [r[0] for r in cdx.json()[1:]] if cdx.status_code == 200 else []
            for ts in stamps[-5:]:
                snap = ctx.session.get(
                    f"http://web.archive.org/web/{ts}id_/{ensure_scheme(host)}/robots.txt",
                    timeout=ctx.timeout)
                if snap.status_code == 200:
                    paths.update(re.findall(r"(?im)^\s*Disallow:\s*(\S+)", snap.text))
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")
        return self.ok(host, {"historical_disallowed": sorted(paths) or "none recovered",
                              "note": "old Disallow paths sometimes still exist & are forgotten"})


@register
class RelatedDomains(Module):
    id, name, category = "related", "Related-domain correlation", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        signals = {}
        # certificate SANs
        try:
            from .tls_adv import SanPivot
            signals["cert_siblings"] = SanPivot().run(host, ctx).data.get("sibling_domains")
        except Exception:
            pass
        # analytics ids
        try:
            from .osint_adv import AnalyticsPivot
            signals["tracking_ids"] = AnalyticsPivot().run(host, ctx).data.get("tracking_ids")
        except Exception:
            pass
        # favicon hash
        try:
            from .subdomains import FaviconHash
            signals["favicon"] = FaviconHash().run(host, ctx).data.get("shodan_pivot")
        except Exception:
            pass
        return self.ok(host, {"correlation_signals": signals,
                              "note": "combine cert SANs + shared tracking IDs + favicon "
                                      "hash to cluster related properties"})
