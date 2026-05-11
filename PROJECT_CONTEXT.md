# PROJECT_CONTEXT — CareerPilot Local 交接说明

> **给 AI / 协作者**：在新 Cursor 对话开始时，请先阅读本文件、`README.md`，再按需查看 `backend/`、`frontend/`、`database/`、`scripts/`、`docs/`。  
> **仓库根目录**：本项目的开发与 Git 根目录为 **`careerpilot-local/`**。上一层桌面文件夹 **`Career Pilot Local/`** 仅工作区入口（可选 `README.md`），Gradle/npm 等命令须在 **`careerpilot-local/`** 或其子目录执行。  
> **接力规则**：项目内已配置 Cursor Rule **`.cursor/rules/careerpilot-project-context-handoff.mdc`**（`alwaysApply: true`）。用户说出 **每日收尾口令** 时，Cursor 应**直接编辑本文件**中「进度 / 下一步 / 最近一次会话交接」等段落，无需再单独下「改文件」指令。

---

## 1. 项目目标（要做什么）

Self-hosted、本地优先的 **AI 求职助手**：用户配置目标公司、**公开**招聘页 URL、地点、岗位/技术关键词；系统从**用户配置的公开职业页**抓取/整理线索、落库、生成面试准备计划；并跟踪投递、面试、准备任务、跟进提醒、周报与备份等。

**产品设计边界（重要）**：

- **不**爬取或依赖 LinkedIn / Indeed / Glassdoor 等聚合站，也**不**处理需要登录的页面；**仅支持用户自行配置的、可公开访问的公司招聘页**。
- AI 调用通过 **外部 API** 完成，密钥与基址来自 **`.env`**，**仓库内不得硬编码密钥**。
- 交付形态：**`docker compose up --build`** 可启动 **MySQL + Ktor 后端 + nginx 静态前端**（见 **`docker-compose.yml`**）；亦可仅用 Compose 跑 MySQL、宿主机跑前后端；本地开发仍可用 **`scripts/local-up.sh`**（MySQL 容器 + 宿主 Gradle + Vite）。

---

## 2. 当前技术栈（实际采用）

| 层级 | 选型 | 备注 |
|------|------|------|
| 后端 | Kotlin + Ktor，Gradle | `backend/settings.gradle.kts`，`backend/build.gradle.kts`；入口 `com.careerpilot.ApplicationKt`，`application.conf` 配端口 |
| 前端 | React 18 + TypeScript + Vite 5 | `frontend/package.json`、`frontend/tsconfig.json`、`frontend/vite.config.ts` |
| 数据库 | MySQL 8.0（开发可 H2） | `database/schema.sql` / `seed.sql` 已有业务表定义；后端通过 JDBC 读写 |
| 脚本 | Python | API/DB helpers、follow-up、周报、备份、CSV 导入、job watcher；**AI：`scripts/common/ai_provider.py`（openai/gemini/mock）、`scripts/test_ai_provider.py`、`scripts/ai_interview_planner.py`**（可直连 DB 或配合 Day 25 **`POST /api/job-leads/{id}/interview-plan`**） |
| 部署 | Docker Compose + 本地脚本 | **全栈**：`mysql` + **`backend`**（`backend/Dockerfile`，Gradle `installDist`）+ **`frontend`**（`frontend/Dockerfile`，Vite build + **nginx**，默认映射 **`FRONTEND_PORT`→3000**）；**`.dockerignore`** 缩小构建上下文；可选仅起 **`mysql`** 或与 **`scripts/local-up.sh`** 组合 |
| 密钥 | `.env`（不提交） | 模板见 `.env.example` |

**Node 提示**：本前端为 **Vite 5**，一般 **Node 18+** 即可；若改用官方最新 `create-vite` 脚手架，可能要求更高 Node 版本，以本机 `node -v` 为准。

---

## 3. 仓库与路径约定

- **Git / 开发根目录**：`careerpilot-local/`
- **父级工作区**（可选）：`Career Pilot Local/README.md` 仅说明子目录入口；不要把另一在研项目与该目录混在一起提交。
- **忽略规则**：`.gitignore` 已包含 `.env`、`backend/build/`、`frontend/node_modules/` 等。

---

## 4. 当前完成进度（随每日收尾滚动更新；下方「最近一次会话交接」记录最新一次）

### 已完成（基础骨架）

