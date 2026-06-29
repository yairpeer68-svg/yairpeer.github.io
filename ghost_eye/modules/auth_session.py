"""Auth & session security modules (features #11-#18). Detection only."""

from __future__ import annotations

import math
import re
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class OAuthAudit(Module):
    id, name, category = "oauthaudit", "OAuth2/OIDC misconfiguration", "Auth & Session"
    target_kind = "url"

    _OIDC_PATHS = [
        "/.well-known/openid-configuration",
        "/.well-known/oauth-authorization-server",
        "/oauth/.well-known/openid-configuration",
        "/auth/realms/master/.well-known/openid-configuration",
    ]

    _OAUTH_PATHS = [
        "/oauth/authorize", "/oauth2/authorize", "/authorize",
        "/oauth/token", "/oauth2/token",
        "/auth/login", "/login/oauth",
        "/connect/authorize", "/connect/token",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # OIDC discovery
        for p in self._OIDC_PATHS:
            try:
                r = ctx.session.get(base + p, timeout=ctx.timeout)
                if r.status_code == 200:
                    try:
                        j = r.json()
                        findings["oidc_discovery"] = {
                            "path": p,
                            "issuer": j.get("issuer", "")[:100],
                            "grant_types": j.get("grant_types_supported", []),
                            "scopes": j.get("scopes_supported", [])[:10],
                            "response_types": j.get("response_types_supported", []),
                        }
                        if "implicit" in str(j.get("grant_types_supported", [])):
                            findings["implicit_grant"] = {
                                "enabled": True, "risk": "HIGH",
                                "note": "implicit grant exposes tokens in URL fragments"}
                        if j.get("registration_endpoint"):
                            findings["dynamic_registration"] = {
                                "endpoint": j["registration_endpoint"][:100],
                                "risk": "HIGH"}
                        break
                    except Exception:
                        pass
            except Exception:
                continue

        # probe OAuth endpoints
        for p in self._OAUTH_PATHS:
            try:
                r = ctx.session.get(base + p, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code in (200, 302, 400, 401):
                    findings.setdefault("oauth_endpoints", {})[p] = r.status_code
                    # redirect_uri open redirect check
                    if r.status_code in (302, 200):
                        test_url = base + p + "?redirect_uri=https://evil.com&response_type=code&client_id=test"
                        try:
                            tr = ctx.session.get(test_url, timeout=ctx.timeout,
                                                  allow_redirects=False)
                            loc = tr.headers.get("Location", "")
                            if "evil.com" in loc:
                                findings["redirect_uri_open"] = {
                                    "path": p, "risk": "CRITICAL",
                                    "note": "redirect_uri accepts arbitrary domain"}
                        except Exception:
                            pass
            except Exception:
                continue

        risk = "informational"
        if findings.get("redirect_uri_open"):
            risk = "CRITICAL"
        elif findings.get("implicit_grant") or findings.get("dynamic_registration"):
            risk = "HIGH"
        elif findings.get("oidc_discovery"):
            risk = "MEDIUM"

        return self.ok(host, findings or {"oauth": "no OAuth/OIDC endpoints found"})


@register
class JwtAudit(Module):
    id, name, category = "jwtaudit", "JWT token analysis", "Auth & Session"
    target_kind = "url"

    _JWT_RE = re.compile(r'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]*')

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"tokens_found": [], "jwt_in_cookies": [], "vulnerabilities": []}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:80_000]

            # check cookies for JWTs
            for cookie in r.cookies:
                if self._JWT_RE.search(cookie.value):
                    findings["jwt_in_cookies"].append({
                        "name": cookie.name,
                        "secure": cookie.secure,
                        "httponly": "httponly" in str(cookie._rest).lower(),
                    })

            # scan body/JS for JWTs
            for m in self._JWT_RE.finditer(body):
                token = m.group(0)
                analysis = self._analyze_jwt(token)
                if analysis:
                    findings["tokens_found"].append(analysis)

            # scan JS files
            scripts = re.findall(r'src=["\']([^"\']+\.js[^"\']*)["\']', body)
            for src in scripts[:8]:
                if src.startswith("/"):
                    src = base + src
                elif not src.startswith("http"):
                    continue
                try:
                    jr = ctx.session.get(src, timeout=ctx.timeout)
                    for m in self._JWT_RE.finditer(jr.text[:50_000]):
                        analysis = self._analyze_jwt(m.group(0))
                        if analysis:
                            analysis["source"] = src.split("/")[-1][:30]
                            findings["tokens_found"].append(analysis)
                except Exception:
                    continue
        except Exception:
            pass

        for tok in findings["tokens_found"][:5]:
            if tok.get("alg") == "none":
                findings["vulnerabilities"].append("alg=none (signature bypass)")
            if tok.get("alg") in ("HS256", "HS384", "HS512"):
                findings["vulnerabilities"].append(
                    f"symmetric alg ({tok['alg']}) — brute-force risk")

        risk = "informational"
        if findings["vulnerabilities"]:
            risk = "HIGH"
        elif findings["tokens_found"]:
            risk = "MEDIUM"

        if not findings["tokens_found"]:
            findings["tokens_found"] = "none"
        if not findings["jwt_in_cookies"]:
            findings["jwt_in_cookies"] = "none"
        if not findings["vulnerabilities"]:
            findings["vulnerabilities"] = "none"

        return self.ok(host, findings)

    @staticmethod
    def _analyze_jwt(token):
        import base64
        parts = token.split(".")
        if len(parts) < 2:
            return None
        try:
            header = base64.urlsafe_b64decode(parts[0] + "==")
            import json
            h = json.loads(header)
            payload = base64.urlsafe_b64decode(parts[1] + "==")
            p = json.loads(payload)
            return {
                "alg": h.get("alg", "unknown"),
                "typ": h.get("typ", ""),
                "claims": list(p.keys())[:10],
                "has_exp": "exp" in p,
                "token_preview": token[:20] + "…",
            }
        except Exception:
            return None


