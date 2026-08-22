FROM python:3.12.11-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --create-home app  && apt-get update && apt-get install -y --no-install-recommends ca-certificates git  && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY alembic.ini ./
COPY alembic ./alembic
COPY app ./app
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 CMD ["python","-c","import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health/live',timeout=3).read()"]
# Trust proxy headers only from the Nginx gateway. With "*" any peer that reaches the
# API can spoof X-Forwarded-For and defeat per-IP rate limiting and audit attribution.
ENV FORWARDED_ALLOW_IPS=""
CMD ["sh","-c","exec uvicorn app.main:app --host 0.0.0.0 --port 8080 --workers 2 --proxy-headers --forwarded-allow-ips \"${FORWARDED_ALLOW_IPS:-nginx}\" --no-access-log"]
