#!/usr/bin/env bash
# Pre-release verification gate. Everything must pass before the code ships.
#   ./scripts/verify.sh
set -uo pipefail
cd "$(dirname "$0")/.."
fail=0
step() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
check() { if [ "$1" -ne 0 ]; then echo "  ✗ FAILED"; fail=1; else echo "  ✓ ok"; fi; }

step "byte-compile every source"
python3 -m compileall -q ghost_eye scripts; check $?

step "import check (all modules register)"
python3 -c "import ghost_eye.modules; from ghost_eye.core import REGISTRY; \
assert len(REGISTRY) > 0; print(' ', len(REGISTRY), 'modules')"; check $?

step "ruff bug-rules (undefined names, syntax)"
python3 -m ruff check ghost_eye; check $?

step "test suite (unit + all-module smoke + behavioural + engine + intelligence)"
python3 -m pytest -q -p no:cacheprovider; check $?

step "live smoke: dashboard serves + auth gate + CSRF gate + CLI report"
python3 - <<'PY'
import json, subprocess, sys, time, urllib.error, urllib.request, tempfile, os
port = 8899
srv = subprocess.Popen([sys.executable, "ghost_eye_web.py", "--port", str(port),
                        "--auth-token", "VTOK"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    time.sleep(2.5)
    def code(url, tok=None, method="GET", body=None, headers=None):
        req = urllib.request.Request(
            url, method=method,
            data=json.dumps(body).encode() if body is not None else None)
        if tok:
            req.add_header("X-Ghost-Token", tok)
        for k, v in (headers or {}).items():
            req.add_header(k, v)
        try:
            return urllib.request.urlopen(req, timeout=5).getcode()
        except urllib.error.HTTPError as e:
            return e.code
    base = f"http://127.0.0.1:{port}"
    assert code(f"{base}/") == 200, "index"
    assert code(f"{base}/api/meta") == 401, "unauth must be 401"
    assert code(f"{base}/api/meta", "VTOK") == 200, "authed must be 200"
    # a page on another origin must not be able to drive the API
    assert code(f"{base}/api/keys", "VTOK", method="POST",
                body={"name": "virustotal", "value": "x"},
                headers={"Content-Type": "text/plain",
                         "Origin": "https://evil.example"}) == 403, \
        "cross-origin POST must be 403"
    # nor may a rebound hostname read it
    assert code(f"{base}/api/meta", "VTOK",
                headers={"Host": "attacker.example"}) == 403, \
        "forged Host must be 403"
finally:
    srv.terminate()

# a real CLI scan against a local page — assert it actually SCANNED, not merely
# that a file appeared. A report containing only an error is still a non-empty
# file, which is how a broken target-parsing path used to pass this gate.
site = tempfile.mkdtemp()
open(os.path.join(site, "index.html"), "w").write("<title>t</title><h1>hi</h1>")
httpd = subprocess.Popen([sys.executable, "-m", "http.server", "8898",
                          "--directory", site],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    time.sleep(1.2)
    out = tempfile.mktemp(suffix=".json")
    subprocess.run([sys.executable, "ghost_eye.py", "-t", "http://127.0.0.1:8898",
                    "-m", "headers", "-o", out, "--no-color"],
                   stdout=subprocess.DEVNULL, timeout=40, check=True)
    report = json.load(open(out))
    results = report.get("results") or []
    assert results, "report has no results"
    errors = [r for r in results if r.get("status") == "error"]
    assert not errors, f"module errored: {errors[0].get('error')}"
    assert results[0]["data"].get("status_code") == 200, \
        f"expected a real 200 from the local site, got {results[0]['data']}"
finally:
    httpd.terminate()
print("  live checks passed")
PY
check $?

if [ "$fail" -ne 0 ]; then
    printf '\n\033[31mVERIFY FAILED — do not ship.\033[0m\n'; exit 1
fi
printf '\n\033[32mVERIFY PASSED — safe to ship.\033[0m\n'
