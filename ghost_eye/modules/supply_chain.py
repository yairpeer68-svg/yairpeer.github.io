"""Supply chain security modules (features #26-#32). Detection only."""

from __future__ import annotations

import re
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class NpmAudit(Module):
    id, name, category = "npmscan", "Exposed package.json vulnerability audit", "Supply Chain"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        paths = ["/package.json", "/package-lock.json", "/yarn.lock",
                 "/node_modules/.package-lock.json"]
        for path in paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 20:
                    ct = r.headers.get("Content-Type", "")
                    if "json" in ct or path.endswith(".json"):
                        try:
                            j = r.json()
                            deps = {}
                            deps.update(j.get("dependencies", {}))
                            deps.update(j.get("devDependencies", {}))
                            findings[path] = {
                                "exposed": True,
                                "name": j.get("name", ""),
                                "version": j.get("version", ""),
                                "dependency_count": len(deps),
                                "sample_deps": dict(list(deps.items())[:15]),
                                "has_scripts": bool(j.get("scripts")),
                                "risk": "HIGH",
                            }
                        except Exception:
                            findings[path] = {"exposed": True, "risk": "MEDIUM"}
                    elif path == "/yarn.lock":
                        pkgs = re.findall(r'^"?([^@\s][^"\s]+)@', r.text[:5000],
                                          re.MULTILINE)
                        findings[path] = {
                            "exposed": True,
                            "packages": len(set(pkgs)),
                            "risk": "HIGH",
                        }
            except Exception:
                continue

        return self.ok(host, {
            "package_files": findings or "none exposed",
            "risk": "HIGH" if findings else "informational",
            "note": "exposed dependency files reveal tech stack and vulnerable versions"
        })


@register
class PipAudit(Module):
    id, name, category = "pipscan", "Exposed requirements.txt CVE check", "Supply Chain"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        paths = ["/requirements.txt", "/requirements-dev.txt",
                 "/requirements-prod.txt", "/Pipfile", "/Pipfile.lock",
                 "/poetry.lock", "/setup.py", "/setup.cfg",
                 "/pyproject.toml"]
        for path in paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 10:
                    body = r.text[:10_000]
                    if path.endswith(".txt"):
                        deps = [line.strip() for line in body.splitlines()
                                if line.strip() and not line.startswith("#")]
                        findings[path] = {
                            "exposed": True,
                            "dependencies": deps[:20],
                            "count": len(deps),
                            "risk": "HIGH",
                        }
                    elif path == "/Pipfile.lock":
                        try:
                            j = r.json()
                            pkgs = list((j.get("default", {})).keys())[:15]
                            findings[path] = {"exposed": True,
                                              "packages": pkgs, "risk": "HIGH"}
                        except Exception:
                            findings[path] = {"exposed": True, "risk": "MEDIUM"}
                    else:
                        findings[path] = {"exposed": True,
                                          "size": len(body), "risk": "MEDIUM"}
            except Exception:
                continue

        return self.ok(host, {
            "python_files": findings or "none exposed",
            "risk": "HIGH" if findings else "informational",
        })


@register
class DockerTagExposure(Module):
    id, name, category = "dockertag", "Docker image tag & digest exposure", "Supply Chain"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # check for exposed Docker files
        docker_paths = ["/Dockerfile", "/docker-compose.yml",
                        "/docker-compose.yaml", "/.dockerenv",
                        "/docker-compose.override.yml"]
        for path in docker_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 10:
                    body = r.text[:5000]
                    if any(kw in body.lower() for kw in
                           ["from ", "image:", "services:", "docker"]):
                        images = re.findall(
                            r'(?:FROM|image:\s*)([^\s#]+)', body, re.I)
                        findings[path] = {
                            "exposed": True,
                            "images": images[:10],
                            "risk": "HIGH",
                        }
            except Exception:
                continue

        # check Docker registry v2 API
        reg_paths = ["/v2/", "/v2/_catalog"]
        for path in reg_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200:
                    findings[path] = {
                        "registry_exposed": True,
                        "risk": "CRITICAL",
                    }
                    if "repositories" in r.text:
                        try:
                            repos = r.json().get("repositories", [])[:10]
                            findings[path]["repositories"] = repos
                        except Exception:
                            pass
            except Exception:
                continue

        return self.ok(host, {
            "docker_exposure": findings or "none found",
            "risk": "CRITICAL" if any(f.get("registry_exposed")
                                       for f in findings.values())
                    else "HIGH" if findings else "informational",
        })


