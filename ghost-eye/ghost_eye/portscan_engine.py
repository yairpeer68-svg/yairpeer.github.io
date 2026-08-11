"""TCP port scanning that reports what it actually established.

The scanner this replaces did the easy 80% and quietly got the important part
wrong. Three things separate a port scan you can act on from one you cannot:

**closed is not filtered.** A connection refused (RST) proves the host is
reachable and nothing is listening. A timeout proves nothing at all — a
firewall dropped it, or the packet was lost, or the host is rate-limiting you.
Collapsing both into "not open" throws away the single most useful signal a
scan produces: *there is a firewall in front of this host*, and which ports it
guards.

**one dropped packet is not a firewall.** A scanner that concludes "filtered"
from a single timeout invents firewalls on any lossy path, and its output
changes run to run. Every non-answer here is retried before it is believed,
and the report says how many probes each verdict rests on.

**scanning a CDN edge is not scanning the target.** If the name resolves into
Cloudflare, a "port scan of example.com" is a port scan of Cloudflare — the
results describe someone else's infrastructure entirely. That gets said loudly
rather than presented as the target's attack surface.

Connect-scan only: a full TCP handshake, immediately closed. No SYN/stealth
scanning (needs root and is a different legal posture), no UDP, no exploitation
— a port is reported open, never opened further.

FOR AUTHORISED USE ONLY.
"""

from __future__ import annotations

import re
import socket
import ssl
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Any, Dict, List, Optional, Sequence, Tuple

OPEN, CLOSED, FILTERED = "open", "closed", "filtered"

# Service labels. Not a claim about what is really listening — that is what the
# banner is for — just the IANA/common assignment for the number.
SERVICES: Dict[int, str] = {
    7: "echo", 20: "ftp-data", 21: "ftp", 22: "ssh", 23: "telnet", 25: "smtp",
    43: "whois", 53: "dns", 67: "dhcp", 69: "tftp", 79: "finger", 80: "http",
    81: "http-alt", 88: "kerberos", 110: "pop3", 111: "rpcbind", 119: "nntp",
    123: "ntp", 135: "msrpc", 137: "netbios-ns", 139: "netbios-ssn",
    143: "imap", 161: "snmp", 179: "bgp", 194: "irc", 389: "ldap",
    427: "svrloc", 443: "https", 445: "smb", 465: "smtps", 500: "isakmp",
    502: "modbus", 512: "exec", 513: "login", 514: "syslog", 515: "printer",
    520: "rip", 523: "db2", 548: "afp", 554: "rtsp", 587: "submission",
    623: "ipmi", 631: "ipp", 636: "ldaps", 646: "ldp", 873: "rsync",
    902: "vmware", 989: "ftps-data", 990: "ftps", 993: "imaps", 995: "pop3s",
    1080: "socks", 1194: "openvpn", 1099: "java-rmi", 1433: "mssql",
    1434: "mssql-mon", 1521: "oracle", 1583: "pervasive", 1723: "pptp",
    1883: "mqtt", 1900: "upnp", 2000: "cisco-sccp", 2049: "nfs",
    2082: "cpanel", 2083: "cpanel-ssl", 2086: "whm", 2087: "whm-ssl",
    2095: "webmail", 2096: "webmail-ssl", 2181: "zookeeper", 2222: "ssh-alt",
    2375: "docker", 2376: "docker-tls", 2379: "etcd", 2380: "etcd-peer",
    2483: "oracle", 2484: "oracle-ssl", 3000: "dev-http", 3128: "squid",
    3268: "globalcat", 3269: "globalcat-ssl", 3306: "mysql", 3389: "rdp",
    3690: "svn", 4000: "dev-http", 4369: "epmd", 4444: "krb524",
    4505: "salt", 4506: "salt", 4567: "galera", 4786: "cisco-smi",
    4840: "opc-ua", 4848: "glassfish", 5000: "upnp/http", 5001: "dev-http",
    5060: "sip", 5061: "sip-tls", 5222: "xmpp", 5269: "xmpp-server",
    5353: "mdns", 5432: "postgres", 5555: "adb", 5601: "kibana",
    5632: "pcanywhere", 5666: "nrpe", 5672: "amqp", 5683: "coap",
    5800: "vnc-http", 5900: "vnc", 5901: "vnc-1", 5984: "couchdb",
    5985: "winrm", 5986: "winrm-ssl", 6000: "x11", 6379: "redis",
    6443: "kube-api", 6543: "pgbouncer", 6666: "irc", 6667: "irc",
    7000: "cassandra", 7001: "weblogic", 7002: "weblogic-ssl", 7077: "spark",
    7199: "cassandra-jmx", 7474: "neo4j", 7547: "cwmp", 7687: "bolt",
    8000: "http-alt", 8005: "tomcat-shutdown", 8008: "http-alt",
    8009: "ajp13", 8010: "http-alt", 8020: "hadoop", 8042: "yarn",
    8069: "odoo", 8080: "http-proxy", 8081: "http-alt", 8086: "influxdb",
    8087: "riak", 8088: "hadoop-ui", 8089: "splunk", 8090: "confluence",
    8091: "couchbase", 8140: "puppet", 8161: "activemq", 8181: "http-alt",
    8200: "vault", 8222: "vmware", 8291: "mikrotik", 8333: "bitcoin",
    8443: "https-alt", 8500: "consul", 8529: "arangodb", 8686: "jmx",
    8834: "nessus", 8888: "http-alt", 8983: "solr", 9000: "http-alt",
    9001: "tor-orport", 9042: "cassandra", 9043: "websphere",
    9060: "websphere", 9090: "prometheus", 9091: "transmission",
    9092: "kafka", 9100: "jetdirect", 9200: "elasticsearch",
    9300: "elastic-transport", 9418: "git", 9443: "https-alt",
    9600: "logstash", 9999: "http-alt", 10000: "webmin", 10250: "kubelet",
    10255: "kubelet-ro", 11211: "memcached", 15672: "rabbitmq-mgmt",
    16992: "amt", 16993: "amt-tls", 27017: "mongodb", 27018: "mongodb",
    27019: "mongodb-cfg", 28017: "mongodb-http", 32400: "plex",
    49152: "upnp-alt", 50000: "sap", 50070: "hadoop-nn", 61616: "activemq",
}

