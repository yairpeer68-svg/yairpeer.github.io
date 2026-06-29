"""Cloud Advanced modules (features #64-#70). Detection only."""

from __future__ import annotations

import re
import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class CloudflareMisconfig(Module):
    id, name, category = "cfdnmisconfig", "Cloudflare DNS misconfiguration", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        domain = host.split(":")[0]
        findings = {}

        import socket as _socket

        # check if behind Cloudflare
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
            hdrs = {k.lower(): v for k, v in r.headers.items()}
            if "cf-ray" in hdrs:
                findings["cloudflare"] = True
                findings["cf_ray"] = hdrs["cf-ray"]
                if "cf-cache-status" in hdrs:
                    findings["cache_status"] = hdrs["cf-cache-status"]
            else:
                return self.ok(host, {
                    "cloudflare": False,
                    "risk": "informational",
                })
        except Exception:
            pass

        # try to find origin IP behind Cloudflare
        origin_checks = []

        # common subdomains that bypass CF
        bypass_subs = ["direct", "origin", "mail", "ftp", "cpanel",
                       "webmail", "smtp", "pop", "imap", "mta",
                       "staging", "dev", "api-direct"]
        for sub in bypass_subs:
            fqdn = f"{sub}.{domain}"
            try:
                ip = _socket.gethostbyname(fqdn)
                # check if this IP is NOT a Cloudflare IP
                try:
                    r2 = ctx.session.get(f"http://{ip}",
                                         headers={"Host": domain},
                                         timeout=min(ctx.timeout, 5),
                                         allow_redirects=False)
                    if r2.status_code < 500:
                        origin_checks.append({
                            "subdomain": fqdn,
                            "ip": ip,
                            "status": r2.status_code,
                        })
                except Exception:
                    origin_checks.append({
                        "subdomain": fqdn,
                        "ip": ip,
                    })
            except Exception:
                continue

        if origin_checks:
            findings["potential_origin_bypass"] = origin_checks[:10]
            findings["risk"] = "HIGH"

        # check for misconfigured security headers via CF
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
            hdrs = {k.lower(): v for k, v in r.headers.items()}
            security_issues = []
            if "strict-transport-security" not in hdrs:
                security_issues.append("missing HSTS")
            if hdrs.get("x-frame-options", "").upper() not in ("DENY", "SAMEORIGIN"):
                if "content-security-policy" not in hdrs or \
                   "frame-ancestors" not in hdrs.get("content-security-policy", ""):
                    security_issues.append("missing frame protection")
            if security_issues:
                findings["cf_security_gaps"] = security_issues
        except Exception:
            pass

        risk = findings.get("risk", "informational")
        if not findings.get("potential_origin_bypass"):
            risk = "LOW" if findings.get("cf_security_gaps") else "informational"

        return self.ok(host, {
            "cloudflare_config": findings,
            "risk": risk,
        })


@register
class AzureAdTenant(Module):
    id, name, category = "azureadtenant", "Azure AD / Entra ID tenant detection", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        domain = host.split(":")[0]
        findings = {}

        # OpenID configuration
        openid_url = f"https://login.microsoftonline.com/{domain}/.well-known/openid-configuration"
        try:
            r = ctx.session.get(openid_url, timeout=ctx.timeout)
            if r.status_code == 200:
                j = r.json()
                findings["tenant_id"] = j.get("token_endpoint", "").split("/")[3] \
                    if "/oauth2/" in j.get("token_endpoint", "") else ""
                findings["issuer"] = j.get("issuer", "")[:80]
                findings["azure_ad"] = True
        except Exception:
            pass

        # user realm discovery
        realm_url = f"https://login.microsoftonline.com/getuserrealm.srf?login=user@{domain}&xml=1"
        try:
            r = ctx.session.get(realm_url, timeout=ctx.timeout)
            if r.status_code == 200:
                body = r.text[:5000]
                ns_type = re.search(r'<NameSpaceType>([^<]+)', body)
                if ns_type:
                    findings["namespace_type"] = ns_type.group(1)
                fed_url = re.search(r'<AuthURL>([^<]+)', body)
                if fed_url:
                    findings["federation_url"] = fed_url.group(1)[:100]
                brand = re.search(r'<FederationBrandName>([^<]+)', body)
                if brand:
                    findings["brand"] = brand.group(1)[:50]
        except Exception:
            pass

        # autodiscover
        try:
            r = ctx.session.get(
                f"https://autodiscover.{domain}/autodiscover/autodiscover.json/v1.0/",
                timeout=min(ctx.timeout, 5), allow_redirects=False)
            if r.status_code in (200, 302):
                findings["autodiscover"] = True
                if r.status_code == 302:
                    findings["autodiscover_redirect"] = r.headers.get("Location", "")[:100]
        except Exception:
            pass

        return self.ok(host, {
            "azure_ad": findings or "no Azure AD detected",
            "risk": "LOW" if findings.get("azure_ad") else "informational",
        })