- 目录结构：`backend/`、`frontend/`、`scripts/`、`database/`、`docs/`
- 后端：Ktor 应用可运行，已提供认证、领域 CRUD 与 Dashboard 聚合 API（见下方 Day 5+）
- 前端：React Router + JWT Auth + 受保护 sidebar layout，已接入部分业务 API（Dashboard / Target Companies / Job Leads / Applications / Kanban）
- 配置：`/.env.example`（MySQL/后端端口/前端 `VITE_API_BASE_URL`/AI 占位）
- 文档：`README.md`、`backend/README.md`、`docs/local-setup.md`、`docs/database-schema.md`、`docs/backup-and-restore.md`、`docs/interview-plan-api.md`（Day 25+）、**`docs/ai-setup.md`**（Day 27+）
- 质量与 CI：**`Makefile`**、`requirements-dev.txt`、**`tests/`**（pytest）、**`.github/workflows/ci.yml`**（Day 29+）
- 开源发布：**`LICENSE`**（MIT）、**`SECURITY.md`**、**`docs/demo.md`**、**`docs/troubleshooting.md`**、`docs/images/` 截图占位说明（Day 30+）

### Day 2 — Docker Compose + MySQL（已完成；全栈扩展见 Day 28）

- `docker-compose.yml`：**`mysql:8.0`**，命名卷 `mysql_data`，主机端口 `${DB_PORT:-3306}:3306`，**healthcheck**（`mysqladmin ping`），首次初始化挂载 `database/schema.sql` → `/docker-entrypoint-initdb.d/01-schema.sql`、`database/seed.sql` → `/docker-entrypoint-initdb.d/02-seed.sql`
- `database/schema.sql`：占位表 **users, target_companies, job_leads, applications, interviews, ai_interview_plans, prep_tasks, reminders**（`USE \`careerpilot\``，须与 `MYSQL_DATABASE` / `DB_NAME` 一致）
- `.env.example`：`MYSQL_ROOT_PASSWORD`, `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`

### Day 3 — 正式数据库 Schema（已完成）

- `database/schema.sql`：9 张表（users/target_companies/job_leads/applications/interviews/ai_interview_plans/prep_tasks/reminders/app_settings），BIGINT 主键、FK、`created_at`/`updated_at`，JSON 列用于关键词/地点等
- 索引满足需求：`target_companies.active`、`job_leads.company_id`、`job_leads.discovered_at`、`applications.status`、`prep_tasks.due_date`、`reminders.due_date`
- `database/seed.sql`：1 个 demo 用户、2 个 target companies、3 条 job leads
- `docs/database-schema.md`：逐表说明与字段约定

### Day 5 — 后端接入 MySQL（已完成）

- 后端已接入 **HikariCP + MySQL JDBC**：`backend/src/main/kotlin/com/careerpilot/db/DatabaseModule.kt`
- `DB_*` 环境变量读取与可选 `DB_JDBC_URL` 覆盖（测试用）
- `GET /health/db`：最小 DB 连通性验证（SELECT 1），失败时返回 `503` 且给出归类后的错误码

### Day 6 — 基础认证（JWT）（已完成）

- 认证接口：
  - `POST /api/auth/register`
  - `POST /api/auth/login`
  - `GET /api/me`（Bearer JWT）
- BCrypt 密码哈希：`backend/src/main/kotlin/com/careerpilot/auth/PasswordHasher.kt`
- JWT 必要配置：`JWT_SECRET` **必须显式配置**（为空或默认 `change-me...` 将拒绝启动）

### Day 7+ — 领域 API 与 Dashboard（后端已完成；细节见 `backend/README.md`）

以下均在 **`Authorization: Bearer <JWT>`** 下按 `user_id` 隔离（具体校验见各 Repository / 路由）：

- **`/api/target-companies`**：目标公司 CRUD
- **`/api/applications`**：投递 CRUD、状态过滤、嵌套 **interviews** / **reminders** 创建、与 **job_leads** 的关联/去重等（见 `backend/README.md`）
- **`/api/interviews`**：列表与按 id 更新/删除（归属经 application 校验）
- **`/api/reminders`**：列表、`/today`、完成/删除等
- **`/api/job-leads`**：线索列表/筛选/CRUD、`save-as-application` 等
- **`/api/dashboard`**（聚合）：`stats`、`follow-ups`、`recent-job-leads`（按 `discovered_at` 降序）、`upcoming-interviews`（按 `scheduled_at` 升序）、`prep-summary`；统计口径与「本周」边界见 **`backend/README.md` → Dashboard**
- **Interview plan / prep tasks REST**（JWT）：详见 **§4 Day 25**（`job-leads/.../interview-plan`、`interview-plans`、`prep/tasks`）

### Day 11–16 — 前端基础与业务页面（已完成到可用骨架）

