"""Advanced email modules v2 (v3.5 features #56-#62). Detection only."""

from __future__ import annotations

import re
import socket
from typing import List

from ..core import Context, Module, Result, clean_host, register


def _txt(name: str, ctx: Context) -> List[str]:
    try:
        import dns.resolver
        r = dns.resolver.Resolver()
        r.lifetime = r.timeout = ctx.timeout
        return [b"".join(x.strings).decode("utf-8", "replace")
                for x in r.resolve(name, "TXT")]
    except Exception:
        return []


def _mx(host: str, ctx: Context) -> List[str]:
    try:
        import dns.resolver
        r = dns.resolver.Resolver()
        r.lifetime = r.timeout = ctx.timeout
        return sorted(str(m.exchange).rstrip(".") for m in r.resolve(host, "MX"))
    except Exception:
        return []


@register
class BimiCheck(Module):
    id, name, category = "bimicheck", "BIMI record check (extended)", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        rec = [t for t in _txt(f"default._bimi.{host}", ctx) if "v=BIMI1" in t]
        data = {"bimi_record": rec[0] if rec else "not set"}
        if rec:
            logo_match = re.search(r"l=(\S+)", rec[0])
            authority_match = re.search(r"a=(\S+)", rec[0])
            data["logo_url"] = logo_match.group(1) if logo_match else "not specified"
            data["authority"] = authority_match.group(1) if authority_match else "none (self-asserted)"
            if logo_match:
                try:
                    r = ctx.session.head(logo_match.group(1), timeout=ctx.timeout)
                    data["logo_accessible"] = r.status_code == 200
                    data["logo_content_type"] = r.headers.get("Content-Type", "unknown")
                except Exception:
                    data["logo_accessible"] = False
            # check for VMC (Verified Mark Certificate)
            data["has_vmc"] = bool(authority_match and authority_match.group(1))
        else:
            data["note"] = "BIMI not configured - no brand logo in email clients"
        # also check DMARC (BIMI requires DMARC p=quarantine or p=reject)
        dmarc = [t for t in _txt(f"_dmarc.{host}", ctx) if "v=DMARC1" in t]
        if dmarc:
            enforcing = "p=reject" in dmarc[0] or "p=quarantine" in dmarc[0]
            data["dmarc_enforcing"] = enforcing
            if not enforcing:
                data["bimi_usable"] = False
                data["note"] = "BIMI requires DMARC with p=quarantine or p=reject"
        return self.ok(host, data)


@register
class MtaStsValidation(Module):
    id, name, category = "mtastsval", "MTA-STS policy validation", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        issues = []
        data = {}
        # DNS record
        dns_rec = _txt(f"_mta-sts.{host}", ctx)
        sts_dns = [t for t in dns_rec if "v=STSv1" in t]
        data["dns_record"] = sts_dns[0] if sts_dns else "not set"
        if not sts_dns:
            issues.append("missing _mta-sts DNS TXT record")
        else:
            id_match = re.search(r"id=(\S+)", sts_dns[0])
            data["policy_id"] = id_match.group(1) if id_match else "missing"
        # policy file
        try:
            r = ctx.session.get(f"https://mta-sts.{host}/.well-known/mta-sts.txt",
                                timeout=ctx.timeout)
            if r.status_code == 200 and "version" in r.text.lower():
                lines = r.text.strip().splitlines()
                data["policy"] = lines
                policy = {}
                for line in lines:
                    if ":" in line:
                        k, v = line.split(":", 1)
                        policy[k.strip().lower()] = v.strip()
                data["mode"] = policy.get("mode", "unknown")
                data["max_age"] = policy.get("max_age", "unknown")
                mx_patterns = [v for k, v in policy.items() if k == "mx"]
                data["mx_patterns"] = mx_patterns
                if policy.get("mode") == "none":
                    issues.append("mode is 'none' (not enforcing)")
                if policy.get("mode") == "testing":
                    issues.append("mode is 'testing' (monitoring only)")
                try:
                    if int(policy.get("max_age", 0)) < 86400:
                        issues.append("max_age < 1 day (too short)")
                except ValueError:
                    pass
                # validate MX match
                actual_mx = _mx(host, ctx)
                for mx in actual_mx:
                    matched = any(
                        mx == pat or (pat.startswith("*.") and mx.endswith(pat[1:]))
                        for pat in mx_patterns)
                    if not matched:
                        issues.append(f"MX {mx} not covered by policy")
            else:
                issues.append("policy file not accessible or invalid")
                data["policy_http_status"] = r.status_code
        except Exception as exc:
            issues.append(f"cannot fetch policy: {exc}")
        data["issues"] = issues or ["valid MTA-STS configuration"]
        return self.ok(host, data)


