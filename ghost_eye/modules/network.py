"""Network / port-scan modules (features #19-#28).

Note: SYN scan (-sS), UDP scan (-sU) and OS detection (-O) require root.
Modules degrade to an unprivileged connect-scan and say so.
"""

from __future__ import annotations

import os
import re
import socket
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Dict, List

from ..core import (Console, Context, Module, Result, clean_host, have_binary,
                    is_ip, register, run_cmd)

_NMAP_PROFILES = {
    "fast": ["-Pn", "-T4", "-F"],
    "top1000": ["-Pn", "-T4"],
    "full": ["-Pn", "-T4", "-p-"],
    "syn": ["-Pn", "-sS", "-T4"],
    "udp": ["-Pn", "-sU", "-T4", "--top-ports", "50"],
    "version": ["-Pn", "-sV", "-T4"],
    "os": ["-Pn", "-O", "-T4"],
    "aggressive": ["-Pn", "-A", "-T4"],
}


def _is_root() -> bool:
    return hasattr(os, "geteuid") and os.geteuid() == 0


@register
class NmapScan(Module):
    id, name, category = "nmap", "Nmap port scan (profiles)", "Network"
    target_kind = "host"
    needs = ["nmap"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        if not have_binary("nmap"):
            return self.fail(host, "nmap binary not installed")

        profile = (ctx.config.get("nmap_profile") or "top1000").lower()
        args = _NMAP_PROFILES.get(profile, _NMAP_PROFILES["top1000"])
        notes = []
        if profile in ("syn", "udp", "os", "aggressive") and not _is_root():
            notes.append(f"profile '{profile}' needs root; falling back to connect scan")
            args = _NMAP_PROFILES["top1000"]

        # python-nmap gives structured output when available
        try:
            import nmap
            scanner = nmap.PortScanner()
            scanner.scan(hosts=host, arguments=" ".join(args))
            parsed: Dict[str, object] = {"nmap_version": ".".join(map(str, scanner.nmap_version()))}
            for h in scanner.all_hosts():
                ports = []
                for proto in scanner[h].all_protocols():
                    for port in sorted(scanner[h][proto].keys()):
                        info = scanner[h][proto][port]
                        ports.append({
                            "port": f"{port}/{proto}",
                            "state": info.get("state"),
                            "service": info.get("name"),
                            "product": (f"{info.get('product','')} "
                                        f"{info.get('version','')}").strip(),
                        })
                parsed[h] = {"state": scanner[h].state(), "ports": ports}
            if notes:
                parsed["notes"] = notes
            return self.ok(host, parsed)
        except ImportError:
            out = run_cmd(["nmap", *args, host], timeout=600)
            data = {"raw": out.splitlines()}
            if notes:
                data["notes"] = notes
            return self.ok(host, data)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"nmap failed: {exc}")


@register
class BannerGrab(Module):
    id, name, category = "banner", "Banner grabbing", "Network"
    target_kind = "host"

    _PORTS = [21, 22, 23, 25, 80, 110, 143, 443, 3306, 3389, 5432, 6379, 8080]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        ip = host if is_ip(host) else None
        if not ip:
            try:
                ip = socket.gethostbyname(host)
            except OSError as exc:
                return self.fail(host, f"cannot resolve: {exc}")

        banners: Dict[str, str] = {}

        def grab(port: int):
            try:
                with socket.create_connection((ip, port), timeout=ctx.timeout) as sock:
                    sock.settimeout(ctx.timeout)
                    if port in (80, 8080):
                        sock.sendall(b"HEAD / HTTP/1.0\r\n\r\n")
                    elif port == 443:
                        return port, "(TLS - see SSL modules)"
                    data = sock.recv(256)
                    return port, data.decode("utf-8", "replace").strip()
            except Exception:
                return port, None

        with ThreadPoolExecutor(max_workers=ctx.threads) as ex:
            for port, banner in ex.map(grab, self._PORTS):
                if banner:
                    banners[str(port)] = banner[:200]
        return self.ok(host, {"ip": ip, "banners": banners})


@register
class PingSweep(Module):
    id, name, category = "pingsweep", "Ping sweep (subnet)", "Network"
    target_kind = "host"  # accepts CIDR too

    def run(self, target: str, ctx: Context) -> Result:
        import ipaddress
        try:
            net = ipaddress.ip_network(target.strip(), strict=False)
        except ValueError:
            return self.fail(target, "provide a CIDR, e.g. 192.168.1.0/24")
        if net.num_addresses > 1024:
            return self.fail(target, "range too large (>1024 hosts); narrow the CIDR")
        param = "-n" if os.name == "nt" else "-c"

        alive: List[str] = []

        def ping(ip: str):
            out = run_cmd(["ping", param, "1", "-w", "1", str(ip)], timeout=4)
            ok = ("ttl=" in out.lower()) or (" 1 received" in out) or ("1 packets received" in out)
            return str(ip), ok

        hosts = list(net.hosts()) or [net.network_address]
        with ThreadPoolExecutor(max_workers=min(ctx.threads * 4, 100)) as ex:
            for ip, ok in ex.map(ping, hosts):
                if ok:
                    alive.append(ip)
        return self.ok(target, {"alive_count": len(alive), "alive": alive})


