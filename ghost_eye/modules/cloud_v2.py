"""Advanced cloud modules v2 (v3.5 features #63-#70). Detection only."""

from __future__ import annotations

import re
import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class S3BucketEnum(Module):
    id, name, category = "s3enum", "Public S3 bucket enumeration", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        name = re.sub(r"[^a-z0-9\-]", "", host.split(".")[0].lower())
        permutations = [
            name, f"{name}-backup", f"{name}-bak", f"{name}-dev", f"{name}-prod",
            f"{name}-staging", f"{name}-test", f"{name}-assets", f"{name}-static",
            f"{name}-media", f"{name}-data", f"{name}-files", f"{name}-uploads",
            f"{name}-logs", f"{name}-private", f"{name}-public", f"{name}-internal",
            f"{name}-archive", f"{name}-cdn", f"{name}-images", f"{name}-docs",
            f"backup-{name}", f"dev-{name}", f"www-{name}", f"api-{name}",
        ]
        results = {}

        def probe(bucket):
            url = f"https://{bucket}.s3.amazonaws.com"
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code == 200 and "<ListBucketResult" in r.text:
                    keys = re.findall(r"<Key>(.*?)</Key>", r.text)[:5]
                    return bucket, {"status": "PUBLIC (listable)", "sample_keys": keys,
                                    "risk": "CRITICAL"}
                elif r.status_code == 403:
                    return bucket, {"status": "exists (private)", "risk": "low"}
            except Exception:
                pass
            return bucket, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for bucket, info in ex.map(probe, permutations):
                if info:
                    results[bucket] = info
        return self.ok(host, {"s3_buckets": results or "none found"})


@register
class AzureBlobExposure(Module):
    id, name, category = "azureblob", "Azure blob storage exposure", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        name = re.sub(r"[^a-z0-9]", "", host.split(".")[0].lower())
        accounts = [name, f"{name}dev", f"{name}prod", f"{name}backup",
                    f"{name}staging", f"{name}test", f"{name}data"]
        containers = ["$web", "public", "data", "uploads", "media", "backup",
                      "assets", "static", "files", "images"]
        results = {}

        def probe(item):
            acct, container = item
            url = f"https://{acct}.blob.core.windows.net/{container}?restype=container&comp=list"
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code == 200 and "<EnumerationResults" in r.text:
                    blobs = re.findall(r"<Name>(.*?)</Name>", r.text)[:5]
                    return f"{acct}/{container}", {"status": "PUBLIC", "sample_blobs": blobs}
                elif r.status_code == 409:
                    return f"{acct}/{container}", {"status": "exists (private)"}
            except Exception:
                pass
            return None, None

        items = [(a, c) for a in accounts for c in containers]
        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for key, info in ex.map(probe, items[:40]):
                if key and info:
                    results[key] = info
        return self.ok(host, {"azure_blobs": results or "none found"})


@register
class GcsBucketCheck(Module):
    id, name, category = "gcsbucket", "GCP storage bucket check", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        name = re.sub(r"[^a-z0-9\-]", "", host.split(".")[0].lower())
        buckets = [name, f"{name}-backup", f"{name}-dev", f"{name}-prod",
                   f"{name}-public", f"{name}-assets", f"{name}-data",
                   f"{name}-staging", f"{name}-media"]
        results = {}

        def probe(bucket):
            url = f"https://storage.googleapis.com/{bucket}"
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code == 200 and ("<ListBucketResult" in r.text or
                                              "Contents" in r.text):
                    return bucket, "PUBLIC (listable)"
                elif r.status_code == 403:
                    return bucket, "exists (private)"
            except Exception:
                pass
            return bucket, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for bucket, status in ex.map(probe, buckets):
                if status:
                    results[bucket] = status
        return self.ok(host, {"gcs_buckets": results or "none found"})


