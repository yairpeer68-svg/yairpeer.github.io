"""Advanced network modules v2 (v3.5 features #41-#55). Detection only."""

from __future__ import annotations

import re
import socket
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import Context, Module, Result, clean_host, have_binary, is_ip, register, run_cmd


@register
class TcpTraceroute(Module):
    id, name, category = "tcptrace", "TCP traceroute", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        if have_binary("tcptraceroute"):
            raw = run_cmd(["tcptraceroute", "-n", "-q", "1", host], timeout=120)
        elif have_binary("traceroute"):
            raw = run_cmd(["traceroute", "-T", "-n", "-w", "2", host], timeout=120)
        elif have_binary("mtr"):
            raw = run_cmd(["mtr", "-T", "-4", "-rwc", "1", host], timeout=120)
        else:
            return self.fail(host, "install tcptraceroute, traceroute, or mtr")
        ips = re.findall(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b", raw)
        hops = []
        seen = set()
        for ip in ips:
            if ip in seen:
                continue
            seen.add(ip)
            hop = {"ip": ip}
            if ip.startswith(("10.", "192.168.", "172.")):
                hop["type"] = "private"
            else:
                try:
                    j = ctx.session.get(f"http://ip-api.com/json/{ip}?fields=org,isp,country,city",
                                        timeout=ctx.timeout).json()
                    hop.update({"org": j.get("org"), "country": j.get("country"),
                                "city": j.get("city")})
                except Exception:
                    pass
            hops.append(hop)
        return self.ok(host, {"hops": hops, "hop_count": len(hops),
                              "raw": raw.splitlines()[:30]})


@register
class FirewallInference(Module):
    id, name, category = "fwinfer", "Firewall rule inference", "Network"
    target_kind = "host"

    _PORTS = [21, 22, 23, 25, 53, 80, 110, 135, 139, 143, 443, 445,
              993, 995, 1433, 1723, 3306, 3389, 5432, 5900, 8080, 8443]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {"open": [], "closed": [], "filtered": []}

        def probe(port):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    return port, "open"
            except ConnectionRefusedError:
                return port, "closed"
            except (socket.timeout, OSError):
                return port, "filtered"

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for port, state in ex.map(probe, self._PORTS):
                results[state].append(port)
        # infer rules
        rules = []
        if results["open"]:
            rules.append(f"ALLOW ports: {sorted(results['open'])}")
        if results["filtered"] and not results["closed"]:
            rules.append("DROP policy (filtered ports timeout, not RST)")
        if results["closed"] and not results["filtered"]:
            rules.append("REJECT policy (closed ports send RST)")
        if results["filtered"] and results["closed"]:
            rules.append("mixed DROP/REJECT - selective filtering")
        return self.ok(host, {"states": {k: sorted(v) for k, v in results.items() if v},
                              "inferred_rules": rules or ["inconclusive"]})


@register
class Ipv4v6Parity(Module):
    id, name, category = "v4v6parity", "IPv4 vs IPv6 parity check", "Network"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        data = {}
        try:
            v4 = [str(r[4][0]) for r in socket.getaddrinfo(host, 443, socket.AF_INET)]
            data["ipv4"] = sorted(set(v4))
        except Exception:
            data["ipv4"] = []
        try:
            v6 = [str(r[4][0]) for r in socket.getaddrinfo(host, 443, socket.AF_INET6)]
            data["ipv6"] = sorted(set(v6))
        except Exception:
            data["ipv6"] = []
        # check if both respond on common ports
        v4_ports, v6_ports = [], []
        for port in (80, 443):
            if data["ipv4"]:
                try:
                    with socket.create_connection((data["ipv4"][0], port), timeout=ctx.timeout):
                        v4_ports.append(port)
                except Exception:
                    pass
            if data["ipv6"]:
                try:
                    s = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
                    s.settimeout(ctx.timeout)
                    s.connect((data["ipv6"][0], port))
                    v6_ports.append(port)
                    s.close()
                except Exception:
                    pass
        data["v4_open_ports"] = v4_ports
        data["v6_open_ports"] = v6_ports
        parity = set(v4_ports) == set(v6_ports) if data["ipv4"] and data["ipv6"] else None
        data["parity"] = parity
        data["note"] = "" if parity else (
            "IPv6 may have different firewall rules than IPv4"
            if data["ipv6"] else "no IPv6 records")
        return self.ok(host, data)


@register
class BgpHijack(Module):
    id, name, category = "bgphijack", "BGP hijack indicators", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = host if is_ip(host) else socket.gethostbyname(host)
        except (ValueError, OSError) as e:
            return self.fail(target, str(e))
        try:
            j = ctx.session.get(f"https://api.bgpview.io/ip/{ip}", timeout=ctx.timeout + 10).json()
            data = j.get("data", {})
            prefixes = data.get("prefixes", [])
            asns = [{
                "asn": p.get("asn", {}).get("asn"),
                "name": p.get("asn", {}).get("name", ""),
                "prefix": p.get("prefix"),
                "description": p.get("asn", {}).get("description", ""),
            } for p in prefixes]
            multi_origin = len(set(a["asn"] for a in asns if a["asn"])) > 1
            return self.ok(host, {
                "ip": ip, "prefixes": asns,
                "multi_origin": multi_origin,
                "note": "MOAS (Multiple Origin AS) detected - possible hijack indicator"
                if multi_origin else "single origin AS (normal)"
            })
        except Exception as exc:
            return self.fail(host, f"BGPView lookup failed: {exc}")


@register
class ServiceVersion(Module):
    id, name, category = "svcver", "Service version fingerprint", "Network"
    target_kind = "host"

    _PROBES = {
        21: (b"", "FTP"),
        22: (b"", "SSH"),
        25: (b"EHLO ghosteye.local\r\n", "SMTP"),
        80: (b"HEAD / HTTP/1.0\r\nHost: {host}\r\n\r\n", "HTTP"),
        110: (b"", "POP3"),
        143: (b"", "IMAP"),
        3306: (b"", "MySQL"),
        5432: (b"", "PostgreSQL"),
        6379: (b"INFO server\r\n", "Redis"),
        8080: (b"HEAD / HTTP/1.0\r\nHost: {host}\r\n\r\n", "HTTP-Alt"),
        27017: (b"", "MongoDB"),
    }

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        services = {}

        def grab(item):
            port, (probe, svc_name) = item
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    s.settimeout(ctx.timeout)
                    if probe:
                        data = probe.replace(b"{host}", host.encode())
                        s.sendall(data)
                    banner = s.recv(512).decode("utf-8", "replace").strip()
                    if banner:
                        return port, {"service": svc_name, "banner": banner[:200]}
                    return port, {"service": svc_name, "banner": "(connected, no banner)"}
            except Exception:
                return port, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for port, info in ex.map(grab, self._PROBES.items()):
                if info:
                    services[str(port)] = info
        return self.ok(host, {"services": services or "no services responded"})


@register
class SshAudit(Module):
    id, name, category = "sshaudit", "SSH algorithm audit", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            with socket.create_connection((host, 22), timeout=ctx.timeout) as s:
                s.settimeout(ctx.timeout)
                banner = s.recv(256).decode("utf-8", "replace").strip()
        except Exception as exc:
            return self.fail(host, f"SSH not reachable: {exc}")
        issues = []
        version_info = {}
        version_info["banner"] = banner
        if "SSH-1" in banner:
            issues.append("SSHv1 protocol (critically insecure)")
        m = re.search(r"SSH-\d+\.\d+-(\S+)", banner)
        if m:
            version_info["software"] = m.group(1)
        # check for known weak patterns
        if "dropbear" in banner.lower():
            version_info["type"] = "Dropbear"
        elif "OpenSSH" in banner:
            version_info["type"] = "OpenSSH"
            ver = re.search(r"OpenSSH[_\s](\d+\.\d+)", banner)
            if ver:
                version_info["version"] = ver.group(1)
                try:
                    if float(ver.group(1)) < 7.4:
                        issues.append(f"OpenSSH {ver.group(1)} is outdated (< 7.4)")
                except ValueError:
                    pass
        if have_binary("ssh-audit"):
            raw = run_cmd(["ssh-audit", "-n", host], timeout=30)
            version_info["ssh_audit_output"] = raw.splitlines()[:30]
        return self.ok(host, {**version_info,
                              "issues": issues or ["no obvious issues"],
                              "note": "use ssh-audit tool for comprehensive algorithm analysis"})


@register
class DohDotDetect(Module):
    id, name, category = "dohdot", "DoH / DoT resolver detection", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        # DNS-over-HTTPS
        for path in ("/dns-query", "/resolve"):
            try:
                r = ctx.session.get(f"https://{host}{path}",
                                    params={"name": "example.com", "type": "A"},
                                    headers={"Accept": "application/dns-json"},
                                    timeout=ctx.timeout)
                if r.status_code == 200 and ("Answer" in r.text or "Status" in r.text):
                    results[f"DoH:{path}"] = "active DNS-over-HTTPS resolver"
            except Exception:
                continue
        # DNS-over-TLS (port 853)
        import ssl
        try:
            c = ssl.create_default_context()
            c.check_hostname = False
            c.verify_mode = ssl.CERT_NONE
            with socket.create_connection((host, 853), timeout=ctx.timeout) as s:
                with c.wrap_socket(s, server_hostname=host):
                    results["DoT:853"] = "TLS on port 853 (DNS-over-TLS)"
        except Exception:
            pass
        return self.ok(host, {"resolvers": results or "no DoH/DoT detected"})


@register
class MqttDetect(Module):
    id, name, category = "mqtt", "MQTT broker detection", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        for port in (1883, 8883):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    s.settimeout(ctx.timeout)
                    # MQTT CONNECT packet (minimal)
                    connect = (b"\x10\x0d\x00\x04MQTT\x04\x02\x00\x3c"
                               b"\x00\x01X")
                    s.sendall(connect)
                    resp = s.recv(4)
                    if resp and resp[0] == 0x20:
                        rc = resp[3] if len(resp) > 3 else -1
                        if rc == 0:
                            results[str(port)] = "OPEN (no auth required)"
                        elif rc == 5:
                            results[str(port)] = "auth required (good)"
                        else:
                            results[str(port)] = f"MQTT present (return code {rc})"
                    elif resp:
                        results[str(port)] = "port open (non-standard response)"
            except Exception:
                continue
        return self.ok(host, {"mqtt": results or "no MQTT broker found",
                              "risk": "IoT message broker exposed" if any(
                                  "no auth" in v for v in results.values()) else "ok"})


@register
class DockerApiExposure(Module):
    id, name, category = "dockerapi", "Docker API exposure (extended)", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}
        # Docker Engine API
        for scheme, port in (("http", 2375), ("https", 2376)):
            try:
                r = ctx.session.get(f"{scheme}://{host}:{port}/version",
                                    timeout=ctx.timeout, verify=False)
                if r.status_code == 200 and "ApiVersion" in r.text:
                    info = r.json()
                    findings[f"engine:{port}"] = {
                        "status": "OPEN (unauthenticated)",
                        "api_version": info.get("ApiVersion"),
                        "os": info.get("Os"),
                        "arch": info.get("Arch"),
                        "risk": "CRITICAL - full container control"
                    }
            except Exception:
                pass
        # Docker Swarm
        try:
            r = ctx.session.get(f"http://{host}:2375/swarm",
                                timeout=ctx.timeout)
            if r.status_code == 200 and "ID" in r.text:
                findings["swarm"] = "Swarm cluster exposed"
        except Exception:
            pass
        return self.ok(host, {"docker": findings or "no exposed Docker API"})


