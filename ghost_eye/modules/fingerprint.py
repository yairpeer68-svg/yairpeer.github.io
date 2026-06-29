"""Active TLS server fingerprinting - JA3S and JA4S (new request).

Python's ``ssl`` module never exposes the raw ServerHello, so a real JA3S/JA4S
cannot be computed through it. This module instead crafts a standard TLS
ClientHello, sends it over a raw socket, and parses the ServerHello bytes the
target returns, then derives:

  * JA3S  - original Salesforce spec: md5("Version,Cipher,Extensions")
  * JA4S  - FoxIO JA4+ spec: ``t<ver><extcount><alpn>_<cipher>_<exthash>``

These are *server* response fingerprints (what the target picked from our
offer). Cross-reference them on Shodan/Censys. Detection only - no exploit.
"""

from __future__ import annotations

import hashlib
import os
import socket
import time

from ..core import Module, Result, clean_host, register

# a fixed, representative modern client offer (so the fingerprint is stable)
_CIPHERS = [0x1302, 0x1303, 0x1301, 0xc02c, 0xc02b, 0xc030, 0xc02f,
            0xcca9, 0xcca8, 0xc013, 0xc014, 0x009c, 0x009d, 0x002f, 0x0035]
_GROUPS = [0x001d, 0x0017, 0x0018]            # x25519, secp256r1, secp384r1
_SIGALGS = [0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501,
            0x0806, 0x0601, 0x0201]
_VER_NAME = {0x0304: "13", 0x0303: "12", 0x0302: "11", 0x0301: "10", 0x0300: "s3"}
_CIPHER_NAME = {
    0x1301: "TLS_AES_128_GCM_SHA256", 0x1302: "TLS_AES_256_GCM_SHA384",
    0x1303: "TLS_CHACHA20_POLY1305_SHA256",
    0xc02b: "ECDHE-ECDSA-AES128-GCM-SHA256", 0xc02f: "ECDHE-RSA-AES128-GCM-SHA256",
    0xc02c: "ECDHE-ECDSA-AES256-GCM-SHA384", 0xc030: "ECDHE-RSA-AES256-GCM-SHA384",
    0xcca8: "ECDHE-RSA-CHACHA20-POLY1305", 0xcca9: "ECDHE-ECDSA-CHACHA20-POLY1305",
}


def _u16(n: int) -> bytes:
    return n.to_bytes(2, "big")


def _u24(n: int) -> bytes:
    return n.to_bytes(3, "big")


def _ext(etype: int, data: bytes) -> bytes:
    return _u16(etype) + _u16(len(data)) + data


def _client_hello(host: str) -> bytes:
    try:
        sni = host.encode("idna")
    except Exception:
        sni = host.encode("utf-8", "ignore")
    name_entry = b"\x00" + _u16(len(sni)) + sni
    ext_sni = _ext(0x0000, _u16(len(name_entry)) + name_entry)
    ext_ecpf = _ext(0x000b, b"\x01\x00")
    grp = b"".join(_u16(g) for g in _GROUPS)
    ext_groups = _ext(0x000a, _u16(len(grp)) + grp)
    sig = b"".join(_u16(s) for s in _SIGALGS)
    ext_sig = _ext(0x000d, _u16(len(sig)) + sig)
    alpn = b"\x02h2" + b"\x08http/1.1"
    ext_alpn = _ext(0x0010, _u16(len(alpn)) + alpn)
    ext_status = _ext(0x0005, b"\x01\x00\x00\x00\x00")
    ver = b"\x04" + _u16(0x0304) + _u16(0x0303)
    ext_versions = _ext(0x002b, ver)
    ext_pskmodes = _ext(0x002d, b"\x01\x01")
    ks_entry = _u16(0x001d) + _u16(32) + os.urandom(32)
    ext_keyshare = _ext(0x0033, _u16(len(ks_entry)) + ks_entry)
    ext_ems = _ext(0x0017, b"")
    ext_reneg = _ext(0xff01, b"\x00")
    exts = (ext_sni + ext_ecpf + ext_groups + ext_sig + ext_alpn + ext_status +
            ext_versions + ext_pskmodes + ext_keyshare + ext_ems + ext_reneg)

    cs = b"".join(_u16(c) for c in _CIPHERS)
    body = (b"\x03\x03" + os.urandom(32) +
            b"\x20" + os.urandom(32) +
            _u16(len(cs)) + cs +
            b"\x01\x00" +
            _u16(len(exts)) + exts)
    handshake = b"\x01" + _u24(len(body)) + body
    return b"\x16\x03\x01" + _u16(len(handshake)) + handshake


