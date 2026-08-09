"""Network / infrastructure detection (v3.8 new features). Detection only."""

from __future__ import annotations

import re
import socket

from ..core import (Module, clean_host, have_binary, is_ip,
                    run_cmd, register)


def _resolve(host: str) -> str:
    return host if is_ip(host) else socket.gethostbyname(host)


# --------------------------------------------------------------------------- #
#  #33 Passive OS fingerprint (from IP TTL)
# --------------------------------------------------------------------------- #
@register
class OsFingerprint(Module):
    id, name, category = "osfp", "Passive OS fingerprint (TTL)", "Network"
    target_kind = "host"

    # initial-TTL -> likely OS family (we round the observed TTL up to these)
    _TTL_MAP = [(64, "Linux / Unix / macOS / Android"),
                (128, "Windows"),
                (255, "Network device / BSD / Solaris / Cisco"),
                (32, "Legacy Windows / embedded")]

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = _resolve(host)
        except ValueError as e:
            return self.fail(target, str(e))
        except OSError as e:
            return self.fail(target, f"cannot resolve: {e}")
        if not have_binary("ping"):
            return self.fail(host, "ping binary not available")
        out = run_cmd(["ping", "-c", "1", "-W", str(max(1, ctx.timeout // 2)), ip],
                      timeout=ctx.timeout + 3)
        m = re.search(r"ttl=(\d+)", out, re.I)
        if not m:
            return self.ok(host, {"ip": ip, "ttl": "no reply",
                                  "note": "host did not answer ICMP (filtered?) — "
                                          "TTL fingerprint unavailable"})
        ttl = int(m.group(1))
        # the observed TTL is the initial TTL minus hop count; round up to the
        # nearest common initial value
        guess, best = "unknown", 10 ** 9
        for init, label in self._TTL_MAP:
            if ttl <= init and (init - ttl) < best:
                best, guess = init - ttl, label
        hops = best if best < 10 ** 9 else None
        return self.ok(host, {
            "ip": ip,
            "observed_ttl": ttl,
            "likely_os": guess,
            "approx_hops": hops,
            "note": "OS guess from initial-TTL heuristic (64=*nix, 128=Windows, "
                    "255=network gear); not authoritative"})


# --------------------------------------------------------------------------- #
#  #38 IPMI / BMC exposure (UDP 623)
# --------------------------------------------------------------------------- #
@register
class IpmiExposure(Module):
    id, name, category = "ipmi", "IPMI / BMC exposure (UDP 623)", "Network"
    target_kind = "host"

    # RMCP "Get Channel Authentication Capabilities" probe (as used by scanners)
    _PROBE = bytes([
        0x06, 0x00, 0xff, 0x07,                          # RMCP header
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,  # IPMI session (none)
        0x09, 0x20, 0x18, 0xc8, 0x81, 0x00, 0x38, 0x8e, 0x04, 0xb5,
    ])

    def run(self, target, ctx):
        try:
            host = clean_host(target)
            ip = _resolve(host)
        except ValueError as e:
            return self.fail(target, str(e))
        except OSError as e:
            return self.fail(target, f"cannot resolve: {e}")
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(max(2, ctx.timeout))
        try:
            sock.sendto(self._PROBE, (ip, 623))
            data, _ = sock.recvfrom(1024)
        except socket.timeout:
            return self.ok(host, {"ip": ip, "ipmi": "no response on UDP/623",
                                  "exposed": False,
                                  "note": "no BMC/IPMI detected (or UDP filtered)"})
        except OSError as e:
            return self.fail(host, f"probe failed: {e}")
        finally:
            sock.close()
        # a valid RMCP reply starts with 0x06 and is > 20 bytes
        exposed = bool(data) and data[0] == 0x06
        details = {}
        if exposed and len(data) >= 22:
            # byte offsets per the IPMI spec for the auth-capabilities response
            try:
                auth_support = data[18]
                details["ipmi_1.5"] = bool(auth_support & 0x80) is False
                details["ipmi_2.0_supported"] = bool(auth_support & 0x80)
                details["null_user_allowed"] = bool(data[19] & 0x20)
                details["anonymous_login"] = bool(data[19] & 0x01)
            except IndexError:
                pass
        return self.ok(host, {
            "ip": ip,
            "exposed": exposed,
            "response_bytes": len(data),
            "details": details or "responded",
            "risk": "high" if exposed else "info",
            "note": "an internet-exposed BMC/IPMI is a critical finding — these "
                    "controllers have a long history of auth-bypass CVEs "
                    "(e.g. cipher-zero). Restrict UDP/623 to management networks."})