- `frontend/src/api/*`：统一 `ApiResponse<T>` 解析、Bearer JWT 注入、401 清 session、按 DTO 定义 `auth` / `dashboard` / `targetCompanies` / `jobLeads` / `applications` API client
- `frontend/src/context/AuthContext.tsx`：登录、注册、`/api/me` session bootstrap，JWT 存 `localStorage` key `careerpilot_auth_token`
- 路由：`/login`、`/register`、`/dashboard`、`/target-companies`、`/job-leads`、`/applications`、`/kanban`；**`/settings`**（Day 27：`.env` / 状态与 **`GET /api/settings/status`**）；**`/prep`** 仍为 placeholder
- 页面能力：
  - Dashboard：当前展示 `GET /api/dashboard/stats` 指标网格；其它 dashboard client 已定义但页面 widget 未完全展开
  - Target Companies：列表、创建、编辑、deactivate/remove、关键词/地点 tag 输入
  - Job Leads：筛选、详情、手动创建、保存为 application、空状态提示；**Day 26**：详情内 **Interview plan**（CLI 引导、刷新、结构化展示、prep 勾选）
  - Applications：筛选、详情、创建、编辑、删除
  - Kanban：按 application status 分列，支持按钮移动与拖拽改状态

### Day 17–18 — Python 自动化与本地工具（已完成一批）

- `scripts/common/config.py` / `api_client.py` / `db.py`：读取 repo-root `.env`，支持 API token 或脚本账号登录，DB 查询 helper
- `scripts/check_followups.py`：读取 `/api/dashboard/follow-ups`，生成 `reports/followups_today.md`（支持 `--dry-run`）
- `scripts/generate_weekly_report.py`：从 MySQL 生成当前/上一周 markdown 周报（支持 `--dry-run`）
- `scripts/backup_database.py`：Docker Compose 或 host `mysqldump` 逻辑备份，支持 gzip、custom output、dry-run，并在 `docs/backup-and-restore.md` 记录恢复方式
- `scripts/import_applications_from_csv.py`：CSV 投递导入，经 API 创建 applications，支持 `--dry-run` 与 job_url 去重
- `scripts/local-up.sh` / `local-down.sh` / `run-backend-with-env.sh`：本地一键启动/停止 MySQL + 后端 + 前端；自动修正本机 `DB_HOST=localhost`、必要时生成本地 `JWT_SECRET`
- `scripts/install-git-hooks.sh` 与 `scripts/git-hooks/commit-msg`：可安装 repo-local commit-msg hook，剥离 `Co-authored-by` / `Made-with` 等 trailer

### Day 24 — AI Provider 抽象与 CLI 面试计划（脚本侧已完成）

- **`scripts/common/ai_provider.py`**：`AI_PROVIDER=openai|gemini|mock`（未设置时可回落 **`AI_MODE`**）；OpenAI 读取 **`AI_API_KEY`**、**`AI_MODEL`**、可选 **`AI_API_BASE_URL`**；Gemini 读取 **`GEMINI_API_KEY`** 或 **`GOOGLE_API_KEY`**、**`GEMINI_MODEL`**、可选 **`GEMINI_API_BASE_URL`**；OpenAI 使用 Chat Completions + JSON object，Gemini 使用 `generateContent` + JSON response MIME；urllib3 重试 + 超时/连接额外重试；**无硬编码密钥**；缺 key 时 **`MissingApiKeyError`** 明确提示；401/403/429 有明确诊断；JSON parse 失败会写 **`reports/ai_provider_debug_*.json`**，且不写请求 headers/API key，并对当前 key 文本做 redaction。
- **`scripts/test_ai_provider.py`**：`python scripts/test_ai_provider.py` 冒烟（mock 默认）。
- **`scripts/ai_interview_planner.py`**：输入 **`job_lead_id`** 或 **`--latest-unsaved`**；可选 **`--from-file`** 覆盖职位描述正文；**`--dry-run`** 只写 `reports/interview_plan_<id>_dry_run.md`、不写库。拉取 **`GET /api/job-leads`**、**`GET /api/me`**，将职位与候选人上下文送入 AI，要求结构化 JSON（summary、skills、topics、seven_day_plan、题库类字段、**prep_tasks** 等）。写入前会做 schema validation，缺字段/类型错会明确报 **`Schema error`** 并停止；职位描述超过 **12,000 chars** 会截断并在控制台/Markdown 提示。**持久化（传统路径）**：脚本可经 **`scripts/common/db.py`** 直连 MySQL：必要时插入 **`applications`**（`SAVED` + `job_lead_id`），写入 **`ai_interview_plans`**（`prompt_json` / `plan_json`）与 **`prep_tasks`**（按 `due_day_offset` 填 `due_date`）；非 dry-run DB 写入使用事务，失败会 rollback。**Day 25 起**亦可改为由脚本或外部流程 **`POST /api/job-leads/{id}/interview-plan`** 提交同一契约 JSON（含 **`plan_markdown`**），由 Ktor 落库，无需在应用服务器上跑 Python。重复生成策略：覆盖同一 application 既有 plan/tasks 后创建新 plan。正式运行报告路径 **`reports/interview_plan_<job_lead_id>.md`**。
- **`.env.example`**：已补充 **`AI_PROVIDER`**、OpenAI/Gemini model/key 等说明（与旧 **`AI_MODE`** 并存）。
- **边界**：计划生成不在浏览器内调模型（CLI / **`POST` JSON**）；全局 **`/prep`** 聚合仍占位（Day 26 已在 **Job leads 详情**提供单 lead 计划 UI）。纯 JS career site 与 discovery 无关本条。

