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


def test_entity_risk_scores_flags_critical():
    from ghost_eye.intelligence import entity_risk_scores
    results = [
        _r("hagezi", {"listed": True}),
        _r("tokenhunt", {"secrets": [{"type": "AWS Access Key", "value": "AKIA…XX"}]}),
        _r("spfdmarc", {"spf": "v=spf1 +all", "spf_all": "+all", "dmarc": "", "dmarc_policy": ""}),
    ]
    rs = entity_risk_scores(results, "acme.com")
    assert rs["risk_band"] in ("high", "critical") and rs["risk_score"] >= 50
    assert any("threat feed" in r for r in rs["reasons"])


def test_brand_abuse_report():
    from ghost_eye.intelligence import brand_abuse_report
    results = [
        _r("lookalike", {"registered_lookalikes": [{"domain": "acrne.com", "ips": ["1.2.3.4"]}]}),
        _r("phishdb", {"listed": True}),
    ]
    ba = brand_abuse_report(results, "acme.com")
    assert ba["lookalike_count"] == 1 and "phishdb" in ba["impersonation_feeds"]
    assert ba["signals"] >= 2 and "brand abuse" in ba["verdict"]


def test_export_maltego_csv():
    from ghost_eye.intelligence import export_maltego_csv
    results = [
        _r("crtsh", {"subdomains": ["www.acme.com", "vpn.acme.com"]}),
        _r("certemails", {"emails": ["admin@acme.com"]}),
    ]
    mt = export_maltego_csv(results, "acme.com")
    assert mt["rows"] >= 3 and "maltego.DNSName" in mt["csv"]
    assert "admin@acme.com" in mt["csv"]


def test_cross_target_correlation_same_owner():
    from ghost_eye.intelligence import cross_target_correlation
    a = [_r("trackers", {"analytics_ids": ["UA-12345-1"], "trackers": ["Google Analytics"]})]
    b = [_r("trackers", {"analytics_ids": ["UA-12345-1"], "trackers": ["Google Analytics"]})]
    cc = cross_target_correlation(a, b, "acme.com", "acme.net")
    assert cc["likely_same_owner"] is True
    assert "UA-12345-1" in cc["shared"].get("analytics_ids", [])
