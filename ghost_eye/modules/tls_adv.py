"""Advanced TLS / certificate modules (new features #9-#17). Detection only."""

from __future__ import annotations

import socket
import ssl
from datetime import datetime, timezone
from typing import List

from ..core import Context, Module, Result, clean_host, register

_HSTS_PRELOAD_HINT = "https://hstspreload.org/api/v2/status?domain="


def _peercert_der(host: str, port: int, timeout: int):
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=timeout) as s:
        with ctx.wrap_socket(s, server_hostname=host) as ss:
            return ss.getpeercert(binary_form=True), ss.getpeercert(), ss.version()


@register
class OcspStapling(Module):
    id, name, category = "ocspstaple", "OCSP stapling", "SSL/TLS"
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
            try:
                c.ocsp_response_callback = None  # not all builds expose it
            except Exception:
                pass
            with socket.create_connection((host, 443), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host):
                    pass
            # python's ssl can't reliably read the stapled response across versions;
            # report capability + advise openssl for definitive answer
            return self.ok(host, {
                "note": "handshake ok; for definitive stapling use: "
                        "openssl s_client -status -connect "
                        f"{host}:443 (look for 'OCSP Response Status: successful')"})
        except Exception as exc:
            return self.fail(host, f"handshake failed: {exc}")


@register
class OcspRevocation(Module):
    id, name, category = "ocsprevoke", "OCSP revocation status", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from cryptography import x509
            from cryptography.x509.oid import ExtensionOID
        except ImportError:
            return self.fail(target, "requires 'cryptography' (pip install cryptography)")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            der, _, _ = _peercert_der(host, 443, ctx.timeout)
            cert = x509.load_der_x509_certificate(der)
            try:
                aia = cert.extensions.get_extension_for_oid(
                    ExtensionOID.AUTHORITY_INFORMATION_ACCESS).value
                ocsp_urls = [d.access_location.value for d in aia
                             if d.access_method.dotted_string == "1.3.6.1.5.5.7.48.1"]
            except Exception:
                ocsp_urls = []
            return self.ok(host, {
                "ocsp_responders": ocsp_urls or "none in cert",
                "serial": format(cert.serial_number, "x"),
                "note": "full OCSP query requires building a request to the responder"})
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")


@register
class HstsPreload(Module):
    id, name, category = "hstspreload", "HSTS preload membership", "SSL/TLS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            r = ctx.session.get(_HSTS_PRELOAD_HINT + host, timeout=ctx.timeout)
            j = r.json()
            return self.ok(host, {"status": j.get("status"),
                                  "preloaded": j.get("status") == "preloaded"})
        except Exception as exc:
            return self.fail(host, f"preload check failed: {exc}")


@register
class KeyAudit(Module):
    id, name, category = "keyaudit", "Key strength + signature algo", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            from cryptography import x509
            from cryptography.hazmat.primitives.asymmetric import rsa, ec
        except ImportError:
            return self.fail(target, "requires 'cryptography'")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            der, _, _ = _peercert_der(host, 443, ctx.timeout)
            cert = x509.load_der_x509_certificate(der)
            pub = cert.public_key()
            issues = []
            if isinstance(pub, rsa.RSAPublicKey):
                bits = pub.key_size
                ktype = f"RSA-{bits}"
                if bits < 2048:
                    issues.append("RSA key < 2048 bits")
            elif isinstance(pub, ec.EllipticCurvePublicKey):
                ktype = f"EC-{pub.curve.name}"
            else:
                ktype = type(pub).__name__
            sig = cert.signature_hash_algorithm.name if cert.signature_hash_algorithm else "?"
            if sig.lower() in ("md5", "sha1"):
                issues.append(f"weak signature: {sig}")
            return self.ok(host, {"key": ktype, "signature_algo": sig,
                                  "issues": issues or ["none"]})
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")


@register
class WeakDh(Module):
    id, name, category = "weakdh", "Weak DH / Logjam", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        c.check_hostname = False
        c.verify_mode = ssl.CERT_NONE
        try:
            c.set_ciphers("DHE")
        except ssl.SSLError:
            return self.ok(host, {"note": "openssl build offers no DHE to test"})
        try:
            with socket.create_connection((host, 443), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host) as ss:
                    cipher = ss.cipher()
                    return self.ok(host, {"dhe_cipher": cipher[0],
                                          "note": "server uses DHE; verify key >=2048 "
                                                  "bits with sslscan for Logjam"})
        except Exception:
            return self.ok(host, {"dhe_accepted": False,
                                  "note": "server rejected DHE (modern ECDHE-only - good)"})


