"""Correlate scan results into a unified intelligence picture."""

from __future__ import annotations

import re
from typing import Any, Dict, List

from ..core import Result, clean_host, is_ip
from ..inventory import build_inventory
from ..reporting import _flatten

# --------------------------------------------------------------------------- #
#  technology classification — keyword -> label, grouped by kind
# --------------------------------------------------------------------------- #
_TECH = {
    "cms": {
        "wordpress": "WordPress", "drupal": "Drupal", "joomla": "Joomla",
        "magento": "Magento", "shopify": "Shopify", "ghost": "Ghost",
        "wix": "Wix", "squarespace": "Squarespace", "typo3": "TYPO3",
        "prestashop": "PrestaShop", "sitecore": "Sitecore",
    },
    "framework": {
        "react": "React", "angular": "Angular", "vue": "Vue.js",
        "next.js": "Next.js", "nextjs": "Next.js", "nuxt": "Nuxt",
        "laravel": "Laravel", "django": "Django", "rails": "Ruby on Rails",
        "express": "Express", "spring": "Spring", "flask": "Flask",
        "asp.net": "ASP.NET", "svelte": "Svelte", "gatsby": "Gatsby",
    },
    "server": {
        "nginx": "nginx", "apache": "Apache", "iis": "IIS",
        "litespeed": "LiteSpeed", "caddy": "Caddy", "gunicorn": "Gunicorn",
        "tomcat": "Tomcat", "openresty": "OpenResty", "envoy": "Envoy",
    },
    "cdn": {
        "cloudflare": "Cloudflare", "akamai": "Akamai", "fastly": "Fastly",
        "cloudfront": "CloudFront", "incapsula": "Incapsula",
        "stackpath": "StackPath", "keycdn": "KeyCDN", "bunnycdn": "BunnyCDN",
    },
    "waf": {
        "cloudflare": "Cloudflare WAF", "imperva": "Imperva", "sucuri": "Sucuri",
        "modsecurity": "ModSecurity", "aws waf": "AWS WAF", "wallarm": "Wallarm",
        "barracuda": "Barracuda WAF", "f5": "F5 BIG-IP",
    },
    "analytics": {
        "google analytics": "Google Analytics", "gtag": "Google Tag Manager",
        "hotjar": "Hotjar", "segment": "Segment", "mixpanel": "Mixpanel",
        "matomo": "Matomo",
    },
}

# specific infrastructure tokens only — avoids false positives from generic
# brand mentions ('amazon', 'microsoft') or ubiquitous CDNs (fonts.googleapis)
_CLOUD = {
    "amazonaws": "AWS", "aws.amazon.com": "AWS", "elasticbeanstalk": "AWS",
    "windows.net": "Azure", "azurewebsites": "Azure", "azureedge": "Azure",
    "cloudapp.azure": "Azure", "googleusercontent": "GCP",
    "google cloud platform": "GCP", "appspot.com": "GCP", "run.app": "GCP",
    "cloudflare": "Cloudflare", "digitalocean": "DigitalOcean",
    "linode": "Linode", "vultr": "Vultr", "herokuapp": "Heroku",
    "netlify": "Netlify", "vercel": "Vercel", "oraclecloud": "Oracle Cloud",
    "alibaba": "Alibaba Cloud", "aliyun": "Alibaba Cloud", "hetzner": "Hetzner",
    "ovh": "OVH", "fastly": "Fastly", "akamai": "Akamai",
}

_LEAK_SIGNALS = ("breach", "leak", "pwned", "credential", "pastebin",
                 "exposed secret", "leaked", "password dump")

_DEV_MARKERS = ("dev", "staging", "stg", "test", "qa", "uat", "sandbox",
                "preprod", "beta", "internal")

