# Backup and restore (MySQL)

CareerPilot keeps data in MySQL (usually the `mysql` service from `docker-compose.yml`). This document describes how to take a logical backup with `mysqldump` and how to restore it.

## Prerequisites

- Repo root: `careerpilot-local/` (same directory as `docker-compose.yml`).
- Root `.env` with at least: `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD` (and Compose service `mysql` reads them from `env_file`).
- Docker running; MySQL container up: `docker compose up -d` and healthy.

## Automated backup script

Install script dependencies (once):

```bash
cd careerpilot-local
python3 -m venv scripts/.venv
source scripts/.venv/bin/activate
pip install -r scripts/requirements.txt
```

### Backup (recommended: Docker Compose)

Default: writes to `backups/<DATABASE>_YYYYMMDD-HHMMSS.sql` under the repo root (directory is created if missing).

```bash
python -m scripts.backup_database
```

Dry-run (no file written; shows plan and **restore template**):

```bash
python -m scripts.backup_database --dry-run
```

Gzip compression:

```bash
python -m scripts.backup_database --compress
# or explicitly:
python -m scripts.backup_database --compress gzip
```

Custom path:

```bash
python -m scripts.backup_database --output backups/my-export.sql
python -m scripts.backup_database --compress --output backups/my-export.sql
# (compression rewrites suffix to .sql.gz when appropriate)
```

If your Compose MySQL service is not named `mysql`, set `--compose-service` or `BACKUP_COMPOSE_SERVICE` in `.env`.

### Backup without Docker (host `mysqldump`)

Requires `mysqldump` on `PATH`. The script reads `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` from `.env` and passes the password only via the `MYSQL_PWD` environment variable inside the subprocess (it is **not** printed).

```bash
python -m scripts.backup_database --no-docker
```

If `mysqldump` is not on your `PATH`, the script prints a short warning on stderr and **falls back to the Docker Compose** path (same as the default behavior), so you can still dump from the container without installing client tools.

## Security: secrets in logs

The backup script **does not** print database passwords or other secrets:

- Docker path: credentials are resolved **inside** the container from Compose/environment variables.
- `--no-docker`: password is supplied only via `MYSQL_PWD` for the mysqldump process; stdout shows placeholders in restore examples.

Restore commands documented below reference `$MYSQL_*` **inside** the container’s shell snippet; do **not** paste `.env` values into chat logs or screenshots.

## Restore (Compress)

From repo root (`careerpilot-local`), with MySQL container running:

```bash
gzip -dc "backups/your_backup.sql.gz" | docker compose -f docker-compose.yml exec -T mysql \
  sh -lc 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
```

## Restore (plain SQL file)

```bash
cat "backups/your_backup.sql" | docker compose -f docker-compose.yml exec -T mysql \
  sh -lc 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
```

The **exact** templates are also printed after you run `python -m scripts.backup_database` successfully (adjust the filename to match yours).

After each successful backup, the script prints the appropriate restore snippet for gzip or plain SQL.

### Warnings before restore

Restoring replaces data in `MYSQL_DATABASE` with the dump contents.

- Prefer taking a backup before restoring.
- If you need clean schema + seed instead, recreate the Docker volume (`docker compose down -v`) and rely on mounted `database/schema.sql` and `seed.sql` on first startup.

## Troubleshooting

- **`docker: command not found`**: Install Docker Desktop or use `--no-docker` with a host `mysqldump`.
- **`mysqldump` not installed on the host**: With `--no-docker`, missing `mysqldump` triggers an automatic fallback to Docker Compose dumps (see stderr message).
- **Backup aborted as empty or tiny**: The script deletes the partial file — fix connectivity or permissions (`Access denied`), then retry.
- **`mysqldump via docker failed`**: Confirm `docker compose ps` shows `mysql` healthy; check `MYSQL_*` values in `.env`.
- **`Access denied`** on restore: ensure the same `.env` that starts Compose is loaded; credential mismatch causes this even when dumps succeed.