@register
class TlsCveProbe(Module):
    id, name, category = "tlscve", "Known TLS CVE indicators", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}
        # Heartbleed (CVE-2014-0160): only old OpenSSL/TLS1.0-1.2 exposed; we
        # DETECT exposure surface via protocol support, not by exploiting.
        from .tls import TlsProtocols
        proto = TlsProtocols().run(host, ctx)
        weak = proto.data.get("weak_enabled", []) if proto.status == "ok" else []
        findings["legacy_protocols"] = weak or "none"
        findings["heartbleed_surface"] = ("possible (TLS1.0/1.1 present - verify with "
                                          "a dedicated CVE-2014-0160 checker)"
                                          if weak else "low (no legacy protocols)")
        # ROBOT / CCS notes - require active probing tools
        findings["note"] = ("definitive Heartbleed/ROBOT/CCS-injection testing needs a "
                            "dedicated checker; this reports exposure surface only")
        return self.ok(host, findings)


@register
class SanPivot(Module):
    id, name, category = "sanpivot", "SAN pivot (sibling domains)", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            _, cert, _ = _peercert_der(host, 443, ctx.timeout)
            sans = sorted({v for (k, v) in cert.get("subjectAltName", ()) if k == "DNS"})
            siblings = [s for s in sans if host not in s and s not in host]
            return self.ok(host, {"san": sans, "sibling_domains": siblings or "none"})
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")


@register
class CtMonitor(Module):
    id, name, category = "ctmonitor", "CT log new-cert monitor", "SSL/TLS"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # primary: crt.sh
        try:
            r = ctx.session.get(f"https://crt.sh/?q=%25.{host}&output=json",
                                timeout=ctx.timeout + 20,
                                headers={"User-Agent": "Mozilla/5.0 GhostEye"})
            if r.status_code == 200:
                entries = r.json()
                recent = sorted(
                    ({"name": e.get("common_name"),
                      "issued": e.get("entry_timestamp"),
                      "issuer": (e.get("issuer_name") or "")[:60]} for e in entries),
                    key=lambda x: x["issued"] or "", reverse=True)[:25]
                return self.ok(host, {"source": "crt.sh", "total_certs": len(entries),
                                      "most_recent": recent,
                                      "note": "store with --save-db --diff to alert on new certs"})
        except Exception:
            pass
        # fallback: CertSpotter (different CT aggregator)
        try:
            r = ctx.session.get("https://api.certspotter.com/v1/issuances",
                                params={"domain": host, "include_subdomains": "true",
                                        "expand": "dns_names,issuer"},
                                timeout=ctx.timeout + 10)
            if r.status_code != 200:
                return self.fail(host, f"crt.sh unreachable; CertSpotter HTTP {r.status_code}")
            certs = r.json()
            recent = [{"names": c.get("dns_names", [])[:4],
                       "not_before": c.get("not_before"),
                       "issuer": (c.get("issuer", {}) or {}).get("name", "")[:60]}
                      for c in certs][-25:]
            return self.ok(host, {"source": "certspotter (crt.sh was down)",
                                  "total_certs": len(certs), "most_recent": recent,
                                  "note": "crt.sh failed; used CertSpotter CT feed instead"})
        except Exception as exc:
            return self.fail(host, f"CT query failed on crt.sh and CertSpotter: {exc}")


@register
class CipherOrder(Module):
    id, name, category = "cipherorder", "Cipher order + forward secrecy", "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        c = ssl.create_default_context()
        c.check_hostname = False
        c.verify_mode = ssl.CERT_NONE
        try:
            with socket.create_connection((host, 443), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host) as ss:
                    name, ver, _ = ss.cipher()
            fs = name.startswith(("ECDHE", "DHE")) or "TLS_AES" in name or "TLS_CHACHA" in name
            return self.ok(host, {"negotiated_cipher": name, "protocol": ver,
                                  "forward_secrecy": fs,
                                  "note": "" if fs else "no forward secrecy in default cipher"})
        except Exception as exc:
            return self.fail(host, f"failed: {exc}")
