# Production Deployment

## Host

Use a supported Ubuntu LTS host, patched regularly. Allocate enough disk for PostgreSQL, Docker layers and backups. Production traffic should terminate at Nginx only.

Recommended UFW policy:

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 443/tcp
sudo ufw allow 80/tcp   # ACME/redirect when used
sudo ufw allow from YOUR_ADMIN_IP to any port 22 proto tcp
sudo ufw enable
```

Do not add public rules for 5432, 6379 or the FastAPI port 8080.

## Install

Extract the release into a controlled directory such as `/opt/ai-platform`, create `.env` from `.env.example`, set mode 600, and run `./INSTALL_ALL.sh`. The installer is idempotent: it does not overwrite `.env`, installs Docker only when absent, validates Compose, starts dependencies, builds containers, runs Alembic, starts the stack and waits for readiness.

## TLS with Let's Encrypt

Set `SERVER_NAME=api.example.com` in `.env`, point DNS at the host, and create `/var/www/certbot`. Obtain the initial certificate with Certbot on the host or a controlled Certbot container. The production Nginx overlay mounts `/etc/letsencrypt` read-only. Renew automatically with Certbot and reload Nginx after successful renewal.

Example host install:

```bash
sudo apt-get install -y certbot
sudo certbot certonly --standalone -d api.example.com
sudo docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

If port 80 is already occupied during the initial standalone challenge, stop the gateway briefly or use the webroot challenge at `/var/www/certbot`.

## Admin

Create the first administrator interactively:

```bash
docker compose run --rm api python -m app.cli create-admin
```

Do not keep `ADMIN_INITIAL_PASSWORD` in `.env` after bootstrap unless an automated secret-injection system requires it.

## Verification

Run `./VERIFY_INSTALL.sh` after installation and upgrades. A production smoke test should also confirm the public HTTPS certificate, `/health/ready`, login, one non-billable integration check, backup creation and recovery procedure.
