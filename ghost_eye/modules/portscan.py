"""Pure-Python TCP connect port scanner (new). No root, no nmap - works on
stock Termux. Detection only: a full TCP connect plus an optional 1-read
banner. Use the nmap module when you need SYN/UDP/version detection."""

from __future__ import annotations

import socket
import ssl
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List, Tuple

from ..core import Context, Module, Result, clean_host, register

# curated common ports -> service label
COMMON_PORTS: Dict[int, str] = {
    21: "ftp", 22: "ssh", 23: "telnet", 25: "smtp", 53: "dns", 80: "http",
    81: "http-alt", 88: "kerberos", 110: "pop3", 111: "rpcbind", 135: "msrpc",
    139: "netbios", 143: "imap", 161: "snmp", 389: "ldap", 443: "https",
    445: "smb", 465: "smtps", 514: "syslog", 587: "submission", 631: "ipp",
    636: "ldaps", 873: "rsync", 993: "imaps", 995: "pop3s", 1080: "socks",
    1194: "openvpn", 1433: "mssql", 1521: "oracle", 1723: "pptp",
    2049: "nfs", 2082: "cpanel", 2083: "cpanel-ssl", 2375: "docker",
    2376: "docker-tls", 2379: "etcd", 2483: "oracle", 3000: "dev-http",
    3128: "proxy", 3306: "mysql", 3389: "rdp", 4444: "metasploit",
    4567: "galera", 5000: "upnp/http", 5432: "postgres", 5601: "kibana",
    5672: "amqp", 5900: "vnc", 5985: "winrm", 5986: "winrm-ssl",
    6379: "redis", 6443: "kube-api", 7001: "weblogic", 8000: "http-alt",
    8008: "http-alt", 8080: "http-proxy", 8081: "http-alt", 8086: "influxdb",
    8088: "http-alt", 8089: "splunk", 8161: "activemq", 8443: "https-alt",
    8500: "consul", 8888: "http-alt", 9000: "http-alt", 9042: "cassandra",
    9092: "kafka", 9200: "elasticsearch", 9300: "elastic-transport",
    9443: "https-alt", 9999: "http-alt", 10000: "webmin", 11211: "memcached",
    15672: "rabbitmq-mgmt", 27017: "mongodb", 27018: "mongodb",
    50070: "hadoop",
}
_TLS_PORTS = {443, 465, 636, 993, 995, 8443, 9443, 2376, 5986, 2083}


def scan_ports(host: str, ports: List[int], timeout: float = 2.0,
               threads: int = 100, grab: bool = True) -> Dict[int, dict]:
    """Connect-scan a list of ports. Returns {port: {service, banner}}."""
    open_ports: Dict[int, dict] = {}

    def probe(port: int) -> Tuple[int, dict]:
        try:
            with socket.create_connection((host, port), timeout=timeout) as s:
                info = {"service": COMMON_PORTS.get(port, "unknown"), "banner": ""}
                if grab:
                    info["banner"] = _banner(s, host, port, timeout)
                return port, info
        except Exception:
            return port, None  # type: ignore[return-value]

    with ThreadPoolExecutor(max_workers=min(threads, max(1, len(ports)))) as ex:
        for port, info in ex.map(probe, ports):
            if info is not None:
                open_ports[port] = info
    return dict(sorted(open_ports.items()))


def _banner(sock: socket.socket, host: str, port: int, timeout: float) -> str:
    try:
        sock.settimeout(min(timeout, 2.0))
        if port in _TLS_PORTS:
            c = ssl.create_default_context()
            c.check_hostname = False
            c.verify_mode = ssl.CERT_NONE
            with c.wrap_socket(sock, server_hostname=host) as ss:
                return f"TLS {ss.version()} / {ss.cipher()[0]}"
        if port in (80, 8080, 8000, 8888, 3000, 5000, 9000):
            sock.sendall(b"HEAD / HTTP/1.0\r\nHost: %b\r\n\r\n" % host.encode())
        data = sock.recv(160)
        return data.decode("latin-1", "replace").strip().split("\r\n")[0][:120]
    except Exception:
        return ""


@register
class PortScan(Module):
    id = "portscan"
    name = "TCP port scan (connect, no root)"
    category = "Network"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        try:
            ip = host if host.replace(".", "").isdigit() else socket.gethostbyname(host)
        except OSError as exc:
            return self.fail(host, f"cannot resolve: {exc}")
        ports = sorted(COMMON_PORTS)
        timeout = min(max(ctx.timeout, 1), 4)
        found = scan_ports(ip, ports, timeout=timeout,
                           threads=max(ctx.threads * 10, 60), grab=True)
        return self.ok(host, {
            "ip": ip,
            "scanned": len(ports),
            "open_count": len(found),
            "open_ports": {f"{p}/{v['service']}": (v["banner"] or "open")
                           for p, v in found.items()} or "no common ports open",
        })
