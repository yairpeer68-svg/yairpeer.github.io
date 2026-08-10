# Ghost Eye — reconnaissance / OSINT / exposure-detection toolkit
# Reconnaissance/detection only. FOR AUTHORISED SECURITY TESTING ONLY.
#
#   docker build -t ghost-eye .
#   docker run --rm -p 8777:8777 ghost-eye            # dashboard
#   docker run --rm ghost-eye -t example.com -p quick # one-off CLI scan
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

EXPOSE 8777
# a tiny dispatcher: no args (or "web") -> dashboard; anything else -> CLI.
#   docker run --rm -p 8777:8777 ghost-eye              # dashboard
#   docker run --rm ghost-eye -t example.com -p quick   # one-off CLI scan
ENTRYPOINT ["/app/docker-entrypoint.sh"]
