# Security Hardening

## Host/network

Patch Ubuntu, Docker and kernel regularly. Restrict SSH by source IP and key authentication. Publish only 443/80. Keep PostgreSQL/Redis/API ports private. Use separate production credentials and encrypted off-host backups.

## Application

Use a random JWT secret of at least 64 characters. Keep access lifetime short. Configure exact CORS origins and trusted hosts; production validation rejects wildcard entries. Set HTTPS-only public URLs. Keep `PROMPT_LOGGING_ENABLED=false` unless encrypted retention has an approved purpose and retention policy.

## Containers

The API runs as UID/GID 10001, uses a read-only filesystem and tmpfs, drops all Linux capabilities and enables `no-new-privileges`. Nginx uses the unprivileged image and high container ports. PostgreSQL and Redis have no production host port mappings.

## Admin

Use a distinct administrator account and long unique password. Avoid persistent browser token storage. Restrict admin access at VPN/WAF/IP level when possible. Review admin/audit logs and revoke unused admin accounts.

## Android

Protect release signing keys outside the repository. Production disallows cleartext. Enable certificate pinning only with an operational rotation plan. Do not bundle DeepSeek, SMTP, FCM-service-account or payment secrets.

## CI

Workflows run Ruff/mypy/pytest, Flutter analysis/tests/build, admin lint/tests/build, Bandit, pip-audit, npm audit, Semgrep, Trivy and Gitleaks. Treat scanner exceptions as reviewed suppressions rather than blanket ignores.
