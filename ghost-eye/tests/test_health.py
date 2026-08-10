"""Tests for the module health harness.

The harness runs live network probes, so these tests deliberately do NOT check
that the 551 real modules are healthy — that would make the suite depend on the
whole internet. They check the *classification logic* with mock modules: that
each outcome (ok+data, ok+empty, error, missing-key, wrong-shape) lands in the
right bucket, and that the `expect` shape contract catches the silent-failure
case a plain "returns a Result" check cannot.
"""

from __future__ import annotations

from ghost_eye import health
from ghost_eye.core import Context, Module, Result
from ghost_eye.health import (BROKEN, DEGRADED, HEALTHY, NO_KEY, SKIPPED,
                              canary_for, check_expect, classify,
                              run_health_checks)


class _Mod(Module):
    """A module whose run() returns whatever it was constructed with."""

    def __init__(self, mid, result=None, exc=None, expect=None,
                 target_kind="domain", health_target=None):
        self.id = self.name = mid
        self.category = "Test"
        self.target_kind = target_kind
        self.expect = expect
        self.health_target = health_target
        self._result = result
        self._exc = exc

    def run(self, target, ctx):
        if self._exc:
            raise self._exc
        return self._result


def _ok(mid, data):
    return Result(mid, "example.com", status="ok", data=data)


def _err(mid, msg):
    return Result(mid, "example.com", status="error", error=msg)


# --------------------------------------------------------------------------- #
#  canary target selection
# --------------------------------------------------------------------------- #
class TestCanary:
    def test_per_kind_defaults(self):
        assert canary_for(_Mod("m", target_kind="ip")) == "1.1.1.1"
        assert canary_for(_Mod("m", target_kind="username")) == "torvalds"
        assert canary_for(_Mod("m", target_kind="domain")) == "example.com"

    def test_explicit_override(self):
        assert canary_for(_Mod("m", health_target="acme.test")) == "acme.test"

    def test_opt_out(self):
        assert canary_for(_Mod("m", health_target=False)) is None


# --------------------------------------------------------------------------- #
#  the expect shape contract
# --------------------------------------------------------------------------- #
class TestExpect:
    def test_no_expect_is_none(self):
        assert check_expect(None, {"a": 1}) is None

    def test_required_keys_present(self):
        assert check_expect(["a", "b"], {"a": 1, "b": 2}) is True

    def test_required_key_missing(self):
        assert check_expect(["a", "b"], {"a": 1}) is False

    def test_empty_value_counts_as_missing(self):
        assert check_expect(["a"], {"a": ""}) is False
        assert check_expect(["a"], {"a": "none"}) is False

    def test_nested_key_matches_flattened(self):
        assert check_expect(["issuer"], {"cert": {"issuer": "R3"}}) is True

    def test_predicate(self):
        assert check_expect(lambda d: d.get("n", 0) > 3, {"n": 5}) is True
        assert check_expect(lambda d: d.get("n", 0) > 3, {"n": 1}) is False

    def test_throwing_predicate_is_unhealthy(self):
        assert check_expect(lambda d: d["missing"], {}) is False


# --------------------------------------------------------------------------- #
#  outcome classification
# --------------------------------------------------------------------------- #
class TestClassify:
    def test_ok_with_data_is_healthy(self):
        m = _Mod("m")
        assert classify(m, _ok("m", {"a": 1}), 0.1)["status"] == HEALTHY

    def test_ok_but_empty_is_degraded(self):
        m = _Mod("m")
        assert classify(m, _ok("m", {}), 0.1)["status"] == DEGRADED

    def test_error_is_broken(self):
        m = _Mod("m")
        assert classify(m, _err("m", "connection refused"), 0.1)["status"] == BROKEN

    def test_missing_key_is_not_broken(self):
        m = _Mod("m")
        v = classify(m, _err("m", "requires the OpenAI API key — set the "
                                  "OPENAI_API_KEY env var"), 0.1)
        assert v["status"] == NO_KEY

    def test_wrong_shape_is_broken_even_when_ok(self):
        """The silent-failure case: a 200 with data, but not the data we expect
        — which 'returns a Result' would happily pass."""
        m = _Mod("m", expect=["subdomains", "count"])
        healthy = classify(m, _ok("m", {"subdomains": ["x"], "count": 1}), 0.1)
        stale = classify(m, _ok("m", {"error_page": "cloudflare"}), 0.1)
        assert healthy["status"] == HEALTHY
        assert stale["status"] == BROKEN
        assert "changed" in stale["detail"]

    def test_predicate_shape(self):
        m = _Mod("m", expect=lambda d: any(k.isupper() for k in d))
        assert classify(m, _ok("m", {"A": ["1.2.3.4"]}), 0.1)["status"] == HEALTHY
        assert classify(m, _ok("m", {"lowercase": 1}), 0.1)["status"] == BROKEN


# --------------------------------------------------------------------------- #
#  full harness aggregation (with a stubbed session, still offline)
# --------------------------------------------------------------------------- #
class TestHarness:
    def _run(self, mods):
        # run_health_checks builds its own session; the mock modules ignore it
        return run_health_checks(mods, workers=4, timeout=1)

    def test_buckets_and_percentage(self):
        mods = [
            _Mod("good", _ok("good", {"a": 1})),
            _Mod("good2", _ok("good2", {"b": 2})),
            _Mod("empty", _ok("empty", {})),
            _Mod("dead", _err("dead", "boom")),
            _Mod("keyless", _err("keyless", "requires the API key")),
            _Mod("optout", health_target=False),
        ]
        rep = self._run(mods)
        c = rep["counts"]
        assert c[HEALTHY] == 2 and c[DEGRADED] == 1 and c[BROKEN] == 1
        assert c[NO_KEY] == 1 and c[SKIPPED] == 1
        # health% is over the *checked* set (excludes no_key + skipped) = 4
        assert rep["health_pct"] == 50.0
        assert [b["id"] for b in rep["broken"]] == ["dead"]

    def test_crash_is_caught_as_broken(self):
        rep = self._run([_Mod("crasher", exc=RuntimeError("kaboom"))])
        # execute_module turns a raise into an error Result -> broken
        assert rep["counts"][BROKEN] == 1

    def test_wrong_shape_module_is_broken(self):
        mods = [_Mod("stale", _ok("stale", {"nope": 1}), expect=["count"])]
        rep = self._run(mods)
        assert rep["counts"][BROKEN] == 1 and rep["broken"][0]["id"] == "stale"


# --------------------------------------------------------------------------- #
#  the real modules that declare a contract are consistent with themselves
# --------------------------------------------------------------------------- #
class TestRealContracts:
    def test_declared_modules_have_sane_expect(self):
        import ghost_eye.modules  # noqa: F401
        from ghost_eye.core import REGISTRY
        declared = [m for m in REGISTRY.values()
                    if getattr(m, "expect", None) is not None]
        assert len(declared) >= 7            # the initial set
        for m in declared:
            exp = m.expect
            assert callable(exp) or (isinstance(exp, list) and all(
                isinstance(k, str) for k in exp))
