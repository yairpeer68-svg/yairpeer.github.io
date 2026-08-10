"""Regression tests for the hardening pass.

Every test here pins a bug that was actually reachable before the fix — several
were demonstrated against a running dashboard — so a future refactor that
reopens one fails the build instead of shipping.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request

import pytest

from ghost_eye import engine, workflow
from ghost_eye.core import (Host, build_session, clean_host, ensure_scheme,
                            _plaintext_retry_ok)
from ghost_eye.reporting import Result, Store
from ghost_eye.scope import Scope


# --------------------------------------------------------------------------- #
#  target parsing: -t host:port used to silently become a scan of :443
# --------------------------------------------------------------------------- #
class TestTargetPorts:
    def test_port_is_preserved(self):
        host = clean_host("example.com:8080")
        assert host == "example.com"          # still a bare host for DNS/sockets
        assert host.port == 8080
        assert ensure_scheme(host) == "https://example.com:8080"

    def test_scheme_is_preserved(self):
        assert ensure_scheme(clean_host("http://example.com")) == "http://example.com"

    def test_scheme_and_port_together(self):
        assert (ensure_scheme(clean_host("http://example.com:8080/a/b?q=1"))
                == "http://example.com:8080")

    def test_plain_host_is_unchanged(self):
        assert ensure_scheme(clean_host("example.com")) == "https://example.com"

    def test_plain_string_still_works(self):
        # ensure_scheme is also called with ordinary strings (discovered subdomains)
        assert ensure_scheme("sub.example.com") == "https://sub.example.com"

    def test_ipv6_is_bracketed_in_urls(self):
        assert ensure_scheme(clean_host("[2001:db8::1]:8080")) == "https://[2001:db8::1]:8080"

    @pytest.mark.parametrize("bad", ["example.com:0", "example.com:99999",
                                     "example.com:abc", "example.com:-1"])
    def test_bad_ports_are_rejected(self, bad):
        with pytest.raises(ValueError):
            clean_host(bad)

    def test_host_behaves_as_a_plain_string(self):
        host = Host("example.com", port=443, scheme="https")
        assert host == "example.com"
        assert json.dumps({"t": host}) == '{"t": "example.com"}'
        assert host.upper() == "EXAMPLE.COM"


# --------------------------------------------------------------------------- #
#  http fallback must never become a downgrade attack
# --------------------------------------------------------------------------- #
class TestPlaintextFallback:
    def _exc(self, kind, msg):
        import requests
        return getattr(requests.exceptions, kind)(msg)

    def test_retries_when_port_is_not_tls(self):
        session = build_session()
        assert _plaintext_retry_ok(
            "https://example.com/", {}, session,
            self._exc("SSLError", "[SSL: WRONG_VERSION_NUMBER] wrong version number"))

    def test_never_retries_on_a_certificate_error(self):
        # a bad certificate is what a man-in-the-middle looks like; downgrading
        # there would let an attacker force the request into the clear
        session = build_session()
        assert not _plaintext_retry_ok(
            "https://example.com/", {}, session,
            self._exc("SSLError", "certificate verify failed: self signed certificate"))

    @pytest.mark.parametrize("header", ["Authorization", "X-API-Key", "Cookie"])
    def test_never_retries_a_request_carrying_a_secret(self, header):
        session = build_session()
        assert not _plaintext_retry_ok(
            "https://example.com/", {"headers": {header: "secret"}}, session,
            self._exc("SSLError", "[SSL: WRONG_VERSION_NUMBER] wrong version number"))

    def test_ignores_unrelated_errors(self):
        session = build_session()
        assert not _plaintext_retry_ok("https://example.com/", {}, session,
                                       self._exc("ReadTimeout", "timed out"))


# --------------------------------------------------------------------------- #
#  the response cache must not be a code-execution sink
# --------------------------------------------------------------------------- #
class TestResponseCache:
    def test_roundtrip_preserves_what_modules_read(self):
        import requests
        original = requests.Response()
        original.status_code = 201
        original._content = b"<html>hi</html>"
        original.url = "https://example.com/x"
        original.headers.update({"Content-Type": "text/html", "Server": "nginx"})

        restored = workflow._response_from_cache(workflow._response_to_cache(original))
        assert restored.status_code == 201
        assert restored.content == b"<html>hi</html>"
        assert restored.text == "<html>hi</html>"
        assert restored.headers["Server"] == "nginx"
        assert restored.url == "https://example.com/x"

    def test_cache_is_json_not_pickle(self, tmp_path):
        """A pickle cache means anyone who can write the cache directory gets
        code execution in the scanning process."""
        import requests
        resp = requests.Response()
        resp.status_code = 200
        resp._content = b"ok"
        blob = workflow._response_to_cache(resp)
        json.loads(blob.decode("utf-8"))          # parses as JSON, i.e. inert

    def test_a_hostile_cache_file_cannot_execute_code(self, tmp_path):
        marker = tmp_path / "pwned"
        payload = (b"cos\nsystem\n(S'touch " + str(marker).encode() + b"'\ntR.")
        bad = tmp_path / "deadbeef.json"
        bad.write_bytes(payload)
        with pytest.raises(Exception):
            workflow._response_from_cache(bad.read_bytes())
        assert not marker.exists()


# --------------------------------------------------------------------------- #
#  scope guard
# --------------------------------------------------------------------------- #
class TestScope:
    def test_ipv6_target_is_not_truncated(self):
        scope = Scope.from_lines(["2001:db8::/32"])
        allowed, _ = scope.allows("2001:db8::1")
        assert allowed, "a bare IPv6 target was being cut at the first colon"

    def test_host_port_still_matches(self):
        scope = Scope.from_lines(["example.com"])
        assert scope.allows("example.com:8443")[0]

    def test_bracketed_ipv6_with_port(self):
        scope = Scope.from_lines(["2001:db8::/32"])
        assert scope.allows("[2001:db8::1]:443")[0]

    def test_wildcard_entry_covers_subdomains_only_once(self):
        scope = Scope.from_lines(["*.example.com"])
        assert scope.allows("api.example.com")[0]
        assert scope.allows("example.com")[0]
        assert not scope.allows("notexample.com")[0]

    def test_out_of_scope_is_refused(self):
        scope = Scope.from_lines(["example.com"])
        assert not scope.allows("evil.test")[0]


# --------------------------------------------------------------------------- #
#  registry invariants
# --------------------------------------------------------------------------- #
class TestRegistry:
    def test_module_names_are_unique(self):
        """Results are keyed by module *name* in every diff/compare view, so a
        duplicate name silently drops one module's findings."""
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY
        seen = {}
        for mod in REGISTRY.values():
            assert mod.name not in seen, (
                f"{mod.id!r} and {seen[mod.name]!r} share the name {mod.name!r}")
            seen[mod.name] = mod.id

    def test_register_rejects_a_duplicate_name(self):
        from ghost_eye.core import Module, REGISTRY, register
        existing = next(iter(REGISTRY.values()))

        with pytest.raises(ValueError, match="duplicate module name"):
            @register
            class _Clash(Module):
                id, name, category = "zz_clash_test", existing.name, "Misc"