def _recv_exact(sock: socket.socket, n: int, deadline: float) -> bytes:
    buf = b""
    while len(buf) < n:
        remaining = deadline - time.time()
        if remaining <= 0:
            raise TimeoutError("read timed out")
        sock.settimeout(remaining)
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed mid-record")
        buf += chunk
    return buf


def _read_server_hello(sock: socket.socket, deadline: float) -> bytes:
    for _ in range(6):
        hdr = _recv_exact(sock, 5, deadline)
        ctype, length = hdr[0], int.from_bytes(hdr[3:5], "big")
        body = _recv_exact(sock, length, deadline)
        if ctype == 0x15:
            raise ConnectionError("server sent a TLS alert")
        if ctype == 0x16 and body and body[0] == 0x02:
            return body            # handshake record beginning with ServerHello
        # else: ChangeCipherSpec (0x14) or other handshake - keep reading
    raise ValueError("no ServerHello in response")


def _parse_server_hello(hs: bytes):
    length = int.from_bytes(hs[1:4], "big")
    sh = hs[4:4 + length]
    legacy_version = int.from_bytes(sh[0:2], "big")
    pos = 2 + 32                       # version + random
    sid_len = sh[pos]; pos += 1 + sid_len
    cipher = int.from_bytes(sh[pos:pos + 2], "big"); pos += 2
    pos += 1                           # compression method
    ext_types, negotiated, alpn = [], legacy_version, ""
    if pos + 2 <= len(sh):
        ext_total = int.from_bytes(sh[pos:pos + 2], "big"); pos += 2
        block = sh[pos:pos + ext_total]
        p = 0
        while p + 4 <= len(block):
            etype = int.from_bytes(block[p:p + 2], "big")
            elen = int.from_bytes(block[p + 2:p + 4], "big")
            edata = block[p + 4:p + 4 + elen]
            ext_types.append(etype)
            if etype == 0x002b and len(edata) >= 2:           # supported_versions
                negotiated = int.from_bytes(edata[0:2], "big")
            elif etype == 0x0010 and len(edata) >= 3:          # ALPN
                # list_len(2) + proto_len(1) + proto
                plen = edata[2]
                alpn = edata[3:3 + plen].decode("latin-1", "replace")
            p += 4 + elen
    return legacy_version, negotiated, cipher, ext_types, alpn


def _ja3s(legacy_version: int, cipher: int, ext_types) -> str:
    raw = f"{legacy_version},{cipher},{'-'.join(str(e) for e in ext_types)}"
    return hashlib.md5(raw.encode()).hexdigest()


def _ja4s(negotiated: int, cipher: int, ext_types, alpn: str) -> str:
    ver = _VER_NAME.get(negotiated, "00")
    n = min(len(ext_types), 99)
    if alpn:
        a = (alpn[0] + alpn[-1]) if len(alpn) > 1 else (alpn[0] * 2)
    else:
        a = "00"
    a_part = f"t{ver}{n:02d}{a}"
    b_part = f"{cipher:04x}"
    ext_str = ",".join(f"{e:04x}" for e in ext_types)
    c_part = hashlib.sha256(ext_str.encode()).hexdigest()[:12]
    return f"{a_part}_{b_part}_{c_part}"


@register
class Ja3Ja4(Module):
    id = "ja4"
    name = "JA3S / JA4S TLS fingerprint"
    category = "SSL/TLS"
    target_kind = "host"

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as exc:
            return self.fail(target, str(exc))
        deadline = time.time() + max(ctx.timeout, 5)
        try:
            with socket.create_connection((host, 443), timeout=ctx.timeout) as sock:
                sock.sendall(_client_hello(host))
                hs = _read_server_hello(sock, deadline)
            legacy, negotiated, cipher, ext_types, alpn = _parse_server_hello(hs)
        except Exception as exc:  # noqa: BLE001
            return self.fail(host, f"handshake/parse failed: {exc}")
        return self.ok(host, {
            "ja3s": _ja3s(legacy, cipher, ext_types),
            "ja4s": _ja4s(negotiated, cipher, ext_types, alpn),
            "negotiated_version": _VER_NAME.get(negotiated, hex(negotiated)),
            "cipher": _CIPHER_NAME.get(cipher, hex(cipher)),
            "alpn": alpn or "(none)",
            "server_extensions": [hex(e) for e in ext_types] or "(none)",
            "note": "server-side fingerprints (JA3S=md5 / JA4S=FoxIO); "
                    "compare on Shodan/Censys. JARM is an alternative active probe.",
        })
