#!/usr/bin/env bash
# ============================================================================
# One-time development setup on a Debian-based Linux (Kali, Debian, Ubuntu).
#
#   ./scripts/setup-dev-linux.sh
#
# Installs what both halves of the project need: the backend's document and
# OCR dependencies, Docker, and the system libraries Flutter and adb expect.
#
# Idempotent — safe to re-run. It reports what is already present rather than
# reinstalling, and never touches Flutter or the Android SDK themselves, which
# are better installed under your own user than through apt.
# ============================================================================
set -euo pipefail

info() { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
ok()   { printf '\033[1;32m ok\033[0m %s\n' "$1"; }
warn() { printf '\033[1;33m !!\033[0m %s\n' "$1"; }

command -v apt-get >/dev/null || {
    echo "This script targets Debian-based systems." >&2
    exit 1
}

have() { command -v "$1" >/dev/null 2>&1; }
pkg_installed() { dpkg -s "$1" >/dev/null 2>&1; }

install_missing() {
    local missing=()
    for pkg in "$@"; do
        pkg_installed "$pkg" || missing+=("$pkg")
    done
    if [[ ${#missing[@]} -eq 0 ]]; then
        ok "already installed: $*"
        return
    fi
    info "installing: ${missing[*]}"
    sudo apt-get install -y --no-install-recommends "${missing[@]}"
}

info "Refreshing package lists"
sudo apt-get update -qq

# ---------------------------------------------------------------- backend
info "Backend: Python, document parsing, OCR, PDF fonts"
install_missing \
    python3 python3-venv python3-dev build-essential \
    tesseract-ocr tesseract-ocr-heb tesseract-ocr-eng \
    poppler-utils \
    fonts-noto-core fonts-noto-hinted \
    libgl1 libglib2.0-0 \
    libpq-dev

# Hebrew OCR is the whole point of the scanning feature; verify rather than
# assume the language pack landed.
if have tesseract && tesseract --list-langs 2>/dev/null | grep -q '^heb$'; then
    ok "Hebrew OCR model present"
else
    warn "Hebrew OCR model missing — scanning will not read Hebrew"
fi

# Without a Hebrew-capable font, PDF export renders boxes instead of letters.
if fc-list 2>/dev/null | grep -qi 'noto.*hebrew\|dejavu'; then
    ok "Hebrew-capable font present for PDF export"
else
    warn "No Hebrew font found — PDF export will not render Hebrew"
fi

# ----------------------------------------------------------------- docker
info "Docker"
if have docker; then
    ok "docker present ($(docker --version | cut -d, -f1))"
else
    install_missing docker.io docker-compose-v2
fi

if groups "$USER" | grep -qw docker; then
    ok "$USER is in the docker group"
else
    info "Adding $USER to the docker group"
    sudo usermod -aG docker "$USER"
    warn "Log out and back in (or run: newgrp docker) before using docker."
fi

# ------------------------------------------------------- flutter / android
info "Flutter and Android prerequisites"
# Flutter itself is deliberately not installed here: the apt/snap builds lag
# behind and the SDK is best kept in your home directory where `flutter
# upgrade` can manage it.
install_missing curl git unzip xz-utils zip libglu1-mesa

# adb needs udev rules to see a phone as a non-root user; without them the
# device shows up as "no permissions".
if pkg_installed android-sdk-platform-tools-common; then
    ok "adb udev rules installed"
else
    info "Installing adb udev rules"
    sudo apt-get install -y --no-install-recommends android-sdk-platform-tools-common || \
        warn "Could not install udev rules — if adb shows 'no permissions', install them manually"
fi

if have flutter; then
    ok "flutter present ($(flutter --version 2>/dev/null | head -1))"
else
    warn "Flutter is not installed. Recommended:"
    cat <<'EOF'

    git clone --depth 1 -b stable https://github.com/flutter/flutter.git ~/flutter
    echo 'export PATH="$HOME/flutter/bin:$PATH"' >> ~/.bashrc
    exec bash
    flutter doctor

  `flutter doctor` will then say what Android tooling is still missing. The
  Android SDK command-line tools are enough — Android Studio is not required
  for building to a physical device.

EOF
fi

# --------------------------------------------------------------- summary
cat <<'EOF'

Setup finished.

Backend:
    cd sanegor-backend
    ./scripts/install.sh
    docker compose up -d postgres redis
    alembic upgrade head
    ./scripts/serve-lan.sh          # serves the phone over WiFi

App:
    cd sanegor-app
    flutter pub get
    ./scripts/run-local.sh run

Phone over USB:
    Enable Developer options, then USB debugging, then:
    adb devices                     # should list the phone, not "unauthorized"

On native Linux the WSL2 port-forwarding steps in the README do not apply —
the server is reachable on the LAN as soon as it binds 0.0.0.0.
EOF
