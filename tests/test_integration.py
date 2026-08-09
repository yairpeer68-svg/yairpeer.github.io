"""End-to-end CLI integration test — runs the real `ghost_eye.py` as a
subprocess against a locally served page and asserts the reports it produces.
This catches regressions in the actual command-line wiring that unit/smoke
tests (which import functions directly) cannot see."""

from __future__ import annotations

import http.server
import json
import subprocess
import sys
import threading
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
_PAGE = (b"<!doctype html><title>IntegTest</title>"
         b"<body><h1>hello</h1><a href='/api/'>api</a></body>")


class _Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html")
        self.send_header("Server", "nginx/1.18.0")
        self.end_headers()
        self.wfile.write(_PAGE)

    def log_message(self, *a):        # keep the test quiet
        return


@pytest.fixture()
def local_site():
    srv = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    port = srv.server_address[1]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    try:
        yield f"127.0.0.1:{port}"
    finally:
        srv.shutdown()


def _run_cli(args, timeout=60):
    return subprocess.run([sys.executable, "ghost_eye.py", *args],
                          cwd=str(ROOT), capture_output=True, text=True,
                          timeout=timeout)


def test_cli_json_report(local_site, tmp_path):
    out = tmp_path / "r.json"
    r = _run_cli(["-t", local_site, "-m", "headers,cors", "-o", str(out),
                  "--no-color"])
    assert r.returncode == 0, r.stderr
    assert out.exists() and out.stat().st_size > 0
    data = json.loads(out.read_text())
    # the report is a dict keyed by module or a list of results — either way,
    # the modules we asked for must be present in the serialized output
    blob = json.dumps(data)
    assert "headers" in blob.lower()


def test_cli_intel_report_html(local_site, tmp_path):
    out = tmp_path / "intel.html"
    r = _run_cli(["-t", local_site, "-m", "headers,tech", "--intel-report",
                  str(out), "--no-color"])
    assert r.returncode == 0, r.stderr
    assert out.exists()
    html = out.read_text()
    assert "<svg" in html and "Intelligence Report" in html


def test_cli_list_and_module_report():
    r = _run_cli(["--module-report", "--no-color"], timeout=40)
    assert r.returncode == 0
    assert "modules" in r.stdout.lower()
