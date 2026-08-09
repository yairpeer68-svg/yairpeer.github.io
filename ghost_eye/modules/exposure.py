"""Exposure / misconfiguration modules (features #65-#70).

All of these are *detection* only - they report that something is publicly
exposed. They never download, modify, or exploit the exposed resource.
"""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import (Context, Module, Result, clean_host, ensure_scheme, register)


@register
class VcsExposure(Module):
    id, name, category = "vcs", "Exposed .git / .svn", "Exposure"
    target_kind = "url"

    _PATHS = {
        ".git/HEAD": "ref:",
        ".git/config": "[core]",
        ".gitignore": "",
        ".svn/entries": "",
        ".svn/wc.db": "SQLite",
        ".hg/requires": "",
        ".bzr/branch-format": "",
    }

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        exposed: Dict[str, str] = {}
        for path, sig in self._PATHS.items():
            try:
                r = ctx.session.get(f"{base}/{path}", timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code == 200 and (not sig or sig in r.text[:200]):
                    exposed[path] = f"EXPOSED (HTTP 200, {len(r.content)} bytes)"
            except Exception:
                continue
        return self.ok(host, {
            "exposed": exposed or "none found",
            "risk": "exposed VCS metadata can leak full source history"
                    if exposed else "",
        })


@register
class BackupFiles(Module):
    id, name, category = "backups", "Exposed backup / config files", "Exposure"
    target_kind = "url"

    def _candidates(self, host: str) -> List[str]:
        base_names = [host.split(".")[0], "backup", "www", "site", "db",
                      "database", "dump", "old", "config", "wp-config"]
        exts = [".zip", ".tar.gz", ".tar", ".sql", ".bak", ".old", ".save",
                ".swp", ".env", ".config", ".conf", ".ini", "~"]
        out = []
        for n in base_names:
            for e in exts:
                out.append(f"{n}{e}")
        out += [".env", ".env.local", ".env.production", "config.php.bak",
                "wp-config.php.bak", "web.config.bak", ".DS_Store"]
        return out

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        found: Dict[str, str] = {}

        def probe(name: str):
            try:
                r = ctx.session.head(f"{base}/{name}", timeout=ctx.timeout,
                                     allow_redirects=False)
                if r.status_code == 405:  # HEAD not allowed, retry GET
                    r = ctx.session.get(f"{base}/{name}", timeout=ctx.timeout,
                                        allow_redirects=False, stream=True)
                if r.status_code == 200:
                    size = r.headers.get("Content-Length", "?")
                    return name, f"HTTP 200 ({size} bytes)"
            except Exception:
                return None
            return None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(probe, self._candidates(host)):
                if res:
                    found[res[0]] = res[1]
        return self.ok(host, {"exposed_files": found or "none found"})


@register
class BucketFinder(Module):
    id, name, category = "buckets", "Open cloud buckets (S3/GCS/Azure)", "Exposure"
    target_kind = "domain"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        name = host.split(".")[0]
        org = re.sub(r"[^a-z0-9\-]", "", name.lower())
        permutations = [org, f"{org}-backup", f"{org}-dev", f"{org}-prod",
                        f"{org}-assets", f"{org}-static", f"{org}-media",
                        f"{org}-data", f"{org}-files", f"{org}-uploads",
                        f"backup-{org}", f"{org}-private", f"{org}-public"]
        targets: Dict[str, str] = {}
        for p in permutations:
            targets[f"https://{p}.s3.amazonaws.com"] = "S3"
            targets[f"https://storage.googleapis.com/{p}"] = "GCS"
            targets[f"https://{p}.blob.core.windows.net/{p}"] = "Azure"

        results: Dict[str, str] = {}

        def probe(item):
            url, kind = item
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code == 200 and ("<ListBucketResult" in r.text
                                             or "<EnumerationResults" in r.text
                                             or "<Contents>" in r.text):
                    return url, f"{kind}: PUBLIC / listable"
                if r.status_code in (403,) and ("AccessDenied" in r.text
                                                or "AuthenticationRequired" in r.text):
                    return url, f"{kind}: exists but private"
            except Exception:
                return None
            return None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(probe, targets.items()):
                if res:
                    results[res[0]] = res[1]
        return self.ok(host, {"buckets": results or "none found",
                              "note": "PUBLIC/listable buckets may leak data"})


@register
class DirListing(Module):
    id, name, category = "dirlisting", "Directory listing exposure", "Exposure"
    target_kind = "url"

    _DIRS = ["", "uploads/", "files/", "images/", "img/", "backup/", "backups/",
             "data/", "docs/", "downloads/", "media/", "static/", "assets/",
             "tmp/", "temp/", "logs/", "old/", "test/", "private/", "admin/"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        listed: List[str] = []

        def probe(d: str):
            try:
                r = ctx.session.get(f"{base}/{d}", timeout=ctx.timeout)
                if r.status_code == 200 and re.search(
                        r"<title>\s*Index of|Directory listing for|\[To Parent Directory\]",
                        r.text, re.I):
                    return f"{base}/{d}"
            except Exception:
                return None
            return None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(probe, self._DIRS):
                if res:
                    listed.append(res)
        return self.ok(host, {"open_listings": listed or "none found"})


@register
class AdminPages(Module):
    id, name, category = "admin", "Exposed login / admin panels", "Exposure"
    target_kind = "url"

    _PATHS = ["admin", "administrator", "admin/login", "wp-admin", "wp-login.php",
              "login", "signin", "user/login", "cpanel", "webmail", "phpmyadmin",
              "adminer.php", "manager/html", "console", "portal", "auth/login",
              "admin.php", "panel", "dashboard"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        found: Dict[str, int] = {}

        def probe(path: str):
            try:
                r = ctx.session.get(f"{base}/{path}", timeout=ctx.timeout,
                                    allow_redirects=True)
                if r.status_code in (200, 401, 403):
                    looks_login = bool(re.search(
                        r"type=[\"']password[\"']|login|sign in|username",
                        r.text, re.I))
                    if looks_login or r.status_code in (401, 403):
                        return path, r.status_code
            except Exception:
                return None
            return None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(probe, self._PATHS):
                if res:
                    found[res[0]] = res[1]
        return self.ok(host, {"login_panels": found or "none found"})


@register
class OpenDashboards(Module):
    id, name, category = "dashboards", "Unauthenticated dashboards/services", "Exposure"
    target_kind = "url"

    # path -> signature that means "open without auth"
    _CHECKS = {
        "": "",
        "actuator/env": "activeProfiles",
        "actuator/health": '"status"',
        "metrics": "# HELP",
        "debug/vars": "cmdline",
        "grafana/login": "Grafana",
        "kibana": "kibana",
        "_cat/indices": "health",          # elasticsearch
        "solr/": "Solr Admin",
        "phpinfo.php": "phpinfo()",
        "info.php": "phpinfo()",
        "server-status": "Apache Server Status",
        "jenkins/": "Jenkins",
        "swagger-ui.html": "swagger",
        ".git/config": "[core]",
    }

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        base = ensure_scheme(host).rstrip("/")
        exposed: Dict[str, str] = {}

        def probe(item):
            path, sig = item
            try:
                r = ctx.session.get(f"{base}/{path}", timeout=ctx.timeout)
                if r.status_code == 200 and (not sig or sig.lower() in r.text.lower()):
                    return path or "/", f"open (HTTP 200, matched '{sig or 'root'}')"
            except Exception:
                return None
            return None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for res in ex.map(probe, self._CHECKS.items()):
                if res:
                    exposed[res[0]] = res[1]
        return self.ok(host, {"open_services": exposed or "none found"})