# --------------------------------------------------------------------------- #
#  adaptive rate limiter
# --------------------------------------------------------------------------- #
class TestAdaptiveRate:
    def test_rate_limit_text_triggers_a_backoff(self):
        """The keyword check sat behind `if not bad`, where `error` is always
        empty — so it never actually ran."""
        rl = engine.AdaptiveRateLimiter()
        rl.observe(Result("m", "t", status="ok", error="429 Too Many Requests"))
        assert rl.backoffs == 1
        assert rl.delay > 0

    def test_clean_results_do_not_back_off(self):
        rl = engine.AdaptiveRateLimiter()
        rl.observe(Result("m", "t", status="ok", data={"a": 1}))
        assert rl.backoffs == 0

    def test_errors_still_back_off(self):
        rl = engine.AdaptiveRateLimiter()
        rl.observe(Result("m", "t", status="error", error="boom"))
        assert rl.backoffs == 1


# --------------------------------------------------------------------------- #
#  store
# --------------------------------------------------------------------------- #
class TestStore:
    def test_count_scans_matches_reality(self, tmp_path):
        store = Store(str(tmp_path / "h.db"))
        assert store.count_scans() == 0
        for i in range(3):
            store.save_scan(f"id{i}", "example.com", [], "LOW", 1)
        assert store.count_scans() == 3
        store.close()


# --------------------------------------------------------------------------- #
#  dashboard: CSRF, DNS rebinding, auth
# --------------------------------------------------------------------------- #
@pytest.fixture(scope="module")
def dashboard(tmp_path_factory):
    """A real dashboard on a real socket — these bugs only show up over HTTP."""
    from ghost_eye import webapp

    db = tmp_path_factory.mktemp("dash") / "h.db"
    port = 8911
    thread = threading.Thread(
        target=webapp.serve,
        kwargs={"host": "127.0.0.1", "port": port, "db": str(db),
                "auth_token": "TESTTOKEN", "quiet": True},
        daemon=True)
    thread.start()
    for _ in range(50):                     # wait for the socket to come up
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=1).read()
            break
        except Exception:  # noqa: BLE001
            time.sleep(0.1)
    yield f"http://127.0.0.1:{port}"


