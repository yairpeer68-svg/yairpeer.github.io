"""Behavioural tests — assert modules and workflows produce the *correct*
result, not merely that they don't crash. All offline and deterministic."""

from __future__ import annotations

import base64
import json
from types import SimpleNamespace

import pytest

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye.core import REGISTRY, Result


# --------------------------------------------------------------------------- #
#  test doubles
# --------------------------------------------------------------------------- #
class FakeResp:
    def __init__(self, text="", headers=None, status=200, payload=None, ctype=None):
        self.text = text
        self.content = text.encode()
        self.status_code = status
        self.headers = headers or {"Content-Type": ctype or "text/html"}
        self._payload = payload

    def json(self):
        if self._payload is not None:
            return self._payload
        return json.loads(self.text or "{}")

    def raise_for_status(self):
        return None


class FakeSession:
    """Returns responses from a routing function (url -> FakeResp)."""

    def __init__(self, router):
        self.router = router
        self.headers = {}

    def _go(self, url, **k):
        return self.router(url, k)

    get = post = head = put = options = request = _go


def ctx(session=None, timeout=3, threads=4):
    return SimpleNamespace(session=session, timeout=timeout, threads=threads,
                           config=SimpleNamespace(get=lambda *a, **k: None,
                                                  api_key=lambda n: None))


# --------------------------------------------------------------------------- #
#  DKIM key-strength (DER parser) — deterministic real keys
# --------------------------------------------------------------------------- #
_RSA_1024 = ("MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDWkZfXQItaKypao4hNxaWwl+uo"
             "rRS1+y9r5pw7K+8nTHerXxlvlrj1W2NnG4B11b+brZBSwLrAP1/EhW4fBbqICp7"
             "tZwaq9WqZt34Vcn5RtSc+opNTr+nN5vcUj5gKrmJgGP15dpXx80QIqbr74YNUac"
             "7I7jW1eeeRhBwbrnU8gQIDAQAB")
_RSA_2048 = ("MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzIYNMQU+qR6TluuBfs0y"
             "d2swSNpKBKWg5kRwdos5m464O/Kvg7hheF6uP2XQTFir9Dtrk/vowHNWtOwpVTM"
             "qY6rfpO2i1jEY29ZxWtY74IjDiyeNxOYt5XkSc5ASOU2S09isHvTs9cuFyVXn7h"
             "ISEAEFJF5Q+YPOkyWD7PWI6VS9XBmzvNgeSXXaH9q7CrLBni7Ynl4F3JK4zk4vN"
             "OGMzZUP9nmFYWjE+m+wmpuA7H08sj010MiXEuIkT1sq6ArTcQIha00WEB3gE1UH"
             "9gzB95tK1cbUlYZHOEzv22OfIzgyl/DIgWZJnYmX5ISpBBTGxwKG266clPPFpYG"
             "QwU6RxQIDAQAB")


def test_der_parser_sizes_rsa_moduli():
    from ghost_eye.modules.email_v3 import _der_int_bit_lengths
    assert max(_der_int_bit_lengths(base64.b64decode(_RSA_1024))) == 1024
    assert max(_der_int_bit_lengths(base64.b64decode(_RSA_2048))) == 2048


def test_dkim_pubkey_bits_and_verdict():
    from ghost_eye.modules.email_v3 import DkimStrength
    d = DkimStrength()
    assert d._pubkey_bits(f"v=DKIM1; k=rsa; p={_RSA_1024}") == 1024
    assert d._pubkey_bits(f"v=DKIM1; k=rsa; p={_RSA_2048}") == 2048
    assert d._pubkey_bits("v=DKIM1; k=rsa; p=") == 0