@register
class ActionsLeak(Module):
    id, name, category = "actionleak", "GitHub Actions workflow analysis", "Supply Chain"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        ci_paths = [
            "/.github/workflows/ci.yml", "/.github/workflows/ci.yaml",
            "/.github/workflows/build.yml", "/.github/workflows/deploy.yml",
            "/.github/workflows/test.yml", "/.github/workflows/main.yml",
            "/.github/workflows/release.yml",
            "/.gitlab-ci.yml",
            "/.circleci/config.yml",
            "/.travis.yml",
            "/Jenkinsfile",
            "/azure-pipelines.yml",
            "/bitbucket-pipelines.yml",
            "/appveyor.yml",
        ]

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 20:
                    body = r.text[:10_000]
                    info = {"exposed": True, "size": len(body)}
                    secrets = re.findall(r'\$\{\{\s*secrets\.(\w+)\s*\}\}', body)
                    if secrets:
                        info["secret_names"] = list(set(secrets))[:10]
                    envs = re.findall(r'(?:env|environment)[:\s]+(\w+)', body, re.I)
                    if envs:
                        info["environments"] = list(set(envs))[:5]
                    if "pull_request_target" in body:
                        info["pr_target_trigger"] = True
                        info["risk"] = "HIGH"
                    return path, info
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, ci_paths):
                if info:
                    findings[path] = info

        return self.ok(host, {
            "ci_files": findings or "none exposed",
            "risk": "HIGH" if findings else "informational",
            "note": "exposed CI configs reveal build process, secret names, and deployment targets"
        })


@register
class CiCdScan(Module):
    id, name, category = "cicdscan", "CI/CD config exposure (expanded)", "Supply Chain"
    target_kind = "url"

    _PATHS = [
        "/Makefile", "/Rakefile", "/Gruntfile.js", "/Gulpfile.js",
        "/webpack.config.js", "/vite.config.js", "/rollup.config.js",
        "/tsconfig.json", "/babel.config.js", "/.babelrc",
        "/jest.config.js", "/karma.conf.js",
        "/.eslintrc", "/.eslintrc.json", "/.prettierrc",
        "/sonar-project.properties", "/codecov.yml",
        "/renovate.json", "/dependabot.yml",
        "/.env.example", "/.env.sample", "/.env.template",
        "/Procfile", "/app.yaml", "/fly.toml",
        "/vercel.json", "/netlify.toml", "/render.yaml",
        "/heroku.yml", "/Aptfile",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        found = {}

        def probe(path):
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 5:
                    return path, {"status": 200, "size": len(r.content)}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 8)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info

        return self.ok(host, {
            "build_configs": found or "none exposed",
            "risk": "MEDIUM" if found else "informational",
        })


@register
class SbomExtract(Module):
    id, name, category = "sbomextract", "SBOM component extraction", "Supply Chain"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        sbom_paths = ["/sbom.json", "/bom.json", "/sbom.xml",
                      "/cyclonedx.json", "/spdx.json",
                      "/.well-known/sbom", "/api/sbom"]
        for path in sbom_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 50:
                    body = r.text[:20_000].lower()
                    if any(kw in body for kw in ["cyclonedx", "spdx",
                                                  "components", "packages"]):
                        findings[path] = {
                            "format": "CycloneDX" if "cyclonedx" in body
                                      else "SPDX" if "spdx" in body
                                      else "unknown",
                            "size": len(r.content),
                        }
            except Exception:
                continue

        # extract components from headers
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            server = r.headers.get("Server", "")
            powered = r.headers.get("X-Powered-By", "")
            gen = r.headers.get("X-Generator", "")
            components = [h for h in [server, powered, gen] if h]
            if components:
                findings["header_components"] = components
        except Exception:
            pass

        return self.ok(host, {
            "sbom": findings or "no SBOM found",
            "risk": "LOW" if findings else "informational",
        })


@register
class DepConfusion(Module):
    id, name, category = "depconfuse", "Dependency confusion surface", "Supply Chain"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"private_packages": [], "registries": []}

        # look for package.json with scoped packages
        for path in ["/package.json", "/package-lock.json"]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                j = r.json()
                all_deps = {}
                all_deps.update(j.get("dependencies", {}))
                all_deps.update(j.get("devDependencies", {}))
                for pkg in all_deps:
                    if pkg.startswith("@") and "/" in pkg:
                        scope = pkg.split("/")[0]
                        # check if scope exists on npm
                        try:
                            nr = ctx.session.get(
                                f"https://registry.npmjs.org/{pkg}",
                                timeout=ctx.timeout)
                            if nr.status_code == 404:
                                findings["private_packages"].append({
                                    "package": pkg,
                                    "note": "not on public npm — confusion target"
                                })
                        except Exception:
                            continue
                break
            except Exception:
                continue

        # check .npmrc / .yarnrc for private registries
        for rc in ["/.npmrc", "/.yarnrc", "/.yarnrc.yml"]:
            try:
                r = ctx.session.get(base + rc, timeout=ctx.timeout)
                if r.status_code == 200:
                    regs = re.findall(r'registry\s*[=:]\s*(\S+)', r.text)
                    if regs:
                        findings["registries"] = regs[:5]
                        findings["npmrc_exposed"] = True
            except Exception:
                continue

        risk = "informational"
        if findings["private_packages"]:
            risk = "HIGH"
        elif findings.get("npmrc_exposed"):
            risk = "MEDIUM"

        if not findings["private_packages"]:
            findings["private_packages"] = "none"
        if not findings["registries"]:
            findings["registries"] = "none"

        return self.ok(host, findings)
