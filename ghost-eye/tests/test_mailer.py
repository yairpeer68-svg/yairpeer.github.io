"""Tests for email delivery.

A report is a list of your own weaknesses, so the interesting tests here are
the ones about not sending it badly: not in clear text, not to a typo'd
address, and never with the password visible anywhere it could be logged.
"""

from __future__ import annotations

import smtplib

import pytest

from ghost_eye.mailer import (IMPLICIT_TLS_PORT, MailError, Mailer,
                              report_email, split_recipients, valid_address)


class _FakeSMTP:
    """Records the whole conversation so a test can assert on the order of it."""

    instances = []

    def __init__(self, host, port, timeout=None):
        self.host, self.port = host, port
        self.calls = []
        self.sent = []
        self.login_with = None
        _FakeSMTP.instances.append(self)

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.calls.append("quit")
        return False

    def starttls(self, context=None):
        self.calls.append("starttls")

    def login(self, user, password):
        self.calls.append("login")
        self.login_with = (user, password)

    def send_message(self, msg):
        self.calls.append("send")
        self.sent.append(msg)


def _mailer(**kw):
    _FakeSMTP.instances = []
    kw.setdefault("host", "smtp.example.com")
    kw.setdefault("sender", "ghosteye@example.com")
    kw.setdefault("client", _FakeSMTP)
    return Mailer(**kw)


# --------------------------------------------------------------------------- #
class TestAddresses:
    @pytest.mark.parametrize("addr", [
        "a@example.com", "first.last@sub.example.co.uk", "x+tag@example.io",
        "Dana <dana@example.com>",
    ])
    def test_valid(self, addr):
        assert valid_address(addr)

    @pytest.mark.parametrize("addr", [
        "", "   ", "example.com", "a@", "@example.com", "a@example",
        "a@b.c", "a@ example.com", "a@example.com, b@example.com",
    ])
    def test_invalid(self, addr):
        assert not valid_address(addr)

    def test_recipients_split_on_the_usual_separators(self):
        assert split_recipients("a@x.com, b@x.com;c@x.com d@x.com") == \
            ["a@x.com", "b@x.com", "c@x.com", "d@x.com"]

    def test_a_list_is_accepted_as_is(self):
        assert split_recipients(["a@x.com", " b@x.com "]) == ["a@x.com", "b@x.com"]

    def test_empty_means_no_recipients(self):
        assert split_recipients("") == [] and split_recipients(None) == []


class TestConfiguration:
    def test_an_unconfigured_mailer_says_so(self):
        m = Mailer()
        assert not m.configured
        assert m.problems()

    def test_a_complete_configuration_has_no_problems(self):
        assert _mailer(username="u", password="p").problems() == []

    def test_tls_off_for_a_remote_host_is_a_problem(self):
        problems = _mailer(use_tls=False).problems()
        assert any("clear text" in p for p in problems)

    def test_tls_off_for_localhost_is_allowed(self):
        """A relay on 127.0.0.1 never puts the message on a wire."""
        assert _mailer(host="127.0.0.1", use_tls=False).problems() == []

    def test_a_username_without_a_password_is_a_problem(self):
        assert any("no password" in p for p in _mailer(username="u").problems())

    def test_every_problem_is_reported_at_once(self):
        """One round-trip per mistake is how a settings page gets abandoned."""
        assert len(Mailer(host="", port=0, sender="nope").problems()) >= 3

    def test_the_password_is_never_in_the_config(self):
        cfg = _mailer(username="u", password="hunter2").config()
        assert "hunter2" not in str(cfg)
        assert cfg["password_set"] is True

    def test_the_password_is_never_in_the_repr(self):
        assert "hunter2" not in repr(_mailer(username="u", password="hunter2"))


