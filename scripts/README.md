## Scripts

Python automation lives here (fetching public career pages, scheduling checks, backups, etc.).

### Setup

```bash
python3 -m venv scripts/.venv
source scripts/.venv/bin/activate
pip install -r scripts/requirements.txt
```

Scripts load env vars from the repo-root `.env`. You can also provide `SCRIPTS_API_TOKEN` to avoid login.

Recommended `.env` additions for scripts:

```bash
SCRIPTS_API_TOKEN=...
# OR
SCRIPTS_EMAIL=you@example.com
SCRIPTS_PASSWORD=...
```

### Conventions
- No hardcoded secrets (use `.env`)
- Support `--dry-run` where useful
- Never scrape LinkedIn/Indeed/Glassdoor or login-required websites

### Commands

Recommended invocation (avoids Python path issues):

```bash
python -m scripts.check_followups --help
python -m scripts.generate_weekly_report --help
```

Check follow-ups due (read-only):

```bash
python -m scripts.check_followups --dry-run
python -m scripts.check_followups
```

Generate weekly report (read-only):

```bash
python -m scripts.generate_weekly_report
python -m scripts.generate_weekly_report --format text
```

Backup database (MySQL in Docker Compose):

```bash
python -m scripts.backup_database --dry-run
python -m scripts.backup_database --output backups/careerpilot.sql
```

Import applications from CSV:

```bash
python -m scripts.import_applications_from_csv --dry-run ./applications.csv
python -m scripts.import_applications_from_csv ./applications.csv
```

Placeholders:

```bash
python -m scripts.job_watcher --dry-run
python -m scripts.ai_interview_planner --dry-run
```

### Troubleshooting

- If `.env` isn't being picked up, scripts look for it at repo root (next to `docker-compose.yml`). Run from the repo root or set env vars in your shell.
- If `mysql-connector-python` fails to install on your machine, alternatives:
  - `pip install pymysql` and update `common/db.py` to use PyMySQL
  - or run scripts in a Docker container
