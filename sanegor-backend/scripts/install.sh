#!/usr/bin/env bash
# ============================================================================
# Sanegor backend — local development setup.
# Creates a virtualenv, installs dependencies, generates secrets into .env.
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$1"; }
warn()  { printf '\033[1;33m!!\033[0m %s\n' "$1" >&2; }
fatal() { printf '\033[1;31mxx\033[0m %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- python
info "Checking Python version"
command -v python3 >/dev/null || fatal "python3 not found"
PY_VERSION="$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')"
python3 - <<'EOF' || fatal "Python 3.12+ is required (found $PY_VERSION)"
import sys
raise SystemExit(0 if sys.version_info >= (3, 12) else 1)
EOF
info "Python $PY_VERSION"

# ------------------------------------------------------------ virtualenv
if [[ ! -d .venv ]]; then
    info "Creating virtualenv at .venv"
    python3 -m venv .venv
fi
# shellcheck disable=SC1091
source .venv/bin/activate

info "Installing dependencies"
pip install --quiet --upgrade pip setuptools wheel
if [[ "${DEV:-1}" == "1" ]]; then
    pip install --quiet -r requirements-dev.txt
else
    pip install --quiet -r requirements.txt
fi

# ------------------------------------------------------------------ .env
if [[ ! -f .env ]]; then
    info "Creating .env with generated secrets"
    cp .env.example .env

    SECRET_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(64))')"
    ENCRYPTION_KEY="$(python3 -c 'from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())')"

    # -i.bak keeps this portable between GNU and BSD sed.
    sed -i.bak "s|^SECRET_KEY=.*|SECRET_KEY=${SECRET_KEY}|" .env
    sed -i.bak "s|^ENCRYPTION_KEY=.*|ENCRYPTION_KEY=${ENCRYPTION_KEY}|" .env
    rm -f .env.bak

    warn "Set DEEPSEEK_API_KEY in .env before using the AI endpoints."
else
    info ".env already exists — leaving it alone"
fi

# ------------------------------------------------------------- system deps
info "Checking optional system dependencies"
command -v tesseract >/dev/null \
    || warn "tesseract not found — OCR disabled. Install: apt install tesseract-ocr tesseract-ocr-heb"
if command -v tesseract >/dev/null && ! tesseract --list-langs 2>/dev/null | grep -q '^heb$'; then
    warn "Hebrew OCR model missing. Install: apt install tesseract-ocr-heb"
fi
fc-list 2>/dev/null | grep -qi 'noto.*hebrew' \
    || warn "No Hebrew font found — PDF export will not render Hebrew. Install: apt install fonts-noto-core"

cat <<'EOF'

Setup complete.

Next steps:
  1. Start the datastores:   docker compose up -d postgres redis
  2. Apply migrations:       alembic upgrade head
  3. Load a legal corpus:    python scripts/seed_corpus.py path/to/corpus.json
                             (no legal text ships with this repo — see the README)
  4. Run the API:            uvicorn app.main:app --reload
  5. Open the docs:          http://localhost:8000/docs

Run the tests with:          pytest
EOF