@register
class GcpEnum(Module):
    id, name, category = "gcpenum", "GCP project & service enumeration", "Cloud"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        domain = host.split(":")[0]
        findings = {}

        # check for GCP service indicators
        base = ensure_scheme(host).rstrip("/")

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            hdrs = {k.lower(): v for k, v in r.headers.items()}

            if "x-cloud-trace-context" in hdrs:
                findings["gcp_trace"] = True
            if hdrs.get("server", "").lower() in ("google frontend", "gws"):
                findings["google_frontend"] = True
            if "appengine" in hdrs.get("server", "").lower():
                findings["app_engine"] = True
            if "via" in hdrs and "google" in hdrs["via"].lower():
                findings["google_lb"] = True
        except Exception:
            pass

        # check Firebase
        firebase_url = f"https://{domain.replace('.', '-')}.firebaseio.com/.json"
        try:
            r = ctx.session.get(firebase_url, timeout=min(ctx.timeout, 5))
            if r.status_code == 200 and r.text != "null":
                findings["firebase_exposed"] = True
                findings["firebase_risk"] = "CRITICAL"
            elif r.status_code == 401:
                findings["firebase_exists"] = True
        except Exception:
            pass

        # check storage bucket
        bucket_names = [domain, domain.replace(".", "-"),
                        f"www.{domain}", f"backup.{domain}",
                        f"assets.{domain}", f"static.{domain}"]
        for bucket in bucket_names:
            try:
                r = ctx.session.get(
                    f"https://storage.googleapis.com/{bucket}/",
                    timeout=min(ctx.timeout, 5))
                if r.status_code == 200:
                    findings.setdefault("public_buckets", []).append(bucket)
                elif r.status_code == 403:
                    findings.setdefault("existing_buckets", []).append(bucket)
            except Exception:
                continue

        risk = "informational"
        if findings.get("firebase_exposed"):
            risk = "CRITICAL"
        elif findings.get("public_buckets"):
            risk = "HIGH"
        elif findings:
            risk = "LOW"

        return self.ok(host, {
            "gcp": findings or "no GCP indicators",
            "risk": risk,
        })


@register
class TerraformCloud(Module):
    id, name, category = "tfcloud", "Terraform / IaC config exposure", "Cloud"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        iac_paths = [
            "/terraform.tfstate", "/terraform.tfstate.backup",
            "/.terraform/terraform.tfstate",
            "/main.tf", "/variables.tf", "/outputs.tf",
            "/terraform.tfvars", "/backend.tf",
            "/pulumi.yaml", "/Pulumi.yaml",
            "/cdk.json", "/cdk.out/manifest.json",
            "/cloudformation.yml", "/cloudformation.yaml",
            "/template.yaml", "/sam.yaml",
            "/serverless.yml", "/serverless.yaml",
        ]

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 10:
                    body = r.text[:10_000]
                    if any(kw in body.lower() for kw in
                           ["resource", "provider", "terraform", "module",
                            "variable", "output", "aws_", "azurerm_",
                            "google_", "pulumi", "stack", "cloudformation",
                            "transform", "resources"]):
                        info = {"exposed": True, "size": len(r.content)}
                        if "tfstate" in path:
                            info["risk"] = "CRITICAL"
                            info["note"] = "Terraform state file contains secrets"
                        elif "tfvars" in path:
                            info["risk"] = "CRITICAL"
                            info["note"] = "tfvars may contain credentials"
                        else:
                            info["risk"] = "HIGH"
                        return path, info
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, iac_paths):
                if info:
                    findings[path] = info

        risk = "informational"
        if any(f.get("risk") == "CRITICAL" for f in findings.values()):
            risk = "CRITICAL"
        elif findings:
            risk = "HIGH"

        return self.ok(host, {
            "iac_exposure": findings or "none found",
            "risk": risk,
        })


