"""Advanced network/service modules (new features #38-#47). Detection only."""

from __future__ import annotations

import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, have_binary, register, run_cmd


@register
class Ipv6Enum(Module):
    id, name, category = "ipv6", "IPv6 / AAAA discovery", "Network"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.resolver
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        for name in (host, f"www.{host}", f"mail.{host}", f"ipv6.{host}"):
            try:
                rr = dns.resolver.resolve(name, "AAAA")
                out[name] = [str(x) for x in rr]
            except Exception:
                continue
        return self.ok(host, {"aaaa": out or "no IPv6 records",
                              "note": "IPv6 hosts are often less firewalled than IPv4"})


@register
class ReverseNetblock(Module):
    id, name, category = "revnet", "Reverse-DNS over netblock", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        import ipaddress
        try:
            net = ipaddress.ip_network(target.strip(), strict=False)
        except ValueError:
            return self.fail(target, "provide a CIDR, e.g. 192.0.2.0/28")
        if net.num_addresses > 512:
            return self.fail(target, "range too large (>512); narrow the CIDR")
        names = {}

        def ptr(ip):
            try:
                return str(ip), socket.gethostbyaddr(str(ip))[0]
            except OSError:
                return str(ip), None
        with ThreadPoolExecutor(max_workers=min(ctx.threads * 4, 80)) as ex:
            for ip, name in ex.map(ptr, net.hosts()):
                if name:
                    names[ip] = name
        return self.ok(target, {"resolved": names or "no PTR records in range"})


@register
class AsnAssets(Module):
    id, name, category = "asnassets", "Assets across ASN", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = host if host.replace(".", "").isdigit() else socket.gethostbyname(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            j = ctx.session.get(f"https://api.bgpview.io/ip/{ip}", timeout=ctx.timeout).json()
            data = j.get("data", {})
            prefixes = data.get("prefixes", [])
            asns = sorted({p.get("asn", {}).get("asn") for p in prefixes if p.get("asn")})
            return self.ok(host, {
                "ip": ip,
                "prefixes": [p.get("prefix") for p in prefixes][:20],
                "asns": asns,
                "note": "enumerate hosts within these prefixes you are authorised for"})
        except Exception as exc:
            return self.fail(host, f"BGPView lookup failed: {exc}")


@register
class OriginIp(Module):
    id, name, category = "origin", "Origin IP behind CDN/WAF", "Network"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        candidates = {}
        # 1) common non-fronted hostnames
        for n in ("origin", "direct", "dev", "staging", "cpanel", "mail",
                  "ftp", "webmail", "server", f"origin.{host}"):
            name = n if "." in n else f"{n}.{host}"
            try:
                candidates[name] = socket.gethostbyname(name)
            except OSError:
                continue
        # 2) MX hosts (mail often runs on the real origin)
        try:
            import dns.resolver
            for m in dns.resolver.resolve(host, "MX"):
                mx = str(m.exchange).rstrip(".")
                try:
                    candidates[f"MX:{mx}"] = socket.gethostbyname(mx)
                except OSError:
                    pass
        except Exception:
            pass
        # flag IPs that are NOT in obvious CDN ranges (best-effort marker)
        return self.ok(host, {"candidate_origins": candidates or "none discovered",
                              "note": "compare these IPs to the CDN-fronted IP; a "
                                      "non-CDN IP may be the true origin"})


@register
class ExposedDb(Module):
    id, name, category = "exposeddb", "Exposed databases (no auth)", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        # Redis - PING (detection, no data access)
        try:
            with socket.create_connection((host, 6379), timeout=ctx.timeout) as s:
                s.sendall(b"PING\r\n")
                if b"PONG" in s.recv(64):
                    out["redis:6379"] = "OPEN (no auth - responds to PING)"
        except Exception:
            pass
        # Memcached - stats
        try:
            with socket.create_connection((host, 11211), timeout=ctx.timeout) as s:
                s.sendall(b"stats\r\n")
                if b"STAT" in s.recv(128):
                    out["memcached:11211"] = "OPEN (no auth - responds to stats)"
        except Exception:
            pass
        # Elasticsearch - HTTP banner
        try:
            r = ctx.session.get(f"http://{host}:9200/", timeout=ctx.timeout)
            if r.status_code == 200 and "cluster_name" in r.text:
                out["elasticsearch:9200"] = "OPEN (cluster info exposed)"
        except Exception:
            pass
        # MongoDB / Postgres / MySQL - port-open only (no protocol probe)
        for svc, port in (("mongodb", 27017), ("postgres", 5432), ("mysql", 3306)):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout):
                    out[f"{svc}:{port}"] = "port open (verify auth manually)"
            except Exception:
                continue
        return self.ok(host, {"databases": out or "none reachable"})


