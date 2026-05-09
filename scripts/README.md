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

### AI provider (scripts abstraction)

`scripts/common/ai_provider.py` implements **`AI_PROVIDER=mock`** (deterministic JSON), **`AI_PROVIDER=openai`** (Chat Completions + `response_format` JSON), and **`AI_PROVIDER=gemini`** (Gemini `generateContent` + JSON response MIME). Set **`AI_API_KEY`** / **`AI_MODEL`** for OpenAI, or **`GEMINI_API_KEY`** / **`GEMINI_MODEL`** for Gemini; see `.env.example`.

```bash
cd careerpilot-local
python scripts/test_ai_provider.py
# or: PYTHONPATH=. python -m scripts.test_ai_provider
```

Debug behavior:
- Missing `AI_API_KEY` in OpenAI mode exits with a clear configuration error.
- Missing `GEMINI_API_KEY` / `GOOGLE_API_KEY` in Gemini mode exits with a clear configuration error.
- HTTP 401/403 points to API key/auth/permission; HTTP 429 points to rate limit/quota and retry.
- Timeout/connection/retry failures are reported as `AiProviderError` instead of silent exits.
- JSON parse failures save raw API/model output to `reports/ai_provider_debug_*.json`; request headers and API keys are not written, and any matching key text is redacted.

Gemini example:

```bash
AI_PROVIDER=gemini
GEMINI_MODEL=gemini-2.5-flash
GEMINI_API_KEY=...
python scripts/test_ai_provider.py
```

AI interview planner (calls `scripts/common/ai_provider.py`, loads job lead via API, writes `reports/interview_plan_<job_lead_id>.md`; persists `ai_interview_plans` + `prep_tasks` via **MySQL** — backend HTTP routes for plans are not required):

```bash
python -m scripts.ai_interview_planner --help
python -m scripts.ai_interview_planner 42 --dry-run
python -m scripts.ai_interview_planner --latest-unsaved --dry-run
python -m scripts.ai_interview_planner 42 --from-file ./job_description.txt
```

Needs `SCRIPTS_*` auth and `DB_*` credentials for non–dry-run saves.

Planner behavior:
- AI output is schema-validated before any Markdown or DB write. Missing keys or wrong types fail with a clear `Schema error`.
- Long job descriptions are truncated to 12,000 characters before sending to AI; the script prints a warning and includes the note in the Markdown preview.
- Non–dry-run DB writes use a transaction. If application/plan/task persistence fails, the script rolls back and does not leave a partial plan.
- Re-running for the same application **overwrites** existing `ai_interview_plans` and their `prep_tasks` for that application, then creates a fresh plan.

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

### Run it daily (manual)

From the repo root (recommended), run:

```bash
python -m scripts.check_followups
```

This will write `reports/followups_today.md` (overwrites the file each run) and print a console summary.

Tips:
- If auth fails, set `SCRIPTS_API_TOKEN` in `.env` (recommended) or provide `SCRIPTS_EMAIL` + `SCRIPTS_PASSWORD`.
- The script checks items **due today or overdue** (based on your machine’s local date), but the backend’s notion of “today” follows the server timezone (usually the same machine).

Generate weekly report (read-only):

```bash
python -m scripts.generate_weekly_report --dry-run
python -m scripts.generate_weekly_report
python -m scripts.generate_weekly_report --week previous
```

Backup database (MySQL via Docker Compose by default):

```bash
python -m scripts.backup_database --help
python -m scripts.backup_database --dry-run
python -m scripts.backup_database
python -m scripts.backup_database --compress gzip
python -m scripts.backup_database --no-docker
```

Restore examples and pitfalls: see **`docs/backup-and-restore.md`**.

Import applications from CSV:

```bash
python -m scripts.import_applications_from_csv --dry-run ./applications.csv
python -m scripts.import_applications_from_csv ./applications.csv
```

Job watcher (configured URL as **seed**; by default follows high-scoring same-org + common ATS links; excludes LinkedIn/Indeed/Glassdoor):

```bash
python -m scripts.job_watcher --help
python -m scripts.job_watcher --dry-run
python -m scripts.job_watcher --dry-run --company-id 1
# Single-page mode (legacy: only careers_url HTML, no crawling):
python -m scripts.job_watcher --no-discovery --dry-run
# Verbosity while crawling brand / hub pages toward job search:
python -m scripts.job_watcher --dry-run --verbose-discovery
python -m scripts.job_watcher --dry-run --max-discovery-pages 8 --max-discovery-depth 3
# Parser smoke test against bundled HTML fixture (still calls API for companies/leads metadata):
python -m scripts.job_watcher --dry-run --mock-html scripts/examples/mock_careers_page.html
```

The watcher understands regular career-page links, JSON-LD `JobPosting`, embedded job URLs in page scripts, and public ATS API fallbacks for Ashby, Workday CXS, and SmartRecruiters.

**Limits:** Truly JavaScript-rendered careers sites are still hit-or-miss without a browser engine; this script only uses public HTTP HTML/JSON/API responses.

Placeholders:

```bash
python -m scripts.ai_interview_planner --dry-run
```

### Troubleshooting

- If `.env` isn't being picked up, scripts look for it at repo root (next to `docker-compose.yml`). Run from the repo root or set env vars in your shell.
- If `mysql-connector-python` fails to install on your machine, alternatives:
  - `pip install pymysql` and update `common/db.py` to use PyMySQL
  - or run scripts in a Docker container
