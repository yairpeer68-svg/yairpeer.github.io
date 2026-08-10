"""Mobile-app association & deep-link recon.

A site that ships a mobile app leaves a public paper trail: the association
files that wire the app to the domain (universal links, Android app links), the
``app-ads.txt`` that authorises its ad sellers, and the custom URL schemes /
deep links its own pages fire. These enumerate an organisation's app portfolio,
the signing certificates behind it, and the deep-link surface — all from
unauthenticated GETs.

Detection only. FOR AUTHORISED SECURITY TESTING ONLY.
"""

from __future__ import annotations

import json
import re
from typing import Dict, List

from ..core import Context, Module, clean_host, ensure_scheme, register


def _get(ctx: Context, url: str):
    return ctx.session.get(url, timeout=ctx.timeout, allow_redirects=True)


# --------------------------------------------------------------------------- #
#  Apple universal links — /.well-known/apple-app-site-association
# --------------------------------------------------------------------------- #
@register
class AppleAppSiteAssociation(Module):
    id, name, category = "applinks", "Apple app-site-association (universal links)", "Mobile"
    target_kind = "domain"

    _PATHS = ["/.well-known/apple-app-site-association",
              "/apple-app-site-association"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        for path in self._PATHS:
            try:
                r = _get(ctx, base + path)
            except Exception:  # noqa: BLE001
                continue
            if r.status_code != 200:
                continue
            try:
                doc = r.json()
            except Exception:  # noqa: BLE001
                try:
                    doc = json.loads(r.text)
                except Exception:  # noqa: BLE001
                    continue
            app_ids, teams, paths = [], set(), []
            applinks = (doc.get("applinks") or {}) if isinstance(doc, dict) else {}
            details = applinks.get("details") or []
            for d in details if isinstance(details, list) else []:
                if not isinstance(d, dict):
                    continue
                ids = d.get("appIDs") or ([d["appID"]] if d.get("appID") else [])
                for aid in ids:
                    app_ids.append(aid)
                    if isinstance(aid, str) and "." in aid:
                        teams.add(aid.split(".", 1)[0])   # 10-char Team ID
                for comp in d.get("components", []) or []:
                    if isinstance(comp, dict) and comp.get("/"):
                        paths.append(comp["/"])
                paths.extend(p for p in (d.get("paths") or []) if isinstance(p, str))
            other = [k for k in ("webcredentials", "appclips", "activitycontinuation")
                     if isinstance(doc, dict) and k in doc]
            return self.ok(host, {
                "url": base + path,
                "app_ids": sorted(set(app_ids)) or "none",
                "apple_team_ids": sorted(teams) or "none",
                "shared_webcredentials": "webcredentials" in other,
                "other_services": other or "none",
                "deep_link_paths": paths[:40] or "none",
                "path_count": len(paths),
                "note": "universal-links config — Team IDs and bundle IDs map the "
                        "org's iOS apps; webcredentials means Associated Domains "
                        "password sharing is enabled for this domain."})
        return self.ok(host, {"apple_app_site_association": "not published"})


# --------------------------------------------------------------------------- #
#  Android app links — /.well-known/assetlinks.json
# --------------------------------------------------------------------------- #
@register
class AndroidAssetLinks(Module):
    id, name, category = "assetlinks", "Android assetlinks.json (app links)", "Mobile"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        url = base + "/.well-known/assetlinks.json"
        try:
            r = _get(ctx, url)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        if r.status_code != 200:
            return self.ok(host, {"assetlinks": "not published",
                                  "status": r.status_code})
        try:
            doc = r.json()
        except Exception:  # noqa: BLE001
            return self.ok(host, {"assetlinks": "present but not valid JSON"})
        packages: Dict[str, Dict[str, object]] = {}
        statements = doc if isinstance(doc, list) else [doc]
        for st in statements:
            if not isinstance(st, dict):
                continue
            tgt = st.get("target") or {}
            pkg = tgt.get("package_name")
            if not pkg:
                continue
            fps = tgt.get("sha256_cert_fingerprints") or []
            rels = st.get("relation") or []
            entry = packages.setdefault(pkg, {"fingerprints": [], "relations": []})
            entry["fingerprints"] = sorted(set(entry["fingerprints"]) | set(fps))
            entry["relations"] = sorted(set(entry["relations"]) | set(rels))
        handles_login = any("handle_all_urls" in r or "get_login_creds" in r
                            for e in packages.values() for r in e["relations"])
        return self.ok(host, {
            "url": url,
            "package_names": sorted(packages) or "none",
            "package_count": len(packages),
            "signing_fingerprints": {p: e["fingerprints"]
                                     for p, e in packages.items()},
            "shares_login_credentials": handles_login,
            "note": "Digital Asset Links wire Android apps to this domain — "
                    "package names enumerate the org's apps and the SHA-256 "
                    "fingerprints are their signing certificates."})


# --------------------------------------------------------------------------- #
#  app-ads.txt — authorised mobile-app ad sellers
# --------------------------------------------------------------------------- #
@register
class AppAdsTxt(Module):
    id, name, category = "appadstxt", "app-ads.txt (authorised app ad sellers)", "Mobile"
    target_kind = "domain"

    _LINE = re.compile(r"^\s*([a-z0-9.\-]+)\s*,\s*([^,]+)\s*,\s*(DIRECT|RESELLER)",
                       re.I)

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        url = base + "/app-ads.txt"
        try:
            r = _get(ctx, url)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        ct = r.headers.get("Content-Type", "")
        if r.status_code != 200 or "html" in ct.lower():
            return self.ok(host, {"app_ads_txt": "not published",
                                  "status": r.status_code})
        sellers: Dict[str, int] = {}
        direct = resellers = 0
        subdomains: List[str] = []
        for line in r.text.splitlines():
            line = line.strip()
            if line.lower().startswith("subdomain="):
                subdomains.append(line.split("=", 1)[1].strip())
                continue
            m = self._LINE.match(line)
            if not m:
                continue
            sellers[m.group(1).lower()] = sellers.get(m.group(1).lower(), 0) + 1
            if m.group(3).upper() == "DIRECT":
                direct += 1
            else:
                resellers += 1
        return self.ok(host, {
            "url": url,
            "ad_systems": sorted(sellers) or "none",
            "system_count": len(sellers),
            "direct_relationships": direct,
            "reseller_relationships": resellers,
            "declared_subdomains": subdomains or "none",
            "note": "app-ads.txt lists the ad networks authorised to sell this "
                    "org's mobile-app inventory — a public map of its monetisation "
                    "partners and any redirect subdomains it declares."})


# --------------------------------------------------------------------------- #
#  Deep-link / custom-URL-scheme surface (from the site's own pages)
# --------------------------------------------------------------------------- #
@register
class DeepLinkSurface(Module):
    id, name, category = "deeplinks", "Deep-link / custom URL scheme surface", "Mobile"
    target_kind = "url"

    # schemes that are the web's own — not app deep links. "intent" is an app
    # deep link but is reported separately below, so exclude it here too.
    _WEB_SCHEMES = {"http", "https", "ftp", "ws", "wss", "data", "javascript",
                    "mailto", "tel", "sms", "blob", "about", "file", "intent"}
    _SCHEME_RE = re.compile(r'(?:href|src|data-[\w-]+)=["\']([a-zA-Z][\w.+-]*)://'
                            r'[^"\']*["\']')
    _INTENT_RE = re.compile(r'intent://[^"\'\s]+#Intent;[^"\'\s]*', re.I)
    _JS_SCHEME_RE = re.compile(r'["\']([a-zA-Z][\w.+-]{2,}?)://[^"\']{0,80}["\']')

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host)
        try:
            r = _get(ctx, base)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        html = r.text[:400_000]
        schemes: Dict[str, int] = {}
        for m in self._SCHEME_RE.finditer(html):
            s = m.group(1).lower()
            if s not in self._WEB_SCHEMES:
                schemes[s] = schemes.get(s, 0) + 1
        # Android intent: links reveal the target package
        intents, packages = [], set()
        for m in self._INTENT_RE.finditer(html):
            intents.append(m.group(0)[:120])
            pm = re.search(r"package=([\w.]+)", m.group(0))
            if pm:
                packages.add(pm.group(1))
        # smart-app-banner meta (iOS) exposes the App Store ID
        banner = re.search(r'name=["\']apple-itunes-app["\'][^>]*content=["\']'
                           r'([^"\']+)', html, re.I)
        app_store_id = ""
        if banner:
            idm = re.search(r"app-id=(\d+)", banner.group(1))
            app_store_id = idm.group(1) if idm else banner.group(1)[:60]
        surface = len(schemes) + (1 if intents else 0) + (1 if app_store_id else 0)
        return self.ok(host, {
            "custom_schemes": schemes or "none",
            "android_intent_links": intents[:15] or "none",
            "intent_packages": sorted(packages) or "none",
            "ios_app_store_id": app_store_id or "not advertised",
            "surface_score": surface,
            "note": "custom URL schemes and intent:// links are the app's deep-link "
                    "entry points — each is an input path worth reviewing for "
                    "unvalidated deep-link handling. No links were followed into an app."})