# --------------------------------------------------------------------------- #
#  Exploit intelligence aggregation
# --------------------------------------------------------------------------- #
def _exploit_router(url, _k):
    if "services.nvd.nist.gov" in url:
        return FakeResp(payload={"vulnerabilities": [{"cve": {
            "metrics": {"cvssMetricV31": [{"cvssData": {
                "baseScore": 9.8, "baseSeverity": "CRITICAL"}}]},
            "references": [{"url": "http://x/poc", "tags": ["Exploit"]}]}}]})
    if "exploit-db.com" in url:
        return FakeResp(payload={"data": [{"id": "50123"}]},
                        ctype="application/json")
    if "api.github.com/advisories" in url:
        return FakeResp(payload=[{"ghsa_id": "GHSA-x", "severity": "critical",
                                  "html_url": "http://g/x"}])
    if "cve.circl.lu" in url:
        return FakeResp(payload={"metasploit": [{"title": "exploit/linux/x"}]})
    if "packetstorm" in url:
        return FakeResp(text='<a href="/files/1/x.txt">x</a>')
    return FakeResp(status=404, text="{}")


def test_check_cve_flags_public_exploit():
    from ghost_eye.modules.exploit_intel import check_cve
    r = check_cve("CVE-2021-23017", FakeSession(_exploit_router), timeout=2)
    assert r["exploit_available"] is True
    assert r["weaponised"] is True
    assert r["verdict"] == "EXPLOIT PUBLIC"
    assert r["cvss"] == 9.8 and r["severity"] == "CRITICAL"
    assert "50123" in r["sources"]["exploit_db"]


def test_check_cve_no_exploit_is_graceful():
    from ghost_eye.modules.exploit_intel import check_cve
    r = check_cve("CVE-2000-0001", FakeSession(lambda u, k: FakeResp(status=404)),
                  timeout=2)
    assert r["exploit_available"] is False
    assert r["verdict"] in ("no public exploit found", "advisory only")


def test_extract_cves_from_results():
    from ghost_eye.modules.exploit_intel import extract_cves
    res = [Result("p", "x", "ok", {"ports": {"443": {"vulns":
           ["CVE-2021-23017", "cve-2019-0211"]}}})]
    got = extract_cves(res)
    assert got == ["CVE-2021-23017", "CVE-2019-0211"]


# --------------------------------------------------------------------------- #
#  Inventory / deep-scan scoping (regression for the junk-target bug)
# --------------------------------------------------------------------------- #
def test_collect_assets_only_subdomains_and_ips():
    from ghost_eye import inventory as inv
    res = [
        Result("Related", "acme.com", "ok",
               {"correlation_signals": {"favicon": None}}),
        Result("Dorks", "acme.com", "ok",
               {"q": ["https://www.google.com/search?q=site%3Apastebin.com"]}),
        Result("Subs", "acme.com", "ok",
               {"subdomains": ["api.acme.com", "mail.acme.com"]}),
        Result("DNS", "acme.com", "ok", {"a": ["1.2.3.4"]}),
    ]
    a = inv.collect_assets(res, "acme.com", None, 25)
    assert set(a["hosts"]) == {"api.acme.com", "mail.acme.com"}
    assert "1.2.3.4" in a["ips"]
    # none of the junk leaks into the deep-scan target list
    for h in a["hosts"]:
        assert "favicon" not in h and not h.startswith("3a")
        assert h not in ("pastebin.com", "github.com", "www.google.com")


def test_build_inventory_decodes_and_drops_key_hosts():
    from ghost_eye import inventory as inv
    res = [Result("Related", "acme.com", "ok",
                  {"correlation_signals": {"favicon": None},
                   "q": "site%3Agithub.com"})]
    hosts = inv.build_inventory(res, "acme.com")["hosts"]
    assert "correlation_signals.favicon" not in hosts
    assert not any(h.startswith("3a") for h in hosts)


# --------------------------------------------------------------------------- #
#  Workflow: scoring, CI gate, notifications
# --------------------------------------------------------------------------- #
def _high_findings():
    return [Result("CORS", "x", "ok", {"acao": "reflects origin WITH credentials"}),
            Result("DB", "x", "ok", {"redis": "OPEN with no auth"})]


def test_ci_gate_thresholds():
    from ghost_eye import workflow
    res = _high_findings()
    assert workflow.ci_gate(res, "high")["exit_code"] == 1
    assert workflow.ci_gate(res, "critical")["exit_code"] == 1   # a critical exists
    clean = [Result("info", "x", "ok", {"note": "present"})]
    assert workflow.ci_gate(clean, "high")["exit_code"] == 0