@register
class VaultDetect(Module):
    id, name, category = "vaultdetect", "HashiCorp Vault exposure detection", "Cloud"
    target_kind = "url"

    _PATHS = ["/v1/sys/health", "/v1/sys/seal-status",
              "/v1/sys/init", "/ui/"]
    _PORTS = [8200, 8201]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        bare = host.split(":")[0]
        findings = {}

        # check standard base
        for path in self._PATHS:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200:
                    if "vault" in r.text.lower()[:5000] or \
                       "sealed" in r.text.lower()[:1000]:
                        info = {"status": 200, "path": path}
                        if path == "/v1/sys/health":
                            try:
                                j = r.json()
                                info["initialized"] = j.get("initialized")
                                info["sealed"] = j.get("sealed")
                                info["version"] = j.get("version", "")
                            except Exception:
                                pass
                        findings[path] = info
            except Exception:
                continue

        # check common ports
        for port in self._PORTS:
            for path in self._PATHS[:2]:
                url = f"http://{bare}:{port}{path}"
                try:
                    r = ctx.session.get(url, timeout=min(ctx.timeout, 5))
                    if r.status_code == 200 and ("vault" in r.text.lower()[:2000]
                                                  or "sealed" in r.text.lower()[:1000]):
                        info = {"url": url, "status": 200}
                        try:
                            j = r.json()
                            info["version"] = j.get("version", "")
                            info["sealed"] = j.get("sealed")
                        except Exception:
                            pass
                        findings[f"{port}{path}"] = info
                except Exception:
                    continue

        risk = "informational"
        if findings:
            risk = "CRITICAL"

        return self.ok(host, {
            "vault": findings or "not detected",
            "risk": risk,
            "note": "exposed Vault API may allow secret enumeration"
        })


@register
class ConsulDetect(Module):
    id, name, category = "consuldetect", "HashiCorp Consul exposure", "Cloud"
    target_kind = "url"

    _PORTS = [8500, 8501]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        bare = host.split(":")[0]
        findings = {}

        consul_paths = ["/v1/agent/self", "/v1/catalog/services",
                        "/v1/catalog/nodes", "/v1/kv/?keys",
                        "/ui/"]

        def probe(item):
            port, path = item
            url = f"http://{bare}:{port}{path}"
            try:
                r = ctx.session.get(url, timeout=min(ctx.timeout, 5))
                if r.status_code == 200:
                    body = r.text[:5000].lower()
                    if any(kw in body for kw in
                           ["consul", "datacenter", "node", "service",
                            "config", "member"]):
                        info = {"url": url, "status": 200}
                        if path == "/v1/catalog/services":
                            try:
                                info["services"] = list(r.json().keys())[:15]
                            except Exception:
                                pass
                        elif path == "/v1/catalog/nodes":
                            try:
                                nodes = r.json()
                                info["node_count"] = len(nodes)
                                info["nodes"] = [n.get("Node", "") for n in nodes[:10]]
                            except Exception:
                                pass
                        return f"{port}{path}", info
            except Exception:
                pass
            return None, None

        items = [(p, path) for p in self._PORTS for path in consul_paths]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 4)) as ex:
            for key, info in ex.map(probe, items):
                if key and info:
                    findings[key] = info

        # also check base URL
        for path in consul_paths[:3]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and "consul" in r.text.lower()[:3000]:
                    findings[path] = {"status": 200}
            except Exception:
                continue

        risk = "informational"
        if findings:
            risk = "CRITICAL"

        return self.ok(host, {
            "consul": findings or "not detected",
            "risk": risk,
        })


@register
class EtcdDetect(Module):
    id, name, category = "etcddetect", "etcd key-value store exposure", "Cloud"
    target_kind = "url"

    _PORTS = [2379, 2380, 4001]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        etcd_paths = ["/version", "/v2/keys/", "/v2/stats/self",
                      "/v2/members", "/v3/kv/range",
                      "/health"]

        def probe(item):
            port, path = item
            url = f"http://{bare}:{port}{path}"
            try:
                r = ctx.session.get(url, timeout=min(ctx.timeout, 5))
                if r.status_code == 200:
                    body = r.text[:5000].lower()
                    if any(kw in body for kw in
                           ["etcd", "cluster", "raftindex", "node",
                            "etcdserver", "etcdcluster"]):
                        info = {"url": url, "status": 200}
                        if path == "/version":
                            try:
                                j = r.json()
                                info["etcd_version"] = j.get("etcdserver", "")
                                info["cluster_version"] = j.get("etcdcluster", "")
                            except Exception:
                                pass
                        elif path == "/v2/keys/":
                            info["keys_exposed"] = True
                            info["risk"] = "CRITICAL"
                        return f"{port}{path}", info
            except Exception:
                pass
            return None, None

        items = [(p, path) for p in self._PORTS for path in etcd_paths]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 4)) as ex:
            for key, info in ex.map(probe, items):
                if key and info:
                    findings[key] = info

        risk = "informational"
        if any(f.get("keys_exposed") for f in findings.values()):
            risk = "CRITICAL"
        elif findings:
            risk = "HIGH"

        return self.ok(host, {
            "etcd": findings or "not detected",
            "risk": risk,
            "note": "exposed etcd may contain Kubernetes secrets and config"
        })
