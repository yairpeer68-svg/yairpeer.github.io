"""Audit harness: feed every module hostile-but-plausible HTTP responses.

The offline smoke test proves a module returns a `Result` against a *stubbed*
network. This proves something narrower and more useful: that a source which
changes shape — starts returning an empty body, binary garbage, `null` JSON,
an HTML error page, or a bare 404 — degrades into an error `Result` instead of
raising out of the module.

Only crashes originating in Ghost Eye's own frames count. Network errors are
expected and ignored; `TypeError`, `AttributeError`, `KeyError`, `IndexError`,
`UnboundLocalError` and friends are code bugs and are reported with the exact
file:line that raised.

Not part of CI — it runs every module five times and takes minutes.

    python3 scripts/audit_hostile_responses.py [profile ...]

profiles: empty | garbage | nulljson | html | notfound   (default: all)
"""
from __future__ import annotations

import io
import pathlib
import socket
import sys
import traceback
from collections import defaultdict

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))

import ghost_eye.modules  # noqa: F401,E402
from ghost_eye.core import REGISTRY, Context  # noqa: E402

CODE_ERRORS = (TypeError, AttributeError, KeyError, IndexError,
               UnboundLocalError, NameError, ZeroDivisionError,
               StopIteration, RecursionError)


class Resp:
    def __init__(self, profile):
        self.profile = profile
        self.status_code = 404 if profile == "notfound" else 200
        self.reason = "OK"
        self.url = "https://example.com/"
        self.history = []
        self.cookies = {}
        self.encoding = "utf-8"
        self.is_redirect = False
        self.links = {}
        self.request = type("R", (), {"headers": {}, "url": self.url, "body": None})()
        if profile == "empty":
            self.text, self._json = "", None
        elif profile == "garbage":
            self.text, self._json = "\x00\xff<<>>{[,,]}", "not-a-dict"
        elif profile == "nulljson":
            self.text, self._json = "null", None
        elif profile == "html":
            self.text = "<html><head><title>x</title></head><body>hi</body></html>"
            self._json = {}
        else:
            self.text, self._json = "", None
        self.content = self.text.encode("utf-8", "replace")
        self.headers = {} if profile in ("empty", "notfound") else {
            "Server": "nginx", "Content-Type": "text/html"}
        self.elapsed = __import__("datetime").timedelta(seconds=0.1)
        self.ok = self.status_code < 400

    def json(self, **kw):
        if self.profile == "garbage":
            raise ValueError("No JSON object could be decoded")
        return self._json

    def raise_for_status(self):
        if self.status_code >= 400:
            raise Exception(f"HTTP {self.status_code}")

    def iter_lines(self, **kw):
        return iter(self.text.splitlines())

    def iter_content(self, *a, **kw):
        return iter([self.content])

    def close(self):
        pass

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class Sess:
    def __init__(self, profile):
        self.profile = profile
        self.headers = {}
        self.cookies = {}
        self.verify = True
        self.proxies = {}
        self.params = {}

    def _r(self, *a, **kw):
        return Resp(self.profile)

    get = post = head = put = delete = options = patch = request = _r

    def mount(self, *a, **kw):
        pass

    def close(self):
        pass


class Cfg:
    def get(self, k, d=None):
        return d

    def require(self, name):
        raise RuntimeError(f"{name} requires an API key")

    def __getitem__(self, k):
        raise KeyError(k)


def blocked(*a, **kw):
    raise OSError("network disabled in fuzz harness")


def main(profiles):
    socket.create_connection = blocked
    try:
        socket.gethostbyname = blocked
    except Exception:
        pass

    crashes = defaultdict(list)
    targets = {"domain": "example.com", "host": "example.com",
               "url": "https://example.com", "ip": "93.184.216.34",
               "username": "torvalds", "email": "a@example.com",
               "phone": "+14155552671", "keyword": "example",
               "asn": "AS15169", "company": "Example Inc"}
    for profile in profiles:
        ctx = Context(config=Cfg(), session=Sess(profile), timeout=2)
        for mid, mod in sorted(REGISTRY.items()):
            tgt = targets.get(getattr(mod, "target_kind", "host"), "example.com")
            buf = io.StringIO()
            old = sys.stdout
            sys.stdout = buf
            try:
                mod.run(tgt, ctx)
            except CODE_ERRORS as exc:
                tb = traceback.extract_tb(sys.exc_info()[2])
                ours = [f for f in tb if "ghost_eye/" in f.filename]
                loc = (f"{ours[-1].filename.split('ghost_eye/')[-1]}:{ours[-1].lineno}"
                       if ours else "?")
                crashes[f"{type(exc).__name__}: {exc}"].append((mid, profile, loc))
            except Exception:
                pass
            finally:
                sys.stdout = old
    for msg, hits in sorted(crashes.items(), key=lambda kv: -len(kv[1])):
        print(f"\n### {msg}  ({len(hits)} hits)")
        for mid, profile, loc in hits[:8]:
            print(f"    {mid:<24} [{profile}] {loc}")
    print(f"\nTOTAL distinct crash signatures: {len(crashes)}")
    print(f"TOTAL crash instances: {sum(len(v) for v in crashes.values())}")


if __name__ == "__main__":
    p = sys.argv[1:] or ["empty", "garbage", "nulljson", "html", "notfound"]
    main(p)
