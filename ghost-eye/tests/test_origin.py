"""Tests for origin verification.

Finding candidate IPs is the easy half; proving one is the origin is the half
that matters. These pin that a real origin is confirmed even when its dynamic
content differs, that an unrelated page or a dead host is rejected, and that the
originhunt module now classifies edge IPs against all 14 providers (the bug
where a private 4-provider table let Imperva/Sucuri IPs pass as origins).
"""

from __future__ import annotations

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye.core import REGISTRY
from ghost_eye.origin import (baseline_fingerprint, compare, fingerprint,
                              verify_candidate, verify_origins)


class _Resp:
    def __init__(self, code, text="", server="", url=""):
        self.status_code = code
        self.text = text
        self.url = url
        self.headers = {"Server": server} if server else {}


_REAL = ('<html><head><title>Acme Corp</title></head><body>Welcome to Acme. '
         'csrf_token:"abc123def456ghi" ts:1700000000 ' + "content " * 400
         + '</body></html>')
# same site, different rotating token + timestamp (what a real re-fetch looks like)
_REAL_VAR = ('<html><head><title>Acme Corp</title></head><body>Welcome to Acme. '
             'csrf_token:"zzz999www000qqq" ts:1700009999 ' + "content " * 400
             + '</body></html>')
_OTHER = ('<html><head><title>Welcome to nginx!</title></head><body>'
          'It works! Default page.</body></html>')


class TestFingerprintCompare:
    def test_dynamic_content_does_not_break_a_match(self):
        base = fingerprint(_Resp(200, _REAL, "cloudflare"))
        cand = fingerprint(_Resp(200, _REAL_VAR, "Apache"))
        result = compare(base, cand)
        assert result["verdict"] == "confirmed"
        assert result["similarity"] >= 0.9

    def test_unrelated_page_is_rejected(self):
        base = fingerprint(_Resp(200, _REAL, "cloudflare"))
        cand = fingerprint(_Resp(200, _OTHER, "nginx"))
        assert compare(base, cand)["verdict"] == "rejected"

    def test_dead_candidate_is_rejected(self):
        base = fingerprint(_Resp(200, _REAL))
        for code in (0, 502, 503, 504):
            assert compare(base, fingerprint(_Resp(code, "")))["verdict"] == "rejected"

    def test_title_match_rescues_a_borderline_body(self):
        base = fingerprint(_Resp(200,
                                 "<title>Acme Corp</title>" + "a " * 300))
        # same title, body differs enough to be 'possible' not 'confirmed'
        cand = fingerprint(_Resp(200,
                                 "<title>Acme Corp</title>" + "a b " * 200))
        verdict = compare(base, cand)["verdict"]
        assert verdict in ("confirmed", "possible")

    def test_empty_inputs_are_safe(self):
        assert compare({}, {})["verdict"] == "unknown"


class TestVerifyPipeline:
    def _session(self):
        class _S:
            headers: dict = {}

            def get(self, url, **kw):
                host = (kw.get("headers") or {}).get("Host", "")
                if "acme.com" in url and not host:          # baseline via CDN
                    return _Resp(200, _REAL, "cloudflare")
                if "1.2.3.4" in url:                        # the true origin
                    return _Resp(200, _REAL_VAR, "Apache")
                if "5.6.7.8" in url:                        # unrelated site
                    return _Resp(200, _OTHER, "nginx")
                if "9.9.9.9" in url:                        # dead
                    return _Resp(502, "")
                return _Resp(404, "")
        return _S()

    def test_confirms_the_real_origin_only(self):
        out = verify_origins(self._session(), "acme.com",
                             ["1.2.3.4", "5.6.7.8", "9.9.9.9"])
        assert out["confirmed_origins"] == ["1.2.3.4"]
        assert "5.6.7.8" not in out["confirmed_origins"]
        assert out["candidates_checked"] == 3

    def test_no_baseline_means_no_false_confirmation(self):
        class _Dead:
            headers: dict = {}

            def get(self, url, **kw):
                raise OSError("unreachable")
        out = verify_origins(_Dead(), "acme.com", ["1.2.3.4"])
        assert out["confirmed_origins"] == []
        assert "nothing to compare" in out["note"]

    def test_single_candidate_verify(self):
        base = fingerprint(_Resp(200, _REAL, "cloudflare"))
        res = verify_candidate(self._session(), "1.2.3.4", "acme.com", base)
        assert res["verdict"] == "confirmed" and res["ip"] == "1.2.3.4"


class TestOriginhuntClassifier:
    """The bug fix: originhunt must classify edge IPs against all 14 providers,
    not the old private 4-provider table."""

    def test_all_providers_are_recognised_as_cdn(self):
        from ghost_eye.modules.newscan_wave import _cdn_of
        assert _cdn_of("104.16.1.1") == "Cloudflare"
        assert _cdn_of("107.154.1.1") == "Imperva/Incapsula"   # was missed
        assert _cdn_of("185.93.228.1") == "Sucuri"             # was missed
        assert _cdn_of("13.107.21.200") == "Azure Front Door"  # was missed
        assert _cdn_of("151.139.1.1") == "StackPath/Highwinds"  # was missed

    def test_real_ip_is_not_flagged_as_cdn(self):
        from ghost_eye.modules.newscan_wave import _cdn_of
        assert _cdn_of("93.184.216.34") == ""

    def test_originhunt_still_registers_and_runs_offline(self):
        from ghost_eye.core import Context

        class _S:
            headers: dict = {}

            def get(self, *a, **k):
                raise OSError("offline")
        res = REGISTRY["originhunt"].run("example.com",
                                         Context(config={}, session=_S(), timeout=2))
        assert res.status in ("ok", "empty", "error")
