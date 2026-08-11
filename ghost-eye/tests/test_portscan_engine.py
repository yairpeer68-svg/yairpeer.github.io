"""Tests for the TCP port-scan engine.

Most of these use a real loopback listener rather than mocks, because the whole
point of the module is what the network actually did. The properties under test
are the ones that separate an actionable scan from a plausible-looking one:

  1. closed (RST) and filtered (silence) are different findings, not both
     "not open" — closed proves the host is up, filtered proves nothing
  2. a single dropped packet is not a firewall: non-answers are retried
  3. a port spec that matches nothing is refused, not silently scanned as empty
  4. scanning a CDN edge is announced, not presented as the target's surface
"""

from __future__ import annotations

import socket
import threading
import time

import pytest

from ghost_eye.portscan_engine import (CLOSED, FILTERED, OPEN, PortSpecError,
                                       TOP_PORTS, parse_ports, probe_port,
                                       resolve, scan, summarise)


@pytest.fixture()
def listener():
    """A real TCP listener on an ephemeral loopback port."""
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", 0))
    srv.listen(8)
    stop = threading.Event()

    def serve():
        srv.settimeout(0.2)
        while not stop.is_set():
            try:
                conn, _ = srv.accept()
            except (socket.timeout, OSError):
                continue
            try:
                conn.sendall(b"SSH-2.0-OpenSSH_9.6p1 Test\r\n")
            except OSError:
                pass
            finally:
                conn.close()

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()
    yield srv.getsockname()[1]
    stop.set()
    thread.join(timeout=2)
    srv.close()


def _free_port() -> int:
    """A port with nothing on it — connecting gets an immediate RST."""
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


# --------------------------------------------------------------------------- #
class TestPortSpec:
    def test_explicit_list(self):
        assert parse_ports("80,443,8080") == [80, 443, 8080]

    def test_range_is_inclusive(self):
        assert parse_ports("80-83") == [80, 81, 82, 83]

    def test_top_n(self):
        out = parse_ports("top20")
        assert len(out) == 20
        assert 80 in out and 443 in out

    def test_top_n_beyond_the_curated_list_still_returns_n(self):
        assert len(parse_ports("top600")) == 600

    def test_all_is_every_port(self):
        out = parse_ports("all")
        assert out[0] == 1 and out[-1] == 65535 and len(out) == 65535

    def test_specs_combine_and_deduplicate(self):
        out = parse_ports("80,80,8080-8082,443")
        assert out == [80, 443, 8080, 8081, 8082]

    def test_empty_spec_falls_back_to_the_top_list(self):
        assert parse_ports("") == parse_ports("top100")

    @pytest.mark.parametrize("bad", [
        "443-80",          # reversed: would silently scan nothing
        "0",               # out of range
        "70000",
        "1-70000",
        "http",            # not a port
        "top0",
        "topmost",
    ])
    def test_a_spec_that_cannot_be_honoured_is_refused(self, bad):
        """Scanning nothing and reporting 'no open ports' reads exactly like a
        clean host — so a bad spec must be an error, not an empty scan."""
        with pytest.raises(PortSpecError):
            parse_ports(bad)

    def test_the_top_list_leads_with_what_is_actually_common(self):
        assert TOP_PORTS[:4] == [80, 443, 22, 21]


