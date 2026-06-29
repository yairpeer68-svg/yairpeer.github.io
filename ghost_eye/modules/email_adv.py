"""Advanced email-security modules (new features #1-#8)."""

from __future__ import annotations

import socket
import ssl
from typing import List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register

_DISPOSABLE = {
    "mailinator.com", "guerrillamail.com", "10minutemail.com", "tempmail.com",
    "yopmail.com", "trashmail.com", "getnada.com", "temp-mail.org",
    "throwawaymail.com", "maildrop.cc", "sharklasers.com", "dispostable.com",
}
_COMMON_SELECTORS = ["default", "google", "selector1", "selector2", "k1", "k2",
                     "dkim", "mail", "smtp", "s1", "s2", "mandrill", "mxvault",
                     "zoho", "amazonses", "pic", "everlytickey1", "fm1", "fm2"]


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
class MtaSts(Module):
    id, name, category = "mtasts", "MTA-STS policy", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        rec = [t for t in _txt(f"_mta-sts.{host}", ctx) if "v=STSv1" in t]
        policy = None
        try:
            r = ctx.session.get(f"https://mta-sts.{host}/.well-known/mta-sts.txt",
                                timeout=ctx.timeout)
            if r.status_code == 200 and "STSv1" in r.text:
                policy = r.text.strip().splitlines()
        except Exception:
            pass
        return self.ok(host, {"dns_record": rec or "(none)",
                              "policy": policy or "(no policy file)",
                              "enforced": bool(policy and any("mode: enforce" in l
                                                              for l in policy))})


@register
class TlsRpt(Module):
    id, name, category = "tlsrpt", "TLS-RPT reporting", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        rec = [t for t in _txt(f"_smtp._tls.{host}", ctx) if "v=TLSRPTv1" in t]
        return self.ok(host, {"tlsrpt": rec or "(none) - no TLS failure reporting"})


@register
class Bimi(Module):
    id, name, category = "bimi", "BIMI brand indicator", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        rec = [t for t in _txt(f"default._bimi.{host}", ctx) if "v=BIMI1" in t]
        return self.ok(host, {"bimi": rec or "(none)"})


@register
class DkimProbe(Module):
    id, name, category = "dkim", "DKIM selector discovery", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        found = {}
        for sel in _COMMON_SELECTORS:
            rec = _txt(f"{sel}._domainkey.{host}", ctx)
            if any("v=DKIM1" in r or "k=rsa" in r or "p=" in r for r in rec):
                found[sel] = (rec[0][:80] + "...") if rec else "present"
        return self.ok(host, {"selectors_found": found or "none of the common selectors"})


@register
class StartTls(Module):
    id, name, category = "starttls", "STARTTLS on MX", "Email"
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
        for mx in mxs[:3]:
            try:
                with socket.create_connection((mx, 25), timeout=ctx.timeout) as s:
                    s.settimeout(ctx.timeout)
                    s.recv(512)
                    s.sendall(b"EHLO ghosteye.local\r\n")
                    caps = s.recv(1024).decode("utf-8", "replace")
                    results[mx] = "STARTTLS supported" if "STARTTLS" in caps.upper() \
                        else "NO STARTTLS - mail sent in cleartext"
            except Exception as exc:
                results[mx] = f"unreachable: {exc}"
        return self.ok(host, {"mx": results})


@register
class CatchAll(Module):
    id, name, category = "catchall", "Catch-all mailbox detection", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        mxs = _mx(host, ctx)
        if not mxs:
            return self.ok(host, {"note": "no MX records"})
        probe = f"ghosteye-no-such-user-7731@{host}"
        try:
            with socket.create_connection((mxs[0], 25), timeout=ctx.timeout) as s:
                s.settimeout(ctx.timeout)
                s.recv(512)
                for cmd in (b"EHLO ghosteye.local\r\n",
                            b"MAIL FROM:<probe@ghosteye.local>\r\n",
                            f"RCPT TO:<{probe}>\r\n".encode()):
                    s.sendall(cmd)
                    resp = s.recv(512).decode("utf-8", "replace")
                accepted = resp.startswith("25")
                return self.ok(host, {"server": mxs[0], "probe": probe,
                                      "catch_all": accepted,
                                      "response": resp.strip()[:120]})
        except Exception as exc:
            return self.fail(host, f"SMTP probe failed: {exc}")


@register
class Disposable(Module):
    id, name, category = "disposable", "Disposable-domain check", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        host = target.strip().lower().split("@")[-1]
        return self.ok(host, {"disposable": host in _DISPOSABLE,
                              "note": "known throwaway-mail domain" if host in _DISPOSABLE
                              else "not in disposable list"})


@register
class DmarcRua(Module):
    id, name, category = "dmarcrua", "DMARC RUA target analysis", "Email"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        rec = [t for t in _txt(f"_dmarc.{host}", ctx) if "v=DMARC1" in t.lower()]
        if not rec:
            return self.ok(host, {"note": "no DMARC record"})
        import re
        rua = re.findall(r"rua=([^;]+)", rec[0])
        ruf = re.findall(r"ruf=([^;]+)", rec[0])
        ext = []
        for addr in (rua[0].split(",") if rua else []):
            dom = addr.split("@")[-1].strip()
            if dom and not dom.endswith(host):
                ext.append(dom)
        return self.ok(host, {"rua": rua, "ruf": ruf,
                              "external_report_domains": ext or "none",
                              "note": "external RUA domains must publish an "
                                      "authorisation record" if ext else ""})
