"""Tests for the wave-4/5 platform features: full-text search, ticketing,
adaptive rate-limit, passive-only classification and the scope editor."""

from __future__ import annotations

from ghost_eye.core import Result


def _sample():
    return [
        Result("subs", "example.com", "ok",
               {"subdomains": ["api.example.com", "admin.example.com"]}),
        Result("headers", "example.com", "ok",
               {"server": "nginx", "x-powered-by": "PHP/7.4",
                "note": "password field found on /login"}),
        Result("cve", "example.com", "ok", {"cves": ["CVE-2021-44228"]}),
    ]


# --- full-text search (feature 48) ----------------------------------------

def test_full_text_search_finds_across_modules():
    from ghost_eye.search import full_text_search
    d = full_text_search(_sample(), "password")
    assert d["count"] >= 1
    assert any("password" in m["snippet"].lower() for m in d["matches"])
    assert "headers" in d["by_module"]


def test_full_text_search_ranks_and_handles_empty():
    from ghost_eye.search import full_text_search
    assert full_text_search(_sample(), "")["count"] == 0
    d = full_text_search(_sample(), "api.example.com")
    assert d["count"] >= 1
    # an exact value match ranks ahead of a mere substring hit
    assert d["matches"][0]["rank"] <= 1
    assert full_text_search(_sample(), "no-such-token-xyz")["count"] == 0


# --- ticketing (feature 60) -----------------------------------------------

def test_build_ticket_jira_and_servicenow():
    from ghost_eye.ticketing import build_ticket
    f = {"module": "cve", "severity": "critical", "field": "CVE-2021-44228",
         "detail": "log4shell", "cve": "CVE-2021-44228", "risk_score": 90}
    j = build_ticket(f, "example.com", "jira", cfg={"JIRA_URL": "https://j",
                     "JIRA_PROJECT": "SEC"})
    assert j["system"] == "jira"
    assert j["payload"]["fields"]["priority"]["name"] == "Highest"
    assert "CVE-2021-44228" in j["payload"]["fields"]["summary"]
    s = build_ticket(f, "example.com", "servicenow",
                     cfg={"SERVICENOW_URL": "https://s"})
    assert s["system"] == "servicenow"
    assert s["payload"]["impact"] == "1"


def test_submit_ticket_dry_run_never_sends():
    from ghost_eye.ticketing import submit_ticket
    f = {"module": "admin", "severity": "high", "detail": "exposed panel"}
    out = submit_ticket(f, "example.com", "jira",
                        cfg={"JIRA_URL": "https://jira.example"}, dry_run=True)
    assert out["ok"] is False and out.get("dry_run") is True
    # the preview must not leak the auth password field
    assert "_auth_pass" not in out["preview"]
    # with no URL configured at all, it also refuses (safe)
    out2 = submit_ticket(f, "example.com", "jira", cfg={})
    assert out2["ok"] is False


# --- adaptive rate-limit (feature 66) -------------------------------------

def test_adaptive_rate_limiter_backs_off_and_recovers():
    from ghost_eye.engine import AdaptiveRateLimiter
    rl = AdaptiveRateLimiter(base=0.0, ceiling=2.0)
    assert rl.snapshot()["delay"] == 0
    rl.observe(Result("m", "t", "error", {}, error="429 too many requests"))
    hot = rl.snapshot()["delay"]
    assert hot > 0 and rl.snapshot()["backoffs"] == 1
    for _ in range(30):
        rl.observe(Result("m", "t", "ok", {"x": 1}))
    assert rl.snapshot()["delay"] < hot


def test_run_scan_accepts_rate_limiter():
    from ghost_eye.engine import AdaptiveRateLimiter, run_scan
    from ghost_eye.core import Context, Module

    class _M(Module):
        id = "t_ok"
        name = "t"

        def run(self, target, ctx):
            return self.ok(target, {"ok": 1})

    rl = AdaptiveRateLimiter()
    out = run_scan([_M()], "example.com", Context(config={}), parallel=1, rate=rl)
    assert len(out) == 1 and out[0].status == "ok"


# --- passive-only classification (feature 71) -----------------------------

def test_passive_only_filters_active_modules():
    from ghost_eye import workflow
    from ghost_eye.core import REGISTRY
    allm = list(REGISTRY.values())
    passive = workflow.passive_only(allm)
    assert 0 < len(passive) < len(allm)
    ids = {m.id for m in passive}
    assert "internetdb" in ids           # passive by id
    assert "nmap" not in ids             # active port scan excluded


# --- scope editor round-trip (feature 72) ---------------------------------

def test_scope_to_lines_round_trip():
    from ghost_eye.scope import Scope
    s = Scope.from_lines(["example.com", "10.0.0.0/8", "1.2.3.4", "# note"])
    lines = s.to_lines()
    assert "example.com" in lines and "1.2.3.4" in lines and "10.0.0.0/8" in lines
    s2 = Scope.from_lines(lines)
    assert s2.allows("api.example.com")[0] is True
    assert s2.allows("evil.test")[0] is False


# --- dedup + backup/restore (features 76, 77) -----------------------------

def test_dedup_findings_collapses_duplicates():
    from ghost_eye.search import dedup_findings
    r = [Result("m1", "x", "ok", {"ip": "1.2.3.4", "port": "80"}),
         Result("m2", "x", "ok", {"ip": "1.2.3.4"}),
         Result("m3", "x", "ok", {"port": "443"})]
    d = dedup_findings(r)
    assert d["total_findings"] == 4 and d["unique"] == 3
    assert d["duplicates_removed"] == 1
    iprow = next(f for f in d["findings"] if f["value"] == "1.2.3.4")
    assert set(iprow["modules"]) == {"m1", "m2"}


def test_store_backup_restore_round_trip(tmp_path):
    from ghost_eye.reporting import Store
    a = Store(str(tmp_path / "a.db"))
    a.save_scan("j1", "example.com",
                [Result("dns", "example.com", "ok", {"A": ["1.2.3.4"]})], "LOW", 20)
    blob = a.export_all()
    a.close()
    assert blob["format"] == "ghosteye-backup" and len(blob["scans"]) == 1
    b = Store(str(tmp_path / "b.db"))
    assert b.import_all(blob) == 1
    assert len(b.recent_scans()) == 1
    b.close()


def test_import_all_rejects_foreign_blob(tmp_path):
    from ghost_eye.reporting import Store
    s = Store(str(tmp_path / "c.db"))
    import pytest
    with pytest.raises(ValueError):
        s.import_all({"format": "something-else"})
    s.close()