# Ghost Eye's own frequency ordering — the ports worth trying first when you
# only have budget for a few. Deliberately NOT presented as a copy of
# nmap-services; it is a curated list, and `--ports` takes anything else.
TOP_PORTS: List[int] = [
    80, 443, 22, 21, 25, 3389, 110, 445, 139, 143, 53, 135, 3306, 8080, 1723,
    111, 995, 993, 5900, 587, 8443, 8888, 199, 1720, 465, 548, 113, 81, 6001,
    10000, 514, 5060, 179, 1026, 2000, 8443, 8000, 32768, 554, 26, 1433, 49152,
    2001, 515, 8008, 49154, 1027, 5666, 646, 5000, 5631, 631, 49153, 8081,
    2049, 88, 79, 5800, 106, 2121, 1110, 49155, 6000, 513, 990, 5357, 427,
    49156, 543, 544, 5101, 144, 7, 389, 8009, 3128, 444, 9999, 5009, 7070,
    5190, 3000, 5432, 1900, 3986, 13, 1029, 9, 5051, 6646, 49157, 1028, 873,
    1755, 2717, 4899, 9100, 119, 37, 1000, 3001, 5001, 82, 10010, 1030, 9090,
    2107, 1024, 2103, 6004, 1801, 5050, 19, 8031, 1041, 255, 1049, 1048, 2967,
    1053, 3703, 1056, 1065, 1064, 1054, 17, 808, 3689, 1031, 1044, 1071, 5901,
    100, 9102, 8010, 2869, 1039, 5120, 4001, 9000, 2105, 636, 1038, 2601,
    7000, 1, 1066, 1069, 625, 311, 280, 254, 4000, 1761, 5003, 2002, 2005,
    1998, 1032, 1050, 6112, 3690, 1521, 2161, 6002, 1080, 2401, 4045, 902,
    7937, 787, 1058, 2383, 32771, 1033, 1040, 1059, 50000, 5555, 10001, 1494,
    2301, 593, 3268, 7938, 1234, 1022, 1074, 8002, 1036, 1035, 9001, 1037,
    464, 497, 1935, 6666, 2003, 6543, 24, 1352, 3269, 1111, 407, 500, 20,
    2006, 3260, 15000, 1218, 1034, 4045, 3517, 4444, 264, 33, 42510, 987,
    475, 6100, 13782, 1300, 9535, 6379, 9200, 27017, 5984, 11211, 2375, 2379,
    6443, 8086, 9092, 5672, 8500, 8200, 2181, 5601, 9300, 15672, 10250, 8161,
]