@register
class CloudMetaSsrf(Module):
    id, name, category = "metassrf", "Cloud metadata SSRF surface (extended)", "Cloud"
    target_kind = "url"

    _ENDPOINTS = {
        "AWS IMDSv1": "http://169.254.169.254/latest/meta-data/",
        "AWS IMDSv2": "http://169.254.169.254/latest/api/token",
        "GCP": "http://metadata.google.internal/computeMetadata/v1/",
        "Azure": "http://169.254.169.254/metadata/instance?api-version=2021-02-01",
        "DigitalOcean": "http://169.254.169.254/metadata/v1/",
        "Oracle Cloud": "http://169.254.169.254/opc/v1/instance/",
        "Alibaba": "http://100.100.100.200/latest/meta-data/",
        "Hetzner": "http://169.254.169.254/hetzner/v1/metadata",
        "Packet/Equinix": "https://metadata.packet.net/metadata",
        "OpenStack": "http://169.254.169.254/openstack/latest/meta_data.json",
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # detect cloud provider from headers
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
            headers = {k.lower(): v for k, v in r.headers.items()}
        except Exception:
            headers = {}
        provider_hints = []
        if any(h.startswith("x-amz") for h in headers):
            provider_hints.append("AWS")
        if any("azure" in h for h in headers):
            provider_hints.append("Azure")
        if any("x-goog" in h for h in headers):
            provider_hints.append("GCP")
        # check for redirect params that could enable SSRF
        redirect_params = ["url", "redirect", "next", "dest", "uri", "path",
                           "continue", "file", "document", "folder", "page"]
        ssrf_params = []
        for param in redirect_params:
            try:
                test_r = ctx.session.get(f"{ensure_scheme(host)}/?{param}=http://127.0.0.1",
                                         timeout=ctx.timeout, allow_redirects=False)
                if test_r.status_code in (301, 302, 303, 307, 308):
                    loc = test_r.headers.get("Location", "")
                    if "127.0.0.1" in loc:
                        ssrf_params.append(param)
            except Exception:
                continue
        return self.ok(host, {
            "metadata_endpoints": self._ENDPOINTS,
            "cloud_hints": provider_hints or "unknown",
            "ssrf_params_found": ssrf_params or "none",
            "risk": "HIGH" if ssrf_params else "informational",
            "note": "test redirect params against metadata endpoints with authorization"
        })


@register
class FirebaseExposure(Module):
    id, name, category = "firebase", "Exposed Firebase database", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        name = re.sub(r"[^a-z0-9\-]", "", host.split(".")[0].lower())
        projects = [name, f"{name}-app", f"{name}-prod", f"{name}-dev",
                    f"{name}-firebase", f"{name}-default-rtdb"]
        results = {}
        for proj in projects:
            url = f"https://{proj}.firebaseio.com/.json"
            try:
                r = ctx.session.get(url, timeout=ctx.timeout)
                if r.status_code == 200 and r.text.strip() != "null":
                    data_size = len(r.text)
                    results[proj] = {
                        "status": "PUBLIC READ ACCESS",
                        "data_size": data_size,
                        "risk": "CRITICAL"
                    }
                elif r.status_code == 401:
                    results[proj] = {"status": "exists (auth required)", "risk": "low"}
            except Exception:
                continue
        # also check Firestore REST
        for proj in projects[:3]:
            try:
                r = ctx.session.get(
                    f"https://firestore.googleapis.com/v1/projects/{proj}/databases/(default)/documents",
                    timeout=ctx.timeout)
                if r.status_code == 200 and "documents" in r.text:
                    results[f"{proj}/firestore"] = {
                        "status": "PUBLIC Firestore access",
                        "risk": "CRITICAL"
                    }
            except Exception:
                continue
        return self.ok(host, {"firebase": results or "none found"})


@register
class GitRepoRecon(Module):
    id, name, category = "gitrecon", "GitHub/GitLab org reconnaissance", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        org = host.split(".")[0]
        results = {}
        # GitHub
        try:
            r = ctx.session.get(f"https://api.github.com/orgs/{org}/repos?per_page=10&sort=updated",
                                timeout=ctx.timeout,
                                headers={"Accept": "application/vnd.github.v3+json"})
            if r.status_code == 200:
                repos = r.json()
                results["github_repos"] = [{
                    "name": repo.get("name"),
                    "description": (repo.get("description") or "")[:80],
                    "stars": repo.get("stargazers_count"),
                    "language": repo.get("language"),
                    "updated": repo.get("updated_at"),
                } for repo in repos[:10]]
            # also check user
            elif r.status_code == 404:
                r2 = ctx.session.get(f"https://api.github.com/users/{org}/repos?per_page=5&sort=updated",
                                     timeout=ctx.timeout)
                if r2.status_code == 200:
                    repos = r2.json()
                    results["github_user_repos"] = [{
                        "name": repo.get("name"),
                        "language": repo.get("language"),
                    } for repo in repos[:5]]
        except Exception:
            pass
        # GitLab
        try:
            r = ctx.session.get(f"https://gitlab.com/api/v4/groups/{org}/projects?per_page=5",
                                timeout=ctx.timeout)
            if r.status_code == 200:
                results["gitlab_repos"] = [{
                    "name": p.get("name"),
                    "visibility": p.get("visibility"),
                } for p in r.json()[:5]]
        except Exception:
            pass
        return self.ok(host, {"repositories": results or "no public repos found",
                              "note": "check repos for leaked secrets, API keys, configs"})


@register
class DnsHostingProvider(Module):
    id, name, category = "dnshost", "DNS hosting provider detection", "Cloud"
    target_kind = "domain"

    _PROVIDERS = {
        "Cloudflare": ["cloudflare.com", "ns.cloudflare.com"],
        "AWS Route53": ["awsdns-"],
        "Google Cloud DNS": ["googledomains.com", "google.com"],
        "Azure DNS": ["azure-dns.com", "azure-dns.net"],
        "Namecheap": ["namecheaphosting.com", "registrar-servers.com"],
        "GoDaddy": ["domaincontrol.com"],
        "DigitalOcean": ["digitalocean.com"],
        "DNSimple": ["dnsimple.com"],
        "NS1": ["nsone.net"],
        "Dyn": ["dynect.net"],
        "Rackspace": ["rackspace.com"],
        "Linode": ["linode.com"],
        "Hetzner": ["hetzner.com"],
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        r = dns.resolver.Resolver()
        r.lifetime = ctx.timeout
        try:
            ns_records = sorted(str(rr.target).rstrip(".").lower()
                                for rr in r.resolve(host, "NS"))
        except Exception:
            return self.fail(host, "cannot resolve NS records")
        ns_str = " ".join(ns_records)
        providers = []
        for provider, hints in self._PROVIDERS.items():
            if any(h in ns_str for h in hints):
                providers.append(provider)
        return self.ok(host, {
            "nameservers": ns_records,
            "dns_provider": providers or ["unknown / self-hosted"],
        })


@register
class CdnGeoMap(Module):
    id, name, category = "cdngeo", "CDN node geolocation", "Cloud"
    target_kind = "domain"

    _RESOLVERS = {
        "US-East": "8.8.8.8",
        "US-West": "208.67.222.222",
        "Europe": "1.1.1.1",
        "Asia": "9.9.9.9",
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        all_ips = set()
        for region, server in self._RESOLVERS.items():
            r = dns.resolver.Resolver()
            r.nameservers = [server]
            r.lifetime = ctx.timeout
            try:
                ans = r.resolve(host, "A")
                ips = sorted(str(rr) for rr in ans)
                results[region] = {"resolver": server, "ips": ips}
                all_ips.update(ips)
            except Exception:
                results[region] = {"resolver": server, "ips": [], "error": "failed"}
        # geolocate unique IPs
        geo = {}
        for ip in sorted(all_ips)[:8]:
            try:
                j = ctx.session.get(f"http://ip-api.com/json/{ip}?fields=country,city,isp,org",
                                    timeout=ctx.timeout).json()
                geo[ip] = {"country": j.get("country"), "city": j.get("city"),
                           "isp": j.get("isp")}
            except Exception:
                geo[ip] = "lookup failed"
        uses_cdn = len(all_ips) > 1
        return self.ok(host, {"regions": results, "unique_ips": sorted(all_ips),
                              "geolocation": geo, "uses_cdn": uses_cdn,
                              "note": "multiple IPs across regions indicates CDN/anycast"
                              if uses_cdn else "single IP (no CDN or anycast)"})
