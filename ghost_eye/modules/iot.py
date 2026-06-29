"""IoT & industrial protocol modules (features #33-#39). Detection only."""

from __future__ import annotations

import re
import socket
import struct
from concurrent.futures import ThreadPoolExecutor
from typing import Dict

from ..core import Context, Module, Result, clean_host, ensure_scheme, register


@register
class UpnpScan(Module):
    id, name, category = "upnpscan", "UPnP/SSDP device discovery", "IoT"
    target_kind = "url"

    _UPNP_PATHS = ["/rootDesc.xml", "/description.xml", "/upnp/desc.xml",
                    "/DeviceDescription.xml", "/igddesc.xml",
                    "/MediaRenderer/desc.xml"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        base = ensure_scheme(host).rstrip("/")
        findings = {}

        # probe UPnP description endpoints on common ports
        ports = [80, 8080, 1900, 5000, 49152]
        scheme = "http"

        def probe(item):
            port, path = item
            url = f"{scheme}://{host.split(':')[0]}:{port}{path}"
            try:
                r = ctx.session.get(url, timeout=min(ctx.timeout, 5))
                if r.status_code == 200 and any(
                    kw in r.text.lower()[:5000]
                    for kw in ["<device", "upnp", "devicetype", "manufacturer"]):
                    info = {"url": url, "status": 200}
                    for tag in ["friendlyName", "manufacturer", "modelName",
                                "modelNumber", "serialNumber", "deviceType"]:
                        m = re.search(f"<{tag}>([^<]+)</{tag}>", r.text, re.I)
                        if m:
                            info[tag] = m.group(1)[:60]
                    return info
            except Exception:
                pass
            return None

        items = [(p, path) for p in ports for path in self._UPNP_PATHS]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 6)) as ex:
            for result in ex.map(probe, items[:30]):
                if result:
                    key = result.get("friendlyName", result["url"])[:40]
                    findings[key] = result

        risk = "informational"
        if findings:
            risk = "HIGH"

        return self.ok(host, {
            "upnp_devices": findings or "none found",
            "risk": risk,
            "note": "exposed UPnP allows device enumeration and potential control"
        })


@register
class RtspScan(Module):
    id, name, category = "rtspscan", "RTSP camera stream detection", "IoT"
    target_kind = "domain"

    _PORTS = [554, 8554, 5554]
    _PATHS = ["/", "/live", "/stream1", "/cam", "/video",
              "/Streaming/Channels/1", "/h264", "/media"]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        found = {}

        def probe(item):
            port, path = item
            try:
                s = socket.create_connection((bare, port),
                                              timeout=min(ctx.timeout, 5))
                req = f"DESCRIBE rtsp://{bare}:{port}{path} RTSP/1.0\r\nCSeq: 1\r\n\r\n"
                s.sendall(req.encode())
                resp = s.recv(1024).decode("utf-8", errors="replace")
                s.close()
                if "RTSP/" in resp:
                    status = resp.split(" ", 2)[1] if " " in resp else "?"
                    info = {"port": port, "path": path, "status": status}
                    if status == "200":
                        info["accessible"] = True
                        info["risk"] = "CRITICAL"
                    elif status == "401":
                        info["accessible"] = False
                        info["note"] = "auth required"
                    return f"{port}{path}", info
            except Exception:
                pass
            return None, None

        items = [(p, path) for p in self._PORTS for path in self._PATHS]
        with ThreadPoolExecutor(max_workers=min(ctx.threads, 4)) as ex:
            for key, info in ex.map(probe, items[:24]):
                if key and info:
                    found[key] = info

        risk = "informational"
        if any(f.get("accessible") for f in found.values()):
            risk = "CRITICAL"
        elif found:
            risk = "MEDIUM"

        return self.ok(host, {
            "rtsp_streams": found or "none found",
            "risk": risk,
        })


@register
class CoapScan(Module):
    id, name, category = "coapscan", "CoAP endpoint detection", "IoT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        # CoAP default port 5683 UDP
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(min(ctx.timeout, 5))
            # CoAP GET /.well-known/core
            coap_get = bytes([
                0x40,  # Ver=1, T=CON, TKL=0
                0x01,  # Code=GET
                0x00, 0x01,  # Message ID
            ])
            # add Uri-Path option for .well-known/core
            coap_get += bytes([0xBB])  # Option delta=11(Uri-Path), length=11
            coap_get += b".well-known"
            coap_get += bytes([0x04])  # Option delta=0, length=4
            coap_get += b"core"

            s.sendto(coap_get, (bare, 5683))
            data, addr = s.recvfrom(1024)
            s.close()
            if len(data) > 4:
                findings["coap_port_5683"] = {
                    "open": True,
                    "response_size": len(data),
                    "risk": "HIGH",
                }
        except Exception:
            pass

        return self.ok(host, {
            "coap": findings or "port 5683 not responding",
            "risk": "HIGH" if findings else "informational",
        })