### Day 25 — AI Interview Plan 后端 API（已完成）

- **路由**（均需 **`Authorization: Bearer <JWT>`**，按 **`applications.user_id`** 归属校验）：  
  `GET`/`POST` **`/api/job-leads/{id}/interview-plan`**，`GET`/`DELETE` **`/api/interview-plans/{id}`**，`GET` **`/api/prep/tasks`**（可选 `application_id`、`status`），`GET` **`/api/prep/tasks/today`**，`PATCH` **`/api/prep/tasks/{id}/complete`**（置 `done`）。
- **契约**：`POST` 接受 **`plan_json`** + **`plan_markdown`** + 可选 **`prompt_json`**、**`provider_mode`**、**`prep_tasks`**；服务端**不**子进程执行 Python。GET 某 lead 的最新计划时**不会**隐式创建 application（无 application 则 404）；**POST** 会通过 **`ensureApplicationForJobLead`** 必要时创建 application 再写入。
- **持久化**：**`ai_interview_plans`** 含 **`plan_markdown`**（MySQL `MEDIUMTEXT`）；替换计划时删除该 application 下旧 plan（级联 **`prep_tasks`**）。
- **实现位置**：`InterviewPlanRepository`、`InterviewPlanModels`、`Application.kt` 路由；H2 测试 DDL 含 **`plan_markdown`**；详见 **`docs/interview-plan-api.md`**、**`backend/README.md`**。

### Day 26 — AI 面试计划前端（Job leads 详情）（已完成）

- **`JobLeadsPage`** 选中 lead 后展示 **`InterviewPlanSection`**：**Generate Interview Plan** 展开 CLI 步骤（`python scripts/ai_interview_planner.py <job_lead_id>` 等）、**Refresh plan** 拉取 **`GET /api/job-leads/{id}/interview-plan`**；展示 **`plan_json`** 中 summary、技能、7 日计划、技术/行为题、项目 talking points；**prep_tasks** 勾选完成调用 **`PATCH /api/prep/tasks/{id}/complete`**；**mock** 提示（**`VITE_AI_PROVIDER=mock`** 与 **`provider_mode`**）；API 错误用 **`ErrorMessage`**。
- **代码**：`frontend/src/components/interviewPlan/InterviewPlanSection.tsx`、`frontend/src/api/interviewPlan.ts`、`frontend/src/types/interviewPlan.ts`；样式 **`frontend/src/styles/global.css`**；**`frontend/.env.example`** 可选 **`VITE_AI_PROVIDER`**。

### Day 27 — Settings 与 AI 配置说明（已完成）

- **后端**：**`GET /api/settings/status`**（JWT）返回 **`app_name` / `app_version`**、**`db_status`**、**`ai_provider`**（`AI_PROVIDER`/`AI_MODE`）、**`openai_api_key_configured`** / **`gemini_api_key_configured`**（布尔，**不返回密钥**）、**`ai_model`**；实现 **`settings/SettingsStatus.kt`**。
- **前端**：**`/settings`** 页面（API base URL、服务端 AI/DB 摘要、隐私提示）；**`docs/ai-setup.md`**（`.env` 中 **`AI_PROVIDER` / `AI_API_KEY` / `AI_MODEL`**、隐私、mock）；根 **`.env.example`**、**`frontend/.env.example`** 已对齐说明。

### Day 28 — 全量 Docker Compose（已完成）

