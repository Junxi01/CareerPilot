## Database schema (MySQL)

This document explains the baseline tables in `database/schema.sql`.

### Conventions

- **Primary keys**: `BIGINT UNSIGNED AUTO_INCREMENT`
- **Timestamps**: `created_at` and `updated_at` where the record is expected to change
- **Foreign keys**: enforced with `ON DELETE` behavior chosen per relationship
- **JSON**: keywords/locations stored as `JSON` when practical for structured data

### Tables

#### 1) `users`

Represents a local user of the app.

- **Columns**: `email` (unique), `password_hash` (bcrypt string; nullable for seed/demo), optional `display_name`, optional `timezone`
- **Indexes**: unique `email`

#### 2) `target_companies`

Per-user target companies and their public careers page URL(s) plus filtering criteria.

- **Columns**:
  - `user_id` → `users.id`
  - `name`
  - `careers_page_url`
  - `active` (boolean)
  - `locations_json`, `role_keywords_json`, `tech_keywords_json` (JSON arrays)
  - `notes` (freeform text)
- **Indexes**:
  - `active` (**required**)
  - `user_id`

#### 3) `job_leads`

Job leads discovered from user-configured public careers pages.

- **Columns**:
  - `company_id` → `target_companies.id` (**required index**)
  - `title`, `url` (unique by URL prefix)
  - `location` (normalized display) and `location_raw` (optional)
  - `raw_description` (optional text)
  - `discovered_at` (**required index**)
  - `match_score` (optional numeric score)
  - `saved_to_applications` (boolean)
  - `status` (e.g. `new`, `ignored`, `applied`)
  - `source` (e.g. `career_page`)
  - `matched_keywords_json` (JSON array), `raw_json` (optional JSON blob)
- **Indexes**:
  - `company_id` (**required**)
  - `discovered_at` (**required**)

#### 4) `applications`

Tracks user applications, optionally linked to a `job_leads` record.

- **Columns**:
  - `user_id` → `users.id`
  - `company_id` → `target_companies.id`
  - `job_lead_id` → `job_leads.id` (nullable)
  - `role_title`, `job_url` (per-user de-dupe on `job_url` via `user_id` + `job_url` unique index)
  - `status` (string enum, e.g. `SAVED`, `APPLIED`, …) (**required index**)
  - `tech_stack_json` (JSON array of strings)
  - `salary_range` (freeform string)
  - `applied_at`, `next_follow_up_date` (API: `applied_date` / `follow_up_date`), `notes`
- **Indexes**:
  - `status` (**required**)
  - `user_id`, `company_id`, `job_lead_id`

#### 5) `interviews`

Interview events under an application.

- **Columns**:
  - `application_id` → `applications.id`
  - optional `round_name`, `scheduled_at`, `status`, `notes`
- **Indexes**: `application_id`, `scheduled_at`

#### 6) `ai_interview_plans`

Stores the AI-generated interview preparation plan for an application.

- **Columns**:
  - `application_id` → `applications.id`
  - `provider_mode` (e.g. `mock` / `real`)
  - `prompt_json` (optional), `plan_json` (required)
- **Indexes**: `application_id`

#### 7) `prep_tasks`

Concrete tasks derived from an AI plan.

- **Columns**:
  - `ai_interview_plan_id` → `ai_interview_plans.id`
  - `label`, `description`
  - `due_date` (**required index**)
  - `status` (e.g. `todo`, `done`)
- **Indexes**:
  - `due_date` (**required**)
  - `ai_interview_plan_id`

#### 8) `reminders`

User reminders (follow-ups, prep tasks, etc.).

- **Columns**:
  - `user_id` → `users.id`
  - `due_date` (**required index**)
  - `message`, `done`
- **Indexes**:
  - `due_date` (**required**)
  - `user_id`

#### 9) `app_settings`

Key/value settings (optionally per-user).

- **Columns**:
  - `user_id` → `users.id` (nullable = global setting)
  - `setting_key`
  - `setting_json` (JSON blob)
- **Indexes**:
  - unique `(user_id, setting_key)`
  - index on `setting_key`

### Notes / future evolution

- This schema is intentionally a baseline; expect changes once business flows are implemented.
- For repeatable migrations, add a migration tool (e.g. Flyway) when the schema stabilizes.
