# syntax=docker/dockerfile:1.7
# ============================================================================
# Sanegor backend — multi-stage build
# Stage 1 builds wheels with a toolchain; stage 2 ships only the runtime.
# ============================================================================

FROM python:3.12-slim-bookworm AS builder

ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential \
        libpq-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY requirements.txt .
RUN python -m venv /opt/venv \
    && /opt/venv/bin/pip install --upgrade pip setuptools wheel \
    && /opt/venv/bin/pip install -r requirements.txt


# ---------------------------------------------------------------------------
FROM python:3.12-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONPATH=/app \
    PATH="/opt/venv/bin:$PATH" \
    TZ=Asia/Jerusalem

# tesseract-ocr-heb   — Hebrew OCR model (the whole point of the OCR feature)
# poppler-utils       — PDF rasterisation fallback for scanned PDFs
# fonts-noto-core     — Hebrew glyphs for PDF export; without it exports are boxes
# libgl1/libglib2.0-0 — OpenCV runtime for image pre-processing
RUN apt-get update && apt-get install -y --no-install-recommends \
        tesseract-ocr \
        tesseract-ocr-heb \
        tesseract-ocr-eng \
        poppler-utils \
        fonts-noto-core \
        fonts-noto-hinted \
        libgl1 \
        libglib2.0-0 \
        libpq5 \
        curl \
        tini \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /opt/venv /opt/venv

# Run as an unprivileged user; the storage volume is the only writable path.
RUN groupadd --system --gid 1001 sanegor \
    && useradd --system --uid 1001 --gid sanegor --create-home sanegor \
    && mkdir -p /var/lib/sanegor/storage \
    && chown -R sanegor:sanegor /var/lib/sanegor

WORKDIR /app
COPY --chown=sanegor:sanegor . .

USER sanegor
EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:8000/health/live || exit 1

# tini reaps zombies left by tesseract/pdftoppm subprocesses.
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["uvicorn", "app.main:app", \
     "--host", "0.0.0.0", \
     "--port", "8000", \
     "--workers", "4", \
     "--proxy-headers", \
     "--forwarded-allow-ips", "*", \
     "--timeout-keep-alive", "75"]