@register
class Masscan(Module):
    id, name, category = "masscan", "Masscan (fast port sweep)", "Network"
    target_kind = "host"
    needs = ["masscan", "root"]

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        if not have_binary("masscan"):
            return self.fail(host, "masscan not installed")
        if not _is_root():
            return self.fail(host, "masscan requires root")
        rate = ctx.config.get("masscan_rate") or "1000"
        out = run_cmd(["masscan", host, "-p1-65535", "--rate", rate], timeout=600)
        ports = re.findall(r"port (\d+)/(\w+)", out)
        return self.ok(host, {"open": [f"{p}/{proto}" for p, proto in ports],
                              "raw": out.splitlines()[:50]})


@register
class TracerouteGeo(Module):
    id, name, category = "traceroute", "Traceroute + geo per hop", "Network"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        if have_binary("mtr"):
            raw = run_cmd(["mtr", "-4", "-rwc", "1", host], timeout=120)
        elif have_binary("traceroute"):
            raw = run_cmd(["traceroute", "-n", "-w", "2", host], timeout=120)
        elif have_binary("tracert"):
            raw = run_cmd(["tracert", host], timeout=120)
        else:
            return self.fail(host, "install mtr or traceroute")

        ips = re.findall(r"\b(\d{1,3}(?:\.\d{1,3}){3})\b", raw)
        sess = ctx.session
        hops = []
        seen = set()
        for ip in ips:
            if ip in seen or ip.startswith(("10.", "192.168.", "172.")):
                hops.append({"ip": ip, "geo": "private/local"})
                seen.add(ip)
                continue
            seen.add(ip)
            try:
                j = sess.get(f"http://ip-api.com/json/{ip}"
                             "?fields=country,city,lat,lon,isp",
                             timeout=ctx.timeout).json()
                hops.append({"ip": ip, "city": j.get("city"),
                             "country": j.get("country"), "isp": j.get("isp"),
                             "lat": j.get("lat"), "lon": j.get("lon")})
            except Exception:
                hops.append({"ip": ip, "geo": "lookup failed"})
            time.sleep(0.4)  # ip-api free tier rate limit
        return self.ok(host, {"hops": hops, "raw": raw.splitlines()})


@register
class UptimeMonitor(Module):
    id, name, category = "uptime", "Latency / uptime check", "Network"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        param = "-n" if os.name == "nt" else "-c"
        count = ctx.config.get("ping_count") or "5"
        out = run_cmd(["ping", param, str(count), host], timeout=int(count) * 2 + 10)
        loss = re.search(r"(\d+(?:\.\d+)?)% (?:packet )?loss", out)
        rtt = re.search(r"=\s*([\d.]+)/([\d.]+)/([\d.]+)", out)
        data = {
            "packet_loss": loss.group(1) + "%" if loss else "?",
            "reachable": "0% " in out or "0.0% " in out or bool(rtt),
        }
        if rtt:
            data.update({"rtt_min_ms": rtt.group(1),
                         "rtt_avg_ms": rtt.group(2),
                         "rtt_max_ms": rtt.group(3)})
        data["raw"] = out.splitlines()[-4:]
        return self.ok(host, data)


@register
class NetMap(Module):
    id, name, category = "netmap", "Network map (DOT graph)", "Network"
    target_kind = "host"

    def run(self, target: str, ctx: Context) -> Result:
        """Builds a Graphviz DOT description from a traceroute. Render with
        `dot -Tpng map.dot -o map.png`, or feed to EtherApe for live view."""
        tr = TracerouteGeo().run(target, ctx)
        if tr.status == "error":
            return tr
        hops = tr.data.get("hops", [])
        lines = ['digraph ghosteye {', '  rankdir=LR; node [shape=box,style=rounded];',
                 '  you [label="YOU",shape=ellipse];']
        prev = "you"
        for i, hop in enumerate(hops):
            node = f"h{i}"
            label = hop.get("ip", "?")
            loc = hop.get("city") or hop.get("country") or hop.get("geo") or ""
            if loc:
                label += f"\\n{loc}"
            lines.append(f'  {node} [label="{label}"];')
            lines.append(f"  {prev} -> {node};")
            prev = node
        lines.append("}")
        dot = "\n".join(lines)
        return self.ok(target, {"dot": dot, "hop_count": len(hops),
                                "render_hint": "dot -Tpng <file>.dot -o map.png"})