def test_attack_score_grades():
    from ghost_eye import workflow
    a = workflow.attack_score(_high_findings())
    assert a["grade"] in ("A+", "A", "B", "C", "D", "F")
    assert 0 <= a["normalized"] <= 100


def test_notify_service_detection():
    from ghost_eye import workflow
    seen = {}

    class S:
        def post(self, url, json=None, data=None, timeout=None):
            seen["url"] = url
            seen["keys"] = list((json or data or {}).keys())
            return SimpleNamespace(status_code=200)

    assert workflow.notify(_high_findings(), "x",
                           "https://hooks.slack.com/services/x", session=S())
    assert "text" in seen["keys"]
    workflow.notify(_high_findings(), "x", "https://discord.com/api/webhooks/1",
                    session=S())
    assert "content" in seen["keys"]


# --------------------------------------------------------------------------- #
#  Executive report rendering
# --------------------------------------------------------------------------- #
def test_exec_report_html_structure(tmp_path):
    from ghost_eye import reporting_ext
    res = _high_findings()
    p = reporting_ext.export_exec_report(res, str(tmp_path / "r.html"), "acme.com",
                                         lang="he")
    html = open(p, encoding="utf-8").read()
    assert "<svg" in html                     # attack-surface graph rendered
    assert 'dir="rtl"' in html                # Hebrew RTL
    assert "acme.com" in html


# --------------------------------------------------------------------------- #
#  Config API-key management round-trip
# --------------------------------------------------------------------------- #
def test_config_api_key_roundtrip(tmp_path, monkeypatch):
    monkeypatch.setenv("GHOSTEYE_CONFIG", str(tmp_path / "cfg.ini"))
    from ghost_eye.config import Config
    c = Config()
    assert c.api_key("virustotal") is None
    c.set_api_key("virustotal", "SECRET123")
    assert Config().api_key("virustotal") == "SECRET123"     # persisted to file


# --------------------------------------------------------------------------- #
#  Persistent error log
# --------------------------------------------------------------------------- #
def test_record_error_writes_file(tmp_path, monkeypatch):
    monkeypatch.setenv("GHOSTEYE_ERRORLOG", str(tmp_path / "errors.log"))
    from ghost_eye import core
    try:
        raise RuntimeError("kaboom")
    except RuntimeError as exc:
        core.record_error("module test", "example.com", exc)
    text = (tmp_path / "errors.log").read_text()
    assert "module test" in text and "kaboom" in text and "RuntimeError" in text


# --------------------------------------------------------------------------- #
#  clean_host validation
# --------------------------------------------------------------------------- #
@pytest.mark.parametrize("raw,expected", [
    ("https://Example.com/path?q=1", "example.com"),
    ("http://example.com:8443", "example.com"),
    ("192.0.2.10", "192.0.2.10"),
])
def test_clean_host_normalises(raw, expected):
    from ghost_eye.core import clean_host
    assert clean_host(raw) == expected


@pytest.mark.parametrize("bad", ["", "not a host", "correlation_signals.favicon",
                                 "a b c"])
def test_clean_host_rejects_junk(bad):
    from ghost_eye.core import clean_host
    with pytest.raises(ValueError):
        clean_host(bad)


# --------------------------------------------------------------------------- #
#  Web AppSec detection behaviour
# --------------------------------------------------------------------------- #
def test_cspbypass_flags_unsafe_inline_and_gadget():
    def router(url, k):
        return FakeResp(headers={
            "Content-Security-Policy": "script-src 'unsafe-inline' ajax.googleapis.com",
            "Content-Type": "text/html"})
    r = REGISTRY["cspbypass"].run("example.com", ctx(FakeSession(router)))
    assert r.status == "ok"
    assert "ajax.googleapis.com" in r.data["bypass_gadget_cdns"]
    assert any("unsafe-inline" in w for w in r.data["weaknesses"])
    assert r.data["risk"] == "high"