class TestSending:
    def test_a_message_goes_out_over_starttls(self):
        m = _mailer(username="u", password="p")
        out = m.send("dana@example.com", "subject", "body")
        assert out["sent"] is True
        smtp = _FakeSMTP.instances[0]
        assert smtp.calls == ["starttls", "login", "send", "quit"], \
            "TLS must be negotiated before the password is sent"

    def test_implicit_tls_does_not_also_starttls(self):
        m = _mailer(port=IMPLICIT_TLS_PORT, username="u", password="p")
        m.send("dana@example.com", "s", "b")
        assert "starttls" not in _FakeSMTP.instances[0].calls

    def test_no_username_means_no_login(self):
        _mailer().send("dana@example.com", "s", "b")
        assert "login" not in _FakeSMTP.instances[0].calls

    def test_a_bad_recipient_is_refused_before_connecting(self):
        m = _mailer()
        with pytest.raises(MailError, match="invalid recipient"):
            m.send("not-an-address", "s", "b")
        assert _FakeSMTP.instances == [], "it dialled out for a typo"

    def test_no_recipients_is_refused(self):
        with pytest.raises(MailError, match="no recipients"):
            _mailer().send("", "s", "b")

    def test_an_unconfigured_mailer_refuses_to_send(self):
        with pytest.raises(MailError):
            Mailer(client=_FakeSMTP).send("dana@example.com", "s", "b")

    def test_an_auth_failure_is_a_readable_error_without_the_password(self):
        class _Auth(_FakeSMTP):
            def login(self, user, password):
                raise smtplib.SMTPAuthenticationError(535, b"nope")
        m = _mailer(username="u", password="hunter2", client=_Auth)
        with pytest.raises(MailError) as exc:
            m.send("dana@example.com", "s", "b")
        assert "535" in str(exc.value)
        assert "hunter2" not in str(exc.value)

    def test_a_connection_failure_is_a_mailerror_not_a_traceback(self):
        class _Down(_FakeSMTP):
            def __enter__(self):
                raise OSError("connection refused")
        with pytest.raises(MailError, match="connection refused"):
            _mailer(client=_Down).send("dana@example.com", "s", "b")

    def test_multiple_recipients_all_arrive(self):
        out = _mailer().send("a@x.com, b@x.com", "s", "b")
        assert out["recipients"] == ["a@x.com", "b@x.com"]
        assert _FakeSMTP.instances[0].sent[0]["To"] == "a@x.com, b@x.com"

    def test_the_body_is_bounded(self):
        m = _mailer()
        msg = m.build(["a@x.com"], "s", "x" * 5_000_000)
        assert len(msg.get_content()) < 500_000

    def test_an_attachment_rides_along(self):
        msg = _mailer().build(["a@x.com"], "s", "b",
                              attachment=("report.json", b"{}"))
        names = [p.get_filename() for p in msg.walk()]
        assert "report.json" in names


class TestReportRendering:
    def test_the_subject_names_the_target_and_the_level(self):
        subject, _ = report_email("example.com", {"risk_level": "high",
                                                  "risk_score": 71}, [])
        assert "example.com" in subject and "high" in subject and "71" in subject

    def test_findings_are_listed(self):
        _, body = report_email("example.com", {"risk_level": "medium"}, [
            {"severity": "high", "title": "missing HSTS"},
            {"severity": "low", "title": "server header leaks version"},
        ])
        assert "missing HSTS" in body and "HIGH" in body

    def test_a_long_list_is_summarised_not_truncated_silently(self):
        findings = [{"severity": "low", "title": f"f{i}"} for i in range(100)]
        _, body = report_email("example.com", {}, findings, limit=10)
        assert "and 90 more" in body

    def test_it_says_nothing_was_exploited(self):
        """The charter is recon-only; the email should not read like a pentest
        report to whoever forwards it."""
        _, body = report_email("example.com", {}, [])
        assert "exploited" in body.lower()

    def test_an_empty_risk_dict_does_not_crash(self):
        subject, body = report_email("example.com", {}, [])
        assert "example.com" in subject and "Findings: 0" in body


class TestHostileInput:
    def test_a_finding_that_is_not_a_dict_does_not_break_the_send(self):
        """A report that fails to render looks exactly like a mail server that
        is down — render what can be rendered and keep going."""
        _, body = report_email("x.com", {}, ["a string", None, 42,
                                             {"severity": "high", "title": "real"}])
        assert "real" in body
        assert "a string" in body
        assert "Findings: 4" in body

    def test_a_none_risk_is_not_a_crash(self):
        subject, _ = report_email("x.com", None, [])
        assert "x.com" in subject
