# Disaster Recovery

1. Provision a patched Ubuntu LTS host with adequate CPU, RAM and storage.
2. Restore firewall policy: 443 public, optional 80 for ACME, SSH restricted by source; do not expose 5432/6379/8080.
3. Extract the same or newer compatible AI Platform release into `/opt/ai-platform`.
4. Restore `.env` and provider/TLS secrets from the secret manager; never reconstruct secrets from source control.
5. Run `./INSTALL_ALL.sh` to install Docker, build services and create an empty migrated database.
6. Run `./scripts/restore.sh /secure/path/backup.dump /secure/path/workspaces.tar.zst`. The script stops `api`, `worker` and `scheduler` itself before dropping the database, and restarts them afterwards. Supplying the workspace archive is required for a complete recovery: without it the database describes engineering projects and Git checkpoints whose files do not exist.
7. Run the current Alembic migration again if the restored backup predates the release.
8. Restore `/etc/letsencrypt` from the CA/host process or issue a new certificate.
9. Start `docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d`.
10. Run `./VERIFY_INSTALL.sh`; confirm public TLS, login, device registration, AI provider configuration, worker connectivity, monitoring and a fresh backup.
11. Rotate any secret that may have been exposed during the incident and document the recovery timeline.

## What backups do and do not contain

`scripts/backup.sh` captures the PostgreSQL database **and** the `engineering_workspaces` volume (imported project source plus its Git checkpoint history). Both are needed; see `BACKUP.md`.

Backups do **not** include `.env`, TLS private keys, Android signing keys or external provider credentials. Those require an independent secret-management recovery plan.

## Verifying an engineering recovery

After step 10, confirm the runtime specifically:

- `GET /api/v1/engineering/projects` lists the expected projects.
- `GET /api/v1/engineering/projects/{id}/tree` returns files, not an empty list.
- `GET /api/v1/engineering/projects/{id}/checkpoints` lists checkpoints, and `git-status` on a project responds.

An empty tree with populated checkpoint rows means the workspace volume was not restored.