@register
class TlsRptCheck(Module):
    id, name, category = "tlsrptcheck", "SMTP TLS reporting (TLSRPT) check", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        rec = _txt(f"_smtp._tls.{host}", ctx)
        tlsrpt = [t for t in rec if "v=TLSRPTv1" in t]
        data = {"record": tlsrpt[0] if tlsrpt else "not set"}
        if tlsrpt:
            rua_match = re.findall(r"rua=(\S+)", tlsrpt[0])
            data["report_targets"] = rua_match or "none specified"
            mailto = [r for r in rua_match if r.startswith("mailto:")]
            https = [r for r in rua_match if r.startswith("https:")]
            data["mailto_targets"] = mailto
            data["https_targets"] = https
        else:
            data["note"] = "no TLS-RPT record - TLS failures will go unreported"
        return self.ok(host, data)


@register
class SpoofabilityCheck(Module):
    id, name, category = "spoofcheck", "Email spoofing risk analysis", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        score = 0
        findings = {}
        # SPF
        txts = _txt(host, ctx)
        spf = [t for t in txts if t.startswith("v=spf1")]
        if spf:
            findings["spf"] = spf[0][:120]
            if "-all" in spf[0]:
                score += 30
                findings["spf_policy"] = "hard fail (-all)"
            elif "~all" in spf[0]:
                score += 15
                findings["spf_policy"] = "soft fail (~all)"
            elif "+all" in spf[0]:
                findings["spf_policy"] = "PASS ALL (+all) - anyone can spoof"
            else:
                score += 10
                findings["spf_policy"] = "neutral"
        else:
            findings["spf"] = "MISSING"
        # DMARC
        dmarc_recs = _txt(f"_dmarc.{host}", ctx)
        dmarc = [t for t in dmarc_recs if "v=DMARC1" in t]
        if dmarc:
            findings["dmarc"] = dmarc[0][:120]
            if "p=reject" in dmarc[0]:
                score += 40
                findings["dmarc_policy"] = "reject"
            elif "p=quarantine" in dmarc[0]:
                score += 25
                findings["dmarc_policy"] = "quarantine"
            elif "p=none" in dmarc[0]:
                score += 5
                findings["dmarc_policy"] = "none (monitoring only)"
        else:
            findings["dmarc"] = "MISSING"
        # DKIM
        selectors = ["default", "google", "selector1", "selector2", "k1", "dkim"]
        dkim_found = False
        for sel in selectors:
            r = _txt(f"{sel}._domainkey.{host}", ctx)
            if any("v=DKIM1" in t or "p=" in t for t in r):
                dkim_found = True
                score += 20
                findings["dkim"] = f"found (selector: {sel})"
                break
        if not dkim_found:
            findings["dkim"] = "not found (common selectors checked)"
        # MX exists
        mxs = _mx(host, ctx)
        findings["mx_records"] = len(mxs)
        spoofable = score < 50
        grade = "A" if score >= 80 else "B" if score >= 60 else "C" if score >= 40 else "D" if score >= 20 else "F"
        return self.ok(host, {**findings, "score": score, "grade": grade,
                              "spoofable": spoofable,
                              "verdict": "LOW risk - good protection" if not spoofable
                              else "HIGH risk - domain can likely be spoofed"})