- **`docker-compose.yml`**：**`mysql`**（原逻辑）+ **`backend`**（**`backend/Dockerfile`**，`healthcheck` **`/health`**）+ **`frontend`**（**`frontend/Dockerfile`** + **`frontend/nginx.conf`**，容器 **80**→宿主 **`FRONTEND_PORT`（默认 3000）**，**`/health`**）；backend 环境覆盖 **`DB_HOST=mysql`**、容器内 **`BACKEND_PORT=8080`**，映射 **`${BACKEND_PORT:-8080}:8080`**。
- **构建**：根目录 **`.dockerignore`**；**`VITE_API_BASE_URL`** 作为 frontend **build-arg**（默认 **`http://localhost:8080`**）。
- **脚本**：未默认打包 Python 服务；文档约定 **宿主机**运行 **`scripts/*.py`**（**`docs/local-setup.md`** / **`README.md`**）。
- **入口**：**`docker compose up --build -d`**；详见 **`README.md` Quick start**、**`.env.example`**。

### Day 29 — 测试、Lint、CI（已完成）

- **Makefile**（repo 根 **`careerpilot-local/`**）：**`make test`** → backend **`./gradlew test`**；frontend **`npm run typecheck`**、**`lint`**（ESLint，`--max-warnings 0`）、**`build`**；Python **`compileall`** + **`pytest tests/`**（mock careers HTML、`MockAiProvider` / `get_ai_provider`）+ **`ruff check tests`**。**`make build` / `dev` / `backup` / `report`** 见 **`README.md`**。
- **前端**：**`frontend/eslint.config.js`**、**`npm run lint`**。
- **Python**：**`requirements-dev.txt`**（pytest、ruff）、**`pytest.ini`**、**`ruff.toml`**（当前 **`extend-exclude = ["scripts"]`**，脚本仍以 **`compileall`** 做语法检查；全量 **`scripts/`** Ruff 清理可后继）。
- **后端**：追加 **`PublicApiSmokeTest`**（公开 **`GET /api/version`**、**`GET /health/db`** 与 H2）；既有 **`ScaffoldTest`** 已覆盖大量 API / 所有权。
- **GitHub Actions**：**`.github/workflows/ci.yml`** 三 job parallel：`backend`、`frontend`、`python`。

### Day 30 — README、Demo、发布打磨（已完成）

- **README**：公开仓库导向重写（前置 **Quick start**、截图占位、`docs/images/`、**`LICENSE`（MIT）**、**`SECURITY.md`**、演示八步、`docs/demo.md` / `docs/troubleshooting.md`）。
- **`docs/demo.md`**：注册 → target company → **`job_watcher --mock-html`** → mock 面试计划 → save application → 改状态 → 周报 → backup；含 **重置/重灌 seed** 说明。
- **`docs/troubleshooting.md`**：JWT、`DB_HOST`、Compose 端口、`SCRIPTS_*`、seed 重置等。
- **`SECURITY.md`**：禁止提交密钥、漏洞报告指引、产品设计边界简述。
- **`database/README.md`** + **`seed.sql`**：演示账号 **`demo@careerpilot.local`** / **`demo12345`**（ bcrypt，**仅限本地 Demo**）。
- **`.env.example`**：补充 **`SCRIPTS_*` / `API_BASE_URL`** 注释；**`.gitignore`**：`reports/*`（保留 **`reports/.gitkeep`**）、`scripts/.venv-test/`、常见 IDE/日志。

### Day 19–22 — Job watcher（已完成可用版，持续与后端 discovery 对齐）

- `scripts/job_watcher.py`：通过 API 读取 active target companies，默认以 `careers_url` 为 seed 做轻量 HTML crawl；支持 `--dry-run`、`--company-id`、`--mock-html`、`--no-discovery`、`--max-discovery-pages`、`--max-discovery-depth`、`--verbose-discovery`
- 抓取边界：继续强制跳过 LinkedIn / Indeed / Glassdoor；只处理 http(s) 公开页；允许从公司 seed 域名跳到同组织域名或常见 ATS host（Greenhouse、Lever、SmartRecruiters、Workday 等）
- 解析能力：普通 `<a>` 链接启发式、JSON-LD `JobPosting`、页面脚本/嵌入文本里的 job URL 与相对路径；按公司 keywords / locations 打分；通过 `/api/job-leads` 创建新线索，并处理重复 URL
- ATS/API fallback：Python watcher 已支持 Ashby Posting API、Workday CXS、SmartRecruiters API；后端 app-native discovery 已支持 Ashby / Workday / SmartRecruiters / **Greenhouse boards API**；同时扩展了 Lever、Jobvite、SuccessFactors、Taleo、Eightfold、Rippling ATS、Pinpoint、Recruiting.com、Oracle 等常见招聘域名识别
- 当前限制：仍不渲染 JavaScript-only careers site；少数强前端渲染或反爬站点仍可能漏抓。（CLI 面试计划见 **§4 Day 24**。）

### Day 23 — App 内岗位发现与失效链接清理（已完成可用版）

