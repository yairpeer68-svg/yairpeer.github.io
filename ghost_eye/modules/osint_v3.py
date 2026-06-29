"""OSINT Advanced modules (features #56-#63). Detection only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List
from urllib.parse import quote

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class JobsTechStack(Module):
    id, name, category = "jobstech", "Job posting tech stack extraction", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}
        domain = host.split(":")[0]

        # check careers / jobs pages
        base = ensure_scheme(host).rstrip("/")
        career_paths = ["/careers", "/jobs", "/hiring", "/work-with-us",
                        "/join", "/openings", "/vacancies"]

        tech_keywords = {
            "languages": ["python", "java", "javascript", "typescript", "golang",
                          "rust", "ruby", "php", "c++", "c#", "scala", "kotlin",
                          "swift", "elixir", "haskell"],
            "frameworks": ["react", "angular", "vue", "django", "flask", "fastapi",
                           "spring", "rails", "laravel", "express", "nextjs",
                           "nestjs", ".net", "gin", "fiber"],
            "databases": ["postgresql", "mysql", "mongodb", "redis", "elasticsearch",
                          "dynamodb", "cassandra", "cockroachdb", "neo4j",
                          "clickhouse", "timescaledb", "influxdb"],
            "infrastructure": ["aws", "gcp", "azure", "kubernetes", "docker",
                                "terraform", "ansible", "jenkins", "circleci",
                                "github actions", "gitlab ci", "datadog",
                                "prometheus", "grafana", "splunk"],
            "messaging": ["kafka", "rabbitmq", "pulsar", "nats", "sqs", "pubsub"],
        }

        for path in career_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:80_000].lower()
                for category, keywords in tech_keywords.items():
                    for kw in keywords:
                        if kw in body:
                            findings.setdefault(category, []).append(kw)
                if findings:
                    findings["source"] = path
                    break
            except Exception:
                continue

        # deduplicate
        for k in list(findings):
            if isinstance(findings[k], list):
                findings[k] = sorted(set(findings[k]))

        return self.ok(host, {
            "tech_stack": findings or "no career pages found",
            "risk": "informational",
            "note": "job postings reveal internal technology choices"
        })


@register
class FeedFinder(Module):
    id, name, category = "feedfind", "RSS/Atom/JSON feed discovery", "OSINT"
    target_kind = "url"

    _PATHS = ["/feed", "/rss", "/atom", "/feed.xml", "/rss.xml",
              "/atom.xml", "/index.xml", "/feed.json", "/blog/feed",
              "/news/feed", "/feed/rss", "/feeds", "/.rss",
              "/sitemap-news.xml"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        feeds = {}

        # check link tags in HTML
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:30_000]
            links = re.findall(
                r'<link[^>]+type=["\']application/(?:rss|atom)\+xml["\'][^>]*href=["\']([^"\']+)',
                body, re.I)
            links += re.findall(
                r'<link[^>]+href=["\']([^"\']+)["\'][^>]*type=["\']application/(?:rss|atom)\+xml',
                body, re.I)
            for link in links[:5]:
                feeds[link[:80]] = {"source": "link_tag"}
        except Exception:
            pass

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200:
                    ct = r.headers.get("Content-Type", "").lower()
                    body = r.text[:2000].lower()
                    if any(kw in ct for kw in ["xml", "rss", "atom", "json"]) or \
                       any(kw in body for kw in ["<rss", "<feed", "<atom", '"items"']):
                        info = {"status": 200, "content_type": ct[:50]}
                        if "<rss" in body:
                            info["format"] = "RSS"
                        elif "<feed" in body:
                            info["format"] = "Atom"
                        elif body.strip().startswith("{"):
                            info["format"] = "JSON Feed"
                        return path, info
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    feeds[path] = info

        return self.ok(host, {
            "feeds": feeds or "none found",
            "count": len(feeds),
            "risk": "informational",
        })


@register
class SitemapIntel(Module):
    id, name, category = "sitemapintel", "Sitemap intelligence extraction", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        sitemap_paths = ["/sitemap.xml", "/sitemap_index.xml",
                         "/sitemap-index.xml", "/sitemaps.xml",
                         "/sitemap1.xml", "/post-sitemap.xml",
                         "/page-sitemap.xml", "/wp-sitemap.xml"]

        for path in sitemap_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:200_000]
                if "<urlset" not in body.lower() and "<sitemapindex" not in body.lower():
                    continue

                urls = re.findall(r'<loc>([^<]+)</loc>', body)
                info = {"url_count": len(urls)}

                # extract interesting paths
                admin_urls = [u for u in urls if any(
                    kw in u.lower() for kw in
                    ["/admin", "/dashboard", "/internal", "/api",
                     "/staging", "/dev", "/test", "/debug"])]
                if admin_urls:
                    info["interesting_urls"] = admin_urls[:15]

                # extract path patterns
                segments = set()
                for u in urls[:500]:
                    parts = u.replace(base, "").split("/")
                    if len(parts) > 1:
                        segments.add(parts[1])
                if segments:
                    info["top_level_sections"] = sorted(segments)[:20]

                # last modified dates
                lastmods = re.findall(r'<lastmod>([^<]+)</lastmod>', body)
                if lastmods:
                    info["newest"] = max(lastmods)
                    info["oldest"] = min(lastmods)

                findings[path] = info
            except Exception:
                continue

        return self.ok(host, {
            "sitemaps": findings or "none found",
            "risk": "informational",
        })


@register
class RobotsDiff(Module):
    id, name, category = "robotsdiff", "robots.txt hidden path extraction", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base + "/robots.txt", timeout=ctx.timeout)
            if r.status_code != 200:
                return self.ok(host, {"robots": "not found", "risk": "informational"})

            body = r.text[:20_000]
            findings["size"] = len(body)

            disallow = re.findall(r'^Disallow:\s*(.+)', body, re.MULTILINE | re.I)
            allow = re.findall(r'^Allow:\s*(.+)', body, re.MULTILINE | re.I)
            sitemaps = re.findall(r'^Sitemap:\s*(.+)', body, re.MULTILINE | re.I)

            disallow = [d.strip() for d in disallow if d.strip()]
            allow = [a.strip() for a in allow if a.strip()]

            findings["disallow_count"] = len(disallow)
            findings["allow_count"] = len(allow)

            interesting = [d for d in disallow if any(
                kw in d.lower() for kw in
                ["/admin", "/api", "/internal", "/private", "/staging",
                 "/dev", "/debug", "/backup", "/tmp", "/config",
                 "/secret", "/wp-admin", "/cpanel", "/phpmyadmin",
                 "/login", "/auth", "/console", "/monitor"])]
            if interesting:
                findings["sensitive_disallowed"] = interesting[:20]

            if sitemaps:
                findings["sitemaps"] = [s.strip() for s in sitemaps[:5]]

            # check user-agents
            agents = re.findall(r'^User-agent:\s*(.+)', body, re.MULTILINE | re.I)
            agents = [a.strip() for a in agents if a.strip() != "*"]
            if agents:
                findings["specific_user_agents"] = list(set(agents))[:10]

            # crawl-delay
            delay = re.findall(r'^Crawl-delay:\s*(\d+)', body, re.MULTILINE | re.I)
            if delay:
                findings["crawl_delay"] = int(delay[0])

        except Exception as e:
            return self.fail(host, str(e)[:80])

        risk = "informational"
        if findings.get("sensitive_disallowed"):
            risk = "LOW"

        return self.ok(host, {
            "robots": findings,
            "risk": risk,
            "note": "disallowed paths reveal hidden application areas"
        })


@register
class OrgProfile(Module):
    id, name, category = "orgprofile", "Organization profile aggregation", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        domain = host.split(":")[0]
        findings = {}

        # homepage metadata
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:50_000]

            title = re.search(r'<title>([^<]+)</title>', body, re.I)
            if title:
                findings["title"] = title.group(1).strip()[:100]

            desc = re.search(
                r'<meta[^>]+name=["\']description["\'][^>]+content=["\']([^"\']+)',
                body, re.I)
            if desc:
                findings["description"] = desc.group(1).strip()[:200]

            og_data = {}
            for prop in ["og:title", "og:description", "og:image", "og:type",
                         "og:site_name"]:
                m = re.search(
                    r'<meta[^>]+property=["\']' + re.escape(prop) +
                    r'["\'][^>]+content=["\']([^"\']+)', body, re.I)
                if m:
                    og_data[prop] = m.group(1)[:100]
            if og_data:
                findings["opengraph"] = og_data

            # social links
            social_patterns = {
                "twitter": r'(?:twitter\.com|x\.com)/([a-zA-Z0-9_]+)',
                "linkedin": r'linkedin\.com/(?:company|in)/([a-zA-Z0-9_-]+)',
                "github": r'github\.com/([a-zA-Z0-9_-]+)',
                "facebook": r'facebook\.com/([a-zA-Z0-9._-]+)',
                "instagram": r'instagram\.com/([a-zA-Z0-9._]+)',
                "youtube": r'youtube\.com/(?:c/|channel/|@)([a-zA-Z0-9_-]+)',
            }
            social = {}
            for platform, pattern in social_patterns.items():
                m = re.search(pattern, body, re.I)
                if m:
                    social[platform] = m.group(1)
            if social:
                findings["social"] = social

            # schema.org
            schema = re.findall(r'"@type"\s*:\s*"([^"]+)"', body)
            if schema:
                findings["schema_types"] = list(set(schema))[:10]

        except Exception:
            pass

        # humans.txt
        try:
            r = ctx.session.get(base + "/humans.txt", timeout=ctx.timeout)
            if r.status_code == 200 and len(r.text) > 10:
                findings["humans_txt"] = r.text[:500]
        except Exception:
            pass

        return self.ok(host, {
            "profile": findings or "minimal info found",
            "risk": "informational",
        })


@register
class CommitAuthors(Module):
    id, name, category = "commitauthors", "Public git commit author enumeration", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # exposed .git
        git_paths = ["/.git/logs/HEAD", "/.git/config",
                     "/.git/COMMIT_EDITMSG"]
        for path in git_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:30_000]
                if path == "/.git/logs/HEAD":
                    authors = re.findall(r'<([^>]+@[^>]+)>', body)
                    if authors:
                        findings["git_authors"] = list(set(authors))[:20]
                        findings["source"] = path
                elif path == "/.git/config":
                    if "[remote" in body or "[core]" in body:
                        urls = re.findall(r'url\s*=\s*(.+)', body)
                        findings["git_remotes"] = [u.strip() for u in urls[:5]]
                        findings["git_config_exposed"] = True
            except Exception:
                continue

        # check page for commit hashes / git references
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:50_000]
            commits = re.findall(r'\b[a-f0-9]{40}\b', body)
            short = re.findall(r'\b[a-f0-9]{7,8}\b', body)
            if commits:
                findings["full_commit_hashes"] = list(set(commits))[:10]
        except Exception:
            pass

        risk = "informational"
        if findings.get("git_config_exposed"):
            risk = "HIGH"
        elif findings.get("git_authors"):
            risk = "MEDIUM"

        return self.ok(host, {
            "git_intel": findings or "no git exposure found",
            "risk": risk,
        })


@register
class WhoisTimeline(Module):
    id, name, category = "whoistimeline", "WHOIS history timeline", "OSINT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        domain = host.split(":")[0]
        findings = {}

        try:
            import whois as _whois
            w = _whois.whois(domain)
            if w:
                findings["registrar"] = getattr(w, "registrar", None)
                findings["creation_date"] = str(getattr(w, "creation_date", ""))
                findings["expiration_date"] = str(getattr(w, "expiration_date", ""))
                findings["updated_date"] = str(getattr(w, "updated_date", ""))
                findings["name_servers"] = getattr(w, "name_servers", [])
                if isinstance(findings["name_servers"], list):
                    findings["name_servers"] = findings["name_servers"][:10]
                findings["status"] = getattr(w, "status", None)
                if isinstance(findings["status"], list):
                    findings["status"] = findings["status"][:5]
                findings["dnssec"] = getattr(w, "dnssec", None)

                # calculate domain age
                cd = getattr(w, "creation_date", None)
                if cd:
                    from datetime import datetime
                    if isinstance(cd, list):
                        cd = cd[0]
                    if isinstance(cd, datetime):
                        age = (datetime.now() - cd).days
                        findings["domain_age_days"] = age
        except ImportError:
            findings["note"] = "python-whois not installed"
        except Exception as e:
            findings["error"] = str(e)[:80]

        findings = {k: v for k, v in findings.items() if v is not None}

        return self.ok(host, {
            "whois_timeline": findings or "lookup failed",
            "risk": "informational",
        })


@register
class FaviconSimilar(Module):
    id, name, category = "favsimilar", "Favicon hash similarity search", "OSINT"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # find favicon URL
        favicon_urls = [base + "/favicon.ico"]
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:20_000]
            icons = re.findall(
                r'<link[^>]+rel=["\'](?:shortcut )?icon["\'][^>]+href=["\']([^"\']+)',
                body, re.I)
            icons += re.findall(
                r'<link[^>]+href=["\']([^"\']+)["\'][^>]+rel=["\'](?:shortcut )?icon',
                body, re.I)
            for icon in icons[:3]:
                if icon.startswith("http"):
                    favicon_urls.append(icon)
                elif icon.startswith("//"):
                    favicon_urls.append("https:" + icon)
                elif icon.startswith("/"):
                    favicon_urls.append(base + icon)
        except Exception:
            pass

        import hashlib
        import base64 as b64

        for url in favicon_urls[:3]:
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code != 200 or len(r.content) < 10:
                    continue

                raw = r.content
                md5 = hashlib.md5(raw).hexdigest()
                sha256 = hashlib.sha256(raw).hexdigest()

                # MurmurHash3 for Shodan favicon search
                encoded = b64.encodebytes(raw).decode()
                import struct as _struct
                mmh3_hash = None
                try:
                    import mmh3
                    mmh3_hash = mmh3.hash(encoded)
                except ImportError:
                    pass

                info = {
                    "url": url[:80],
                    "size": len(raw),
                    "md5": md5,
                    "sha256": sha256,
                    "content_type": r.headers.get("Content-Type", "")[:40],
                }
                if mmh3_hash is not None:
                    info["mmh3_hash"] = mmh3_hash
                    info["shodan_query"] = "http.favicon.hash:" + str(mmh3_hash)

                findings["favicon"] = info
                break
            except Exception:
                continue

        return self.ok(host, {
            "favicon": findings or "not found",
            "risk": "informational",
        })