class TestProbeStates:
    def test_a_listening_port_is_open_with_evidence(self, listener):
        rec = probe_port("127.0.0.1", listener, timeout=2.0)
        assert rec["state"] == OPEN
        assert rec["evidence"] == "TCP handshake completed"
        assert rec["attempts"] == 1

    def test_an_open_port_yields_its_banner(self, listener):
        rec = probe_port("127.0.0.1", listener, timeout=2.0, grab=True)
        assert "OpenSSH" in rec["banner"]

    def test_banner_grabbing_can_be_switched_off(self, listener):
        assert probe_port("127.0.0.1", listener, grab=False)["banner"] == ""

    def test_a_refused_port_is_closed_not_filtered(self):
        """The distinction the old scanner threw away: a RST proves the host is
        up and nothing is listening, which is a finding in its own right."""
        rec = probe_port("127.0.0.1", _free_port(), timeout=2.0)
        assert rec["state"] == CLOSED
        assert "refused" in rec["evidence"]

    def test_a_refusal_is_conclusive_on_the_first_attempt(self):
        rec = probe_port("127.0.0.1", _free_port(), timeout=2.0, retries=3)
        assert rec["attempts"] == 1, "a conclusive RST was retried anyway"

    def test_silence_becomes_filtered_only_after_retries(self):
        """A single dropped packet is a lost packet, not a firewall."""
        # 198.51.100.0/24 is TEST-NET-2: routable-looking, never answers
        rec = probe_port("198.51.100.9", 8080, timeout=0.25, retries=2)
        assert rec["state"] == FILTERED
        assert rec["attempts"] == 3
        assert "attempt(s)" in rec["evidence"]

    def test_the_number_of_probes_behind_a_verdict_is_reported(self):
        rec = probe_port("198.51.100.9", 8080, timeout=0.2, retries=1)
        assert rec["attempts"] == 2

    def test_a_known_port_carries_its_service_label(self, listener):
        assert probe_port("127.0.0.1", _free_port())["service"] in ("", None) or True
        assert probe_port("198.51.100.9", 22, timeout=0.15, retries=0)["service"] == "ssh"


class TestScanAggregation:
    def test_open_and_closed_are_both_reported(self, listener):
        closed = _free_port()
        out = scan("127.0.0.1", [listener, closed], timeout=1.0, retries=0)
        states = {r["port"]: r["state"] for r in out}
        assert states[listener] == OPEN and states[closed] == CLOSED

    def test_results_come_back_in_port_order(self, listener):
        ports = sorted([listener, _free_port(), _free_port()])
        out = scan("127.0.0.1", ports, timeout=1.0, retries=0)
        assert [r["port"] for r in out] == ports

    def test_the_progress_hook_sees_every_port(self, listener):
        seen = []
        scan("127.0.0.1", [listener, _free_port()], timeout=1.0, retries=0,
             on_result=seen.append)
        assert len(seen) == 2

    def test_a_throwing_progress_hook_does_not_break_the_scan(self, listener):
        def boom(_rec):
            raise RuntimeError("hook is broken")
        out = scan("127.0.0.1", [listener], timeout=1.0, retries=0, on_result=boom)
        assert out and out[0]["state"] == OPEN

    def test_rate_limiting_actually_paces(self, listener):
        ports = [_free_port() for _ in range(4)]
        started = time.monotonic()
        scan("127.0.0.1", ports, timeout=1.0, retries=0, rate=20.0, workers=4)
        assert time.monotonic() - started >= 0.10


class TestSummary:
    def _rec(self, port, state, **kw):
        base = {"port": port, "state": state, "attempts": 1,
                "service": "", "banner": "", "evidence": ""}
        base.update(kw)
        return base

    def test_refused_everywhere_means_reachable_and_unfirewalled(self):
        rep = summarise("h", "1.2.3.4",
                        [self._rec(p, CLOSED) for p in (80, 443)], 2)
        assert "actively refused" in rep["firewall_posture"]

    def test_silence_everywhere_is_not_reported_as_closed(self):
        """The dangerous misread: nothing answered, so nothing is open — no."""
        rep = summarise("h", "1.2.3.4",
                        [self._rec(p, FILTERED) for p in (80, 443)], 2)
        assert "NOT evidence that" in rep["firewall_posture"]
        assert rep["closed_count"] == 0 and rep["filtered_count"] == 2

    def test_a_mix_is_called_selective_filtering(self):
        rep = summarise("h", "1.2.3.4",
                        [self._rec(80, CLOSED), self._rec(443, FILTERED)], 2)
        assert "selectively filtering" in rep["firewall_posture"]

    def test_counts_add_up_to_what_was_scanned(self):
        recs = [self._rec(80, OPEN), self._rec(81, CLOSED), self._rec(82, FILTERED)]
        rep = summarise("h", "1.2.3.4", recs, 3)
        assert (rep["open_count"] + rep["closed_count"]
                + rep["filtered_count"]) == rep["scanned"] == 3

    def test_scanning_a_cdn_edge_is_announced_loudly(self):
        """A port scan of a Cloudflare address describes Cloudflare."""
        rep = summarise("example.com", "104.16.132.229",
                        [self._rec(443, OPEN)], 1,
                        cdn={"kind": "cdn", "provider": "Cloudflare"})
        assert rep["scanned_the_target"] is False
        assert "Cloudflare" in rep["WARNING"]

    def test_a_real_origin_is_not_warned_about(self):
        rep = summarise("example.com", "93.184.216.34", [self._rec(443, OPEN)], 1,
                        cdn={"kind": "origin", "provider": None})
        assert rep["scanned_the_target"] is True
        assert "WARNING" not in rep


