# Backup and Restore

A complete recovery point is **two** artifacts. The database alone is not sufficient.

## What is captured

`./scripts/backup.sh` produces both:

1. **PostgreSQL** — `pg_dump` inside the container, custom format, compression level 9, written mode-600 with a SHA-256 sidecar.
2. **Engineering workspaces** — a zstd-compressed tar of the `engineering_workspaces` volume, also with a SHA-256 sidecar.

The volume holds every user's imported source code **and the Git history that checkpoint rollback depends on**. Restoring the database without it produces rows describing projects, checkpoints and commits whose files no longer exist: the project tree is empty and `POST /projects/{id}/checkpoints/{id}/rollback` fails.

Set `BACKUP_WORKSPACES=0` only when you have deliberately accepted a database-only recovery point.

Set `BACKUP_DIR` to choose local storage, and `BACKUP_UPLOAD_DIR` to copy both artifacts to a separately mounted filesystem. The project intentionally embeds no cloud credentials.

## Verifying

```bash
./scripts/verify-backup.sh backups/ai_platform-<ts>.dump backups/workspaces-<ts>.tar.zst
```

Both checksums are checked, `pg_restore --list` parses the database archive, and the workspace tar is listed end to end. Omitting the second argument prints a warning: a database-only check does not prove a complete recovery point.

## Restoring

```bash
./scripts/restore.sh backups/ai_platform-<ts>.dump backups/workspaces-<ts>.tar.zst
```

Restore is destructive and requires typing `RESTORE`. The script stops `api`, `worker` and `scheduler` before dropping the database — terminating backends alone is not enough, because those pools reconnect immediately and `dropdb` then fails with *"database is being accessed by other users"*. Services are restarted on exit, including on failure.

Omitting the workspace archive is allowed and prints an explicit warning.

## Retention

`BACKUP_RETENTION_DAYS` prunes both artifact types and their sidecars. Database row retention is separate and is applied by the `scheduler` service (`AUDIT_RETENTION_DAYS`, `AI_METADATA_RETENTION_DAYS`); see `MONITORING.md`.

A backup is not proven until a restore has been exercised on an isolated host. Schedule restore drills and record the resulting RPO/RTO.
