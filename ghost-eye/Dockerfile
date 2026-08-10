# Ghost Eye — reconnaissance / OSINT / exposure-detection toolkit
# Reconnaissance/detection only. FOR AUTHORISED SECURITY TESTING ONLY.
#
#   docker build -t ghost-eye .
#   docker run --rm -p 8777:8777 -e GHOSTEYE_TOKEN=pick-a-secret ghost-eye
#       -> open http://127.0.0.1:8777/?token=pick-a-secret
#   docker run --rm ghost-eye -t example.com -p quick   # one-off CLI scan
#
# The dashboard always requires a token. Set GHOSTEYE_TOKEN to choose it;
# without one the container generates a random token and prints it on startup,
# and you will need to read it from `docker logs` to open the dashboard.
FROM python:3.12-slim

# optional external binaries used when present (nmap etc.); kept minimal
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN pip install --no-cache-dir -e . && chmod +x docker-entrypoint.sh

# Don't scan as root: nothing here needs it, and a recon tool parses a lot of
# untrusted output. Raw-socket features (masscan, nmap SYN scans) are the
# exception — run those with --user root if you actually need them.
RUN useradd --create-home --uid 10001 ghosteye \
    && mkdir -p /app/data \
    && chown -R ghosteye:ghosteye /app
USER ghosteye
ENV HOME=/home/ghosteye

EXPOSE 8777
# a tiny dispatcher: no args (or "web") -> dashboard; anything else -> CLI.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