def _request(url, *, method="GET", body=None, headers=None):
    req = urllib.request.Request(url, method=method,
                                 data=json.dumps(body).encode() if body else None)
    for key, val in (headers or {}).items():
        req.add_header(key, val)
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            return resp.getcode(), resp.read().decode()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read().decode()


class TestDashboardSecurity:
    TOKEN = {"X-Ghost-Token": "TESTTOKEN"}

    def test_cross_origin_post_is_refused(self, dashboard):
        """Any page the user visits could POST here: a JSON body sent as
        text/plain is a CORS "simple request" and skips the preflight."""
        code, _ = _request(f"{dashboard}/api/scan", method="POST",
                           body={"target": "example.com"},
                           headers={"Content-Type": "text/plain",
                                    "Origin": "https://evil.example",
                                    **self.TOKEN})
        assert code == 403

    def test_cross_origin_cannot_write_an_api_key(self, dashboard):
        code, _ = _request(f"{dashboard}/api/keys", method="POST",
                           body={"name": "virustotal", "value": "attacker"},
                           headers={"Content-Type": "text/plain",
                                    "Origin": "https://evil.example",
                                    **self.TOKEN})
        assert code == 403

    def test_forged_host_header_is_refused(self, dashboard):
        """DNS rebinding: an attacker-controlled name resolving to 127.0.0.1
        would otherwise be same-origin and could read every response."""
        code, _ = _request(f"{dashboard}/api/keys",
                           headers={"Host": "attacker.example", **self.TOKEN})
        assert code == 403

    def test_api_requires_a_token_even_on_localhost(self, dashboard):
        code, _ = _request(f"{dashboard}/api/meta")
        assert code == 401

    def test_valid_token_works(self, dashboard):
        code, body = _request(f"{dashboard}/api/meta", headers=self.TOKEN)
        assert code == 200 and "modules" in json.loads(body)

    def test_same_origin_post_is_allowed(self, dashboard):
        code, _ = _request(f"{dashboard}/api/scope", method="POST",
                           body={"entries": ["example.com"]},
                           headers={"Content-Type": "application/json",
                                    "Origin": dashboard, **self.TOKEN})
        assert code == 200

    def test_non_browser_client_still_works(self, dashboard):
        # curl / scripts send no Origin at all and can't be a confused deputy
        code, _ = _request(f"{dashboard}/api/scope", method="POST",
                           body={"entries": ["example.com"]},
                           headers={"Content-Type": "application/json",
                                    **self.TOKEN})
        assert code == 200

    def test_security_headers_are_present(self, dashboard):
        with urllib.request.urlopen(f"{dashboard}/", timeout=5) as resp:
            headers = {k.lower(): v for k, v in resp.getheaders()}
        assert "frame-ancestors 'none'" in headers["content-security-policy"]
        assert headers["x-frame-options"] == "DENY"
        assert headers["x-content-type-options"] == "nosniff"

    def test_static_traversal_is_refused(self, dashboard):
        code, _ = _request(f"{dashboard}/static/../../../../etc/passwd")
        assert code == 404


class TestDashboardWiring:
    def test_db_flag_is_honoured(self, tmp_path):
        """--db was accepted and then ignored; every store went to the default."""
        from ghost_eye.config import Config
        from ghost_eye.webapp import JobManager

        custom = str(tmp_path / "custom.db")
        assert JobManager(Config(), db=custom).db_path == custom

    def test_finished_jobs_are_pruned(self):
        from ghost_eye.config import Config
        from ghost_eye.webapp import JobManager, MAX_FINISHED_JOBS

        jobs = JobManager(Config())
        for i in range(MAX_FINISHED_JOBS + 25):
            jobs.jobs[f"j{i}"] = {"id": f"j{i}", "status": "done",
                                  "finished": float(i), "started": float(i)}
        with jobs.lock:
            jobs._prune()
        assert len(jobs.jobs) == MAX_FINISHED_JOBS

    def test_running_jobs_are_never_pruned(self):
        from ghost_eye.config import Config
        from ghost_eye.webapp import JobManager, MAX_FINISHED_JOBS

        jobs = JobManager(Config())
        for i in range(MAX_FINISHED_JOBS + 10):
            jobs.jobs[f"r{i}"] = {"id": f"r{i}", "status": "running",
                                  "finished": None, "started": float(i)}
        with jobs.lock:
            jobs._prune()
        assert len(jobs.jobs) == MAX_FINISHED_JOBS + 10