@register
class K8sExposure(Module):
    id, name, category = "k8sadv", "Kubernetes API exposure (extended)", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}
        # API server
        for port in (6443, 8443, 443):
            for path in ("/version", "/api", "/api/v1/namespaces", "/healthz", "/openapi/v2"):
                try:
                    r = ctx.session.get(f"https://{host}:{port}{path}",
                                        timeout=ctx.timeout, verify=False)
                    if r.status_code == 200 and (
                            "gitVersion" in r.text or "apiVersion" in r.text
                            or "kubernetes" in r.text.lower()):
                        risk = "CRITICAL" if "namespaces" in r.text else "HIGH"
                        findings[f"api:{port}{path}"] = {
                            "status": f"HTTP {r.status_code}",
                            "risk": risk}
                        break
                except Exception:
                    continue
        # Kubelet
        for port in (10250, 10255):
            try:
                r = ctx.session.get(f"https://{host}:{port}/pods",
                                    timeout=ctx.timeout, verify=False)
                if r.status_code == 200 and "metadata" in r.text:
                    findings[f"kubelet:{port}"] = "OPEN (pod listing exposed)"
            except Exception:
                pass
        # etcd
        try:
            r = ctx.session.get(f"http://{host}:2379/version",
                                timeout=ctx.timeout)
            if r.status_code == 200 and "etcdserver" in r.text:
                findings["etcd:2379"] = "OPEN (cluster data store exposed)"
        except Exception:
            pass
        return self.ok(host, {"kubernetes": findings or "no exposed K8s endpoints"})