@register
class IcsScan(Module):
    id, name, category = "icsscan", "Modbus/OPC-UA industrial detection", "IoT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        # Modbus TCP (port 502)
        try:
            s = socket.create_connection((bare, 502),
                                          timeout=min(ctx.timeout, 5))
            # Modbus read device identification
            req = bytes([
                0x00, 0x01,  # Transaction ID
                0x00, 0x00,  # Protocol ID
                0x00, 0x05,  # Length
                0x01,        # Unit ID
                0x2B,        # Function: Encapsulated Interface Transport
                0x0E,        # MEI type: Read Device Identification
                0x01,        # Read Device ID: Basic
                0x00,        # Object ID
            ])
            s.sendall(req)
            resp = s.recv(256)
            s.close()
            if len(resp) > 8:
                findings["modbus_502"] = {
                    "open": True,
                    "response_size": len(resp),
                    "risk": "CRITICAL",
                    "note": "Modbus TCP accessible — industrial control system"
                }
        except Exception:
            pass

        # OPC-UA (port 4840)
        try:
            s = socket.create_connection((bare, 4840),
                                          timeout=min(ctx.timeout, 5))
            s.close()
            findings["opcua_4840"] = {
                "open": True,
                "risk": "HIGH",
                "note": "OPC-UA port open — industrial automation"
            }
        except Exception:
            pass

        # BACnet (port 47808 UDP)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(min(ctx.timeout, 3))
            bacnet_who = bytes([0x81, 0x0B, 0x00, 0x0C, 0x01, 0x20,
                                0xFF, 0xFF, 0x00, 0xFF, 0x10, 0x08])
            s.sendto(bacnet_who, (bare, 47808))
            data, _ = s.recvfrom(512)
            s.close()
            if len(data) > 4:
                findings["bacnet_47808"] = {
                    "open": True,
                    "risk": "CRITICAL",
                    "note": "BACnet accessible — building automation"
                }
        except Exception:
            pass

        return self.ok(host, {
            "industrial": findings or "no ICS protocols detected",
            "risk": "CRITICAL" if findings else "informational",
        })


@register
class TelnetScan(Module):
    id, name, category = "telnetscan", "Telnet service detection", "IoT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        for port in [23, 2323, 8023]:
            try:
                s = socket.create_connection((bare, port),
                                              timeout=min(ctx.timeout, 5))
                s.settimeout(3)
                banner = b""
                try:
                    banner = s.recv(1024)
                except Exception:
                    pass
                s.close()
                info = {"port": port, "open": True}
                if banner:
                    text = banner.decode("utf-8", errors="replace")[:200]
                    info["banner"] = text.strip()
                    if any(kw in text.lower() for kw in
                           ["login", "username", "password"]):
                        info["login_prompt"] = True
                findings[f"telnet:{port}"] = info
            except Exception:
                continue

        risk = "informational"
        if findings:
            risk = "HIGH"
            if any(f.get("login_prompt") for f in findings.values()):
                risk = "CRITICAL"

        return self.ok(host, {
            "telnet": findings or "no telnet services found",
            "risk": risk,
            "note": "telnet transmits credentials in cleartext"
        })


@register
class SnmpV3Detect(Module):
    id, name, category = "snmpv3", "SNMP v1/v2c/v3 detection", "IoT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        bare = host.split(":")[0]
        findings = {}

        # SNMP v2c GET sysDescr with community "public"
        snmp_get = bytes([
            0x30, 0x29,
            0x02, 0x01, 0x01,  # version: v2c
            0x04, 0x06, 0x70, 0x75, 0x62, 0x6c, 0x69, 0x63,  # community: public
            0xa0, 0x1c,  # GET-request
            0x02, 0x04, 0x00, 0x00, 0x00, 0x01,  # request-id
            0x02, 0x01, 0x00,  # error-status
            0x02, 0x01, 0x00,  # error-index
            0x30, 0x0e,  # varbind list
            0x30, 0x0c,
            0x06, 0x08, 0x2b, 0x06, 0x01, 0x02, 0x01, 0x01, 0x01, 0x00,  # sysDescr.0
            0x05, 0x00,  # NULL
        ])

        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(min(ctx.timeout, 5))
            s.sendto(snmp_get, (bare, 161))
            data, _ = s.recvfrom(2048)
            s.close()
            if len(data) > 10:
                findings["snmp_161"] = {
                    "version": "v2c",
                    "community": "public",
                    "response_size": len(data),
                    "risk": "CRITICAL",
                    "note": "SNMP accessible with default community string"
                }
        except Exception:
            pass

        return self.ok(host, {
            "snmp": findings or "not responding on port 161",
            "risk": "CRITICAL" if findings else "informational",
        })


@register
class MdnsScan(Module):
    id, name, category = "mdnsscan", "mDNS/Bonjour service discovery", "IoT"
    target_kind = "domain"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        findings = {}

        # check for mDNS-related HTTP services
        base = ensure_scheme(host).rstrip("/")
        mdns_indicators = []

        try:
            r = ctx.session.get(base, timeout=ctx.timeout)
            body = r.text[:20_000].lower()
            for kw in ["bonjour", "mdns", "avahi", "zeroconf",
                        "_http._tcp", "_ipp._tcp", "_airplay"]:
                if kw in body:
                    mdns_indicators.append(kw)
        except Exception:
            pass

        # check for Avahi/mDNS service pages
        for path in ["/avahi", "/mdns", "/services"]:
            try:
                r = ctx.session.get(base + path, timeout=ctx.timeout)
                if r.status_code == 200 and any(
                    kw in r.text.lower()[:3000]
                    for kw in ["service", "avahi", "mdns"]):
                    findings[path] = {"status": 200}
            except Exception:
                continue

        if mdns_indicators:
            findings["indicators"] = mdns_indicators

        return self.ok(host, {
            "mdns": findings or "no mDNS indicators found",
            "risk": "LOW" if findings else "informational",
        })
