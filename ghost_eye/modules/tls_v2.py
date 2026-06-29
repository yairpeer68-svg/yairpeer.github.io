"""Advanced TLS/SSL modules v2 (v3.5 features #13-#22). Detection only."""

from __future__ import annotations

import hashlib
import re
import socket
import ssl
from datetime import datetime, timezone
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


def _peercert(host: str, port: int, timeout: int):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=timeout) as s:
        with ctx.wrap_socket(s, server_hostname=host) as ss:
            return ss.getpeercert(binary_form=True), ss.getpeercert(), ss.version()


@register
class CtDiff(Module):
    id, name, category = "ctdiff", "Certificate transparency diff", "SSL/TLS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(
                f"https://crt.sh/?q=%25.{host}&output=json",
                timeout=ctx.timeout + 20,
                headers={"User-Agent": "Mozilla/5.0 GhostEye"})
            if r.status_code != 200:
                return self.fail(host, f"crt.sh HTTP {r.status_code}")
            entries = r.json()
            from collections import Counter
            issuers = Counter(
                (e.get("issuer_name") or "")[:60] for e in entries)
            recent = sorted(entries, key=lambda e: e.get("entry_timestamp", ""),
                            reverse=True)[:15]
            recent_certs = [{"cn": e.get("common_name"),
                             "issued": e.get("entry_timestamp"),
                             "issuer": (e.get("issuer_name") or "")[:60],
                             "id": e.get("id")} for e in recent]
            # find new certs in last 7 days
            from datetime import timedelta
            cutoff = (datetime.now(timezone.utc) - timedelta(days=7)).isoformat()
            new_certs = [c for c in recent_certs if (c.get("issued") or "") > cutoff]
            return self.ok(host, {"total_certs": len(entries),
                                  "new_last_7_days": len(new_certs),
                                  "recent": recent_certs,
                                  "top_issuers": dict(issuers.most_common(5)),
                                  "note": "compare with previous scan to detect unauthorized certs"})
        except Exception as exc:
            return self.fail(host, f"CT query failed: {exc}")


@register
class Ja3Fingerprint(Module):
    id, name, category = "ja3", "TLS fingerprinting (JA3/JA3S)", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            c = ssl.create_default_context()
            c.check_hostname = False
            c.verify_mode = ssl.CERT_NONE
            with socket.create_connection((host, 443), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host) as ss:
                    cipher = ss.cipher()
                    version = ss.version()
                    cert = ss.getpeercert()
            # JA3S approximation from server-side observables
            ja3s_raw = f"{version},{cipher[0]}"
            ja3s_hash = hashlib.md5(ja3s_raw.encode()).hexdigest()
            return self.ok(host, {
                "ja3s_approximation": ja3s_hash,
                "ja3s_raw": ja3s_raw,
                "negotiated_cipher": cipher[0],
                "protocol": version,
                "note": "full JA3/JA3S requires packet capture; this is a server-side approximation"
            })
        except Exception as exc:
            return self.fail(host, f"TLS handshake failed: {exc}")


@register
class DeprecatedCa(Module):
    id, name, category = "deprecatedca", "Deprecated CA detection", "SSL/TLS"
    target_kind = "host"

    _DEPRECATED_CAS = [
        "symantec", "verisign", "thawte", "geotrust", "rapidssl",
        "wosign", "startcom", "startssl", "cnnic", "india cca",
        "turktrust", "diginotar", "trustcor",
    ]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            _, cert, _ = _peercert(host, 443, ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"TLS handshake failed: {exc}")
        issuer = dict(x[0] for x in cert.get("issuer", ()))
        issuer_cn = issuer.get("commonName", "")
        issuer_org = issuer.get("organizationName", "")
        issuer_str = f"{issuer_org} {issuer_cn}".lower()
        deprecated = [ca for ca in self._DEPRECATED_CAS if ca in issuer_str]
        return self.ok(host, {
            "issuer_cn": issuer_cn,
            "issuer_org": issuer_org,
            "deprecated_ca": deprecated or "none",
            "risk": "HIGH - certificate from deprecated/distrusted CA" if deprecated else "ok"
        })