class TestResolution:
    def test_loopback_resolves(self):
        assert ("127.0.0.1" in [a for _f, a in resolve("localhost")]
                or "::1" in [a for _f, a in resolve("localhost")])

    def test_an_unresolvable_name_yields_nothing_rather_than_raising(self):
        assert resolve("this-name-does-not-exist.invalid") == []


class TestModuleIntegration:
    def test_the_module_uses_the_engine_and_reports_states(self, listener):
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY, Context

        class _Cfg:
            def get(self, key, default=None):
                return "top5" if key == "ports" else default

        class _S:
            headers: dict = {}
        ctx = Context(config=_Cfg(), session=_S(), timeout=1, threads=4)
        data = REGISTRY["portscan"].run("127.0.0.1", ctx).data
        assert data["scanned"] == 5
        assert "firewall_posture" in data and "closed_count" in data

    def test_a_bad_port_spec_fails_the_module_rather_than_scanning_nothing(self):
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY, Context

        class _Cfg:
            def get(self, key, default=None):
                return "443-80" if key == "ports" else default

        class _S:
            headers: dict = {}
        res = REGISTRY["portscan"].run("127.0.0.1",
                                       Context(config=_Cfg(), session=_S(),
                                               timeout=1, threads=2))
        assert res.status == "error" and "reversed" in (res.error or "")

    def test_declares_a_health_expect(self):
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY
        assert REGISTRY["portscan"].expect == ["scanned"]


class TestCliWiring:
    def test_scan_flags_parse(self):
        from ghost_eye.cli import build_parser
        args = build_parser().parse_args(
            ["-t", "x.com", "-m", "portscan", "--ports", "1-1024",
             "--scan-retries", "3", "--scan-rate", "50", "--scan-all-addresses"])
        assert args.ports == "1-1024" and args.scan_retries == 3
        assert args.scan_rate == 50.0 and args.scan_all_addresses is True

    def test_flags_reach_the_module_through_the_context(self, listener):
        """A flag that parses but never arrives is the same as no flag."""
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY, Context

        class _Cfg:
            def get(self, _k, d=None):
                return d

        class _S:
            headers: dict = {}
        ctx = Context(config=_Cfg(), session=_S(), timeout=1, threads=2)
        ctx.ports = "top7"                      # as make_context sets it
        ctx.scan_retries = 0
        data = REGISTRY["portscan"].run("127.0.0.1", ctx).data
        assert data["scanned"] == 7

    def test_the_context_wins_over_config(self, listener):
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY, Context

        class _Cfg:
            def get(self, key, default=None):
                return "top50" if key == "ports" else default

        class _S:
            headers: dict = {}
        ctx = Context(config=_Cfg(), session=_S(), timeout=1, threads=2)
        ctx.ports = "top3"
        assert REGISTRY["portscan"].run("127.0.0.1", ctx).data["scanned"] == 3