@register
class SamlDetect(Module):
    id, name, category = "samldetect", "SAML endpoint discovery", "Auth & Session"
    target_kind = "url"

    _PATHS = [
        "/saml/login", "/saml/sso", "/saml/metadata",
        "/saml2/login", "/saml2/sso",
        "/adfs/ls", "/adfs/services/trust/mex",
        "/FederationMetadata/2007-06/FederationMetadata.xml",
        "/auth/saml/login", "/sso/saml",
        "/simplesaml/module.php/core/frontpage_welcome.php",
        "/simplesaml/saml2/idp/metadata.php",
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
                r = ctx.session.get(base + path, timeout=ctx.timeout,
                                    allow_redirects=False)
                if r.status_code < 404:
                    is_saml = any(kw in r.text.lower()[:5000]
                                  for kw in ["saml", "entityid", "sso",
                                             "x509certificate", "idpsso"])
                    if is_saml or r.status_code in (200, 302):
                        return path, {"status": r.status_code,
                                      "confirmed_saml": is_saml,
                                      "size": len(r.content)}
            except Exception:
                pass
            return path, None

        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for path, info in ex.map(probe, self._PATHS):
                if info:
                    found[path] = info

        return self.ok(host, {
            "saml_endpoints": found or "none found",
            "risk": "MEDIUM" if any(v.get("confirmed_saml")
                                     for v in found.values()) else "informational",
        })


@register
class SessionAudit(Module):
    id, name, category = "sessionaudit", "Session cookie entropy & strength", "Auth & Session"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"cookies": []}

        samples = []
        for _ in range(3):
            try:
                r = ctx.session.get(base, timeout=ctx.timeout)
                for cookie in r.cookies:
                    name_lower = cookie.name.lower()
                    if any(kw in name_lower for kw in
                           ["sess", "sid", "token", "auth", "jwt", "id"]):
                        samples.append(cookie)
            except Exception:
                continue

        seen = set()
        for cookie in samples:
            if cookie.name in seen:
                continue
            seen.add(cookie.name)
            val = cookie.value
            info = {
                "name": cookie.name,
                "length": len(val),
                "secure": cookie.secure,
                "httponly": "httponly" in str(getattr(cookie, '_rest', {})).lower(),
                "samesite": "unknown",
            }
            # entropy estimate
            if len(val) > 0:
                freq = Counter(val)
                entropy = -sum((c / len(val)) * math.log2(c / len(val))
                               for c in freq.values() if c > 0)
                info["entropy_per_char"] = round(entropy, 2)
                info["total_entropy_bits"] = round(entropy * len(val), 1)
                if entropy < 3.0:
                    info["weakness"] = "low entropy (predictable)"
                if len(val) < 16:
                    info["weakness"] = "too short"

            findings["cookies"].append(info)

        risk = "informational"
        for c in findings["cookies"]:
            if c.get("weakness"):
                risk = "HIGH"
            elif not c.get("secure") or not c.get("httponly"):
                risk = "MEDIUM"

        if not findings["cookies"]:
            findings["cookies"] = "no session cookies detected"

        findings["risk"] = risk
        return self.ok(host, findings)


