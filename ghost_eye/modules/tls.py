"""SSL/TLS analysis modules (features #29-#35).

Pure-stdlib (ssl + socket) so there are no extra dependencies.
"""

from __future__ import annotations

import socket
import ssl
from datetime import datetime, timezone
from typing import Dict, List, Tuple

from ..core import Context, Module, Result, clean_host, register

_PROTOCOLS: List[Tuple[str, int]] = [
    ("TLSv1.0", ssl.TLSVersion.TLSv1),
    ("TLSv1.1", ssl.TLSVersion.TLSv1_1),
    ("TLSv1.2", ssl.TLSVersion.TLSv1_2),
    ("TLSv1.3", ssl.TLSVersion.TLSv1_3),
]
_WEAK_PROTOCOLS = {"TLSv1.0", "TLSv1.1", "SSLv3", "SSLv2"}


def _split_hostport(target: str, default_port: int = 443) -> Tuple[str, int]:
    host = clean_host(target)
    return host, default_port


def _get_cert(host: str, port: int, timeout: int) -> dict:
    ctx = ssl.create_default_context()
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=timeout) as sock:
        with ctx.wrap_socket(sock, server_hostname=host) as ss:
            return {
                "cert": ss.getpeercert(),
                "cipher": ss.cipher(),
                "version": ss.version(),
            }


@register
class TlsCertificate(Module):
    id, name, category = "cert", "SSL certificate analysis", "SSL/TLS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host, port = _split_hostport(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            info = _get_cert(host, port, ctx.timeout)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"TLS handshake failed: {exc}")
        cert = info["cert"] or {}

        def _name(seq):
            return {k: v for tup in (seq or ()) for (k, v) in tup}

        subject = _name(cert.get("subject"))
        issuer = _name(cert.get("issuer"))
        sans = [v for (k, v) in cert.get("subjectAltName", ()) if k == "DNS"]
        not_after = cert.get("notAfter")
        days_left = None
        if not_after:
            try:
                exp = datetime.strptime(not_after, "%b %d %H:%M:%S %Y %Z").replace(
                    tzinfo=timezone.utc)
                days_left = (exp - datetime.now(timezone.utc)).days
            except ValueError:
                pass
        return self.ok(host, {
            "subject_cn": subject.get("commonName"),
            "issuer": issuer.get("organizationName") or issuer.get("commonName"),
            "valid_from": cert.get("notBefore"),
            "valid_until": not_after,
            "days_until_expiry": days_left,
            "san": sans,
            "serial": cert.get("serialNumber"),
            "negotiated": f"{info['version']} / {info['cipher'][0] if info['cipher'] else '?'}",
        })


@register
class CertExpiry(Module):
    id, name, category = "certexpiry", "Certificate expiry alert", "SSL/TLS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host, port = _split_hostport(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            cert = _get_cert(host, port, ctx.timeout)["cert"] or {}
            exp = datetime.strptime(cert["notAfter"], "%b %d %H:%M:%S %Y %Z").replace(
                tzinfo=timezone.utc)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"could not read expiry: {exc}")
        days = (exp - datetime.now(timezone.utc)).days
        level = ("EXPIRED" if days < 0 else "critical" if days < 7
                 else "warning" if days < 30 else "ok")
        return self.ok(host, {"expires": cert["notAfter"], "days_left": days,
                              "alert": level})


@register
class TlsProtocols(Module):
    id, name, category = "tlsversions", "Supported TLS protocols", "SSL/TLS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host, port = _split_hostport(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        supported: Dict[str, bool] = {}
        for label, version in _PROTOCOLS:
            c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            c.check_hostname = False
            c.verify_mode = ssl.CERT_NONE
            try:
                c.minimum_version = version
                c.maximum_version = version
            except (ValueError, OSError):
                supported[label] = False
                continue
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    with c.wrap_socket(s, server_hostname=host):
                        supported[label] = True
            except Exception:
                supported[label] = False
        weak = [p for p, ok in supported.items() if ok and p in _WEAK_PROTOCOLS]
        return self.ok(host, {"protocols": supported,
                              "weak_enabled": weak,
                              "note": "disable TLS 1.0/1.1" if weak else "modern config"})


@register
class CipherEnum(Module):
    id, name, category = "ciphers", "Cipher suite enumeration", "SSL/TLS"
    target_kind = "host"

    # representative buckets; full enumeration would test every IANA suite
    _SUITES = {
        "strong": "ECDHE+AESGCM:ECDHE+CHACHA20",
        "acceptable": "ECDHE+AES:DHE+AES",
        "weak_rc4": "RC4",
        "weak_3des": "3DES:DES-CBC3",
        "weak_export": "EXPORT",
        "null": "NULL:aNULL:eNULL",
    }

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host, port = _split_hostport(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        results: Dict[str, object] = {}
        weak_found: List[str] = []
        for bucket, cipher_str in self._SUITES.items():
            c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            c.check_hostname = False
            c.verify_mode = ssl.CERT_NONE
            try:
                c.set_ciphers(cipher_str)
            except ssl.SSLError:
                results[bucket] = "not offered by openssl build"
                continue
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    with c.wrap_socket(s, server_hostname=host) as ss:
                        results[bucket] = ss.cipher()[0]
                        if bucket.startswith("weak") or bucket == "null":
                            weak_found.append(bucket)
            except Exception:
                results[bucket] = "rejected (good)" if bucket.startswith(("weak", "null")) else "rejected"
        return self.ok(host, {"buckets": results, "weak_accepted": weak_found})


@register
class CertChain(Module):
    id, name, category = "chain", "Certificate chain validation", "SSL/TLS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host, port = _split_hostport(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        c = ssl.create_default_context()  # verifies by default
        try:
            with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host) as ss:
                    cert = ss.getpeercert()
            return self.ok(host, {"chain_valid": True,
                                  "verified_subject": dict(x[0] for x in cert.get("subject", ()))})
        except ssl.SSLCertVerificationError as exc:
            return self.ok(host, {"chain_valid": False, "reason": str(exc)})
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"validation error: {exc}")


@register
class TlsGrade(Module):
    id, name, category = "tlsgrade", "TLS grade (SSL-Labs style)", "SSL/TLS"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host, _ = _split_hostport(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        proto = TlsProtocols().run(host, ctx)
        ciphers = CipherEnum().run(host, ctx)
        chain = CertChain().run(host, ctx)
        expiry = CertExpiry().run(host, ctx)
        if proto.status == "error":
            return proto

        score = 100
        reasons = []
        for weak in proto.data.get("weak_enabled", []):
            score -= 15
            reasons.append(f"weak protocol enabled: {weak}")
        if not proto.data.get("protocols", {}).get("TLSv1.2") and \
           not proto.data.get("protocols", {}).get("TLSv1.3"):
            score -= 30
            reasons.append("no TLS 1.2/1.3")
        for weak in ciphers.data.get("weak_accepted", []):
            score -= 20
            reasons.append(f"weak cipher bucket accepted: {weak}")
        if chain.data.get("chain_valid") is False:
            score -= 25
            reasons.append("certificate chain does not validate")
        if expiry.data.get("alert") in ("EXPIRED", "critical"):
            score -= 20
            reasons.append(f"certificate {expiry.data.get('alert')}")

        score = max(score, 0)
        grade = ("A+" if score >= 95 else "A" if score >= 85 else "B" if score >= 70
                 else "C" if score >= 55 else "D" if score >= 35 else "F")
        return self.ok(host, {"grade": grade, "score": score,
                              "deductions": reasons or ["none"]})
