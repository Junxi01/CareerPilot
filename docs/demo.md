# Demo walkthrough

End-to-end flow for a **new developer** after **clone** + **`.env`** + **run stack** (`docker compose up --build -d` or `./scripts/local-up.sh`). Screenshots placeholders live under **`docs/images/`** — add PNGs named like `readme-dashboard.png` and link them from **`README.md`**.

---

## Prerequisites

```bash
cd careerpilot-local
cp .env.example .env
# Edit .env: JWT_SECRET (openssl rand -hex 32), keep AI_PROVIDER=mock for scripted demo
python3 -m pip install -r scripts/requirements.txt   # scripts only
docker compose up -d mysql                  # minimal DB, OR full stack below
```

**Full stack:**

```bash
docker compose up --build -d
```

Open **http://localhost:3000** (Compose) or **http://localhost:5173** (`local-up.sh`).

---

## Optional: seeded demo user (Compose / first MySQL init)

If MySQL ran **`database/seed.sql`** on **first volume init**, you can sign in:

| Field | Value |
|--------|--------|
| Email | `demo@careerpilot.local` |
| Password | `demo12345` |

Seeded companies **Acme Corp** / **Globex** and sample job leads appear for that account.  
If you **registered first** with another email, use that account instead (seed skipped or different DB state).

---

## 1. Register

- Open **`/register`**, create an account (**email + password + display name**), or login as **`demo@careerpilot.local`** if seed is active.
- You land on **Dashboard** after login.

---

## 2. Add a target company

- Go to **Target companies** → **Add** (or equivalent).
- Example (safe, public-style URL for testing):
  - **Name:** `Mock Careers Co`
  - **Careers URL:** `https://example.com/careers` (or any **http(s)** URL not on the blocklist — see **`README`** / **`PROJECT_CONTEXT`**)
  - Add **keywords** / **locations** if you want scoring (optional).
- Save. Note **`company_id`** if you use **`--company-id`** below (from API or UI network tab).

---

## 3. Run job watcher on the mock HTML page

From repo root, with API up and **script auth** configured:

```bash
# In .env (same copy you use for the app):
# SCRIPTS_EMAIL=your@email.com
# SCRIPTS_PASSWORD=your-password
# Or: SCRIPTS_API_TOKEN=<JWT from login>

python3 -m scripts.job_watcher \
  --mock-html scripts/examples/mock_careers_page.html \
  --mock-page-url https://careers.example.com/jobs/ \
  --no-discovery
```

This **parses** the fixture and **POSTs** new job leads for your active target companies (no external HTTP to the mock file). Use **`--dry-run`** first to preview. If you have more than one active company, pass **`--company-id <id>`**.

---

## 4. Generate AI interview plan (mock mode)

Ensure **`.env`** has **`AI_PROVIDER=mock`**.

Pick a **job lead id** from **Job leads** (or seed data).

```bash
python3 -m scripts.ai_interview_planner <job_lead_id> --dry-run
```

Review **`reports/interview_plan_<id>_dry_run.md`**.  
To persist via API (requires valid auth + same contract as UI):

```bash
# After generating JSON (see scripts/ai_interview_planner.py help), OR use planner without --dry-run with DB/API configured
python3 -m scripts.ai_interview_planner <job_lead_id>
```

See **`docs/interview-plan-api.md`** for **`POST /api/job-leads/{id}/interview-plan`** and **`docs/ai-setup.md`** for modes.

---

## 5. Save job lead as application

- Open **Job leads**, select a lead → **Save as application** (or equivalent).

---

## 6. Update status

- Open **Applications** (or **Kanban**): change status (e.g. **SAVED → APPLIED**).

---

## 7. Generate weekly report

Requires **MySQL** reachable with **`DB_*`** from **`.env`** (host scripts use **`localhost`** when DB is Compose-published).

```bash
python3 -m scripts.generate_weekly_report --dry-run    # stdout only
python3 scripts/generate_weekly_report.py              # writes under reports/
```

---

## 8. Backup database

With Compose MySQL (and **`docker`** on PATH):

```bash
python3 scripts/backup_database.py --dry-run
python3 scripts/backup_database.py
```

Outputs typically under **`backups/`**. Restore recipes: **`docs/backup-and-restore.md`**.

---

## Reset sample data

**Destructive:** removes Docker volume data.

```bash
docker compose down -v
docker compose up --build -d    # mysql re-runs schema + seed on fresh volume
```

**Host-only MySQL volume:** delete the Docker volume Compose uses for `mysql_data`.

**Manual reseed (advanced):**

```bash
docker compose exec -T mysql mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" \
  < database/seed.sql
```

(adjust flags if your Compose env differs.)

After reset, redo **JWT** / **login** steps as needed.

---

## References

| Doc | Topic |
|-----|--------|
| **`README.md`** | Quick start, ports, Compose |
| **`docs/troubleshooting.md`** | Common errors |
| **`docs/local-setup.md`** | Detailed local setup |
| **`docs/interview-plan-api.md`** | Interview plan REST |
| **`docs/ai-setup.md`** | AI keys & mock |
| **`docs/backup-and-restore.md`** | Backup / restore |