# third-party sites that OSINT dorks reference (the target is *mentioned* there,
# they are not the target's own assets) — kept out of the related-domain graph.
_REFERENCE_DOMAINS = {
    "github.com", "gitlab.com", "bitbucket.org", "pastebin.com", "ghostbin.com",
    "google.com", "www.google.com", "bing.com", "duckduckgo.com", "yandex.com",
    "twitter.com", "x.com", "facebook.com", "linkedin.com", "youtube.com",
    "instagram.com", "reddit.com", "medium.com", "stackoverflow.com", "t.me",
    "shodan.io", "censys.io", "virustotal.com", "crt.sh", "archive.org",
    "web.archive.org", "wikipedia.org", "pastecode.io", "controlc.com",
}
# leading tokens that are un-decoded URL-encode artifacts (%2A→2a, %20→20, …)
_ENC_TOKENS = ("2a", "20", "2f", "3a", "3d", "26", "2b", "2c", "3f", "5b",
               "5d", "7c", "25", "23", "40", "252")


def is_noise_domain(host: str, target: str) -> bool:
    """True if `host` is OSINT noise for `target`: a third-party reference site
    (github.com, pastebin.com, google.com…) or a URL-encode artifact of the
    target itself ('3agithub.com', '20example.com', '2a.example.com')."""
    d = (host or "").lower().rstrip(".")
    tgt = (target or "").lower().rstrip(".")
    if not d or d in _REFERENCE_DOMAINS:
        return True
    labels = d.split(".")
    first, rest = labels[0], ".".join(labels[1:])
    # pure artifact label, e.g. '2a.example.com' -> real host is the rest
    if first in _ENC_TOKENS and (rest == tgt or rest in _REFERENCE_DOMAINS
                                 or not rest):
        return True
    # glued artifact, e.g. '3agithub.com' / '20example.com'
    for tok in _ENC_TOKENS:
        if first.startswith(tok) and len(first) > len(tok):
            cand = first[len(tok):] + (("." + rest) if rest else "")
            if cand == tgt or cand in _REFERENCE_DOMAINS:
                return True
    return False


def _clean_related(domains: List[str], target: str) -> List[str]:
    """Drop OSINT-reference sites, URL-encode artifacts and the target itself from
    the related-domain set, so the graph shows the target's *own* related
    domains — not github.com / pastebin.com / mangled dork leftovers."""
    tgt = (target or "").lower().rstrip(".")
    out: List[str] = []
    for d in domains:
        dl = (d or "").lower().rstrip(".")
        if dl and dl != tgt and not is_noise_domain(dl, tgt):
            out.append(dl)
    return out


def _blob(results: List[Result]) -> str:
    parts = []
    for r in results:
        flat: Dict[str, str] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        parts.append(" ".join(f"{k} {v}" for k, v in flat.items()))
        if getattr(r, "module", ""):
            parts.append(r.module)
    return " ".join(parts).lower()


def _classify_tech(techs: List[str], blob: str) -> Dict[str, List[str]]:
    hay = (" ".join(techs) + " " + blob).lower()
    out: Dict[str, List[str]] = {}
    for kind, table in _TECH.items():
        found = []
        for needle, label in table.items():
            if needle in hay and label not in found:
                found.append(label)
        if found:
            out[kind] = found
    return out


def _detect_cloud(blob: str) -> List[str]:
    found = []
    for needle, label in _CLOUD.items():
        if needle in blob and label not in found:
            found.append(label)
    return found


def _email_posture(blob: str) -> Dict[str, Any]:
    """Heuristic email-security score from SPF/DKIM/DMARC/MTA-STS signals."""
    score = 100
    notes = []
    has_spf = "v=spf1" in blob
    # unambiguous DKIM evidence only (a bare "p=" can come from DMARC)
    has_dkim = "v=dkim1" in blob or ("dkimstrength" in blob and "-bit" in blob)
    has_dmarc = "v=dmarc1" in blob
    p_none = "p=none" in blob
    has_mtasts = "mta-sts" in blob or "mtasts" in blob
    weak_dkim = "rsa 1024" in blob or ("dkim" in blob and "weak" in blob)
    if not has_spf:
        score -= 25; notes.append("no SPF")
    if not has_dmarc:
        score -= 30; notes.append("no DMARC")
    elif p_none:
        score -= 15; notes.append("DMARC p=none (monitor only)")
    if not has_dkim:
        score -= 15; notes.append("no DKIM selector found")
    elif weak_dkim:
        score -= 10; notes.append("weak DKIM key")
    if not has_mtasts:
        score -= 10; notes.append("no MTA-STS")
    score = max(0, min(100, score))
    grade = ("A" if score >= 85 else "B" if score >= 70 else
             "C" if score >= 55 else "D" if score >= 40 else "F")
    return {"score": score, "grade": grade,
            "spf": has_spf, "dkim": has_dkim, "dmarc": has_dmarc,
            "mta_sts": has_mtasts, "issues": notes or ["none detected"]}