@register
class NtpCheck(Module):
    id, name, category = "ntp", "NTP amplification check", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        # NTP basic query (mode 3, client)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(ctx.timeout)
            ntp_data = b'\x1b' + 47 * b'\0'
            s.sendto(ntp_data, (host, 123))
            resp, _ = s.recvfrom(1024)
            s.close()
            if len(resp) >= 48:
                results["ntp_response"] = True
                results["response_size"] = len(resp)
        except Exception:
            results["ntp_response"] = False
        # monlist check (amplification vector)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(ctx.timeout)
            # REQ_MON_GETLIST_1 packet
            monlist = b'\x17\x00\x03\x2a' + b'\0' * 4
            s.sendto(monlist, (host, 123))
            resp, _ = s.recvfrom(4096)
            s.close()
            if len(resp) > 100:
                results["monlist"] = f"ENABLED (amplification risk, {len(resp)} byte response)"
            else:
                results["monlist"] = "disabled (good)"
        except Exception:
            results["monlist"] = "no response (likely disabled)"
        return self.ok(host, results)


@register
class LdapExposure(Module):
    id, name, category = "ldap", "LDAP exposure detection", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        for port in (389, 636):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    if port == 636:
                        import ssl
                        c = ssl.create_default_context()
                        c.check_hostname = False
                        c.verify_mode = ssl.CERT_NONE
                        s = c.wrap_socket(s, server_hostname=host)
                    # LDAP search request for rootDSE (anonymous bind)
                    search_req = (
                        b"\x30\x25"  # SEQUENCE
                        b"\x02\x01\x01"  # messageID
                        b"\x63\x20"  # SearchRequest
                        b"\x04\x00"  # baseObject (empty = rootDSE)
                        b"\x0a\x01\x00"  # scope: base
                        b"\x0a\x01\x00"  # derefAliases
                        b"\x02\x01\x00"  # sizeLimit
                        b"\x02\x01\x00"  # timeLimit
                        b"\x01\x01\x00"  # typesOnly: false
                        b"\x87\x0b" b"objectclass"  # filter: present
                        b"\x30\x00"  # attributes
                    )
                    s.sendall(search_req)
                    resp = s.recv(1024)
                    if resp and len(resp) > 10:
                        results[str(port)] = "OPEN (anonymous bind accepted)"
                    else:
                        results[str(port)] = "port open (auth required)"
            except Exception:
                continue
        return self.ok(host, {"ldap": results or "no LDAP endpoints found"})


