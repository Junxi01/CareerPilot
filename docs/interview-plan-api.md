# AI interview plan API (Day 25)

All routes require `Authorization: Bearer <JWT>`. Data is scoped through `applications.user_id` joins so users cannot read or mutate another account’s plans or prep tasks.

## Design: POST accepts generated JSON (no subprocess)

The backend **does not** shell out to Python or call external LLMs. Automation should:

1. Run `scripts/ai_interview_planner.py` (or any generator) **outside** the JVM.
2. **POST** the resulting payload to `POST /api/job-leads/{id}/interview-plan`.

That keeps deployment simple (no Python on the app server), avoids timeouts and shell injection, and matches how other scripts already integrate with HTTP APIs.

Stored fields:

- **`plan_json`** — structured JSON (topics, timelines, question banks, etc.).
- **`plan_markdown`** — human-readable narrative for UI or exports.
- **`prompt_json`** (optional) — audit/metadata about what was sent to the model.
- **`prep_tasks`** — optional explicit list; if omitted, tasks may be derived from `plan_json.prep_tasks` when present and parseable.

Replacing a plan for the same application deletes previous `prep_tasks` and `ai_interview_plans` rows for that application before inserting the new plan. Deleting a plan also deletes its `prep_tasks`. The MySQL schema keeps `ON DELETE CASCADE` as a database-level guard, while the repository performs explicit task cleanup so the behavior is the same in tests and in MySQL.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/job-leads/{id}/interview-plan` | Latest plan for that job lead (requires an existing application linked to the lead; **does not** create one). |
| POST | `/api/job-leads/{id}/interview-plan` | Creates or replaces the plan for this lead (creates an application via `ensureApplicationForJobLead` if needed). **201 Created**. |
| GET | `/api/interview-plans/{id}` | Fetch plan by primary key (owner only). |
| DELETE | `/api/interview-plans/{id}` | Delete plan (owner only); cascades prep tasks. |
| GET | `/api/prep/tasks` | Query params: `application_id`, `status` (optional filters). |
| GET | `/api/prep/tasks/today` | Prep tasks due **today** (server-local date), excluding `status = done`. |
| PATCH | `/api/prep/tasks/{id}/complete` | Sets task `status` to `done`. |

### POST body shape (`UpsertInterviewPlanRequest`)

```json
{
  "plan_json": { "summary": "…", "topics": ["…"] },
  "plan_markdown": "## Section\n…",
  "prompt_json": { "model": "…" },
  "provider_mode": "external",
  "prep_tasks": [
    { "label": "Research company", "due_day_offset": 0 },
    { "label": "Mock interview", "due_date": "2026-05-10" }
  ]
}
```

`due_date` (ISO `YYYY-MM-DD`) overrides relative offsets when both are applicable.

## Database

MySQL schema includes `ai_interview_plans.plan_markdown` (`MEDIUMTEXT`). Existing deployments must run:

```sql
ALTER TABLE ai_interview_plans ADD COLUMN plan_markdown MEDIUMTEXT NULL AFTER plan_json;
```

(if the column is not already present.)

## Frontend (Job leads)

The **Job leads** page loads `GET /api/job-leads/{id}/interview-plan` when you select a lead. There is no in-browser AI call: use **Generate Interview Plan** to expand CLI instructions (`python scripts/ai_interview_planner.py <job_lead_id>`), then **Refresh plan**. Prep task checkboxes call `PATCH /api/prep/tasks/{id}/complete`.

Optional `VITE_AI_PROVIDER=mock` in `frontend/.env.local` shows a mock-mode banner (alongside `provider_mode` from the saved plan).
