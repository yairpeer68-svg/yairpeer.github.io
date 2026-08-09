"""Tests for Features batch 1 — advanced analysis (no network)."""

from __future__ import annotations

from ghost_eye.core import Result
from ghost_eye.intelligence import (
    email_security_audit, supply_chain_map, attack_surface_techniques,
    secrets_report, investigation_narrative,
)


def _r(module, data):
    return Result(module, "acme.com", "ok", data)


def test_email_security_audit_grades_weak_posture():
    results = [
        _r("spfdmarc", {"spf": "v=spf1 +all", "spf_all": "+all", "spf_lookups": 12,
                        "dmarc": "", "dmarc_policy": ""}),
        _r("dkimscan", {"count": 0, "weak_keys": []}),
        _r("dnsseccaa", {"dnssec_enabled": False, "caa_present": False}),
        _r("mxintel", {"mx_count": 1, "mail_providers": ["Microsoft 365"]}),
    ]
    a = email_security_audit(results)
    assert a["grade"] == "F" and a["spoofable"] is True
    assert "no DMARC record" in a["issues"] and a["score"] < 50


def test_email_security_audit_strong():
    results = [
        _r("spfdmarc", {"spf": "v=spf1 -all", "spf_all": "-all", "spf_lookups": 3,
                        "dmarc": "v=DMARC1; p=reject", "dmarc_policy": "reject"}),
        _r("dkimscan", {"count": 2, "weak_keys": []}),
        _r("dnsseccaa", {"dnssec_enabled": True, "caa_present": True}),
    ]
    a = email_security_audit(results)
    assert a["grade"] in ("A", "B") and a["spoofable"] is False


def test_supply_chain_map_aggregates_vendors():
    results = [
        _r("trackers", {"trackers": ["Google Analytics", "Meta Pixel"]}),
        _r("jsassets", {"external_js_domains": ["cdn.jsdelivr.net"]}),
        _r("spfvendors", {"vendors": ["SendGrid"]}),
        _r("mxintel", {"mail_providers": ["Google Workspace"]}),
        _r("idpfinger", {"vendor": "Okta"}),
    ]
    sc = supply_chain_map(results)
    assert sc["unique_vendors"] >= 5
    assert "Okta" in sc["vendors"] and "SendGrid" in sc["vendors"]
    assert "identity-provider" in sc["by_category"]


def test_attack_surface_techniques_maps_attack():
    results = [
        _r("crtsh", {"subdomains": ["a.acme.com"]}),
        _r("wpusers", {"usernames": ["admin"]}),
        _r("lookalike", {"count": 1}),
        _r("githubemail", {"emails": [{"email": "x@acme.com"}]}),
    ]
    at = attack_surface_techniques(results)
    tids = {t["technique"] for t in at["techniques"]}
    assert "T1590" in tids and "T1598" in tids and "T1589" in tids
    assert at["tactic"].startswith("Reconnaissance")


def test_secrets_report_prioritises_critical():
    ghp = "ghp_" + "A" * 36
    results = [
        _r("tokenhunt", {"secrets": [{"type": "AWS Access Key", "value": "AKIA…XX"},
                                     {"type": "GitHub Token", "value": "ghp_…YY"}]}),
        _r("gitexposed", {"git_exposed": True, "remotes": ["https://***@github.com/x.git"]}),
        _r("jssecrets", {"count": 3}),
    ]
    sr = secrets_report(results)
    assert sr["critical"] >= 3 and "CRITICAL" in sr["verdict"]
    assert ghp not in str(sr)   # only redacted values flow through


def test_investigation_narrative_reads_naturally():
    results = [
        _r("crtsh", {"subdomains": ["a.acme.com", "b.acme.com"]}),
        _r("spfdmarc", {"spf": "v=spf1 +all", "spf_all": "+all", "dmarc": "",
                        "dmarc_policy": ""}),
        _r("tokenhunt", {"secrets": [{"type": "AWS Access Key", "value": "AKIA…XX"}]}),
        _r("hagezi", {"listed": True}),
    ]
    n = investigation_narrative(results, "acme.com")
    assert "subdomain" in n["narrative"].lower()
    assert "spoofable" in n["narrative"].lower()
    assert any("threat feed" in b.lower() for b in n["bullet_points"])