- 后端新增 app-native 操作：
  - `POST /api/job-leads/discover`：读取用户 active target companies，用 careers URL + keywords/locations 发现岗位并创建 job leads
  - `POST /api/job-leads/refresh-invalid`：检查现有 job lead URL，删除明确 404/410/closed 的 **unsaved** leads（默认保护 saved leads）
- 前端 `JobLeadsPage` 新增普通用户可用按钮：
  - **Find jobs**：无需终端、无需脚本 token，直接扫描所选公司或全部 active companies
  - **Remove closed links**：检查当前所选公司或全部 leads，清理明确失效的 unsaved links
- 后端 discovery 与 Python watcher 已同步主要规则：公开 HTML/JSON-LD/脚本 URL、ATS API fallback、静态资源过滤、泛导航/福利页过滤、LinkedIn / Indeed / Glassdoor 跳过。
- 重复链接处理：discovery 写入时遇到已有 `job_leads.url` 唯一索引冲突会计入 `duplicates_skipped`，不会中断整次扫描。
- 2026-05-10 针对真实 **JetBrains** 场景增强并验证：用户只填公司官网主页 **`https://www.jetbrains.com/`** 时，后端会自动尝试常见 career seed（`/careers/jobs/`、`/careers/`、`/jobs/`、`/company/careers/`、`/career/`），识别 JetBrains 跳转到 Greenhouse 后走 **Greenhouse boards API** 抓结构化职位；避免继续解析 Greenhouse HTML/脚本噪声；屏蔽 `my.greenhouse.io`、`job-boards.cdn.greenhouse.io`、`boards.eu.greenhouse.io`、`api-geocode-earth-proxy.greenhouse.io` 等非岗位域名。
- 写入稳健性：`JobLeadRepository.insert/update` 会截断 `title`（255）、`url`（2048）、`location`（255），避免真实站点长标题导致 MySQL `Data truncation` 中断整次 discovery；后端测试已覆盖超长 title。
- 实测结果：用 JetBrains 官网主页 + keywords `backend/backend engineer/kotlin/java/cloud platform` + locations `remote/united states/germany/europe`，`POST /api/job-leads/discover` 可创建 100+ 条 JetBrains Greenhouse 岗位；`keyword=backend` 返回 3 条匹配（含 `Backend Customer Success Engineer (Kotlin Ecosystem)` 两条与 `Senior Fullstack Developer (AIR Automations)`）；`Job posting` 噪声为 0。
- 边界：仍不渲染 JavaScript-only careers site；真实命中率取决于目标公司 career page 是否公开、是否暴露 HTML/JSON/API 数据。`refresh-invalid` 对 100+ 外部链接逐条访问，功能可用但耗时可能超过脚本默认 30s timeout，UI/脚本侧后续可做进度/更长超时/批量并发优化。

### 未实现 / 仍占位

- **无**后端内置迁移（Flyway/Liquibase）；仍以 **外部** `schema.sql` 初始化为主
- **AI 面试计划（产品流）**：Day 25 **REST** + Day 26 **Job leads 详情页**已可查看计划与勾选 prep；**浏览器内不直接调 LLM**（生成仍靠 CLI 或自行 **`POST`**）。**`/prep` 页面仍为 placeholder**（全局 prep 聚合入口待做）
- **前端 Dashboard** 仍只展示 stats；follow-ups、recent leads、upcoming interviews、prep summary 的 API client 已有，页面 widget 待完善
- **`app_settings` 表**：持久化用户偏好尚未产品化（Settings 页仅为部署/env **只读**状态）
- **Job discovery** 仍是 HTTP HTML/JSON/API 方案；JS 渲染站点命中率有限；app-native 后端与 Python watcher 已同步主要规则，但仍是两套实现，后续可抽 fixture/测试来防止规则漂移。Greenhouse/JetBrains 真实链路已验证；`refresh-invalid` 对大量链接仍偏慢。
- **E2E / Playwright**、前端组件单测仍为后续；可选 **`scripts` 专用容器镜像**尚未落地。（Day 29 已有 Gradle / ESLint+Vite build / **`tests/` pytest** / GitHub Actions。）

### 最近一次会话交接（模板：每次收尾覆写本小节）

