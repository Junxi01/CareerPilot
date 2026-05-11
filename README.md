## CareerPilot Local

Self-hosted **AI career assistant**: target companies, public career URLs, job leads, applications, interview prep plans, reminders, backups, and weekly reporting. Configure LLM keys and secrets in **`.env`** only (never commit — see **`SECURITY.md`**).

**License:** [**MIT**](LICENSE).

---

### Screenshots (placeholders)

Screenshots live under **`docs/images/`**. Add PNGs and uncomment or replace the lines below.

<!--
![Dashboard](docs/images/readme-dashboard.png)
![Job leads](docs/images/readme-job-leads.png)
![Interview plan](docs/images/readme-interview-plan.png)
-->

<!-- screenshot: Dashboard — `docs/images/readme-dashboard.png` -->
<!-- screenshot: Job leads — `docs/images/readme-job-leads.png` -->
<!-- screenshot: Interview plan — `docs/images/readme-interview-plan.png` -->

---

### Product boundaries

- **Supported:** user-configured **public** `http(s)` career pages.
- **Not supported:** LinkedIn / Indeed / Glassdoor automation, login-gated boards, or scraping policy violations.

---

### Prerequisites

| Tool | Role |
|------|------|
| **Docker** + Compose v2 | MySQL (and optional full stack) |
| **JDK 21** | Backend `./gradlew` |
| **Node.js 18+** (20+ recommended) | Frontend |
| **Python 3.11+** | Scripts (`pip install -r scripts/requirements.txt`) |

Maintainers / AI assistants: see **`PROJECT_CONTEXT.md`** for architecture and handoff rules.

---

### Quick start (clone → run)

```bash
git clone <your-fork-or-upstream-url>
cd careerpilot-local
cp .env.example .env
# Required: set JWT_SECRET (e.g. openssl rand -hex 32). Do not use empty or change-me* values.
```

**Option A — Full stack in Docker (recommended first run)**

```bash
docker compose up --build -d
```

| Service | Default URL |
|---------|-------------|
| Web app | **http://localhost:3000** (`FRONTEND_PORT`) |
| API | **http://localhost:8080** (`BACKEND_PORT`) |
| MySQL | **localhost:3306** |

Health: `curl -s http://localhost:8080/health` · `curl -s http://localhost:3000/health` · `docker compose ps`

Stop: `docker compose down`. **Erase DB volume:** `docker compose down -v`.

**Option B — MySQL in Docker, backend + Vite on host**

```bash
./scripts/local-up.sh
```

Frontend: **http://localhost:5173** · API: **http://localhost:8080**.

**First-time database:** Compose applies **`database/schema.sql`** + **`database/seed.sql`** on a **new** volume. Optional login: **`demo@careerpilot.local`** / **`demo12345`** — see **`database/README.md`**.

---

### Demo flow (guided tour)

Step-by-step commands and UI path: **[`docs/demo.md`](docs/demo.md)**.

1. Register (or use seeded demo user).
2. Add a target company (public careers URL).
3. Run **`job_watcher`** on **`scripts/examples/mock_careers_page.html`**.
4. Generate an interview plan in **`AI_PROVIDER=mock`**.
5. Save a job lead as an application.
6. Update application status (Kanban / Applications).
7. Run **`generate_weekly_report.py`**.
8. Run **`backup_database.py`**.

---

### Documentation

| Doc | Purpose |
|-----|---------|
| [`docs/demo.md`](docs/demo.md) | End-to-end demo + reset |
| [`docs/troubleshooting.md`](docs/troubleshooting.md) | Common errors |
| [`docs/local-setup.md`](docs/local-setup.md) | Detailed local & Compose setup |
| [`docs/ai-setup.md`](docs/ai-setup.md) | AI keys, mock mode, privacy |
| [`docs/interview-plan-api.md`](docs/interview-plan-api.md) | Interview plan REST |
| [`docs/backup-and-restore.md`](docs/backup-and-restore.md) | Backups |
| [`docs/database-schema.md`](docs/database-schema.md) | Tables |
| [`SECURITY.md`](SECURITY.md) | Secrets policy, reporting |

---

### Configuration

- **`.env.example`** — template only; copy to **`.env`** (gitignored). Scripts also read **`SCRIPTS_EMAIL`** / **`SCRIPTS_PASSWORD`** or **`SCRIPTS_API_TOKEN`** — see `.env.example` tail.
- **`frontend/.env.example`** — **`VITE_*`** for host dev.

---

### Quality checks & CI

```bash
make test    # backend tests + frontend typecheck/lint/build + Python smoke
```

`make test` creates `scripts/.venv-test` and installs Python test dependencies automatically.

GitHub Actions: **`.github/workflows/ci.yml`**.

---

### Tech stack

- **Backend:** Kotlin, Ktor, Gradle, MySQL (H2 in tests)
- **Frontend:** React 18, TypeScript, Vite
- **Scripts:** Python (**`scripts/`**)
- **Deploy:** **`docker-compose.yml`**, **`Makefile`**

---

### Repo layout

```
careerpilot-local/
  PROJECT_CONTEXT.md       # maintainer / Cursor handoff (zh + en context)
  SECURITY.md
  LICENSE
  README.md                # this file
  .env.example
  Makefile
  docker-compose.yml
  database/                # schema.sql, seed.sql
  backend/
  frontend/
  scripts/
  docs/
  tests/                   # pytest smoke (Day 29)
  .github/workflows/
```

### Git hygiene (optional)

```bash
./scripts/install-git-hooks.sh
```

Strips `Co-authored-by:` / `Made-with:` trailers so commits stay single-author if you want.

---

### Roadmap / status

Feature status and backlog: **`PROJECT_CONTEXT.md`** §4–§7.