# TLS is spoken first on these, so a plaintext banner grab gets nothing back.
TLS_PORTS = {443, 465, 563, 636, 989, 990, 993, 995, 1443, 2083, 2087, 2096,
             2376, 2484, 3269, 5061, 5986, 6443, 8443, 9443, 10250, 16993}

# Ports where the server waits for the client to speak before saying anything.
HTTP_PORTS = {80, 81, 88, 591, 2082, 2086, 2095, 3000, 4000, 4848, 5000, 5001,
              7001, 8000, 8008, 8010, 8069, 8080, 8081, 8088, 8090, 8161,
              8181, 8888, 8983, 9000, 9090, 9200, 9600, 10000, 15672, 28017}

_MAX_PORT = 65535


class PortSpecError(ValueError):
    """A port specification that cannot be honoured."""


def parse_ports(spec: str, top_n_default: int = 100) -> List[int]:
    """Turn a port specification into a concrete, de-duplicated list.

        "80,443"          explicit
        "1-1024"          inclusive range
        "top100"          the first N of TOP_PORTS
        "all"             1-65535
        "top50,8080-8090" combined

    Refuses silently-empty or reversed ranges rather than scanning nothing and
    reporting "no open ports", which reads identically to a clean host.
    """
    text = (spec or "").strip().lower()
    if not text:
        return _top(top_n_default)
    out: List[int] = []
    for part in re.split(r"[,\s]+", text):
        if not part:
            continue
        if part == "all":
            out.extend(range(1, _MAX_PORT + 1))
        elif part.startswith("top"):
            n = part[3:] or str(top_n_default)
            if not n.isdigit() or int(n) < 1:
                raise PortSpecError(f"bad top-N spec: {part!r}")
            out.extend(_top(int(n)))
        elif "-" in part:
            lo, _, hi = part.partition("-")
            if not (lo.isdigit() and hi.isdigit()):
                raise PortSpecError(f"bad range: {part!r}")
            lo_i, hi_i = int(lo), int(hi)
            if lo_i > hi_i:
                raise PortSpecError(f"reversed range: {part!r}")
            if not (1 <= lo_i <= _MAX_PORT and 1 <= hi_i <= _MAX_PORT):
                raise PortSpecError(f"ports must be 1-65535: {part!r}")
            out.extend(range(lo_i, hi_i + 1))
        elif part.isdigit():
            port = int(part)
            if not 1 <= port <= _MAX_PORT:
                raise PortSpecError(f"port out of range: {part!r}")
            out.append(port)
        else:
            raise PortSpecError(f"unrecognised port spec: {part!r}")
    if not out:
        raise PortSpecError(f"port spec matched no ports: {spec!r}")
    return sorted(dict.fromkeys(out))


def _top(n: int) -> List[int]:
    seen = list(dict.fromkeys(TOP_PORTS))
    if n <= len(seen):
        return sorted(seen[:n])
    # asked for more than the curated list holds: extend with low ports, which
    # is where the remaining density actually is
    extra = [p for p in range(1, 1025) if p not in seen]
    return sorted(seen + extra[:n - len(seen)])


class _Pacer:
    """A shared minimum gap between connection attempts.

    Blasting a host is both impolite and inaccurate: rate-limiting turns real
    open ports into timeouts, which this scanner would then have to report as
    filtered. Slower is more correct, not just gentler.
    """

    def __init__(self, per_second: float = 0.0) -> None:
        self.gap = 1.0 / per_second if per_second and per_second > 0 else 0.0
        self._lock = threading.Lock()
        self._next = 0.0

    def wait(self) -> None:
        if not self.gap:
            return
        with self._lock:
            now = time.monotonic()
            if self._next > now:
                time.sleep(self._next - now)
                now = time.monotonic()
            self._next = now + self.gap