@register
class SmbEnum(Module):
    id, name, category = "smb", "SMB / NetBIOS enumeration", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        # SMB port 445
        try:
            with socket.create_connection((host, 445), timeout=ctx.timeout) as s:
                results["smb:445"] = "open"
                s.settimeout(ctx.timeout)
                # SMB negotiation probe
                neg = (b"\x00\x00\x00\x45"
                       b"\xffSMB"
                       b"\x72"
                       + b"\x00" * 23
                       + b"\x00\x22"
                       b"\x02NT LM 0.12\x00"
                       b"\x02SMB 2.002\x00"
                       b"\x02SMB 2.???\x00")
                s.sendall(neg)
                resp = s.recv(1024)
                if resp and b"\xffSMB" in resp:
                    results["smb_version"] = "SMBv1 supported (insecure)"
                elif resp and b"\xfeSMB" in resp:
                    results["smb_version"] = "SMBv2/3"
        except Exception:
            pass
        # NetBIOS port 139
        try:
            with socket.create_connection((host, 139), timeout=ctx.timeout) as s:
                results["netbios:139"] = "open"
        except Exception:
            pass
        # NetBIOS name service (UDP 137)
        if have_binary("nmblookup"):
            raw = run_cmd(["nmblookup", "-A", host], timeout=10)
            if "Looking up" in raw:
                names = re.findall(r"\s+(\S+)\s+<(\w+)>", raw)
                results["netbios_names"] = [{"name": n, "type": t} for n, t in names[:10]]
        return self.ok(host, {"smb": results or "no SMB/NetBIOS found"})