def test_lfisurface_finds_file_param():
    def router(url, k):
        return FakeResp(text='<a href="/view?file=index.php">x</a>')
    r = REGISTRY["lfisurface"].run("example.com", ctx(FakeSession(router)))
    assert r.status == "ok"
    assert "file" in r.data["suspect_parameters"]


# --------------------------------------------------------------------------- #
#  CLI helpers (regression: --rollup/--deep must not NameError)
# --------------------------------------------------------------------------- #
def test_print_rollup_runs():
    from ghost_eye import cli
    res = [Result("Tech fingerprint", "api.acme.com", "ok", {"server": "nginx"}),
           Result("TCP port scan", "api.acme.com", "ok",
                  {"open_ports": {"443/https": "open"}})]
    cli._print_rollup(res, "acme.com")   # prints via Console, must not raise


# --------------------------------------------------------------------------- #
#  More module behaviour
# --------------------------------------------------------------------------- #
def test_protopollute_flags_vulnerable_jquery():
    def router(url, k):
        if url.endswith(".js"):
            return FakeResp(text="$.extend(true,{}); __proto__")
        return FakeResp(text='<script src="/j.js"></script> jquery-3.2.1.min.js')
    r = REGISTRY["protopollute"].run("example.com", ctx(FakeSession(router)))
    assert any("jquery" in v for v in r.data["vulnerable_libraries"])
    assert r.data["risk"] in ("high", "medium")


def test_promptinject_scores_llm_surface():
    def router(url, k):
        return FakeResp(text="AI assistant powered by GPT-4 "
                             '<textarea name="chatPrompt"></textarea>')
    r = REGISTRY["promptinject"].run("example.com", ctx(FakeSession(router)))
    assert r.data["surface_score"] >= 2
    assert r.data["prompt_input_fields"] >= 1


def test_osfp_maps_ttl_to_os():
    from ghost_eye import core, modules  # noqa: F401
    m = REGISTRY["osfp"]
    import ghost_eye.modules.network_v4 as nv
    # 64 -> *nix, 128 -> Windows via the module's TTL map
    fam = dict((init, label) for init, label in m._TTL_MAP)
    assert "Linux" in fam[64] and "Windows" in fam[128]
    assert nv  # module import sanity


def test_ipmi_reports_not_exposed_on_no_reply(monkeypatch):
    import socket as _s

    class _Sock:
        def __init__(self, *a, **k): pass
        def settimeout(self, *a, **k): pass
        def sendto(self, *a, **k): pass
        def recvfrom(self, *a, **k): raise _s.timeout()
        def close(self): pass
    monkeypatch.setattr(_s, "socket", _Sock)
    monkeypatch.setattr(_s, "gethostbyname", lambda h: "192.0.2.10")
    r = REGISTRY["ipmi"].run("192.0.2.10", ctx())
    assert r.data["exposed"] is False


def test_mxfingerprint_identifies_google(monkeypatch):
    import ghost_eye.modules.email_v3 as e

    class _RR:
        preference = 10
        exchange = "aspmx.l.google.com."
    monkeypatch.setattr(e, "_resolver",
                        lambda ctx: SimpleNamespace(resolve=lambda h, t: [_RR()]))
    r = REGISTRY["mxfingerprint"].run("example.com", ctx())
    assert "Google Workspace" in r.data["gateway_detected"]


def test_compliance_check_frameworks():
    from ghost_eye import workflow
    res = _high_findings()
    rep = workflow.compliance_check(res, "owasp_top10")
    assert isinstance(rep, dict) and rep


def test_exec_report_english_is_ltr(tmp_path):
    from ghost_eye import reporting_ext
    p = reporting_ext.export_exec_report(_high_findings(),
                                         str(tmp_path / "en.html"), "acme.com",
                                         lang="en")
    html = open(p, encoding="utf-8").read()
    assert 'dir="ltr"' in html and "<svg" in html


def test_notify_and_ci_gate_empty_url_and_clean():
    from ghost_eye import workflow
    assert workflow.notify(_high_findings(), "x", "") is False   # no URL -> no-op
    clean = [Result("info", "x", "ok", {"note": "advertised"})]
    assert workflow.ci_gate(clean, "critical")["passed"] is True
