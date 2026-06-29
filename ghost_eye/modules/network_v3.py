"""Network Advanced modules (features #71-#75). Detection only."""

from __future__ import annotations

import re
import socket
import struct
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class QuicDetect(Module):
    id, name, category = "quicdetect", "QUIC / HTTP/3 transport detection", "Network"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        # check Alt-Svc header for h3
        base = ensure_scheme(host).rstrip("/")
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            alt_svc = r.headers.get("Alt-Svc", "")
            if alt_svc:
                findings["alt_svc"] = alt_svc[:200]
                if "h3" in alt_svc:
                    findings["http3_advertised"] = True
                    h3_matches = re.findall(r'h3[^;]*="([^"]*)"', alt_svc)
                    if h3_matches:
                        findings["h3_endpoints"] = h3_matches[:5]
                if "quic" in alt_svc.lower():
                    findings["quic_advertised"] = True
        except Exception:
            pass

        # probe UDP 443 for QUIC
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(min(ctx.timeout, 3))
            # QUIC initial packet (simplified version header)
            quic_init = bytes([
                0xC0,  # Long header, Initial
                0x00, 0x00, 0x00, 0x01,  # Version 1
                0x08,  # DCID length
            ]) + b"\x00" * 8 + bytes([0x00])  # DCID + SCID length
            s.sendto(quic_init, (bare, 443))
            try:
                data, _ = s.recvfrom(1500)
                if len(data) > 5:
                    findings["udp_443_responsive"] = True
                    findings["quic_detected"] = True
            except socket.timeout:
                pass
            s.close()
        except Exception:
            pass

        return self.ok(host, {
            "quic": findings or "no QUIC/HTTP3 detected",
            "risk": "informational",
        })


@register
class WireguardDetect(Module):
    id, name, category = "wgdetect", "WireGuard VPN endpoint detection", "Network"
    target_kind = "domain"

    _PORTS = [51820, 51821, 51822]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        for port in self._PORTS:
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.settimeout(min(ctx.timeout, 3))
                # WireGuard handshake initiation (type 1)
                # 4 bytes type + 4 bytes sender index + random
                wg_init = struct.pack("<I", 1) + b"\x00" * 4 + b"\x00" * 32
                s.sendto(wg_init, (bare, port))
                try:
                    data, _ = s.recvfrom(256)
                    if len(data) > 0:
                        findings[f"udp:{port}"] = {
                            "responsive": True,
                            "response_size": len(data),
                            "possible_wireguard": True,
                        }
                except socket.timeout:
                    pass
                s.close()
            except Exception:
                continue

        # check for WireGuard web admin panels
        base = ensure_scheme(host).rstrip("/")
        admin_paths = ["/wg", "/wireguard", "/vpn",
                       "/api/wireguard", "/wg-easy"]
        for path in admin_paths:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and any(
                    kw in r.text.lower()[:5000]
                    for kw in ["wireguard", "wg0", "peer", "tunnel"]):
                    findings[f"web{path}"] = {
                        "status": 200,
                        "admin_panel": True,
                        "risk": "HIGH",
                    }
            except Exception:
                continue

        risk = "informational"
        if any(f.get("admin_panel") for f in findings.values()
               if isinstance(f, dict)):
            risk = "HIGH"
        elif findings:
            risk = "LOW"

        return self.ok(host, {
            "wireguard": findings or "not detected",
            "risk": risk,
        })


@register
class ServiceMeshDetect(Module):
    id, name, category = "meshdetect", "Service mesh / sidecar detection", "Network"
    target_kind = "url"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            hdrs = {k.lower(): v for k, v in r.headers.items()}

            # Istio / Envoy
            if "x-envoy-upstream-service-time" in hdrs:
                findings["envoy_proxy"] = True
                findings["upstream_time"] = hdrs["x-envoy-upstream-service-time"]
            if "server" in hdrs and "envoy" in hdrs["server"].lower():
                findings["envoy_server"] = True
            if "x-envoy-decorator-operation" in hdrs:
                findings["envoy_operation"] = hdrs["x-envoy-decorator-operation"][:80]

            # Linkerd
            if "l5d-success-class" in hdrs:
                findings["linkerd"] = True
            for h in hdrs:
                if h.startswith("l5d-"):
                    findings.setdefault("linkerd_headers", []).append(h)

            # Traefik
            if "x-traefik" in " ".join(hdrs.keys()):
                findings["traefik"] = True

            # Kong
            if "kong" in hdrs.get("server", "").lower() or \
               "x-kong-" in " ".join(hdrs.keys()):
                findings["kong"] = True
                for h in hdrs:
                    if h.startswith("x-kong-"):
                        findings.setdefault("kong_headers", {})[h] = hdrs[h][:50]

        except Exception as e:
            return self.fail(host, str(e)[:80])

        # check mesh admin endpoints
        bare = host.split(":")[0]
        admin_ports = {
            15000: "Envoy admin",
            15001: "Envoy egress",
            15004: "Istio debug",
            15014: "Istio control plane",
            4191: "Linkerd admin",
            8001: "Kong admin",
            8080: "Traefik dashboard",
        }
        for port, name in admin_ports.items():
            try:
                r = ctx.session.get(f"http://{bare}:{port}/",
                                    timeout=min(ctx.timeout, 3))
                if r.status_code == 200:
                    findings[f"admin:{port}"] = {
                        "service": name,
                        "accessible": True,
                        "risk": "HIGH",
                    }
            except Exception:
                continue

        risk = "informational"
        if any(isinstance(v, dict) and v.get("accessible")
               for v in findings.values()):
            risk = "HIGH"
        elif findings:
            risk = "LOW"

        return self.ok(host, {
            "service_mesh": findings or "not detected",
            "risk": risk,
        })


