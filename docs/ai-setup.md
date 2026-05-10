# AI setup (scripts & privacy)

CareerPilot uses Python helpers under `scripts/` (for example `scripts/common/ai_provider.py`, `scripts/ai_interview_planner.py`). The **Kotlin backend does not call OpenAI/Gemini by default**; it only exposes **safe status** on **`GET /api/settings/status`** (provider label, whether keys exist—never the secret values).

Configure AI in the **repository root `.env`** (see `.env.example`). Scripts load env via `scripts/common/config.py`.

## Where env is read

- **Python scripts** read the repository root `.env` each time the script starts.
- **Kotlin backend** reads environment variables when the backend process starts. Settings mirrors safe backend env state; the backend still does not send prompts to OpenAI/Gemini by default.
- **React frontend** only receives variables prefixed with `VITE_`, and those are baked into the browser bundle when Vite starts/builds. Never put API keys in `VITE_*`.

## Environment variables

| Variable | Purpose |
|----------|---------|
| **`AI_PROVIDER`** | `mock`, `openai`, or `gemini`. If unset, **`AI_MODE`** is used for backward compatibility; default behaves like **`mock`**. |
| **`AI_API_KEY`** | OpenAI API key when **`AI_PROVIDER=openai`**. |
| **`AI_MODEL`** | OpenAI model id (e.g. `gpt-4o-mini`). |
| **`AI_API_BASE_URL`** | Optional OpenAI-compatible base URL override. |
| **`GEMINI_API_KEY`** or **`GOOGLE_API_KEY`** | Google Gemini key when **`AI_PROVIDER=gemini`**. |
| **`GEMINI_MODEL`** | Gemini model id (e.g. `gemini-2.5-flash`). |

Frontend-only (optional, **not** used for real AI calls):

| Variable | Purpose |
|----------|---------|
| **`VITE_AI_PROVIDER`** | Optional UI hint (e.g. `mock`) for banners in the React app. Must use the `VITE_` prefix so Vite exposes it. Does **not** replace server `.env`. |

## Changing provider or mode

After editing `.env`:

- **Running a Python script manually**: run the script again; no long-lived Python service needs restarting.
- **Host dev backend (`./scripts/local-up.sh` or `./scripts/run-backend-with-env.sh`)**: restart the backend process so `/api/settings/status` reflects the new server env.
- **Vite dev frontend**: restart `npm run dev` only if you changed `frontend/.env.local` or any `VITE_*` value.
- **Docker Compose backend**: run `docker compose up -d --force-recreate backend` after changing backend/script env values such as `AI_PROVIDER`, `AI_API_KEY`, `GEMINI_API_KEY`, or models.
- **Docker Compose frontend**: rebuild/recreate frontend only when `VITE_*` values change, for example `docker compose up --build -d frontend`.

Switching **`mock` ↔ `openai` ↔ `gemini`** changes what the Python AI provider does. It does not make the React app call the model directly.

### Example (local testing, no external calls)

```env
AI_PROVIDER=mock
AI_MODEL=gpt-4o-mini
AI_API_KEY=
```

### Example (OpenAI)

```env
AI_PROVIDER=openai
AI_API_KEY=sk-...
AI_MODEL=gpt-4o-mini
```

## Privacy

- **OpenAI / Gemini modes**: Tools such as `ai_interview_planner.py` send **job descriptions**, role metadata, and **prompt text** to the **external provider** you configured. Review each vendor’s terms and your employer’s policy before enabling.
- **API keys**: Stored only in **`.env`** on the machine running scripts (and in deployment secrets). They are **not** bundled into the frontend and **never** returned by the REST API—Settings shows **configured: yes/no** only.
- **Mock mode**: No network call to OpenAI/Gemini for generation logic that uses the mock provider; useful for offline development and CI-style checks.

## Mock mode for local testing

Set **`AI_PROVIDER=mock`** (or leave keys empty and rely on mock defaults). The mock provider returns deterministic placeholder content so you can test schema validation, DB writes, and UI without consuming billable API quota.

The in-app **Settings** page (`/settings`) calls **`GET /api/settings/status`** and shows **database connectivity**, **app version**, **`AI_PROVIDER`** (server env), **whether OpenAI/Gemini keys are configured** (boolean only), and reported **model id** — never secret values. Optional **`VITE_AI_PROVIDER=mock`** in `frontend/.env.local` only affects UI messaging (e.g. mock banners elsewhere).