@register
class FtpAnon(Module):
    id, name, category = "ftpanon", "FTP anonymous login check", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        try:
            with socket.create_connection((host, 21), timeout=ctx.timeout) as s:
                s.settimeout(ctx.timeout)
                banner = s.recv(512).decode("utf-8", "replace").strip()
                s.sendall(b"USER anonymous\r\n")
                user_resp = s.recv(256).decode("utf-8", "replace").strip()
                if user_resp.startswith("331") or user_resp.startswith("230"):
                    s.sendall(b"PASS anonymous@ghosteye.local\r\n")
                    pass_resp = s.recv(256).decode("utf-8", "replace").strip()
                    anon = pass_resp.startswith("230")
                    return self.ok(host, {
                        "banner": banner[:200], "anonymous_login": anon,
                        "response": pass_resp[:120],
                        "risk": "HIGH - anonymous FTP access" if anon else "ok"})
                else:
                    return self.ok(host, {"banner": banner[:200],
                                          "anonymous_login": False,
                                          "note": "anonymous user rejected"})
        except Exception as exc:
            return self.fail(host, f"FTP not reachable: {exc}")


@register
class GrpcReflection(Module):
    id, name, category = "grpc", "gRPC reflection detection", "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        results = {}
        for port in (50051, 443, 8443, 9090):
            try:
                with socket.create_connection((host, port), timeout=ctx.timeout) as s:
                    # send HTTP/2 preface
                    s.sendall(b"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
                    resp = s.recv(256)
                    if resp:
                        if b"\x00\x00" in resp[:3]:
                            results[str(port)] = "HTTP/2 detected (possible gRPC)"
                        else:
                            results[str(port)] = "port open (non-HTTP/2)"
            except Exception:
                continue
        # also try via HTTP
        for port in (50051, 8080, 9090):
            try:
                r = ctx.session.get(f"http://{host}:{port}/",
                                    timeout=ctx.timeout, headers={"Content-Type": "application/grpc"})
                if "grpc" in r.headers.get("Content-Type", "").lower() or r.status_code == 415:
                    results[f"http:{port}"] = "gRPC service detected"
            except Exception:
                continue
        return self.ok(host, {"grpc": results or "no gRPC endpoints found"})
