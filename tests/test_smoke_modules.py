"""Smoke test: every registered module must handle a run() call without an
unhandled exception, even fully offline. Modules are expected to return a
Result (typically a graceful .fail()) — never to crash the process.

Network, DNS, sockets and subprocesses are stubbed so the suite is fast,
deterministic and offline. This is the safety net that catches the class of
regression where a module raises instead of returning a Result."""

from __future__ import annotations

import socket

import pytest

import ghost_eye.modules  # noqa: F401 - populates REGISTRY
from ghost_eye import core
from ghost_eye.config import Config
from ghost_eye.core import REGISTRY, Context, Result


class _FakeResp:
    status_code = 200
    reason = "OK"
    url = "https://example.com/"
    text = "<html><head></head><body>ok</body></html>"
    content = b"<html><head></head><body>ok</body></html>"
    cookies = {}
    history = []
    elapsed = 0.0

    def __init__(self):
        self.headers = {"Content-Type": "text/html", "Server": "nginx/1.18.0"}

    def json(self):
        return {}

    def iter_content(self, chunk_size=1):
        return iter([b""])

    def raise_for_status(self):
        return None

    def close(self):
        return None


class _FakeSession:
    def __init__(self):
        self.headers = {}

    def _resp(self, *a, **k):
        return _FakeResp()

    get = post = head = put = delete = options = request = _resp

    def mount(self, *a, **k):
        return None

    def close(self):
        return None


class _DeadSocket:
    """A socket that refuses every network op quickly, so socket-based modules
    exercise their error paths instead of hanging on real I/O."""

    def __init__(self, *a, **k):
        pass

    def settimeout(self, *a, **k):
        return None

    def setsockopt(self, *a, **k):
        return None

    def _fail(self, *a, **k):
        raise OSError("network disabled in smoke test")

    connect = connect_ex = send = sendall = sendto = recv = recvfrom = _fail
    bind = listen = accept = _fail

    def close(self):
        return None

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


@pytest.fixture(autouse=True)
def _no_network(monkeypatch):
    monkeypatch.setattr(socket, "socket", _DeadSocket)
    monkeypatch.setattr(socket, "create_connection",
                        lambda *a, **k: (_ for _ in ()).throw(OSError("no net")))
    monkeypatch.setattr(socket, "gethostbyname",
                        lambda *a, **k: (_ for _ in ()).throw(OSError("no dns")))
    monkeypatch.setattr(socket, "gethostbyname_ex",
                        lambda *a, **k: (_ for _ in ()).throw(OSError("no dns")))
    # binaries and subprocesses: pretend nothing is installed / no output
    monkeypatch.setattr(core, "have_binary", lambda name: False)
    monkeypatch.setattr(core, "run_cmd", lambda *a, **k: "")


def _ctx():
    return Context(config=Config(), session=_FakeSession(), threads=4, timeout=2)


@pytest.mark.parametrize("mid", sorted(REGISTRY))
def test_module_run_never_crashes(mid):
    module = REGISTRY[mid]
    target = {"ip": "192.0.2.10", "url": "https://example.com",
              "domain": "example.com", "host": "example.com"}.get(
                  getattr(module, "target_kind", "host"), "example.com")
    result = module.run(target, _ctx())
    assert isinstance(result, Result), f"{mid} did not return a Result"
    assert result.status in ("ok", "empty", "error", "fail", "warn"), \
        f"{mid} returned unexpected status {result.status!r}"
