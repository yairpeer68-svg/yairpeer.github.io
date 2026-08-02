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

_CLOUD = {
    "aws": "AWS", "amazonaws": "AWS", "amazon": "AWS",
    "azure": "Azure", "windows.net": "Azure", "microsoft": "Azure",
    "gcp": "GCP", "googleusercontent": "GCP", "google cloud": "GCP",
    "googleapis": "GCP", "cloudflare": "Cloudflare",
    "digitalocean": "DigitalOcean", "linode": "Linode", "vultr": "Vultr",
    "heroku": "Heroku", "netlify": "Netlify", "vercel": "Vercel",
    "oracle cloud": "Oracle Cloud", "alibaba": "Alibaba Cloud",
    "hetzner": "Hetzner", "ovh": "OVH", "fastly": "Fastly", "akamai": "Akamai",
}

_LEAK_SIGNALS = ("breach", "leak", "pwned", "credential", "pastebin",
                 "exposed secret", "leaked", "password dump")

_DEV_MARKERS = ("dev", "staging", "stg", "test", "qa", "uat", "sandbox",
                "preprod", "beta", "internal")


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
            for k, v in flat.items():
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
    other_domains = [h for h in hosts if h not in subdomains and not is_ip(h)]

    tech = _classify_tech(inv.get("technologies", []), blob)
    cloud = _detect_cloud(blob)
    email = _email_posture(blob)
    certs = _cert_intel(results)
    leaks = _leak_indicators(results, blob)
    screenshots = _screenshots(results)

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