@register
class LoginSurface(Module):
    id, name, category = "loginsurface", "Login brute-force surface", "Auth & Session"
    target_kind = "url"

    _PATHS = ["/login", "/signin", "/auth/login", "/user/login",
              "/api/login", "/api/auth/login", "/account/login",
              "/admin/login", "/wp-login.php"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        for path in self._PATHS:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 100:
                    body = r.text[:20_000].lower()
                    if any(kw in body for kw in ["password", "login", "sign in",
                                                  "username", "email"]):
                        info = {"path": path, "status": 200}
                        info["has_csrf_token"] = bool(
                            re.search(r'name=["\']_?csrf', body) or
                            re.search(r'name=["\']_token', body))
                        info["has_captcha"] = any(
                            kw in body for kw in ["captcha", "recaptcha",
                                                   "hcaptcha", "turnstile"])
                        info["autocomplete_off"] = "autocomplete=\"off\"" in body
                        # try invalid login to check error messages
                        try:
                            pr = ctx.session.post(base + path, timeout=ctx.timeout,
                                                   data={"username": "ghosteye_test_nonexistent",
                                                         "password": "x"},
                                                   allow_redirects=False)
                            if pr.status_code in (200, 401, 403):
                                resp = pr.text[:5000].lower()
                                if "user not found" in resp or "no account" in resp:
                                    info["user_enumeration"] = True
                                if "too many" in resp or "locked" in resp:
                                    info["rate_limiting"] = True
                                if any(h.lower().startswith("x-ratelimit")
                                       for h in pr.headers):
                                    info["rate_limiting"] = True
                        except Exception:
                            pass
                        findings[path] = info
            except Exception:
                continue

        risk = "informational"
        for info in findings.values():
            if info.get("user_enumeration"):
                risk = "HIGH"
            elif not info.get("has_captcha") and not info.get("rate_limiting"):
                risk = "MEDIUM"

        return self.ok(host, {
            "login_forms": findings or "none found",
            "risk": risk,
        })


@register
class PwResetAudit(Module):
    id, name, category = "pwresetaudit", "Password reset flow audit", "Auth & Session"
    target_kind = "url"

    _PATHS = ["/forgot-password", "/password/reset", "/reset-password",
              "/account/forgot", "/auth/forgot", "/api/password/forgot",
              "/user/forgot-password", "/forgot"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        for path in self._PATHS:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and len(r.text) > 100:
                    body = r.text[:10_000].lower()
                    if any(kw in body for kw in ["reset", "forgot", "password",
                                                  "email", "recover"]):
                        info = {"path": path, "status": 200}
                        info["has_csrf"] = bool(re.search(r'csrf|_token', body))
                        info["has_captcha"] = any(
                            kw in body for kw in ["captcha", "recaptcha", "hcaptcha"])
                        # test user enumeration
                        try:
                            pr = ctx.session.post(base + path, timeout=ctx.timeout,
                                                   data={"email": "ghosteye_nonexistent@example.com"},
                                                   allow_redirects=False)
                            resp = pr.text[:3000].lower()
                            if "not found" in resp or "no account" in resp or "doesn't exist" in resp:
                                info["user_enumeration"] = True
                        except Exception:
                            pass
                        findings[path] = info
                        break
            except Exception:
                continue

        risk = "informational"
        if any(f.get("user_enumeration") for f in findings.values()):
            risk = "MEDIUM"

        return self.ok(host, {
            "reset_forms": findings or "none found",
            "risk": risk,
        })


@register
class MfaCheck(Module):
    id, name, category = "mfacheck", "MFA indicator detection", "Auth & Session"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        indicators = []

        pages = ["/login", "/signin", "/auth/login", "/account/security",
                 "/settings/security", "/mfa", "/2fa", "/totp",
                 "/auth/mfa", "/account/two-factor"]

        for path in pages:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:15_000].lower()
                for kw in ["two-factor", "2fa", "mfa", "totp", "authenticator",
                           "verification code", "sms code", "backup codes",
                           "security key", "webauthn", "fido", "passkey"]:
                    if kw in body:
                        indicators.append({"path": path, "indicator": kw})
            except Exception:
                continue

        return self.ok(host, {
            "mfa_indicators": indicators or "none detected",
            "risk": "informational",
            "note": "absence of MFA indicators may mean weak account protection"
        })


@register
class CaptchaCheck(Module):
    id, name, category = "captchacheck", "CAPTCHA implementation audit", "Auth & Session"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {"captcha_detected": [], "missing_captcha": []}

        critical_paths = ["/login", "/register", "/signup", "/contact",
                          "/forgot-password", "/api/login"]

        for path in critical_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code != 200:
                    continue
                body = r.text[:15_000].lower()
                has_form = "<form" in body
                if not has_form:
                    continue
                captcha_type = None
                if "recaptcha" in body or "grecaptcha" in body:
                    captcha_type = "reCAPTCHA"
                elif "hcaptcha" in body:
                    captcha_type = "hCaptcha"
                elif "turnstile" in body:
                    captcha_type = "Cloudflare Turnstile"
                elif "captcha" in body:
                    captcha_type = "generic CAPTCHA"
                if captcha_type:
                    sitekey = re.search(r'(?:data-sitekey|sitekey)[=:]\s*["\']([^"\']+)', body)
                    findings["captcha_detected"].append({
                        "path": path, "type": captcha_type,
                        "sitekey": sitekey.group(1)[:40] if sitekey else "not found"
                    })
                else:
                    findings["missing_captcha"].append(path)
            except Exception:
                continue

        risk = "informational"
        if findings["missing_captcha"]:
            risk = "MEDIUM"

        if not findings["captcha_detected"]:
            findings["captcha_detected"] = "none"
        if not findings["missing_captcha"]:
            findings["missing_captcha"] = "none"

        findings["risk"] = risk
        return self.ok(host, findings)
