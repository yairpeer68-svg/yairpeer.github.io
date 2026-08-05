"""Tests for the infrastructure features: custom alert rules (49),
encryption at rest (79), offline CVE mirror (78), distributed scanning (75)."""

from __future__ import annotations

import threading


# --- 49 custom alert rules -------------------------------------------------

def test_alert_rules_default_fires_on_any_change():
    from ghost_eye.alert_rules import evaluate
    v = evaluate({"new_subdomains": ["a.x.com"], "new_cves": []})
    assert v["fire"] is True and v["severity"] == "medium"


def test_alert_rules_min_severity_filters_low_events():
    from ghost_eye.alert_rules import evaluate
    diff = {"new_subdomains": ["a"], "new_cves": []}
    assert evaluate(diff, {"min_severity": "critical"})["fire"] is False
    diff2 = {"new_cves": ["CVE-1"], "new_subdomains": ["a"]}
    v = evaluate(diff2, {"min_severity": "critical"})
    assert v["fire"] is True and v["severity"] == "critical"


def test_alert_rules_ignore_and_threshold_and_targets():
    from ghost_eye.alert_rules import evaluate
    diff = {"new_subdomains": ["a", "b"]}
    assert evaluate(diff, {"ignore": ["new_subdomains"]})["fire"] is False
    assert evaluate(diff, {"min_events": 5})["fire"] is False
    assert evaluate(diff, {"only_targets": ["other"]}, "x")["fire"] is False
    assert evaluate(diff, {"only_targets": ["x"]}, "x")["fire"] is True
    assert evaluate(diff, {"enabled": False})["fire"] is False


# --- 79 encryption at rest -------------------------------------------------

def test_secure_store_graceful_and_roundtrip():
    from ghost_eye import secure_store as ss
    # passthrough must always work, even when crypto is unavailable
    assert ss.maybe_encrypt("secret") in ("secret",) or ss.is_encrypted(ss.maybe_encrypt("secret"))
    assert ss.maybe_decrypt("plain") == "plain"
    if ss.available():
        tok = ss.encrypt("s3cr3t", "pw")
        assert ss.is_encrypted(tok)
        assert ss.decrypt(tok, "pw") == "s3cr3t"
        assert ss.decrypt(tok, "wrong") is None          # wrong key fails closed
        assert ss.encrypt("a", "pw") != ss.encrypt("a", "pw")  # random salt


def test_config_key_roundtrip_regardless_of_encryption(tmp_path, monkeypatch):
    monkeypatch.setenv("GHOSTEYE_CONFIG", str(tmp_path / "c.ini"))
    monkeypatch.setenv("GHOSTEYE_NO_KEYRING", "1")
    from ghost_eye.config import Config, _ENV_MAP
    c = Config()
    name = list(_ENV_MAP)[0]
    c.set_api_key(name, "my-secret-key")
    assert Config().api_key(name) == "my-secret-key"


# --- 78 offline CVE mirror -------------------------------------------------

def test_cve_mirror_put_get_and_stats():
    from ghost_eye.cve_mirror import CveMirror
    m = CveMirror(":memory:")
    m.put("CVE-2021-44228", {"cve": "CVE-2021-44228", "cvss": 10.0,
                             "known_exploited": True})
    assert m.get("CVE-2021-44228")["cvss"] == 10.0
    assert m.get("CVE-2000-0001") is None
    assert m.stats()["cves"] == 1
    m.close()


def test_cve_mirror_seed_kev_and_import_feed(tmp_path):
    from ghost_eye.cve_mirror import CveMirror

    class _R:
        status_code = 200

        def json(self):
            return {"vulnerabilities": [
                {"cveID": "CVE-2019-0001", "dueDate": "2022-01-01",
                 "knownRansomwareCampaignUse": "Known"}]}

    class _S:
        def get(self, url, timeout=30):
            return _R()

    m = CveMirror(":memory:")
    assert m.seed_kev(_S()) == 1
    assert m.get("CVE-2019-0001")["known_exploited"] is True
    feed = tmp_path / "feed.json"
    feed.write_text('[{"cve":"CVE-2020-1","cvss":7.5,"severity":"high"}]')
    assert m.import_feed(str(feed)) == 1
    assert m.get("CVE-2020-1")["cvss"] == 7.5
    m.close()


def test_check_cve_offline_uses_mirror(tmp_path, monkeypatch):
    monkeypatch.setenv("GHOSTEYE_OFFLINE", "1")
    monkeypatch.setenv("GHOSTEYE_CVE_MIRROR", str(tmp_path / "m.db"))
    import ghost_eye.cve_mirror as cm
    cm._SHARED = None
    cm.shared().put("CVE-2021-44228",
                    {"cve": "CVE-2021-44228", "verdict": "ACTIVELY EXPLOITED",
                     "known_exploited": True, "_complete": True})
    from ghost_eye.modules.exploit_intel import check_cve
    assert check_cve("CVE-2021-44228", None)["verdict"] == "ACTIVELY EXPLOITED"
    assert "offline" in check_cve("CVE-9999-9999", None)["verdict"]
    cm._SHARED = None


# --- 75 distributed scanning ----------------------------------------------

def test_job_queue_enqueue_and_stats(tmp_path):
    from ghost_eye.distributed import JobQueue
    q = JobQueue(str(tmp_path / "q.db"))
    assert q.enqueue_many(["a.com", "b.com", "c.com"], "quick") == 3
    assert q.stats()["pending"] == 3
    job = q.claim("w1")
    assert job and job["target"] in ("a.com", "b.com", "c.com")
    assert q.stats()["running"] == 1
    q.complete(job["id"], {"ok": True})
    assert q.stats()["done"] == 1
    q.close()


def test_concurrent_workers_no_double_processing(tmp_path):
    from ghost_eye.distributed import JobQueue, run_worker
    dbp = str(tmp_path / "q.db")
    q = JobQueue(dbp)
    q.enqueue_many([f"t{i}.com" for i in range(40)], "quick")
    q.close()
    seen: list = []
    lock = threading.Lock()

    def stub(target, profile, cfg):
        with lock:
            seen.append(target)
        return {"target": target}

    def work():
        run_worker(dbp, cfg=None, poll=0.02, idle_exits=2, scan_fn=stub)

    ts = [threading.Thread(target=work) for _ in range(4)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert len(seen) == 40 and len(set(seen)) == 40   # each exactly once
    q = JobQueue(dbp)
    assert q.stats()["done"] == 40
    q.close()


def test_requeue_stale(tmp_path):
    from ghost_eye.distributed import JobQueue
    q = JobQueue(str(tmp_path / "q.db"))
    q.enqueue("a.com", "quick")
    q.claim("dead-worker")
    assert q.stats()["running"] == 1
    assert q.requeue_stale(older_than=-1) == 1     # everything older than "now+1"
    assert q.stats()["pending"] == 1
    q.close()
