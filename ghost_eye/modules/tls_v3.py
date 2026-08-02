"""TLS transport-security detection (v3.8 new features). Detection only."""

from __future__ import annotations

import socket
import ssl

from ..core import Context, Module, Result, clean_host, register


# --------------------------------------------------------------------------- #
#  #21 TLS 1.3 0-RTT / early-data replay risk
# --------------------------------------------------------------------------- #
@register
class ZeroRttReplay(Module):
    id, name, category = "zerortt", "TLS 1.3 0-RTT / early-data replay risk", "SSL/TLS"
    target_kind = "host"

    def _connect(self, host, port, timeout, session=None):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        try:
            ctx.minimum_version = ssl.TLSVersion.TLSv1_2
        except (ValueError, AttributeError):
            pass
        with socket.create_connection((host, port), timeout=timeout) as sock:
            with ctx.wrap_socket(sock, server_hostname=host,
                                 session=session) as s:
                return s.version(), s.cipher(), s.session, s.session_reused

    def run(self, target, ctx):
        try:
            host = clean_host(target)
        except ValueError as e:
            return self.fail(target, str(e))
        port = 443
        try:
            ver, cipher, session, _ = self._connect(host, port, ctx.timeout)
        except Exception as e:  # noqa: BLE001
            return self.fail(host, f"TLS connect failed: {e}")
        tls13 = ver == "TLSv1.3"
        resumable = False
        if session is not None:
            try:
                _, _, _, resumable = self._connect(host, port, ctx.timeout,
                                                   session=session)
            except Exception:  # noqa: BLE001
                resumable = False
        # 0-RTT requires TLS 1.3 + a resumable session; Python's ssl cannot read
        # the server's max_early_data_size, so resumption is the observable proxy.
        if not tls13:
            risk, note = "low", ("server negotiated %s, not TLS 1.3 — 0-RTT "
                                 "replay does not apply" % ver)
        elif resumable:
            risk, note = "medium", ("TLS 1.3 with session resumption enabled — if "
                                    "the server also accepts early-data (0-RTT), "
                                    "requests can be replayed; ensure 0-RTT is off "
                                    "for state-changing endpoints")
        else:
            risk, note = "low", ("TLS 1.3 but session resumption not observed — "
                                 "0-RTT replay unlikely")
        return self.ok(host, {
            "tls_version": ver,
            "cipher": cipher[0] if cipher else None,
            "session_resumption": resumable,
            "zero_rtt_precondition": tls13 and resumable,
            "risk": risk,
            "note": note})