- **日期**：2026-05-08
- **本次完成**：**Day 30**——面向 GitHub 的 **`README`**、**`docs/demo.md`** / **`docs/troubleshooting.md`**、**`SECURITY.md`**、**`LICENSE`（MIT）**、演示账号 **`demo@careerpilot.local`** / **`demo12345`**（seed）、**`.gitignore`**（`reports/*`、`scripts/.venv-test/`）、截图占位 **`docs/images/`**。
- **未完成 / 阻塞**：
  - **`/prep` 全局页**仍为 placeholder
  - App 内 **一键调 LLM** 未做（仍为 CLI / **`POST`**）
  - 宿主机 **`DB_HOST=localhost`** vs Compose 内 **`mysql`**：已在 **`.env.example`** / **`docs/local-setup.md`** 说明；仍需初学者留意
  - Dashboard 全量 widgets、**E2E**、可选 **scripts 容器化**
- **关键路径 / 涉及文件**：**`README.md`**、**`LICENSE`**、**`SECURITY.md`**、**`docs/demo.md`**、**`docs/troubleshooting.md`**、**`database/seed.sql`**、**`.env.example`**、**`.gitignore`**；质量与 Discovery 仍见 **`Makefile`** /**`JobLeadDiscoveryService.kt`** / **`JobLeadRepository.kt`**。（本 **`PROJECT_CONTEXT.md`**）
- **已运行验证（文档依据）**：**`make test`**（等价：backend `./gradlew test`、frontend **`npm run typecheck` + `lint` + `build`**、Python **`compileall` + `pytest` + `ruff check tests`**）；此前 **JetBrains/Greenhouse** discovery 手册验证仍有效（见上文 Day 23）。
- **给下一对话的一句话**：公开说明书与演示路径已齐；下一步可补真实截图、**`/prep` 页**、Dashboard widgets、或 **Playwright** smoke。

---

## 5. 重要约定（开发时必须遵守）

1. **秘钥**：不得硬编码；使用 `.env` + `.env.example` 占位说明。
2. **数据来源**：仅存取用户配置的公开招聘页；禁止针对 LinkedIn/Indeed/Glassdoor 等站内抓取策略。
3. **架构**：优先清晰分层（API、领域、持久化、集成），避免大泥球。
4. **接口契约**：后端响应宜统一、可序列化类型；前端对 API 响应使用 TypeScript 类型对齐。
5. **脚本**：Python 自动化在有意义时提供 `--dry-run`；不写死密钥。
6. **AI**：同时支持真实 API 与本地 **mock**，便于离线/CI。
7. **测试与验证**：新增功能应有最小测试或可重复的 `curl`/文档步骤。
8. **变更范围**：只改当前任务需要的文件；未要求不要批量「顺手重构」无关模块。

---

## 6. 本地快速验证（确认环境没坏）

**后端测试**（无本地 Gradle 时可用容器）：

```bash
cd careerpilot-local/backend
docker run --rm -v "$PWD":/app -w /app gradle:8.10.2-jdk21 gradle test --no-daemon
```

**前端类型检查**：

```bash
cd careerpilot-local/frontend
npm install
npm run typecheck
```

**手工看后端是否起来**（若已 `gradle run` 且端口与 `application.conf` 一致）：访问 **`GET /health`** 或 **`GET /api/version`**（仓库中已无 `/api/scaffold`）。

**MySQL only 或全栈（Docker Compose）**：

```bash
cd careerpilot-local
cp .env.example .env
# 仅 MySQL：docker compose up -d mysql
# 全栈（默认前端 http://localhost:3000 ，API :8080）：docker compose up --build -d
docker compose exec mysql mysql -u careerpilot -pcareerpilot_password careerpilot -e "SHOW TABLES;"
```

**本地一键启动（推荐）**：

```bash
cd careerpilot-local
./scripts/local-up.sh
```

**聚合质量检查（Day 29）**：

```bash
cd careerpilot-local
pip install -r scripts/requirements.txt -r requirements-dev.txt  # 首次
make test
```

**脚本 smoke test 示例**：

```bash
cd careerpilot-local
python -m scripts.job_watcher --dry-run --mock-html scripts/examples/mock_careers_page.html
python scripts/test_ai_provider.py
python -m scripts.ai_interview_planner --latest-unsaved --dry-run   # 需有效 JWT + 至少一条 unsaved lead
python -m scripts.check_followups --dry-run
python -m scripts.generate_weekly_report --dry-run
```

---

## 7. 建议的下一步任务（可按优先级推进）

以下顺序可按产品节奏调整，供新对话直接 pick：

1. **Job discovery 质量**：补 fixture/E2E，统一 Python watcher 与 app-native discovery 的规则；确认 crawl、JSON-LD、ATS 跳转、duplicate handling、closed-link cleanup 可重复。
2. **前端 Dashboard**：把已存在的 `/api/dashboard/follow-ups`、`recent-job-leads`、`upcoming-interviews`、`prep-summary` client 接到页面 widgets。
3. **Prep / AI（剩余产品化）**：**`/prep` 占位页**接入 `GET /api/prep/tasks`（及 today）、导航；可选让 **`ai_interview_planner.py`** 默认通过 **`POST /api/job-leads/{id}/interview-plan`** 写库；App 内一键调 LLM 仍为可选增强。
4. **Compose 运维（可选）**：生产级镜像签名、非 root、资源限制、单独 **`compose.override.yml`**；可选 **scripts** 服务镜像（当前推荐宿主机跑脚本）。
5. **迁移（可选）**：引入 Flyway/Liquibase，与现有 `schema.sql` 初始化策略衔接。
6. **测试**：补前端单测/**E2E** 或最小 Playwright smoke（Day 29 已为 **`make test`/CI** 打底）；后端可继续按需加 repository/route 细分测试；可对 **`scripts/`** 收窄 Ruff **`extend-exclude`** 并逐步修。

---

## 8. Cursor 会话接力：每日收尾时如何「自动」更新本文件

### 8.1 现实限制（必读）

Cursor **无法在后台默默监视**你每天何时写完代码；所谓「自动化」在项目里的落地方式是：

- **仓库规则** `.cursor/rules/careerpilot-project-context-handoff.mdc` 已开启 `alwaysApply: true`，模型在会话里会看到「收尾时要改 `PROJECT_CONTEXT.md`」的义务。
- 你在 **同一天收工时必须发一条口令**（或粘贴下方收尾指令）。推荐 **固定用同一句**：**「今天的任务结束了」**。  
→ 随后在**同一会话或新会话**开头说一次 **「请先读 PROJECT_CONTEXT.md」**（见 §9），即可衔接。

若你希望更强约束，可把「今天的任务结束了」粘在 Composer/Agent **独立一条消息**末尾发送，以减少模型漏执行文件更新的概率。

### 8.2 收尾口令示例（任选其一）

- 「今天的任务结束了」
- 「今日收工」「结束今天」「今天先到这里」「EOD」「更新交接文档」「更新 PROJECT_CONTEXT」

### 8.3 收尾时请 Cursor 执行的「命令」（复制到对话框即可）

下面这些不是 Shell 指令，是给 **Cursor Agent / Chat** 的自然语言指令，用于触发对 **本 Markdown 文件的更新**：

```text
今天的任务结束了。请严格执行 .cursor/rules/careerpilot-project-context-handoff.mdc 与 PROJECT_CONTEXT.md §8：
1) 根据今天实际改动与仓库现状更新 PROJECT_CONTEXT.md：§4 进度列表、§7 下一步清单、§4 小节「最近一次会话交接」；
2) 不要编造未完成的功能；不清楚处标「待核对」；
3) 若改了对外行为，检查 README/.env.example 是否需一并说明（只做必要的最小补充）。
```

规则文件：**`careerpilot-local/.cursor/rules/careerpilot-project-context-handoff.mdc`**（已向 Cursor 设为 `alwaysApply: true`）。

### 8.4 收尾时 Assistant 必须在 `PROJECT_CONTEXT.md` 内更新的块

| 更新块 | 内容 |
|--------|------|
| §4 上文列表 | 「已完成 / 未实现」与真实代码一致；删减已完成占位描述 |
| §4「最近一次会话交接」 | **覆写**：日期 + 摘要 + 阻塞 + 关键路径 + 验证情况 + 一句话接力 |
| §7 | 下一步：勾掉已完成，追加明日事项；保留优先级 |
| 其他 | 仅在栈/部署/密钥约定变动时改写 §2、§5、§6 |

### 8.5 不推荐的做法

- 依赖大脑记忆而不改文档 → 新开对话极易丢上下文。
- 把 `.env` 真值贴进文档 → **禁止**，只能写占位名或变量名。
- 在另一台机器/分支未 pulled 前就覆写整块 §4 → 应先 `git pull` 或写明「基于分支 X、commit Y」（可选）。

---

## 9. 新对话推荐开场白（复制给 Cursor）

```
请阅读 careerpilot-local/PROJECT_CONTEXT.md、careerpilot-local/README.md，
并浏览与当前任务相关的目录（例如 backend/ 或 frontend/）。
在遵守 PROJECT_CONTEXT 中「重要约定」的前提下，我们要实现：<你的具体任务>。
```

同一对话内需更新文档时：**「请先按 §8 更新 PROJECT_CONTEXT.md，然后再继续。」**

---

## 10. 文档维护（长期）

- 重大架构、栈或里程碑变化时，**同步更新**本文件与 `README.md`。
- 若某功能已落地，将「当前完成进度」与「下一步任务」相应改版；**每天至少通过 §8 收尾一次**，避免与新对话脱节。
