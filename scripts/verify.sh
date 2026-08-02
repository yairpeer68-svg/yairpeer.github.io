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

step "live smoke: dashboard serves + auth gate + CLI report"
python3 - <<'PY'
import subprocess, sys, time, urllib.request, tempfile, os
port = 8899
srv = subprocess.Popen([sys.executable, "ghost_eye_web.py", "--port", str(port),
                        "--auth-token", "VTOK"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    time.sleep(2.5)
    def code(url, tok=None):
        req = urllib.request.Request(url)
        if tok:
            req.add_header("X-Ghost-Token", tok)
        try:
            return urllib.request.urlopen(req, timeout=5).getcode()
        except urllib.error.HTTPError as e:
            return e.code
    assert code(f"http://127.0.0.1:{port}/") == 200, "index"
    assert code(f"http://127.0.0.1:{port}/api/meta") == 401, "unauth must be 401"
    assert code(f"http://127.0.0.1:{port}/api/meta", "VTOK") == 200, "authed must be 200"
finally:
    srv.terminate()
# a real CLI report against a local page
site = tempfile.mkdtemp()
open(os.path.join(site, "index.html"), "w").write("<title>t</title><h1>hi</h1>")
httpd = subprocess.Popen([sys.executable, "-m", "http.server", "8898",
                          "--directory", site],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    time.sleep(1.2)
    out = tempfile.mktemp(suffix=".json")
    subprocess.run([sys.executable, "ghost_eye.py", "-t", "127.0.0.1:8898",
                    "-m", "headers", "-o", out, "--no-color"],
                   stdout=subprocess.DEVNULL, timeout=40, check=True)
    assert os.path.getsize(out) > 0, "report empty"
finally:
    httpd.terminate()
print("  live checks passed")
PY
check $?

if [ "$fail" -ne 0 ]; then
    printf '\n\033[31mVERIFY FAILED — do not ship.\033[0m\n'; exit 1
fi
printf '\n\033[32mVERIFY PASSED — safe to ship.\033[0m\n'
