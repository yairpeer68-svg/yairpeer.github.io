"""Tests for the Telegram bot.

A bot that runs scans is a remote-command channel into the machine hosting it,
so most of these are about refusing things rather than doing them. The property
that matters more than any feature: an unconfigured bot must authorise
*nobody*, because a token that leaks would otherwise let a stranger point your
host at any target on the internet.
"""

from __future__ import annotations

import pytest

from ghost_eye.core import Result
from ghost_eye.scope import Scope
from ghost_eye.telegram_bot import COOLDOWN, MAX_MESSAGE, TelegramBot


def _result(module="headers", target="a.com", data=None, status="ok", error=None):
    r = Result(module=module, target=target)
    r.data = data if data is not None else {"server": "nginx"}
    r.status = status
    r.error = error
    return r


class _Sess:
    """Records what the bot would have sent, and answers getUpdates."""

    def __init__(self, updates=None):
        self.sent = []
        self._updates = updates or []

    def get(self, url, params=None, timeout=None):
        params = params or {}
        outer = self

        class _R:
            status_code = 200

            @staticmethod
            def json():
                if "sendMessage" in url:
                    outer.sent.append(params)
                    return {"ok": True}
                if "getUpdates" in url:
                    batch, outer._updates = outer._updates, []
                    return {"result": batch}
                if "getMe" in url:
                    return {"result": {"username": "ghosteye_test_bot"}}
                return {}
        return _R()


def _bot(**kw):
    kw.setdefault("token", "123456:TEST-TOKEN")
    kw.setdefault("session", _Sess())
    kw.setdefault("runner", lambda t, s, o: [_result(target=t)])
    return TelegramBot(**kw)


# --------------------------------------------------------------------------- #
class TestAuthorisation:
    """Default deny. This is the whole security posture of the feature."""

    def test_an_empty_allow_list_authorises_nobody(self):
        bot = _bot()
        reply = bot.handle(999, "/scan example.com")
        assert "not authorised" in reply
        assert bot._last is None, "an unauthorised chat started a scan"

    def test_an_allow_listed_chat_is_served(self):
        bot = _bot(allowed_chats=[42])
        assert "not authorised" not in bot.handle(42, "/status")

    def test_a_different_chat_is_still_refused(self):
        bot = _bot(allowed_chats=[42])
        assert "not authorised" in bot.handle(43, "/scan example.com")

    def test_whoami_answers_anyone_so_you_can_allow_list_yourself(self):
        """You need your own id to put yourself on the list; it tells the
        caller nothing they do not already know."""
        bot = _bot()
        reply = bot.handle(777, "/whoami")
        assert "777" in reply and "not on the allow-list" in reply

    def test_unauthorised_chats_are_recorded_for_the_operator(self):
        bot = _bot()
        bot.handle(555, "/scan example.com")
        assert 555 in bot.seen_unauthorised

    def test_string_chat_ids_in_the_allow_list_still_match(self):
        assert _bot(allowed_chats=["42"]).authorised(42)

    def test_a_malformed_token_is_refused_at_construction(self):
        with pytest.raises(ValueError):
            TelegramBot(token="not-a-token")


class TestScopeStillApplies:
    """Remote convenience must not widen what you are allowed to scan."""

    def test_an_out_of_scope_target_is_refused(self):
        bot = _bot(allowed_chats=[1], scope=Scope.from_lines(["example.com"]))
        reply = bot.handle(1, "/scan evil.test")
        assert "out of scope" in reply
        assert bot._last is None

    def test_an_in_scope_target_runs(self):
        bot = _bot(allowed_chats=[1], scope=Scope.from_lines(["example.com"]))
        assert "out of scope" not in bot.handle(1, "/scan example.com")

    def test_no_scope_configured_means_no_extra_restriction(self):
        bot = _bot(allowed_chats=[1])
        assert "out of scope" not in bot.handle(1, "/scan anything.test")


class TestRateLimit:
    def test_back_to_back_scans_are_refused(self):
        bot = _bot(allowed_chats=[1])
        bot.handle(1, "/scan example.com")
        assert "slow down" in bot.handle(1, "/scan example.com")

    def test_a_read_only_command_is_not_rate_limited(self):
        bot = _bot(allowed_chats=[1])
        bot.handle(1, "/scan example.com")
        assert "slow down" not in bot.handle(1, "/status")

    def test_the_cooldown_is_not_zero(self):
        assert COOLDOWN >= 1