@register
class SmtpRelay(Module):
    id, name, category = "smtprelay", "SMTP open relay test", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        mxs = _mx(host, ctx)
        if not mxs:
            return self.ok(host, {"note": "no MX records"})
        results = {}
        for mx in mxs[:2]:
            try:
                with socket.create_connection((mx, 25), timeout=ctx.timeout) as s:
                    s.settimeout(ctx.timeout)
                    banner = s.recv(512).decode("utf-8", "replace")
                    s.sendall(b"EHLO ghosteye.local\r\n")
                    s.recv(1024)
                    s.sendall(b"MAIL FROM:<test@ghosteye.local>\r\n")
                    s.recv(256)
                    # try to relay to external domain
                    s.sendall(b"RCPT TO:<relay-test@example.com>\r\n")
                    resp = s.recv(256).decode("utf-8", "replace").strip()
                    relay = resp.startswith("2")
                    results[mx] = {
                        "banner": banner.strip()[:100],
                        "open_relay": relay,
                        "response": resp[:120],
                        "risk": "CRITICAL - open relay (spam abuse)" if relay else "ok"
                    }
                    s.sendall(b"QUIT\r\n")
            except Exception as exc:
                results[mx] = {"error": str(exc)[:80]}
        return self.ok(host, {"servers": results})


@register
class DisposableEmailDetect(Module):
    id, name, category = "dispdetect", "Disposable email domain detection", "Email"
    target_kind = "domain"

    _DISPOSABLE_PROVIDERS = {
        "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com",
        "yopmail.com", "trashmail.com", "getnada.com", "temp-mail.org",
        "throwawaymail.com", "maildrop.cc", "sharklasers.com", "dispostable.com",
        "guerrillamailblock.com", "grr.la", "guerrillamail.info", "guerrillamail.biz",
        "guerrillamail.de", "guerrillamail.net", "mailnesia.com", "tempail.com",
        "fakeinbox.com", "mailcatch.com", "mintemail.com", "mytemp.email",
        "mt2015.com", "mohmal.com", "receiveee.com", "tmail.ws",
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        mxs = _mx(host, ctx)
        mx_domains = set()
        for mx in mxs:
            parts = mx.split(".")
            if len(parts) >= 2:
                mx_domains.add(".".join(parts[-2:]))
        is_disposable = host in self._DISPOSABLE_PROVIDERS
        mx_disposable = mx_domains & self._DISPOSABLE_PROVIDERS
        return self.ok(host, {
            "domain": host,
            "is_disposable": is_disposable or bool(mx_disposable),
            "mx_records": mxs,
            "mx_matches_disposable": sorted(mx_disposable) or "none",
            "note": "known disposable email domain" if is_disposable
            else "MX points to disposable provider" if mx_disposable
            else "not a known disposable domain"
        })


@register
class CatchAllDetect(Module):
    id, name, category = "catchalldetect", "Catch-all email detection (extended)", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        mxs = _mx(host, ctx)
        if not mxs:
            return self.ok(host, {"note": "no MX records"})
        probes = [f"ghosteye-probe-{hash(host) & 0xffff}@{host}",
                  f"zzz-nonexistent-user-999@{host}"]
        results = {}
        for mx in mxs[:1]:
            accepted = 0
            for probe in probes:
                try:
                    with socket.create_connection((mx, 25), timeout=ctx.timeout) as s:
                        s.settimeout(ctx.timeout)
                        s.recv(512)
                        for cmd in (b"EHLO ghosteye.local\r\n",
                                    b"MAIL FROM:<probe@ghosteye.local>\r\n",
                                    f"RCPT TO:<{probe}>\r\n".encode()):
                            s.sendall(cmd)
                            resp = s.recv(512).decode("utf-8", "replace")
                        if resp.startswith("25"):
                            accepted += 1
                        s.sendall(b"QUIT\r\n")
                except Exception:
                    continue
            results[mx] = {
                "catch_all": accepted == len(probes),
                "accepted_probes": accepted,
                "total_probes": len(probes),
            }
        is_catch_all = any(r["catch_all"] for r in results.values())
        return self.ok(host, {"servers": results, "catch_all": is_catch_all,
                              "note": "catch-all accepts any address (email enumeration not possible)"
                              if is_catch_all else "not catch-all"})
