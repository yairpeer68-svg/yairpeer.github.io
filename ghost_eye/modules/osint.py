"""OSINT modules (features #48-#57).

These gather information that is already public. Use only against assets you
are authorised to assess (e.g. your own org's external footprint).
"""

from __future__ import annotations

import os
import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Set

from ..core import (Console, Context, Module, Result, clean_host, ensure_scheme,
                    register)

_EMAIL_RE = re.compile(r"[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}")


@register
class EmailHarvest(Module):
    id, name, category = "emails", "Email harvesting (public)", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        emails: Set[str] = set()
        pages = [ensure_scheme(host), ensure_scheme(host).rstrip("/") + "/contact",
                 ensure_scheme(host).rstrip("/") + "/about"]
        for url in pages:
            try:
                txt = ctx.session.get(url, timeout=ctx.timeout).text
                emails.update(m.lower() for m in _EMAIL_RE.findall(txt)
                              if m.lower().endswith(host) or host in m.lower())
            except Exception:
                continue
        # crt.sh certificate contact leakage is sometimes useful too
        return self.ok(host, {"count": len(emails), "emails": sorted(emails)})


@register
class EmailAuth(Module):
    id, name, category = "emailauth", "SPF / DKIM / DMARC", "OSINT"
    target_kind = "domain"
    needs = ["dnspython"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as exc:
            return self.fail(target, str(exc))

        def txt(name):
            try:
                return [b"".join(r.strings).decode("utf-8", "replace")
                        for r in dns.resolver.resolve(name, "TXT")]
            except Exception:
                return []

        spf = [t for t in txt(host) if t.lower().startswith("v=spf1")]
        dmarc = [t for t in txt(f"_dmarc.{host}") if "v=dmarc1" in t.lower()]
        findings = []
        if not spf:
            findings.append("no SPF record - domain can be spoofed")
        elif any("+all" in s or "?all" in s for s in spf):
            findings.append("weak SPF (+all/?all) - effectively no protection")
        if not dmarc:
            findings.append("no DMARC record - no anti-spoofing policy")
        elif any("p=none" in d.lower() for d in dmarc):
            findings.append("DMARC p=none - monitoring only, not enforced")
        return self.ok(host, {"spf": spf or "(none)", "dmarc": dmarc or "(none)",
                              "spoofable_findings": findings or ["looks configured"]})


# NOTE: the HIBP breach check was removed in v3.3 - its API now requires a paid
# subscription. There is no free no-key equivalent for domain breach search.


@register
class UsernameSearch(Module):
    id, name, category = "username", "Username search (public profiles)", "OSINT"
    target_kind = "host"  # actually a username

    _SITES = {
        "GitHub": "https://github.com/{u}",
        "GitLab": "https://gitlab.com/{u}",
        "Twitter/X": "https://x.com/{u}",
        "Instagram": "https://www.instagram.com/{u}/",
        "Reddit": "https://www.reddit.com/user/{u}",
        "Medium": "https://medium.com/@{u}",
        "Dev.to": "https://dev.to/{u}",
        "Keybase": "https://keybase.io/{u}",
        "Telegram": "https://t.me/{u}",
        "TikTok": "https://www.tiktok.com/@{u}",
        "Pinterest": "https://www.pinterest.com/{u}/",
        "HackerNews": "https://news.ycombinator.com/user?id={u}",
    }

    def run(self, target: str, ctx: Context) -> Result:
        username = re.sub(r"[^A-Za-z0-9_.\-]", "", target.strip())
        if not username:
            return self.fail(target, "invalid username")
        found: Dict[str, str] = {}

        def check(item):
            site, tpl = item
            url = tpl.format(u=username)
            try:
                r = ctx.session.get(url, timeout=ctx.timeout, allow_redirects=True)
                if r.status_code == 200 and username.lower() in r.text.lower()[:5000]:
                    return site, url
                if r.status_code == 200 and site in ("GitHub", "GitLab", "Keybase"):
                    return site, url
            except Exception:
                return None
            return None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(check, self._SITES.items()):
                if res:
                    found[res[0]] = res[1]
        return self.ok(username, {"found_on": found or "no public profiles matched"})


@register
class DorkHelper(Module):
    id, name, category = "dorks", "Google dorking helper", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        dorks = {
            "exposed files": f'site:{host} (ext:sql OR ext:env OR ext:log OR ext:bak)',
            "config files": f'site:{host} (ext:conf OR ext:config OR ext:ini OR ext:yml)',
            "documents": f'site:{host} (ext:pdf OR ext:doc OR ext:xls OR ext:csv)',
            "login pages": f'site:{host} (inurl:login OR inurl:signin OR inurl:admin)',
            "directory listing": f'site:{host} intitle:"index of"',
            "exposed errors": f'site:{host} "sql syntax near" OR "fatal error"',
            "subdomains": f'site:*.{host} -www',
            "pastebin leaks": f'site:pastebin.com {host}',
            "github mentions": f'site:github.com {host}',
            "credentials": f'site:{host} (intext:password OR intext:apikey)',
        }
        urls = {k: "https://www.google.com/search?q=" +
                __import__("urllib.parse", fromlist=["quote"]).quote(v)
                for k, v in dorks.items()}
        return self.ok(host, {"dorks": dorks, "search_urls": urls,
                              "note": "open these in a browser; automating Google breaks its ToS"})


@register
class ExifMeta(Module):
    id, name, category = "exif", "EXIF / metadata extractor", "OSINT"
    target_kind = "url"  # accepts a URL or a local file path

    def run(self, target: str, ctx: Context) -> Result:
        # Resolve to local bytes (download if URL, else read file)
        path = target.strip()
        tmp = None
        try:
            if re.match(r"^https?://", path):
                data = ctx.session.get(path, timeout=ctx.timeout).content
                tmp = "/tmp/ghosteye_meta_dl"
                with open(tmp, "wb") as fh:
                    fh.write(data)
                path = tmp
            if not os.path.exists(path):
                return self.fail(target, "file not found / not downloadable")

            meta: Dict[str, object] = {}
            # images via Pillow
            try:
                from PIL import Image
                from PIL.ExifTags import GPSTAGS, TAGS
                img = Image.open(path)
                meta["format"] = img.format
                meta["size"] = f"{img.width}x{img.height}"
                raw = getattr(img, "_getexif", lambda: None)()
                if raw:
                    exif = {TAGS.get(k, k): v for k, v in raw.items()}
                    gps = exif.get("GPSInfo")
                    if gps:
                        exif["GPSInfo"] = {GPSTAGS.get(k, k): v for k, v in gps.items()}
                    meta["exif"] = {k: str(v)[:120] for k, v in exif.items()}
            except Exception:
                pass
            # anything (incl. PDF/docx) via exiftool if present
            from ..core import have_binary, run_cmd
            if have_binary("exiftool"):
                meta["exiftool"] = run_cmd(["exiftool", path],
                                           timeout=ctx.timeout).splitlines()
            if not meta:
                return self.fail(target, "no metadata (install Pillow or exiftool)")
            return self.ok(target, meta)
        finally:
            if tmp and os.path.exists(tmp):
                try:
                    os.remove(tmp)
                except OSError:
                    pass


@register
class Validate(Module):
    id, name, category = "validate", "Email / phone validation", "OSINT"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        value = target.strip()
        data: Dict[str, object] = {"input": value}
        if "@" in value:
            valid = bool(_EMAIL_RE.fullmatch(value))
            data["type"] = "email"
            data["syntax_valid"] = valid
            if valid:
                domain = value.split("@")[1]
                try:
                    import dns.resolver
                    mx = dns.resolver.resolve(domain, "MX")
                    data["mx_records"] = [str(m.exchange).rstrip(".") for m in mx]
                    data["deliverable_domain"] = True
                except Exception:
                    data["deliverable_domain"] = False
        else:
            data["type"] = "phone"
            try:
                import phonenumbers
                from phonenumbers import carrier, geocoder
                num = phonenumbers.parse(value, None)
                data["valid"] = phonenumbers.is_valid_number(num)
                data["country"] = geocoder.description_for_number(num, "en")
                data["carrier"] = carrier.name_for_number(num, "en")
                data["type_e164"] = phonenumbers.format_number(
                    num, phonenumbers.PhoneNumberFormat.E164)
            except ImportError:
                data["note"] = "pip install phonenumbers for full phone validation"
                data["basic_valid"] = bool(re.fullmatch(r"\+?\d[\d\s\-]{6,}\d", value))
            except Exception as exc:  # noqa: BLE001
                data["error"] = str(exc)
        return self.ok(value, data)


@register
class GithubSecrets(Module):
    id, name, category = "github", "Exposed secrets on GitHub", "OSINT"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        token = os.environ.get("GITHUB_TOKEN")
        headers = {"Accept": "application/vnd.github+json", "User-Agent": "GhostEye"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        queries = [
            f'"{host}" password', f'"{host}" api_key', f'"{host}" secret',
            f'"{host}" filename:.env', f'"{host}" AWS_SECRET',
        ]
        hits: Dict[str, List[str]] = {}
        for q in queries:
            try:
                r = ctx.session.get("https://api.github.com/search/code",
                                    params={"q": q, "per_page": 5},
                                    headers=headers, timeout=ctx.timeout)
                if r.status_code == 401:
                    return self.fail(host, "GitHub code search needs a token "
                                           "(set GITHUB_TOKEN)")
                if r.status_code == 403:
                    hits[q] = ["rate limited - set GITHUB_TOKEN for more"]
                    continue
                if r.status_code == 200:
                    items = r.json().get("items", [])
                    hits[q] = [it["html_url"] for it in items] or ["no results"]
            except Exception as exc:  # noqa: BLE001
                hits[q] = [f"error: {exc}"]
        return self.ok(host, {"queries": hits,
                              "note": "review matches for YOUR leaked secrets, then rotate them"})
