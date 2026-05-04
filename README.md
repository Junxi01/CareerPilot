## CareerPilot Local

**交接与上下文**：新项目成员或 Cursor 新对话请先读 **`PROJECT_CONTEXT.md`**（目标、栈、进度、约定、下一步）。

Self-hosted AI career assistant (local-first).

The goal is a local app where users configure target companies + public career page URLs, and the system tracks job leads, applications, interview prep, reminders, and weekly reporting. AI features will use an **external API provider** configured via `.env` (no hardcoded keys).

### Tech stack

- **Backend**: Kotlin + Ktor (Gradle)
- **Frontend**: React + TypeScript + Vite
- **Database**: MySQL 8.0 (Docker Compose; schema from `database/schema.sql`)
- **Automation**: Python scripts (planned)
- **Deployment**: Docker Compose (**MySQL service** enabled; backend/frontend wiring later)

### 本地一键跑起来（推荐）

**一条命令**（需已安装 Docker、JDK 21、Node；首次会自动 `npm install`、必要时自动生成 `JWT_SECRET`）：

```bash
cd careerpilot-local
./scripts/local-up.sh
```

浏览器打开 **http://localhost:5173**。后端默认 **http://localhost:8080**；日志在 **`.logs/backend.log`**。  
按 **Ctrl+C** 会结束前端并尝试关掉本会话启动的后端。

停止后端（可选）：`./scripts/local-down.sh`；连 MySQL 一起停：`./scripts/local-down.sh --all`。

更多细节与排错见 **`docs/local-setup.md`**。

### MySQL via Docker Compose

```bash
cd careerpilot-local
cp .env.example .env
# Edit .env: set JWT_SECRET (e.g. openssl rand -hex 32); keep DB_HOST=localhost when the backend runs on the host.
docker compose up -d
docker compose ps
```

Verify tables (optional; matches default `.env.example` credentials):

```bash
docker compose exec mysql mysql -u careerpilot -pcareerpilot_password careerpilot -e "SHOW TABLES;"
```

### Local development goals

- Run fully locally (self-hosted)
- No secrets committed (use `.env` / `.env.example`)
- Clean architecture (clear boundaries; no shortcuts)
- Typed, consistent API responses (backend) and typed API clients (frontend)
- AI supports real-provider mode + mock mode for local testing
- Only support user-configured **public** company career pages (no LinkedIn/Indeed/Glassdoor/login-required sites)

### Backend (current)

The backend already includes a minimal, working baseline:

- Health/version:
  - `GET /health`
  - `GET /api/version`
  - `GET /health/db` (returns `503` when DB is unreachable)
- Auth (JWT):
  - `POST /api/auth/register`
  - `POST /api/auth/login`
  - `GET /api/me` (requires `Authorization: Bearer <token>`)

See `backend/README.md` for run commands and examples.

### Frontend (current)

- **React + TypeScript + Vite**, **React Router 6** (`/login`, `/register`, sidebar layout, placeholder sections).
- API base URL: **`VITE_API_BASE_URL`** (see `frontend/.env.example`; default `http://localhost:8080`).
- **JWT** stored in **`localStorage`** under `careerpilot_auth_token`. Shared DTO-oriented types live in `frontend/src/types/` (aligned with Kotlin `ApiResponse` / domain DTOs).
- Run: `cd frontend && npm install && npm run dev` (or use repo `./scripts/local-up.sh`).

### Git hygiene: keep commits single-author

If you don't want GitHub to show AI/tools (e.g. "Cursor") as contributors, install the repo-local git hook once:

```bash
cd careerpilot-local
./scripts/install-git-hooks.sh
```

This hook strips commit-message trailers like `Co-authored-by:` / `Made-with:` at commit time.

### Folder structure

```
careerpilot-local/
  PROJECT_CONTEXT.md # 交接文档（给 AI / 同事）
  .cursor/rules/    # Cursor 规则：每日收尾时更新 PROJECT_CONTEXT.md 等
  backend/           # Kotlin + Ktor (Gradle)
  frontend/          # React + TypeScript + Vite
  scripts/           # Python automation (placeholders)
  database/          # schema.sql / seed.sql (placeholders)
  docs/              # architecture notes
  docker-compose.yml
  .env.example
  README.md
```

### Planned features

- Company/career page configuration (public URLs only)
- Job lead discovery + keyword matching + dedupe
- Application tracking (status, notes, follow-ups)
- Interview schedule + prep task planning
- AI-powered interview preparation plans (real + mock)
- Reminders + weekly summary reports
- Database backups and restore flow
