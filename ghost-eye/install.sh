#!/usr/bin/env bash
# Ghost Eye — one-command installer for Linux, macOS and Termux (Android).
# Reconnaissance/detection only. FOR AUTHORISED SECURITY TESTING ONLY.
#
#   curl -fsSL <raw-url>/install.sh | bash
# or, from a checkout:
#   bash install.sh
#
# It installs Ghost Eye and its dependencies, and drops an `update` helper that
# pulls the latest version and re-installs (feature 80: auto-update).
set -eu

REPO_URL="${GHOSTEYE_REPO:-https://github.com/yairpeer68-svg/yairpeer.github.io.git}"
DEST="${GHOSTEYE_HOME:-$HOME/ghost-eye}"

say(){ printf '\033[36m» %s\033[0m\n' "$1"; }
err(){ printf '\033[31m✗ %s\033[0m\n' "$1" >&2; }

is_termux(){ [ -n "${PREFIX:-}" ] && echo "$PREFIX" | grep -q com.termux; }

say "Ghost Eye installer — authorised security testing only"

# --- 1. dependencies -------------------------------------------------------
if is_termux; then
  say "Termux detected — installing base packages"
  pkg install -y python git >/dev/null 2>&1 || pkg install -y python git
  REQ="requirements-termux.txt"
elif command -v apt-get >/dev/null 2>&1; then
  say "Debian/Ubuntu detected"
  sudo apt-get update -y >/dev/null 2>&1 || true
  sudo apt-get install -y python3 python3-pip git >/dev/null 2>&1 || true
  REQ="requirements.txt"
elif command -v brew >/dev/null 2>&1; then
  say "macOS/Homebrew detected"
  brew install python git >/dev/null 2>&1 || true
  REQ="requirements.txt"
else
  say "Unknown platform — assuming python3 + git are already present"
  REQ="requirements.txt"
fi

command -v python3 >/dev/null 2>&1 || { err "python3 not found; install it and re-run"; exit 1; }
command -v git      >/dev/null 2>&1 || { err "git not found; install it and re-run"; exit 1; }

# --- 2. fetch / update the source -----------------------------------------
if [ -d "$DEST/.git" ]; then
  say "Updating existing checkout in $DEST"
  git -C "$DEST" pull --ff-only || err "git pull failed (local changes?) — continuing with current copy"
elif [ -f "./ghost_eye.py" ]; then
  say "Running from a checkout — using the current directory"
  DEST="$(pwd)"
else
  say "Cloning into $DEST"
  git clone --depth 1 "$REPO_URL" "$DEST"
fi
cd "$DEST"

# --- 3. python deps + editable install ------------------------------------
say "Installing Python dependencies ($REQ)"
python3 -m pip install --upgrade pip >/dev/null 2>&1 || true
python3 -m pip install -r "$REQ" || err "some optional deps failed — Ghost Eye still runs (modules degrade gracefully)"
python3 -m pip install -e . >/dev/null 2>&1 || true

# --- 4. auto-update helper -------------------------------------------------
say "Installing the 'update' helper"
cat > "$DEST/update.sh" <<'UPD'
#!/usr/bin/env bash
# Pull the latest Ghost Eye and re-install (feature 80: auto-update).
set -eu
cd "$(dirname "$0")"
echo "» updating Ghost Eye…"
git pull --ff-only
python3 -m pip install -e . >/dev/null 2>&1 || true
echo "✓ up to date — $(python3 -c 'import ghost_eye;print("v"+ghost_eye.__version__)')"
UPD
chmod +x "$DEST/update.sh"

VER="$(python3 -c 'import ghost_eye;print(ghost_eye.__version__)' 2>/dev/null || echo '?')"
say "Installed Ghost Eye v$VER in $DEST"
cat <<EOF

  Run it:
    cd "$DEST"
    python3 ghost_eye.py                 # interactive CLI
    python3 ghost_eye.py -t example.com -p quick
    python3 ghost_eye_web.py --open      # web dashboard
  Update later:
    "$DEST/update.sh"

  Reconnaissance / detection only — no exploitation. Authorised testing only.
EOF