@register
class Ipv6Scan(Module):
    id, name, category = "ipv6only", "IPv6 configuration & dual-stack check", "Network"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        domain = host.split(":")[0]
        findings = {}

        # resolve AAAA records
        try:
            import socket as _socket
            ipv4 = []
            ipv6 = []
            try:
                results = _socket.getaddrinfo(domain, 443, _socket.AF_INET)
                ipv4 = list(set(r[4][0] for r in results))
            except Exception:
                pass
            try:
                results = _socket.getaddrinfo(domain, 443, _socket.AF_INET6)
                ipv6 = list(set(r[4][0] for r in results))
            except Exception:
                pass

            findings["ipv4"] = ipv4[:5] if ipv4 else "none"
            findings["ipv6"] = ipv6[:5] if ipv6 else "none"

            if ipv4 and ipv6:
                findings["dual_stack"] = True
            elif ipv6 and not ipv4:
                findings["ipv6_only"] = True
            elif ipv4 and not ipv6:
                findings["ipv4_only"] = True
        except Exception:
            pass

        # DNS lookup for AAAA
        try:
            import subprocess
            result = subprocess.run(
                ["dig", "+short", "AAAA", domain],
                capture_output=True, text=True, timeout=10)
            aaaa = [l.strip() for l in result.stdout.splitlines() if l.strip()]
            if aaaa:
                findings["dns_aaaa"] = aaaa[:5]
        except Exception:
            pass

        return self.ok(host, {
            "ipv6": findings or "lookup failed",
            "risk": "informational",
        })


@register
class DnsRebindGuard(Module):
    id, name, category = "rebindguard", "DNS rebinding protection check", "Network"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        domain = host.split(":")[0]
        findings = {}

        # check DNS TTL
        try:
            import subprocess
            result = subprocess.run(
                ["dig", "+noall", "+answer", domain],
                capture_output=True, text=True, timeout=10)
            lines = result.stdout.strip().splitlines()
            for line in lines:
                parts = line.split()
                if len(parts) >= 5:
                    ttl = int(parts[1])
                    findings["dns_ttl"] = ttl
                    if ttl < 30:
                        findings["low_ttl_warning"] = True
                        findings["rebind_risk"] = "possible"
                    break
        except Exception:
            pass

        # check Host header validation
        base = ensure_scheme(host).rstrip("/")
        try:
            r = ctx.session.get(base, timeout=ctx.timeout,
                                headers={"Host": "evil.example.com"})
            if r.status_code == 200:
                findings["host_header_accepted"] = True
                findings["host_validation"] = "MISSING"
            else:
                findings["host_validation"] = "present"
                findings["rejected_status"] = r.status_code
        except Exception:
            findings["host_validation"] = "connection_failed"

        # check for private IP in response
        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:30_000]
            private_ips = re.findall(
                r'\b(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|'
                r'172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|'
                r'192\.168\.\d{1,3}\.\d{1,3})\b', body)
            if private_ips:
                findings["private_ips_leaked"] = list(set(private_ips))[:10]
        except Exception:
            pass

        risk = "informational"
        if findings.get("host_header_accepted") and findings.get("low_ttl_warning"):
            risk = "HIGH"
        elif findings.get("host_header_accepted"):
            risk = "MEDIUM"
        elif findings.get("low_ttl_warning"):
            risk = "LOW"

        return self.ok(host, {
            "dns_rebinding": findings,
            "risk": risk,
        })
