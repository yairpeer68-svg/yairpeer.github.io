"""Mobile app static analysis (v3.8 new category). Finds mobile app packages
linked from a site and statically inspects a downloadable APK for leaked
endpoints, secrets and dangerous permissions. Detection only.
FOR AUTHORISED SECURITY TESTING ONLY."""

from __future__ import annotations

import io
import re
import zipfile
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

_MAX_APK = 25 * 1024 * 1024          # do not download packages larger than 25 MB

_SECRETS = {
    "Google API key": re.compile(r"AIza[0-9A-Za-z_\-]{35}"),
    "AWS access key": re.compile(r"AKIA[0-9A-Z]{16}"),
    "Firebase URL": re.compile(r"https://[a-z0-9\-]+\.firebaseio\.com"),
    "Slack token": re.compile(r"xox[baprs]-[0-9A-Za-z\-]{10,}"),
    "Private key block": re.compile(r"-----BEGIN (?:RSA |EC )?PRIVATE KEY-----"),
    "Bearer/JWT": re.compile(r"eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\."
                             r"[A-Za-z0-9_\-]{10,}"),
    "Generic secret": re.compile(r"(?:api[_-]?key|secret|password|token)"
                                 r"['\"]?\s*[:=]\s*['\"][A-Za-z0-9_\-]{12,}",
                                 re.I),
}
_URL_RE = re.compile(rb"https?://[A-Za-z0-9._~:/?#\[\]@!$&'()*+,;=%\-]{6,120}")
_DANGEROUS_PERMS = [
    "READ_SMS", "SEND_SMS", "RECEIVE_SMS", "READ_CONTACTS", "RECORD_AUDIO",
    "ACCESS_FINE_LOCATION", "READ_CALL_LOG", "CAMERA", "READ_EXTERNAL_STORAGE",
    "WRITE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES",
    "READ_PHONE_STATE", "GET_ACCOUNTS", "WRITE_SETTINGS",
]


@register
class MobileAppScan(Module):
    id, name, category = "mobileapp", "Mobile app (APK/IPA) static analysis", "Mobile"
    target_kind = "url"

    def _find_links(self, base: str, html: str) -> Dict[str, List[str]]:
        apks, ipas, stores = [], [], []
        for link in re.findall(r'href=["\']([^"\']+)["\']', html, re.I):
            low = link.lower()
            full = link if link.startswith("http") else base.rstrip("/") + "/" + link.lstrip("/")
            if low.endswith(".apk"):
                apks.append(full)
            elif low.endswith(".ipa"):
                ipas.append(full)
            elif "play.google.com/store" in low or "apps.apple.com" in low \
                    or "appgallery" in low:
                stores.append(link)
        return {"apk": apks, "ipa": ipas, "store": stores}

    def _scan_apk(self, url: str, ctx) -> dict:
        out: dict = {"source": url}
        try:
            r = ctx.session.get(url, timeout=ctx.timeout + 30, stream=True)
            size = int(r.headers.get("Content-Length") or 0)
            if size and size > _MAX_APK:
                return {"source": url, "skipped": f"too large ({size} bytes)"}
            raw = r.content
            if len(raw) > _MAX_APK:
                return {"source": url, "skipped": "too large (streamed)"}
        except Exception as e:  # noqa: BLE001
            return {"source": url, "error": f"download failed: {e}"}
        try:
            zf = zipfile.ZipFile(io.BytesIO(raw))
        except Exception as e:  # noqa: BLE001
            return {"source": url, "error": f"not a valid APK/zip: {e}"}
        secrets: Dict[str, int] = {}
        urls: set = set()
        perms: set = set()
        manifest = b""
        for name in zf.namelist():
            if name == "AndroidManifest.xml":
                try:
                    manifest = zf.read(name)
                except Exception:  # noqa: BLE001
                    manifest = b""
            if name.endswith((".dex", ".xml", ".json", ".properties", ".txt",
                              ".js", ".so")) or name == "resources.arsc":
                try:
                    blob = zf.read(name)
                except Exception:  # noqa: BLE001
                    continue
                for u in _URL_RE.findall(blob)[:200]:
                    urls.add(u.decode("latin-1"))
                text = blob.decode("latin-1", "ignore")
                for label, rx in _SECRETS.items():
                    hits = len(rx.findall(text))
                    if hits:
                        secrets[label] = secrets.get(label, 0) + hits
        # dangerous permissions appear as plaintext strings in the binary manifest
        man_text = manifest.decode("latin-1", "ignore")
        for perm in _DANGEROUS_PERMS:
            if perm in man_text:
                perms.add(perm)
        interesting = sorted(u for u in urls
                             if not any(s in u for s in
                                        ("schemas.android.com", "w3.org",
                                         "apache.org", "google.com/apis")))
        out.update({
            "leaked_secrets": secrets or "none",
            "dangerous_permissions": sorted(perms) or "none",
            "endpoints_in_binary": interesting[:60],
            "endpoint_count": len(interesting),
            "risk": "high" if secrets else "medium" if interesting else "low",
        })
        return out

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host)
        try:
            html = ctx.session.get(base, timeout=ctx.timeout).text[:250_000]
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"request failed: {e}")
        links = self._find_links(base, html)
        if not links["apk"] and not links["ipa"] and not links["store"]:
            return self.ok(host, {
                "app_packages": "none linked from this page",
                "note": "no .apk/.ipa or app-store links found; point this module "
                        "directly at an APK download URL to statically analyse it"})
        result = {
            "apk_links": links["apk"][:10] or "none",
            "ipa_links": links["ipa"][:10] or "none",
            "store_links": links["store"][:10] or "none",
        }
        # statically analyse the first downloadable APK, if any
        if links["apk"]:
            result["static_analysis"] = self._scan_apk(links["apk"][0], ctx)
        elif base.lower().endswith(".apk"):
            result["static_analysis"] = self._scan_apk(base, ctx)
        result["note"] = ("APK strings are scanned for endpoints/secrets and the "
                          "binary manifest for dangerous permissions. IPA files "
                          "are listed only. Detection only.")
        return self.ok(host, result)
