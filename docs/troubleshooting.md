# Troubleshooting

Quick fixes for common **clone → run** problems. Prereqs: **Docker** (with Compose v2), **JDK 21**, **Node 18+**, **Python 3.11+** for scripts. See **`docs/local-setup.md`** for the full setup.

## Backend will not start: JWT / `change-me`

**Symptom:** Log shows JWT / secret error, or process exits immediately.

**Fix:** In **`.env`**, set **`JWT_SECRET`** to a long random string (e.g. `openssl rand -hex 32`). It must not be empty or start with **`change-me`**.

---

## `DB_HOST`: `mysql` vs `localhost`

**Symptom:** Backend logs cannot connect to MySQL, or scripts fail with connection refused.

| How you run the API | Typical `DB_HOST` |
|---------------------|-------------------|
| **Docker Compose** `backend` service | Handled by Compose (**`mysql`**) overriding `.env` |
| Backend on your **host** (`./gradlew run`, `local-up.sh`) | **`localhost`** |
| Python **scripts on host** hitting Compose MySQL | **`localhost`** + port **`3306`** |

Do not set **`DB_HOST=mysql`** when the backend runs on your laptop outside Docker.

---

## Compose: frontend cannot reach API (login fails, network errors)

**Symptom:** Browser shows API errors from **`http://localhost:8080`** or CORS-ish failures.

**Check:**

1. **`docker compose ps`** — **`backend`** should be **`healthy`**.
2. **`VITE_API_BASE_URL`** must be whatever the **browser** uses (default **`http://localhost:8080`** when publishing ports on localhost). Changing it requires **`docker compose build --no-cache frontend`** (build-time embed).

See **`README.md`** Quick start table.

---

## `docker compose config` prints secrets

**Symptom:** Running **`docker compose config`** expands values from **`.env`** in your terminal output.

**Fix:** Treat that command output as sensitive. The Compose file only passes the variables each container needs, but Compose still resolves passwords and secrets while rendering config. Do not paste full output into issues or commits.

---

## Port already in use (3306, 8080, 3000, 5173)

**Symptom:** `bind: address already in use`.

**Fix:** Stop the other service, or override in **`.env`**: **`DB_PORT`**, **`BACKEND_PORT`**, **`FRONTEND_PORT`** (Compose), or run Vite on another port.

---

## Fresh database / “no tables”

**Symptom:** App errors, MySQL empty.

**Fix:** First init only runs **`database/schema.sql`** and **`database/seed.sql`** when the **named volume is new**. To force a full reset (**deletes all data**):

```bash
docker compose down -v
docker compose up -d mysql   # or full stack
```

See **`docs/demo.md`** → *Reset sample data*.

---

## Python scripts: `401` / `Unauthorized`

**Symptom:** `job_watcher`, `ai_interview_planner`, etc. cannot call the API.

**Fix:** Set one of:

- **`SCRIPTS_API_TOKEN`** — JWT from login (e.g. copy from browser devtools → Application → localStorage `careerpilot_auth_token`, or use `POST /api/auth/login` with `curl`), **or**
- **`SCRIPTS_EMAIL`** + **`SCRIPTS_PASSWORD`** — same account you use in the UI.

Also ensure **`VITE_API_BASE_URL`** or **`API_BASE_URL`** points at your running API (default **`http://localhost:8080`**).

---

## Job watcher: `No active target companies…`

**Symptom:** Script exits with no work.

**Fix:** In the UI, add an **active** target company with an **allowed** public **`http`/`https`** careers URL (not LinkedIn/Indeed/Glassdoor). For offline demo, use **`docs/demo.md`** mock HTML flow.

---

## AI interview plan CLI: missing key / mock mode

**Symptom:** `MissingApiKeyError` or you want offline behavior.

**Fix:** Set **`AI_PROVIDER=mock`** (and optionally **`AI_MODE=mock`**) in **`.env`**. See **`docs/ai-setup.md`**.

---

## Still stuck?

- **`PROJECT_CONTEXT.md`** — architecture and “next steps”
- **`docs/local-setup.md`** — step-by-step local and Compose
- **`docs/ai-setup.md`** — AI env and privacy
- **`make test`** — verify backend, frontend, and Python smoke tests (see **`README.md`**)
