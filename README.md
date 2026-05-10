## CareerPilot Local

**交接与上下文**：新项目成员或 Cursor 新对话请先读 **`PROJECT_CONTEXT.md`**（目标、栈、进度、约定、下一步）。

Self-hosted AI career assistant (local-first).

The goal is a local app where users configure target companies + public career page URLs, and the system tracks job leads, applications, interview prep, reminders, and weekly reporting. AI features will use an **external API provider** configured via `.env` (no hardcoded keys).

### Tech stack

- **Backend**: Kotlin + Ktor (Gradle)
- **Frontend**: React + TypeScript + Vite
- **Database**: MySQL 8.0 (Docker Compose; schema from `database/schema.sql`)
- **Automation**: Python scripts (planned)
- **Deployment**: Docker Compose — **MySQL + backend (Ktor) + frontend (nginx static)**; dev 仍可用 `scripts/local-up.sh` 只起 MySQL + 宿主机前后端

### Quick start

#### Option A — Full stack in Docker (Compose)

需要 **Docker**（含 Compose v2）。会 **build** 后端/前端镜像并启动三服务（见根目录 **`docker-compose.yml`**、**`backend/Dockerfile`**、**`frontend/Dockerfile`**）。

```bash
cd careerpilot-local
cp .env.example .env
# 编辑 .env：设置 JWT_SECRET，例如: openssl rand -hex 32
docker compose up --build -d
```

| 服务 | 默认本机地址 |
|------|----------------|
| 前端 (SPA) | **http://localhost:3000**（`FRONTEND_PORT` → 容器内 nginx **80**） |
| 后端 API | **http://localhost:8080**（`BACKEND_PORT`） |
| MySQL | `localhost` **3306**（`DB_PORT`） |

检查健康状态：`docker compose ps`（各服务 `healthy` 后可用）。快速探测：`curl -s http://localhost:8080/health` 与 `curl -s http://localhost:3000/health`。

查看日志：`docker compose logs -f backend frontend mysql`。

停止：`docker compose down`。清库重载 schema（**删数据**）：`docker compose down -v`。

**环境变量**：根目录 **`.env.example`** 已说明 Compose 与宿主机跑法的区别（`DB_HOST=localhost` 在「本机跑后端」时仍适用；Compose 为 backend 服务覆盖 **`DB_HOST=mysql`**）。**`VITE_API_BASE_URL`** 在构建前端镜像时写入，默认 **`http://localhost:8080`**，与浏览器经本机访问 API 一致。

**Python 脚本**（`scripts/`）未放入默认 Compose；在**宿主机**用同一 `.env` 跑即可（`DB_HOST=localhost`、API `http://localhost:8080`），见 **`docs/local-setup.md`**。

更细排错与手工分步启动见 **`docs/local-setup.md`**。AI 与隐私见 **`docs/ai-setup.md`**。

#### Option B — Host dev: one script (MySQL in Docker, backend + Vite on host)

需 **Docker、JDK 21、Node**；**`./scripts/local-up.sh`** 会（必要时）补全 **`.env`**、起 MySQL、装 `frontend` 依赖、后台跑 Gradle、前台跑 Vite。

```bash
cd careerpilot-local
./scripts/local-up.sh
```

浏览器一般为 **http://localhost:5173**；API **http://localhost:8080**。停止：`Ctrl+C` 或 **`./scripts/local-down.sh`**；连 MySQL 停：**`./scripts/local-down.sh --all`**。

### MySQL only (Compose)

```bash
cd careerpilot-local
cp .env.example .env
docker compose up -d mysql
docker compose ps
```

Verify tables (optional):

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

- **React + TypeScript + Vite**, **React Router 6** (`/login`, `/register`, sidebar layout).
- API base URL: **`VITE_API_BASE_URL`** (see `frontend/.env.example`; default `http://localhost:8080`; baked in at image **build** time for Docker).
- **JWT** stored in **`localStorage`** under `careerpilot_auth_token`. Shared DTO-oriented types live in `frontend/src/types/` (aligned with Kotlin `ApiResponse` / domain DTOs).
- **Dev:** `cd frontend && npm install && npm run dev` → **http://localhost:5173** (or `./scripts/local-up.sh`).
- **Docker Compose:** nginx serves production build at **http://localhost:3000** by default (`FRONTEND_PORT`).

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
  backend/Dockerfile
  frontend/Dockerfile
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