def probe_port(host: str, port: int, timeout: float = 2.0, retries: int = 1,
               family: int = 0, pacer: Optional[_Pacer] = None,
               grab: bool = True) -> Dict[str, Any]:
    """Probe one TCP port and classify the result.

    A refusal is conclusive on the first try. A *non-answer* is not: it is
    retried `retries` more times before being called filtered, because a single
    dropped packet is a lost packet, not a firewall.
    """
    attempts = 0
    last_error = ""
    for _ in range(max(1, retries + 1)):
        attempts += 1
        if pacer:
            pacer.wait()
        try:
            sock = socket.create_connection((host, port), timeout=timeout)
        except ConnectionRefusedError:
            # conclusive: the host is up and nothing is listening here
            return {"port": port, "state": CLOSED, "attempts": attempts,
                    "service": SERVICES.get(port, ""), "banner": "",
                    "evidence": "connection refused (RST)"}
        except socket.timeout:
            last_error = "no response"
            continue
        except OSError as exc:
            text = str(exc).lower()
            if "refused" in text:
                return {"port": port, "state": CLOSED, "attempts": attempts,
                        "service": SERVICES.get(port, ""), "banner": "",
                        "evidence": "connection refused (RST)"}
            if any(w in text for w in ("unreachable", "no route", "reset")):
                return {"port": port, "state": FILTERED, "attempts": attempts,
                        "service": SERVICES.get(port, ""), "banner": "",
                        "evidence": str(exc)[:80]}
            last_error = str(exc)[:80]
            continue
        with sock:
            banner = _grab(sock, host, port, timeout) if grab else ""
        return {"port": port, "state": OPEN, "attempts": attempts,
                "service": SERVICES.get(port, ""), "banner": banner,
                "evidence": "TCP handshake completed"}
    return {"port": port, "state": FILTERED, "attempts": attempts,
            "service": SERVICES.get(port, ""), "banner": "",
            "evidence": f"{last_error or 'no response'} after {attempts} attempt(s)"}


def _grab(sock: socket.socket, host: str, port: int, timeout: float) -> str:
    """One read of whatever the service volunteers. Never sends a payload
    beyond the minimal protocol nudge needed to make it speak."""
    try:
        sock.settimeout(min(timeout, 3.0))
        if port in TLS_PORTS:
            ctx = ssl.create_default_context()
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
            with ctx.wrap_socket(sock, server_hostname=host) as tls:
                cipher = tls.cipher()
                return f"TLS {tls.version()} / {cipher[0] if cipher else '?'}"
        if port in HTTP_PORTS:
            sock.sendall(b"HEAD / HTTP/1.0\r\nHost: " + host.encode("idna", "ignore")
                         + b"\r\n\r\n")
        data = sock.recv(256)
        if not data:
            return ""
        text = data.decode("latin-1", "replace").strip()
        return re.sub(r"\s+", " ", text.split("\r\n")[0])[:140]
    except Exception:  # noqa: BLE001 - a silent service is not an error
        return ""


def resolve(host: str, prefer_v6: bool = False) -> List[Tuple[int, str]]:
    """Every address the name resolves to, as (family, address).

    Both families, because a host that is hardened on IPv4 and wide open on
    IPv6 is a real and frequently-missed situation.
    """
    out: List[Tuple[int, str]] = []
    order = ((socket.AF_INET6, socket.AF_INET) if prefer_v6
             else (socket.AF_INET, socket.AF_INET6))
    for family in order:
        try:
            for info in socket.getaddrinfo(host, None, family, socket.SOCK_STREAM):
                addr = str(info[4][0]).split("%")[0]
                if (family, addr) not in out:
                    out.append((family, addr))
        except Exception:  # noqa: BLE001
            continue
    return out


def scan(host: str, ports: Sequence[int], timeout: float = 2.0,
         workers: int = 100, retries: int = 1, rate: float = 0.0,
         grab: bool = True, on_result=None) -> List[Dict[str, Any]]:
    """Scan a list of ports on one address. Returns one record per port."""
    pacer = _Pacer(rate)
    results: List[Dict[str, Any]] = []
    limit = max(1, min(workers, len(ports) or 1))
    with ThreadPoolExecutor(max_workers=limit) as ex:
        futs = [ex.submit(probe_port, host, p, timeout, retries, 0, pacer, grab)
                for p in ports]
        for fut in as_completed(futs):
            try:
                rec = fut.result()
            except Exception as exc:  # noqa: BLE001
                continue_rec = {"port": 0, "state": FILTERED, "attempts": 0,
                                "service": "", "banner": "",
                                "evidence": f"probe crashed: {str(exc)[:60]}"}
                rec = continue_rec
            results.append(rec)
            if on_result:
                try:
                    on_result(rec)
                except Exception:  # noqa: BLE001
                    pass
    return sorted(results, key=lambda r: r["port"])