@register
class RdpVnc(Module):
    id, name, category = "rdpvnc", "Exposed RDP / VNC", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        try:
            with socket.create_connection((host, 3389), timeout=ctx.timeout):
                out["rdp:3389"] = "open"
        except Exception:
            pass
        for port in (5900, 5901):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    banner = s.recv(16).decode("latin-1", "replace")
                    out[f"vnc:{port}"] = f"open (banner: {banner.strip()})" \
                        if "RFB" in banner else "open"
            except Exception:
                continue
        return self.ok(host, {"remote_access": out or "none reachable"})


@register
class SnmpCheck(Module):
    id, name, category = "snmp", "Exposed SNMP (default community)", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        # UDP/161 reachability + optional pysnmp default-community read
        try:
            from pysnmp.hlapi import (CommunityData, ContextData, ObjectIdentity,
                                      ObjectType, SnmpEngine, UdpTransportTarget,
                                      getCmd)
            it = getCmd(SnmpEngine(), CommunityData("public", mpModel=0),
                        UdpTransportTarget((host, 161), timeout=ctx.timeout, retries=0),
                        ContextData(),
                        ObjectType(ObjectIdentity("1.3.6.1.2.1.1.1.0")))
            errInd, errStat, _, varBinds = next(it)
            if not errInd and not errStat:
                return self.ok(host, {"snmp": "OPEN with community 'public'",
                                      "sysDescr": str(varBinds[0][1])[:120]})
            return self.ok(host, {"snmp": "no response to 'public' (good or filtered)"})
        except ImportError:
            return self.ok(host, {"note": "install pysnmp for a default-community check"})
        except Exception:
            return self.ok(host, {"snmp": "no SNMP response"})


@register
class OpenResolver(Module):
    id, name, category = "openresolver", "Open DNS resolver", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            import dns.message
            import dns.query
        except ImportError:
            return self.fail(target, "requires dnspython")
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            q = dns.message.make_query("example.com", "A")
            resp = dns.query.udp(q, host, timeout=ctx.timeout)
            recursive = bool(resp.flags & dns.flags.RA)
            return self.ok(host, {"responds": True, "recursion_available": recursive,
                                  "note": "OPEN RESOLVER - usable for DNS amplification"
                                  if recursive else "recursion not offered (good)"})
        except Exception:
            return self.ok(host, {"responds": False, "note": "no DNS on udp/53"})


@register
class VpnIke(Module):
    id, name, category = "vpnike", "VPN / IKE endpoints", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        out = {}
        for svc, port in (("OpenVPN", 1194), ("PPTP", 1723), ("SSTP/HTTPS-VPN", 443)):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout):
                    out[f"{svc}:{port}"] = "tcp open"
            except Exception:
                continue
        # IKE is UDP/500 - send a tiny probe
        try:
            sk = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sk.settimeout(ctx.timeout)
            sk.sendto(b"\x00" * 20, (host, 500))
            try:
                sk.recvfrom(64)
                out["IKE:500/udp"] = "responded"
            except socket.timeout:
                pass
            sk.close()
        except Exception:
            pass
        return self.ok(host, {"vpn_endpoints": out or "none reachable"})


@register
class TlsNonStandard(Module):
    id, name, category = "tlsports", "TLS on non-standard ports", "Network"
    target_kind = "host"

    _PORTS = [443, 8443, 9443, 4443, 10443, 8080, 8888, 7443, 6443, 2376, 5986]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        import ssl
        found = {}

        def probe(port):
            try:
                c = ssl.create_default_context()
                c.check_hostname = False
                c.verify_mode = ssl.CERT_NONE
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    with c.wrap_socket(s, server_hostname=host) as ss:
                        cert = ss.getpeercert()
                        cn = dict(x[0] for x in cert.get("subject", ())).get("commonName", "?") \
                            if cert else "?"
                        return port, f"TLS up (CN={cn}, {ss.version()})"
            except Exception:
                return port, None
        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for port, res in ex.map(probe, self._PORTS):
                if res:
                    found[port] = res
        return self.ok(host, {"tls_ports": found or "no TLS on tested ports"})
