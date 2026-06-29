"""Cloud / container / CI-CD exposure modules (new features #29-#37).
Detection only - reports public exposure, never accesses private data."""

from __future__ import annotations

import re
import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

_CLOUD_HINTS = {
    "AWS": ["amazonaws.com", "aws", "ec2", "cloudfront"],
    "Google Cloud": ["googleusercontent", "googleapis", "gcp", "appspot"],
    "Azure": ["azure", "windows.net", "azurewebsites", "azureedge"],
    "Cloudflare": ["cloudflare"],
    "DigitalOcean": ["digitalocean"],
    "Fastly": ["fastly"],
    "Akamai": ["akamai"],
}


@register
class CloudProvider(Module):
    id, name, category = "cloudprov", "Cloud provider from IP/org", "Cloud"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = host if re.match(r"\d+\.\d+\.\d+\.\d+", host) else socket.gethostbyname(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            j = ctx.session.get(f"http://ip-api.com/json/{ip}?fields=org,isp,as,asname",
                                timeout=ctx.timeout).json()
        except Exception as exc:
            return self.fail(host, f"lookup failed: {exc}")
        blob = " ".join(str(v) for v in j.values()).lower()
        prov = [p for p, hints in _CLOUD_HINTS.items() if any(h in blob for h in hints)]
        return self.ok(host, {"ip": ip, "org": j.get("org"), "asn": j.get("as"),
                              "providers": prov or ["unknown / self-hosted"]})


@register
class K8sApi(Module):
    id, name, category = "k8s", "Exposed Kubernetes API", "Cloud"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        for port in (6443, 8080, 10250):
            for path in ("/version", "/api", "/healthz"):
                url = f"https://{host}:{port}{path}"
                try:
                    r = ctx.session.get(url, timeout=ctx.timeout, verify=False)
                    if r.status_code in (200, 401, 403) and \
                       ("kubernetes" in r.text.lower() or "gitVersion" in r.text or
                            "apiVersion" in r.text or port == 10250):
                        out[f"{port}{path}"] = (
                            "OPEN (unauthenticated)" if r.status_code == 200
                            else f"present (HTTP {r.status_code})")
                        break
                except Exception:
                    continue
        return self.ok(host, {"kubernetes": out or "no exposed API found"})


@register
class DockerApi(Module):
    id, name, category = "docker", "Exposed Docker registry/API", "Cloud"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        # Docker Engine API
        for scheme, port in (("http", 2375), ("https", 2376)):
            try:
                r = ctx.session.get(f"{scheme}://{host}:{port}/version",
                                    timeout=ctx.timeout, verify=False)
                if r.status_code == 200 and "ApiVersion" in r.text:
                    out[f"engine:{port}"] = "OPEN Docker Engine API (critical)"
            except Exception:
                pass
        # Registry v2
        for scheme in ("https", "http"):
            try:
                r = ctx.session.get(f"{scheme}://{host}:5000/v2/_catalog",
                                    timeout=ctx.timeout, verify=False)
                if r.status_code == 200 and "repositories" in r.text:
                    out["registry:5000"] = f"OPEN registry catalog: {r.text[:120]}"
                elif r.status_code == 401:
                    out["registry:5000"] = "registry present (auth required)"
            except Exception:
                continue
        return self.ok(host, {"docker": out or "none found"})


@register
class TfState(Module):
    id, name, category = "tfstate", "Exposed Terraform state", "Cloud"
    target_kind = "url"

    _PATHS = ["terraform.tfstate", "terraform.tfstate.backup",
              ".terraform/terraform.tfstate", "infra/terraform.tfstate",
              "state/terraform.tfstate"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        out = {}
        for p in self._PATHS:
            try:
                r = ctx.session.get(f"{base}/{p}", timeout=ctx.timeout)
                if r.status_code == 200 and '"terraform_version"' in r.text:
                    out[p] = "EXPOSED tfstate (may contain secrets)"
            except Exception:
                continue
        return self.ok(host, {"terraform_state": out or "none found"})


@register
class CicdConfig(Module):
    id, name, category = "cicd", "Exposed CI/CD config", "Cloud"
    target_kind = "url"

    _PATHS = [".github/workflows/", ".gitlab-ci.yml", "Jenkinsfile",
              ".circleci/config.yml", "azure-pipelines.yml", "bitbucket-pipelines.yml",
              ".travis.yml", ".drone.yml", "buildspec.yml", ".github/dependabot.yml"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        out = {}
        for p in self._PATHS:
            try:
                r = ctx.session.get(f"{base}/{p}", timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code == 200 and len(r.text) > 10:
                    out[p] = f"HTTP 200 ({len(r.content)} bytes)"
            except Exception:
                continue
        return self.ok(host, {"cicd_files": out or "none found"})


@register
class CloudMetadata(Module):
    id, name, category = "metadata", "Cloud metadata SSRF surface", "Cloud"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # We do NOT perform SSRF. We map the surface: known metadata IPs an
        # SSRF would target, plus a check for obvious open redirect/proxy params
        # that could enable it. Informational/defensive.
        endpoints = {
            "AWS IMDS": "http://169.254.169.254/latest/meta-data/",
            "GCP": "http://metadata.google.internal/computeMetadata/v1/",
            "Azure": "http://169.254.169.254/metadata/instance?api-version=2021-02-01",
            "DigitalOcean": "http://169.254.169.254/metadata/v1/",
            "Alibaba": "http://100.100.100.200/latest/meta-data/",
        }
        from .web import OpenRedirect
        redir = OpenRedirect().run(host, ctx)
        return self.ok(host, {
            "ssrf_target_endpoints": endpoints,
            "open_redirect_finding": redir.data.get("findings", "n/a"),
            "note": "if the app fetches user-supplied URLs, these are the IMDS "
                    "endpoints an SSRF would hit - ensure IMDSv2 / egress blocks"})


@register
class RegistryTags(Module):
    id, name, category = "regtags", "Public registry tag enumeration", "Cloud"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        for scheme in ("https", "http"):
            try:
                cat = ctx.session.get(f"{scheme}://{host}:5000/v2/_catalog",
                                      timeout=ctx.timeout, verify=False)
                if cat.status_code == 200 and "repositories" in cat.text:
                    repos = cat.json().get("repositories", [])[:10]
                    for repo in repos:
                        t = ctx.session.get(f"{scheme}://{host}:5000/v2/{repo}/tags/list",
                                            timeout=ctx.timeout, verify=False)
                        if t.status_code == 200:
                            out[repo] = t.json().get("tags", [])
                    break
            except Exception:
                continue
        return self.ok(host, {"repositories": out or "no open registry catalog"})


@register
class ServerlessUrls(Module):
    id, name, category = "serverless", "Serverless / function URLs", "Cloud"
    target_kind = "url"

    _RX = [
        re.compile(r"https://[a-z0-9\-]+\.execute-api\.[a-z0-9\-]+\.amazonaws\.com[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.lambda-url\.[a-z0-9\-]+\.on\.aws[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.cloudfunctions\.net[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.azurewebsites\.net[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.run\.app[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.workers\.dev[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.vercel\.app[^\s'\"]*", re.I),
        re.compile(r"https://[a-z0-9\-]+\.netlify\.app[^\s'\"]*", re.I),
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            html = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout).text
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        urls = set()
        for rx in self._RX:
            urls.update(rx.findall(html))
        return self.ok(host, {"function_urls": sorted(urls) or "none found"})


@register
class DanglingCloud(Module):
    id, name, category = "dangling", "Dangling cloud CNAME", "Cloud"
    target_kind = "domain"

    _SERVICES = ["s3.amazonaws.com", "cloudfront.net", "azureedge.net",
                 "blob.core.windows.net", "storage.googleapis.com",
                 "trafficmanager.net", "azurewebsites.net", "github.io",
                 "herokuapp.com", "fastly.net"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        from .subdomains import SubdomainEnum
        subs = list(SubdomainEnum().run(host, ctx).data.get("subdomains", {}).keys())
        candidates = [host, f"www.{host}"] + subs
        findings = []
        for sub in dict.fromkeys(candidates):
            try:
                cname = str(dns.resolver.resolve(sub, "CNAME")[0].target).rstrip(".")
            except Exception:
                continue
            svc = next((s for s in self._SERVICES if s in cname), None)
            if not svc:
                continue
            try:
                socket.gethostbyname(cname)
                state = "resolves"
            except OSError:
                state = "DANGLING (CNAME target does not resolve - takeover risk)"
            findings.append({"subdomain": sub, "cname": cname,
                             "service": svc, "state": state})
        return self.ok(host, {"cname_targets": findings or "no cloud CNAMEs found"})