def summarise(host: str, address: str, records: List[Dict[str, Any]],
              scanned: int, cdn: Optional[Dict[str, Any]] = None,
              elapsed: float = 0.0) -> Dict[str, Any]:
    """Turn per-port records into a report that states what was established."""
    by_state: Dict[str, List[Dict[str, Any]]] = {OPEN: [], CLOSED: [], FILTERED: []}
    for rec in records:
        by_state.setdefault(rec["state"], []).append(rec)
    open_ports = by_state[OPEN]
    closed_n, filtered_n = len(by_state[CLOSED]), len(by_state[FILTERED])

    # A host that refuses everything is reachable and firewalled *closed*; one
    # that answers nothing at all is behind a drop-everything filter — or was
    # never reachable. Those are different findings.
    if closed_n and not filtered_n:
        posture = ("host reachable, unlisted ports actively refused — no "
                   "drop-style firewall on the scanned range")
    elif filtered_n and not closed_n and not open_ports:
        posture = ("nothing answered at all — a drop-all firewall, an unroutable "
                   "address, or the scan was blocked. This is NOT evidence that "
                   "the ports are closed")
    elif filtered_n and closed_n:
        posture = (f"{filtered_n} port(s) silently dropped while {closed_n} were "
                   "refused — a firewall is selectively filtering")
    else:
        posture = "mixed / see per-port evidence"

    report: Dict[str, Any] = {
        "host": host,
        "address": address,
        "scanned": scanned,
        "open_count": len(open_ports),
        "closed_count": closed_n,
        "filtered_count": filtered_n,
        "open_ports": {
            f"{r['port']}/{r['service'] or 'unknown'}":
                (r["banner"] or "open, no banner") for r in open_ports
        } or "none open",
        "filtered_ports": sorted(r["port"] for r in by_state[FILTERED])[:60],
        "firewall_posture": posture,
        "elapsed_seconds": round(elapsed, 1),
        "note": ("connect-scan: a full TCP handshake, closed immediately. "
                 "'closed' means the host actively refused (RST) — proof it is "
                 "up and nothing listens there. 'filtered' means no answer "
                 "after retries, which is not proof of anything: a firewall "
                 "dropped it, or the packet was lost. Nothing was sent beyond "
                 "the minimal nudge needed to make a service announce itself."),
    }
    if cdn and cdn.get("kind") == "cdn":
        report["WARNING"] = (
            f"{address} is a {cdn.get('provider')} CDN/WAF edge address — these "
            f"results describe {cdn.get('provider')}'s infrastructure, not "
            f"{host}'s. Find the origin first (--filter-cdn, originhunt) and "
            "scan that.")
        report["scanned_the_target"] = False
    else:
        report["scanned_the_target"] = True
    return report


def scan_host(host: str, spec: str = "", timeout: float = 2.0,
              workers: int = 100, retries: int = 1, rate: float = 0.0,
              grab: bool = True, all_addresses: bool = False,
              on_result=None) -> Dict[str, Any]:
    """Resolve, scan, and summarise — the whole thing in one call."""
    ports = parse_ports(spec)
    addresses = resolve(host)
    if not addresses:
        raise PortSpecError(f"cannot resolve {host!r}")
    chosen = addresses if all_addresses else addresses[:1]
    started = time.time()
    reports = []
    for _family, address in chosen:
        records = scan(address, ports, timeout, workers, retries, rate, grab,
                       on_result)
        cdn = None
        try:
            from .netclass import classify_ip
            cdn = classify_ip(address)
        except Exception:  # noqa: BLE001
            cdn = None
        reports.append(summarise(host, address, records, len(ports), cdn,
                                 time.time() - started))
    if len(reports) == 1:
        out = dict(reports[0])
        out["addresses_resolved"] = [a for _f, a in addresses]
        return out
    return {"host": host, "addresses_resolved": [a for _f, a in addresses],
            "per_address": reports,
            "note": "scanned every resolved address; IPv4 and IPv6 postures "
                    "differ more often than people expect."}
