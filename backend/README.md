## Backend (Kotlin + Ktor)

### Requirements

- JDK 21
- Gradle (or use the Gradle Docker image)

### Run tests

Local Gradle:

```bash
cd backend
gradle test
```

Via Docker (no local Gradle needed):

```bash
cd backend
docker run --rm -v "$PWD":/app -w /app gradle:8.10.2-jdk21 gradle test --no-daemon
```

### Run the server

```bash
cd backend
gradle run
```

By default it listens on port **8080**.

### Manual verification

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/api/version
curl -s http://localhost:8080/health/db
```

### Auth endpoints (Day 6)

- `POST /api/auth/register`
- `POST /api/auth/login`
- `GET /api/me` (requires `Authorization: Bearer <token>`)

Example:

```bash
curl -s http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"password123","displayName":"You"}'
```

### Target companies API (Day 7)

All endpoints require `Authorization: Bearer <token>` and are scoped to the authenticated user.

- `GET /api/target-companies`
- `POST /api/target-companies`
- `GET /api/target-companies/{id}`
- `PATCH /api/target-companies/{id}`
- `DELETE /api/target-companies/{id}`

Delete strategy:

- Permanent remove the row: **`POST /api/target-companies/{id}/delete`** (JSON `{}`) or **`DELETE /api/target-companies/{id}`**. Related rows follow FK **`ON DELETE CASCADE`** (varies per table). Prefer **`PATCH`** `{ "active": false }` to deactivate without deleting.

### Job leads API (Day 8)

All endpoints require `Authorization: Bearer <token>` and are scoped to job leads under the authenticated user's target companies.

- `GET /api/job-leads` (filters: `company_id`, `keyword`, `min_match_score`, `saved_to_applications`)
- `POST /api/job-leads`
- `GET /api/job-leads/{id}`
- `PATCH /api/job-leads/{id}`
- `DELETE /api/job-leads/{id}`

Notes:

- Duplicate prevention: `job_url` is unique **per user** (enforced at the API/repository level).
- `matched_keywords` is stored as JSON (MySQL `JSON` column; H2 tests use `TEXT` with the same JSON encoding).

### Applications API (Day 9)

All endpoints require `Authorization: Bearer <token>`.

- `GET /api/applications` (filters: `status`, `company_id`, `keyword`)
- `POST /api/applications` (provide **either** `company_id` **or** `company_name`, not both)
- `GET /api/applications/{id}`
- `PATCH /api/applications/{id}`
- `DELETE /api/applications/{id}` (**hard delete** — removes the application row; MySQL **ON DELETE CASCADE** removes dependent rows: **interviews**, **reminders** linked to that application, **ai_interview_plans** and their **prep_tasks**; `job_lead_id` on the application is set **NULL** if the lead still exists)
- `POST /api/job-leads/{id}/save-as-application` — creates an application from a job lead, sets `saved_to_applications=true` on that lead, and uses status `SAVED`.

Statuses (API / JSON): `SAVED`, `APPLIED`, `ONLINE_ASSESSMENT`, `INTERVIEW`, `OFFER`, `REJECTED`, `GHOSTED`, `ARCHIVED`. Matching is **case-insensitive** in JSON/query strings (e.g. `applied`, `online-assessment`).

Duplicate prevention: `job_url` is unique **per user** for applications. `applied_date` / `follow_up_date` in JSON map to `applied_at` / `next_follow_up_date` in MySQL (omit or null → stored as SQL `NULL`).

`POST .../save-as-application` runs insert + lead flag update in a **transaction**. Calling it again returns **200 OK** with the existing application (idempotent) when the lead is already saved.

### Interviews & reminders (Day 10)

All endpoints require `Authorization: Bearer <token>`. List and mutation operations are **scoped to the authenticated user** (interviews are visible only if they belong to an application owned by the user).

**Interviews**

- `GET /api/interviews` — all interviews for the user’s applications (`scheduled_at` is ISO-8601 instant string or omitted/null).
- `POST /api/applications/{id}/interviews` — body: `round_name`, `scheduled_at` (ISO-8601), `status`, `notes` (404 if the application is not yours).
- `PATCH /api/interviews/{id}` — partial update (same fields as create).
- `DELETE /api/interviews/{id}`

**Reminders**

- `GET /api/reminders` — all reminders for the user.
- `GET /api/reminders/today` — reminders whose `due_at` falls on the **current calendar day on the server** (JVM default timezone, typically the host OS — **not** per-user timezone). The window is midnight-to-midnight in that zone.
- `POST /api/applications/{id}/reminders` — body: `type` (`FOLLOW_UP` \| `INTERVIEW_PREP` \| `CUSTOM`), `due_at` (ISO-8601 instant, e.g. `2026-05-03T15:00:00Z`), `message` (404 if the application is not yours).
- `PATCH /api/reminders/{id}/complete` — sets `done` on the existing row (**does not delete** the reminder); idempotent if already done.
- `DELETE /api/reminders/{id}` — removes the row (only owner; otherwise 404).

**Timezone note (current limitation)**  

There is **no per-user timezone** in the API yet. `/today` and “what counts as today” follow **server local time**. Clients should read/write instants as **ISO-8601** strings from/to JSON so parsing stays consistent across frontend and backend.

### Dashboard (aggregated)

All endpoints require `Authorization: Bearer <token>` and return only data for the authenticated user. Aggregations are always filtered by the authenticated `user_id` (directly on `applications` / `reminders`, or via `applications` / `target_companies` joins for interviews, job leads, and prep tasks).

- `GET /api/dashboard/stats` — counts and `response_rate` (see `DashboardStatsDto` / `DashboardModels.kt` for definitions). `COUNT(*)` queries always yield numeric zeros when there are no rows (never null / never divide-by-zero for `response_rate`).
- **This week** = from **Monday 00:00** (inclusive) through **now** (inclusive, `Instant.now()` at query time) in the **JVM default timezone**, compared as timestamps on `applications.created_at` and `job_leads.discovered_at`.
- **`follow_ups_due`** = open reminders with `due_at` strictly before **midnight at the start of the next calendar day** (server-local), plus applications with `next_follow_up_date` set and **≤ today’s calendar date** (server-local). This is a “due or overdue” count, not the same 14-day planning window as the list endpoint.
- `GET /api/dashboard/follow-ups` — merged list of application `next_follow_up_date` rows (on or before **today + 14 calendar days**) and open reminders with `due_at` before the **exclusive** instant at **start of day 15** from today, merged and sorted **soonest due first** (see `DashboardRepository.listFollowUps`).
- `GET /api/dashboard/recent-job-leads` — up to 10 leads for the user, **`ORDER BY discovered_at DESC, id DESC`** (same shape as job-lead DTOs).
- `GET /api/dashboard/upcoming-interviews` — interviews with non-null `scheduled_at` from **today’s local midnight** onward, **`ORDER BY scheduled_at ASC, id ASC`** (max 20).
- `GET /api/dashboard/prep-summary` — prep tasks due **today** (server-local calendar date), `status <> 'done'`, with application/company context.

### Database connectivity (Day 5)

This backend uses:

- **HikariCP** connection pool
- **MySQL JDBC driver**

Schema management decision:

- **Direct `database/schema.sql` compatibility**: schema is applied externally (e.g. Docker MySQL init scripts).
- The backend **does not run migrations yet** (no Flyway at this stage).

The server reads DB config from environment variables:

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`

### Manual DB verification (with Docker Compose MySQL)

Start MySQL first (from repo root):

```bash
cd ..
cp .env.example .env
docker compose up -d mysql
```

Run the backend (this directory):

```bash
cd backend
# IMPORTANT:
# - If backend runs on your host machine: DB_HOST should be "localhost"
# - If backend runs as a Docker service on the same compose network: DB_HOST should be "mysql"
DB_HOST=localhost DB_PORT=3306 DB_NAME=careerpilot DB_USER=careerpilot DB_PASSWORD=careerpilot_password ./gradlew run
```

Then:

```bash
curl -i http://localhost:8080/health/db
```

