"""Tests for the entity-investigation capstone.

Ties together the data-driven OSINT engine, the confidence model and OPSEC into
one person-focused investigation + dossier. Uses a mock run_fn so no network is
touched.
"""

from __future__ import annotations

import ghost_eye.modules  # noqa: F401 - populate REGISTRY
from ghost_eye import workflow
from ghost_eye.core import Result
from ghost_eye.intelligence import entity_dossier


def _fake_run(target, module_ids, cfg):
    out = []
    if "usernamescan" in module_ids:
        out.append(Result("Username enumeration at scale (data-driven)", target,
                          data={"username": target, "found_on": [
                              {"site": "GitHub",
                               "url": f"https://github.com/{target}",
                               "confidence": "high"},
                              {"site": "Reddit",
                               "url": f"https://reddit.com/user/{target}",
                               "confidence": "high"},
                              {"site": "Medium",
                               "url": f"https://medium.com/@{target}",
                               "confidence": "medium"}]}))
        out.append(Result("GitHub user", target,
                          data={"email": f"{target}@gmail.com",
                                "profile_url": f"https://github.com/{target}"}))
    if "emailfootprint" in module_ids:
        out.append(Result("Email footprint (Gravatar/Libravatar)", target,
                          data={"email": target, "linked_accounts": [
                              {"service": "twitter",
                               "url": "https://x.com/alice"}]}))
    return out


class TestSeedKind:
    def test_username(self):
        assert workflow._seed_kind("alice") == "username"
        assert workflow._seed_kind("@alice") == "username"

    def test_email(self):
        assert workflow._seed_kind("bob@example.com") == "email"

    def test_domain(self):
        assert workflow._seed_kind("example.com") == "domain"
        assert workflow._seed_kind("sub.example.co.uk") == "domain"


class TestEntityInvestigation:
    def test_username_investigation(self):
        inv = workflow.entity_investigation("alice", run_fn=_fake_run)
        assert inv["kind"] == "username"
        assert inv["profile_count"] >= 3
        confirmed = {p["site"] for p in inv["confirmed_profiles"]}
        assert "GitHub" in confirmed and "Reddit" in confirmed
        assert "Medium" not in confirmed          # medium-confidence, not "confirmed"

    def test_email_pivot(self):
        inv = workflow.entity_investigation("alice", run_fn=_fake_run)
        assert "alice@gmail.com" in inv["linked_emails"]
        assert "alice@gmail.com" in inv["pivoted_emails"]

    def test_opsec_recorded(self):
        inv = workflow.entity_investigation("alice", run_fn=_fake_run)
        op = inv["opsec"]
        hosts = {t["host"] for t in op["third_parties_contacted"]}
        assert "github.com" in hosts
        assert op["third_party_count"] >= 1

    def test_email_seed(self):
        inv = workflow.entity_investigation("bob@example.com", run_fn=_fake_run)
        assert inv["kind"] == "email"

    def test_domain_seed_is_rejected(self):
        inv = workflow.entity_investigation("example.com", run_fn=_fake_run)
        assert "error" in inv and inv["kind"] == "domain"

    def test_empty_seed(self):
        assert "error" in workflow.entity_investigation("", run_fn=_fake_run)


class TestEntityDossier:
    def test_renders_key_sections(self):
        inv = workflow.entity_investigation("alice", run_fn=_fake_run)
        md = entity_dossier(inv)
        assert "# Entity dossier — alice" in md
        assert "## Accounts / profiles" in md
        assert "## OPSEC exposure" in md
        assert "github.com" in md
        # confirmed profiles are rendered with their confidence
        assert "GitHub" in md and "_(high)_" in md

    def test_dossier_of_empty_investigation(self):
        # a domain-seed error dict must still render without raising
        inv = workflow.entity_investigation("example.com", run_fn=_fake_run)
        md = entity_dossier(inv)
        assert "Entity dossier" in md
