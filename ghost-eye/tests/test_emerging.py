"""Tests for the emerging / freshly-disclosed vulnerability early-warning.

This is deliberately NOT a zero-day *discovery* engine — it surfaces
vulnerabilities disclosed so recently they may pre-date NVD, and cross-checks
them against the target's advertised stack. The tests pin the two things that
make it useful rather than a firehose: the freshness window, and the
correlation that says "this just-dropped vuln affects software you run".
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

from ghost_eye.core import Context, REGISTRY
import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye.modules.emerging import _age_days, _matches


def _now():
    return datetime.now(timezone.utc).isoformat()


def _days_ago(n):
    return (datetime.now(timezone.utc) - timedelta(days=n)).isoformat()


class TestHelpers:
    def test_age_days(self):
        assert _age_days(_now()) == 0
        assert _age_days(_days_ago(10)) == 10
        assert _age_days("") == 10_000
        assert _age_days("garbage") == 10_000

    def test_matches_is_word_boundaried(self):
        assert _matches("Remote code exec in nginx", ["nginx"]) == ["nginx"]
        # 'ng' must not match inside 'nginx'; 'apache' absent
        assert _matches("nginx bug", ["ng", "apache"]) == []
        assert _matches("Apache Struts RCE", ["struts"]) == ["struts"]


class _Resp:
    def __init__(self, j=None, text="", headers=None, code=200):
        self._j, self.text, self.headers, self.status_code = j, text, headers or {}, code

    def json(self):
        return self._j


class _Sess:
    """Serves a target fetch (Server: nginx), a GHSA feed and a KEV feed."""

    def __init__(self, ghsa, kev, server="nginx/1.18.0"):
        self.headers = {}
        self._ghsa, self._kev, self._server = ghsa, kev, server

    def get(self, url, **kw):
        if "advisories" in url:
            return _Resp(j=self._ghsa)
        if "known_exploited" in url:
            return _Resp(j={"vulnerabilities": self._kev})
        return _Resp(text="<html></html>", headers={"Server": self._server})


class _Cfg:
    def get(self, _k, d=None):
        return d


def _run(ghsa, kev, server="nginx/1.18.0"):
    ctx = Context(config=_Cfg(), session=_Sess(ghsa, kev, server), timeout=5)
    return REGISTRY["freshvulns"].run("acme.com", ctx).data


class TestFreshnessWindow:
    def test_old_disclosures_are_excluded(self):
        ghsa = [
            {"ghsa_id": "GHSA-new", "cve_id": "CVE-2025-1", "severity": "high",
             "summary": "fresh nginx bug", "published_at": _days_ago(3),
             "vulnerabilities": [{"package": {"name": "nginx"}}]},
            {"ghsa_id": "GHSA-old", "cve_id": "CVE-2019-1", "severity": "critical",
             "summary": "ancient nginx bug", "published_at": _days_ago(900),
             "vulnerabilities": []},
        ]
        d = _run(ghsa, [])
        assert d["fresh_advisories"] == 1          # the 900-day-old one dropped

    def test_kev_recent_only(self):
        kev = [
            {"cveID": "CVE-2025-9", "vendorProject": "Nginx", "product": "nginx",
             "vulnerabilityName": "nginx rce", "dateAdded": _days_ago(2)},
            {"cveID": "CVE-2010-9", "vendorProject": "Old", "product": "old",
             "vulnerabilityName": "old", "dateAdded": _days_ago(4000)},
        ]
        d = _run([], kev)
        assert d["kev_recent_count"] == 1


class TestCorrelation:
    def _feeds(self):
        ghsa = [
            {"ghsa_id": "GHSA-a", "cve_id": "CVE-2025-0001", "severity": "critical",
             "summary": "Remote code execution in nginx request smuggling",
             "published_at": _now(),
             "vulnerabilities": [{"package": {"ecosystem": "generic", "name": "nginx"}}]},
            {"ghsa_id": "GHSA-b", "cve_id": "CVE-2025-0002", "severity": "high",
             "summary": "XSS in some-unrelated-lib", "published_at": _now(),
             "vulnerabilities": [{"package": {"ecosystem": "npm", "name": "unrelated"}}]},
        ]
        kev = [{"cveID": "CVE-2025-7777", "vendorProject": "Nginx",
                "product": "nginx", "vulnerabilityName": "nginx path traversal",
                "dateAdded": _now(), "knownRansomwareCampaignUse": "Known"}]
        return ghsa, kev

    def test_matches_the_targets_stack_only(self):
        d = _run(*self._feeds())
        assert d["target_products"] == ["nginx"]
        cves = {a["cve"] for a in d["affecting_your_stack"]}
        assert "CVE-2025-0001" in cves          # the nginx RCE
        assert "CVE-2025-7777" in cves          # the actively-exploited KEV item
        assert "CVE-2025-0002" not in cves      # unrelated npm lib

    def test_actively_exploited_is_labelled(self):
        d = _run(*self._feeds())
        kev_hit = next(a for a in d["affecting_your_stack"]
                       if a["cve"] == "CVE-2025-7777")
        assert kev_hit["severity"] == "kev-actively-exploited"

    def test_no_stack_match_is_not_a_firehose(self):
        ghsa, kev = self._feeds()
        d = _run(ghsa, kev, server="Apache/2.4")   # target runs apache, not nginx
        assert d["affecting_count"] == 0
        assert d["affecting_your_stack"] == "none matched your products"


class TestResilience:
    def test_source_errors_are_reported_not_fatal(self):
        # GHSA returns a non-list (error), KEV empty
        class _BadSess(_Sess):
            def get(self, url, **kw):
                if "advisories" in url:
                    return _Resp(code=403, j=None)
                if "known_exploited" in url:
                    return _Resp(code=503, j=None)
                return _Resp(text="", headers={"Server": "nginx"})
        ctx = Context(config=_Cfg(), session=_BadSess(None, None), timeout=5)
        d = REGISTRY["freshvulns"].run("acme.com", ctx).data
        assert d["source_errors"] != "none"
        assert d["window_days"] == 21              # still returns a shaped result

    def test_declares_a_health_expect(self):
        assert REGISTRY["freshvulns"].expect == ["window_days"]