@register
class CertPinning(Module):
    id, name, category = "certpin", "Certificate pinning detection", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(ensure_scheme(host), timeout=ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        headers = {k.lower(): v for k, v in r.headers.items()}
        hpkp = headers.get("public-key-pins", "")
        hpkp_ro = headers.get("public-key-pins-report-only", "")
        expect_ct = headers.get("expect-ct", "")
        return self.ok(host, {
            "hpkp": hpkp or "not set (deprecated but still informative)",
            "hpkp_report_only": hpkp_ro or "not set",
            "expect_ct": expect_ct or "not set",
            "note": "HPKP is deprecated; modern pinning uses CAA records + CT monitoring"
        })


@register
class MixedContentScan(Module):
    id, name, category = "mixedcontent", "Mixed content deep scan", "SSL/TLS"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from bs4 import BeautifulSoup
        except ImportError:
            return self.fail(target, "requires beautifulsoup4")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(f"https://{host}", timeout=ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"request failed: {exc}")
        soup = BeautifulSoup(r.text, "html.parser")
        mixed = {"scripts": [], "stylesheets": [], "images": [], "iframes": [],
                 "forms": [], "other": []}
        for tag in soup.find_all("script", src=True):
            src = tag["src"]
            if src.startswith("http://"):
                mixed["scripts"].append(src[:100])
        for tag in soup.find_all("link", rel="stylesheet"):
            href = tag.get("href", "")
            if href.startswith("http://"):
                mixed["stylesheets"].append(href[:100])
        for tag in soup.find_all("img", src=True):
            if tag["src"].startswith("http://"):
                mixed["images"].append(tag["src"][:100])
        for tag in soup.find_all("iframe", src=True):
            if tag["src"].startswith("http://"):
                mixed["iframes"].append(tag["src"][:100])
        for tag in soup.find_all("form", action=True):
            if tag["action"].startswith("http://"):
                mixed["forms"].append(tag["action"][:100])
        for tag in soup.find_all(["video", "audio", "source", "embed", "object"]):
            src = tag.get("src") or tag.get("data", "")
            if src.startswith("http://"):
                mixed["other"].append(src[:100])
        total = sum(len(v) for v in mixed.values())
        mixed_clean = {k: v for k, v in mixed.items() if v}
        return self.ok(host, {"total_mixed": total,
                              "findings": mixed_clean or "no mixed content",
                              "risk": "HIGH" if mixed.get("scripts") or mixed.get("forms")
                              else "MEDIUM" if total > 0 else "none"})


@register
class WildcardCertScope(Module):
    id, name, category = "wildcertscope", "Wildcard cert scope mapping", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            _, cert, _ = _peercert(host, 443, ctx.timeout)
        except Exception as exc:
            return self.fail(host, f"TLS handshake failed: {exc}")
        sans = sorted({v for k, v in cert.get("subjectAltName", ()) if k == "DNS"})
        wildcards = [s for s in sans if s.startswith("*.")]
        specific = [s for s in sans if not s.startswith("*.")]
        subject = dict(x[0] for x in cert.get("subject", ()))
        cn = subject.get("commonName", "")
        covered_domains = set()
        for w in wildcards:
            base = w[2:]
            covered_domains.add(base)
        return self.ok(host, {"cn": cn, "san_count": len(sans),
                              "wildcards": wildcards or "none",
                              "specific_names": specific[:30],
                              "wildcard_base_domains": sorted(covered_domains) or "none",
                              "note": "wildcard certs cover any subdomain one level deep"})


@register
class TlsResumption(Module):
    id, name, category = "tlsresume", "TLS session resumption test", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        c = ssl.create_default_context()
        c.check_hostname = False
        c.verify_mode = ssl.CERT_NONE
        data = {}
        # first connection
        try:
            with socket.create_connection((host, 443), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host) as ss:
                    data["protocol"] = ss.version()
                    data["cipher"] = ss.cipher()[0]
                    # Session tickets are TLS 1.3's mechanism
                    data["tls13"] = ss.version() == "TLSv1.3"
        except Exception as exc:
            return self.fail(host, f"TLS handshake failed: {exc}")
        if data.get("tls13"):
            data["session_resumption"] = "TLS 1.3 uses PSK-based resumption (0-RTT capable)"
            data["note"] = "0-RTT replay risk depends on server config"
        else:
            data["session_resumption"] = "TLS 1.2 uses session IDs/tickets"
            data["note"] = "verify session ticket rotation with sslyze for details"
        return self.ok(host, data)


@register
class CaaAudit(Module):
    id, name, category = "caaaudit", "CAA record vs actual issuer audit", "SSL/TLS"
    target_kind = "host"

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
        # get CAA records
        caa_records = []
        try:
            for rr in r.resolve(host, "CAA"):
                caa_records.append(str(rr))
        except Exception:
            pass
        allowed_issuers = set()
        for rec in caa_records:
            m = re.search(r'issue\s+"([^"]+)"', rec)
            if m:
                allowed_issuers.add(m.group(1).lower())
        # get actual cert issuer
        actual_issuer = None
        try:
            _, cert, _ = _peercert(host, 443, ctx.timeout)
            issuer = dict(x[0] for x in cert.get("issuer", ()))
            actual_issuer = issuer.get("organizationName") or issuer.get("commonName", "")
        except Exception:
            pass
        mismatch = False
        if allowed_issuers and actual_issuer:
            issuer_lower = actual_issuer.lower()
            mismatch = not any(ai in issuer_lower or issuer_lower in ai
                               for ai in allowed_issuers)
        return self.ok(host, {
            "caa_records": caa_records or "none (any CA may issue)",
            "allowed_issuers": sorted(allowed_issuers) or "unrestricted",
            "actual_issuer": actual_issuer or "unknown",
            "mismatch": mismatch,
            "note": "CAA mismatch may indicate unauthorized certificate issuance"
            if mismatch else ""
        })


@register
class MtlsDetect(Module):
    id, name, category = "mtls", "Mutual TLS (mTLS) detection", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        for port in (443, 8443, 9443):
            try:
                c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
                c.check_hostname = False
                c.verify_mode = ssl.CERT_NONE
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    with c.wrap_socket(s, server_hostname=host) as ss:
                        # if server requires client cert, handshake fails differently
                        results[port] = "TLS up, no client cert required"
            except ssl.SSLError as e:
                err = str(e).lower()
                if "certificate required" in err or "bad certificate" in err:
                    results[port] = "mTLS REQUIRED (server demands client certificate)"
                elif "handshake" in err:
                    results[port] = f"handshake error: {str(e)[:80]}"
            except Exception:
                continue
        return self.ok(host, {"ports": results or "no TLS ports responded",
                              "note": "mTLS endpoints require a client certificate for access"})


@register
class SctsValidation(Module):
    id, name, category = "scts", "Signed Certificate Timestamps (SCTs)", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from cryptography import x509
            from cryptography.x509.oid import ExtensionOID
        except ImportError:
            return self.fail(target, "requires cryptography")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            der, cert_dict, _ = _peercert(host, 443, ctx.timeout)
            cert = x509.load_der_x509_certificate(der)
            scts = []
            try:
                sct_ext = cert.extensions.get_extension_for_oid(
                    ExtensionOID.PRECERT_SIGNED_CERTIFICATE_TIMESTAMPS)
                for sct in sct_ext.value:
                    scts.append({
                        "version": sct.version.name if hasattr(sct, 'version') else "unknown",
                        "log_id": sct.log_id[:8].hex() + "..." if hasattr(sct, 'log_id') else "?",
                        "timestamp": str(sct.timestamp) if hasattr(sct, 'timestamp') else "?",
                    })
            except Exception:
                pass
            return self.ok(host, {
                "scts_embedded": len(scts),
                "scts": scts[:10] or "none embedded in certificate",
                "note": "SCTs prove the cert was logged to CT; absence may indicate a misissued cert"
            })
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")
