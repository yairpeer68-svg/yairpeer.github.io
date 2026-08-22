FROM python:3.12.11-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
RUN groupadd --gid 10001 runner && useradd --uid 10001 --gid runner --create-home runner \
 && apt-get update && apt-get install -y --no-install-recommends git nodejs npm openjdk-17-jdk-headless ca-certificates \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /runner
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt pytest ruff mypy
COPY runner.py ./
# Only Python and Node toolchains are present here. Flutter/Dart/Gradle gates report
# toolchain_missing; build Dockerfile.flutter when those projects must be verified.
USER 10001:10001
EXPOSE 8090
CMD ["uvicorn","runner:app","--host","0.0.0.0","--port","8090","--no-access-log"]
