# Monitoring

Prometheus scrapes the internal API `/metrics`. Production Nginx returns 404 for the public `/metrics` route. If direct metrics access is ever required, use `METRICS_TOKEN` and network ACLs.

Metrics include HTTP request count, latency and errors plus AI requests, latency, failures, cache hits and rate-limit-related counters. Grafana provisioning includes `AI Platform Overview` panels for request rate, p95 HTTP latency, HTTP errors, AI calls, p95 AI latency and AI errors.

Recommended alerts: readiness failing for 2+ minutes; HTTP 5xx >2% for 5 minutes; p95 latency over service objective; AI provider error spike; circuit-open events; database disk under 20%; backup older than 26 hours; Redis memory pressure; unexpected authentication-failure or refresh-reuse spike.

Logs are JSON and include timestamp, severity, request ID, path, response status and latency. Sensitive-key redaction covers passwords, authorization/cookies, tokens, secrets and API keys. Uvicorn's default access log is disabled in the container to avoid duplicate unstructured logging.

## Data retention

Retention is enforced by the `scheduler` service, which enqueues `cleanup_expired_data` every `RETENTION_SWEEP_INTERVAL_SECONDS` (default 24h). The sweep deletes expired refresh, reset and verification tokens, audit logs and security events older than `AUDIT_RETENTION_DAYS`, AI request metadata older than `AI_METADATA_RETENTION_DAYS`, and engineering events older than `AUDIT_RETENTION_DAYS`.

The `scheduler` container must be running for any of these windows to take effect: before 2.1.1 the actor existed but nothing ever enqueued it, so the configured windows were never applied. Check it with `docker compose ps scheduler` and look for `retention_sweep_completed` in the worker logs.

## Metric cardinality

HTTP metrics are labelled with the matched route template. Unmatched requests are labelled `unmatched` rather than their raw path, so arbitrary 404 URLs cannot create unbounded time series.