def _leak_indicators(results: List[Result], blob: str) -> List[str]:
    hits = []
    for r in results:
        mod = getattr(r, "module", "").lower()
        if any(m in mod for m in ("breach", "leak", "pastebin", "threat")):
            data = getattr(r, "data", {}) or {}
            flat: Dict[str, str] = {}
            _flatten("", data, flat)
            for _k, v in flat.items():
                sval = str(v).lower()
                if any(s in sval for s in _LEAK_SIGNALS) and "no " not in sval[:4]:
                    hits.append(f"{r.module}: {str(v)[:80]}")
    return hits[:20]


def _screenshots(results: List[Result]) -> List[Dict[str, str]]:
    """Collect any visual-recon thumbnails captured during the scan."""
    shots = []
    for r in results:
        data = getattr(r, "data", {}) or {}
        img = data.get("screenshot")
        if isinstance(img, str) and img.startswith("data:image"):
            shots.append({"host": r.target,
                          "title": str(data.get("title", ""))[:80],
                          "url": data.get("final_url") or "",
                          "image": img})
    return shots[:24]


def _profiles(results: List[Result]) -> List[Dict[str, str]]:
    """Collect discovered social/username profiles + linked accounts so the
    person side of an investigation shows up in the graph, not just the domain
    side. Fed by usernamescan / emailfootprint / username / social modules."""
    seen = set()
    out: List[Dict[str, str]] = []

    def add(site: str, url: str, who: str = "", conf: str = ""):
        key = (site.lower(), url)
        if not url or key in seen:
            return
        seen.add(key)
        out.append({"site": site, "url": url, "identity": who,
                    "confidence": conf})

    for r in results:
        data = getattr(r, "data", {}) or {}
        who = data.get("username") or data.get("email") or ""
        # usernamescan: [{site, url, confidence}]
        for hit in data.get("found_on", []) or []:
            if isinstance(hit, dict):
                add(hit.get("site", ""), hit.get("url", ""), who,
                    hit.get("confidence", ""))
        # emailfootprint: Gravatar linked accounts
        for acc in data.get("linked_accounts", []) or []:
            if isinstance(acc, dict):
                add(acc.get("service", ""), acc.get("url", ""), who)
        # generic {site: url} maps used by the older username/social modules
        for k, v in data.items():
            if isinstance(v, str) and v.startswith(("http://", "https://")) \
                    and any(t in k.lower() for t in ("profile", "url", "account")):
                add(k, v, who)
    return out[:120]


def _cert_intel(results: List[Result]) -> Dict[str, Any]:
    issuers, sans, related = set(), set(), set()
    for r in results:
        mod = getattr(r, "module", "").lower()
        if not any(m in mod for m in ("cert", "ssl", "san", "chain", "tls")):
            continue
        flat: Dict[str, str] = {}
        _flatten("", getattr(r, "data", {}) or {}, flat)
        for k, v in flat.items():
            kl = k.lower()
            if "issuer" in kl and v:
                issuers.add(str(v)[:80])
            if ("san" in kl or "sibling" in kl or "subject" in kl) and v:
                for host in re.findall(r"[a-z0-9.\-*]+\.[a-z]{2,}", str(v).lower()):
                    (related if host.startswith("*.") else sans).add(host.lstrip("*."))
    return {"issuers": sorted(issuers)[:10],
            "san_domains": sorted(sans)[:60],
            "wildcard_or_related": sorted(related)[:30]}