class TestCommands:
    def test_help_lists_the_commands(self):
        out = _bot(allowed_chats=[1]).handle(1, "/help")
        for cmd in ("/scan", "/ports", "/findings", "/fixorder", "/stop"):
            assert cmd in out

    def test_status_is_idle_when_nothing_runs(self):
        assert "Idle" in _bot(allowed_chats=[1]).handle(1, "/status")

    def test_scan_without_a_target_explains_itself(self):
        assert "Usage" in _bot(allowed_chats=[1]).handle(1, "/scan")

    def test_an_unknown_command_does_not_crash(self):
        assert "Unknown command" in _bot(allowed_chats=[1]).handle(1, "/nonsense")

    def test_a_scan_reports_the_findings_summary(self):
        bot = _bot(allowed_chats=[1], runner=lambda t, s, o: [
            _result(data={"clickjacking": "VULNERABLE - no X-Frame-Options"},
                    target=t)])
        out = bot.handle(1, "/scan example.com")
        assert "example.com" in out and "modules" in out

    def test_errored_modules_are_surfaced_not_hidden(self):
        bot = _bot(allowed_chats=[1], runner=lambda t, s, o: [
            _result(status="error", error="source is down", target=t)])
        assert "errored" in bot.handle(1, "/scan example.com")

    def test_findings_needs_a_scan_first(self):
        assert "No scan yet" in _bot(allowed_chats=[1]).handle(1, "/findings")

    def test_stop_sets_the_flag(self):
        bot = _bot(allowed_chats=[1])
        assert "Stopping" in bot.handle(1, "/stop")
        assert bot._stop.is_set()

    def test_ports_takes_a_spec(self, monkeypatch):
        seen = {}
        bot = _bot(allowed_chats=[1],
                   runner=lambda t, s, o: seen.update(sel=s, opt=o) or [_result(target=t)])
        bot.handle(1, "/ports example.com 1-1024")
        assert seen["sel"] == {"mode": "modules", "value": ["portscan"]}
        assert seen["opt"]["ports"] == "1-1024"

    def test_scan_takes_a_profile(self):
        seen = {}
        bot = _bot(allowed_chats=[1],
                   runner=lambda t, s, o: seen.update(sel=s) or [_result(target=t)])
        bot.handle(1, "/scan example.com perimeter")
        assert seen["sel"] == {"mode": "profile", "value": "perimeter"}


class TestReplies:
    def test_a_long_reply_is_truncated_not_dropped(self):
        """Telegram rejects anything over 4096, and a rejected reply looks
        exactly like a bot that stopped working."""
        sess = _Sess()
        bot = _bot(allowed_chats=[1], session=sess)
        bot.send(1, "x" * 10000)
        assert len(sess.sent[0]["text"]) <= MAX_MESSAGE + 80
        assert "truncated" in sess.sent[0]["text"]

    def test_a_short_reply_is_sent_verbatim(self):
        sess = _Sess()
        _bot(session=sess).send(1, "hello")
        assert sess.sent[0]["text"] == "hello"

    def test_replies_never_contain_the_token(self):
        sess = _Sess()
        bot = _bot(allowed_chats=[1], session=sess)
        for cmd in ("/help", "/status", "/whoami", "/nonsense"):
            bot.send(1, bot.handle(1, cmd))
        assert not any("TEST-TOKEN" in m["text"] for m in sess.sent)

    def test_user_text_is_escaped_into_the_html_reply(self):
        bot = _bot(allowed_chats=[1])
        assert "<b>" not in bot.handle(1, "/<b>evil</b>")


class TestPolling:
    def test_updates_are_answered_and_the_offset_advances(self):
        sess = _Sess(updates=[
            {"update_id": 7, "message": {"chat": {"id": 1}, "text": "/help"}}])
        bot = _bot(allowed_chats=[1], session=sess)
        assert bot.poll_once() == 1
        assert bot._offset == 8
        assert "Ghost Eye" in sess.sent[0]["text"]

    def test_a_message_without_text_is_skipped(self):
        sess = _Sess(updates=[{"update_id": 1, "message": {"chat": {"id": 1}}}])
        bot = _bot(allowed_chats=[1], session=sess)
        bot.poll_once()
        assert sess.sent == []

    def test_one_bad_message_does_not_stop_the_bot(self):
        class _Boom(TelegramBot):
            def handle(self, chat_id, text):
                raise RuntimeError("handler exploded")
        sess = _Sess(updates=[
            {"update_id": 1, "message": {"chat": {"id": 1}, "text": "/help"}}])
        bot = _Boom(token="1:X", allowed_chats=[1], session=sess)
        bot.poll_once()
        assert sess.sent and "handler exploded" in sess.sent[0]["text"]


class TestCliWiring:
    def test_flags_parse(self):
        from ghost_eye.cli import build_parser
        a = build_parser().parse_args(
            ["--telegram-bot", "--telegram-token", "1:X", "--telegram-allow", "42,43"])
        assert a.telegram_bot and a.telegram_token == "1:X"
        assert a.telegram_allow == "42,43"

    def test_a_missing_token_is_refused_with_an_exit_code(self, capsys):
        from ghost_eye.cli import _run_telegram, build_parser

        class _Cfg:
            def get(self, _k, d=None):
                return d
        args = build_parser().parse_args(["--telegram-bot"])
        assert _run_telegram(args, _Cfg()) == 2
        assert "telegram-token" in capsys.readouterr().out

    def test_allow_list_accepts_commas_and_semicolons(self):
        from ghost_eye.telegram_bot import TelegramBot
        bot = TelegramBot(token="1:X",
                          allowed_chats=[c for c in "42;43,44".replace(";", ",").split(",")])
        assert bot.authorised(42) and bot.authorised(44)
