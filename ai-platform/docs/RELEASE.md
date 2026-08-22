# Release Process — 2.1

1. Update `VERSION`, component versions and `CHANGELOG.md`.
2. Run `python -m compileall -q server runner` and backend tests.
3. Run migration review and `alembic upgrade head` against staging PostgreSQL.
4. Run Docker compose validation/build and isolated-runner health checks.
5. Run admin install/lint/test/build.
6. Run Flutter `pub get`, format/analyze/test and a debug APK build; configure release signing only in the protected release environment.
7. Run Bandit, Ruff, mypy, pip-audit/SBOM/CVE feeds, Semgrep, Trivy, Gitleaks and npm audit where configured.
8. Exercise engineering acceptance: ZIP import, code search, run, approval pause/resume, parallel verification, diff, export and rollback.
9. Take a pre-upgrade database backup and verify restore procedure.
10. Run `./scripts/package.sh`, validate every archive with `unzip -t` and validate `SHA256SUMS.txt`.
11. Deploy to staging, run `VERIFY_INSTALL.sh`, then production upgrade with `UPGRADE.sh`.

Do not convert unavailable toolchains or skipped integration gates into successful checks.