def correlate(results: List[Result], target: str = "") -> Dict[str, Any]:
    """Fuse every module's output into one intelligence picture."""
    inv = build_inventory(results, target)
    tgt = (target or inv.get("target", "")).lower().rstrip(".")
    try:
        tgt = clean_host(tgt) if tgt else tgt
    except ValueError:
        pass
    blob = _blob(results)

    hosts = inv["hosts"]
    suffix = "." + tgt if tgt else ""
    subdomains = [h for h in hosts if tgt and (h == tgt or h.endswith(suffix))]
    other_domains = _clean_related(
        [h for h in hosts if h not in subdomains and not is_ip(h)], tgt)

    tech = _classify_tech(inv.get("technologies", []), blob)
    cloud = _detect_cloud(blob)
    email = _email_posture(blob)
    certs = _cert_intel(results)
    leaks = _leak_indicators(results, blob)
    screenshots = _screenshots(results)
    profiles = _profiles(results)

    assets = (len(hosts) + len(inv["ips"]) + len(inv["services"])
              + len(inv["emails"]) + len(inv["urls"]))

    return {
        "target": tgt or target,
        "counts": {
            "assets": assets,
            "domains": len(other_domains) + (1 if tgt else 0),
            "subdomains": len(subdomains),
            "ips": len(inv["ips"]),
            "services": len(inv["services"]),
            "technologies": sum(len(v) for v in tech.values()),
            "emails": len(inv["emails"]),
            "urls": len(inv["urls"]),
            "leak_indicators": len(leaks),
            "screenshots": len(screenshots),
            "profiles": len(profiles),
        },
        "subdomains": subdomains[:200],
        "related_domains": other_domains[:100],
        "ips": inv["ips"],
        "services": inv["services"],
        "emails": inv["emails"],
        "technologies": tech,
        "cloud": cloud or ["unknown / self-hosted"],
        "email_security": email,
        "certificates": certs,
        "leak_indicators": leaks,
        "screenshots": screenshots,
        "profiles": profiles,
        "note": "correlated from module output only — no additional scanning",
    }


def organization_profile(intel: Dict[str, Any],
                         results: List[Result]) -> Dict[str, Any]:
    """A plain-language organization profile derived from the intelligence:
    what the org appears to use, and its main risks. Rule-based (no LLM)."""
    from ..reporting_ext import score_findings

    uses: List[str] = []
    for kind in ("cms", "framework", "server", "cdn", "waf", "analytics"):
        uses.extend(intel["technologies"].get(kind, []))
    for c in intel.get("cloud", []):
        if c not in uses and "unknown" not in c:
            uses.append(c)

    risks: List[str] = []
    em = intel.get("email_security", {})
    if em.get("score", 100) < 70:
        risks.append(f"Weak email protection ({em.get('grade','?')}, "
                     f"score {em.get('score','?')}/100 — "
                     f"{', '.join(em.get('issues', [])[:2])})")
    dev = [s for s in intel.get("subdomains", [])
           if any(m in s.split(".")[0] for m in _DEV_MARKERS)]
    if dev:
        risks.append(f"Exposed non-production subdomain(s): "
                     f"{', '.join(dev[:3])}")
    blob = _blob(results)
    if any(w in blob for w in ("tlsv1.0", "tls 1.0", "tlsv1.1", "legacy protocol",
                               "weak cipher", "sslv3")):
        risks.append("Outdated TLS configuration (legacy protocol/cipher enabled)")
    if intel["counts"].get("leak_indicators"):
        risks.append(f"{intel['counts']['leak_indicators']} public leak indicator(s)")
    # pull in the top scored findings not already covered
    try:
        for f in score_findings(results).get("findings", [])[:5]:
            if f["severity"] in ("critical", "high"):
                line = f"{f['module']}: {str(f['detail'])[:70]}"
                if line not in risks:
                    risks.append(line)
    except Exception:  # noqa: BLE001
        pass

    return {
        "uses": uses or ["not fingerprinted"],
        "main_risks": risks[:6] or ["no major risks surfaced by this scan"],
        "cloud_footprint": intel.get("cloud", []),
        "email_grade": em.get("grade"),
    }
