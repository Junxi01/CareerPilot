# 本地部署指南（CareerPilot Local）

## 最快方式（一条命令）

```bash
cd careerpilot-local
./scripts/local-up.sh
```

依赖：**Docker**、**JDK 21**、**Node 18+**（以及通常自带的 **openssl**）。脚本会：创建/修补 `.env`（含随机 `JWT_SECRET`）、`docker compose up` MySQL、后台启动后端、前台启动 Vite。停止：`Ctrl+C` 或 `./scripts/local-down.sh`。

---

在「本机运行后端 + 前端，MySQL 用 Docker」的标准形态下，也可按下面步骤手动执行。

## 1. 前置条件

| 工具 | 用途 |
|------|------|
| **Docker Desktop**（或兼容引擎） | 运行 MySQL 8 |
| **JDK 21** | 编译、运行 Kotlin 后端 |
| **Node.js 18+** | 前端 Vite / npm |

检查示例：

```bash
docker version
java -version   # 需显示 21
node -v
```

## 2. 环境与密钥

仓库根目录为 **`careerpilot-local/`**（以下命令均在该目录执行）。

```bash
cd careerpilot-local
cp .env.example .env
```

编辑 **`.env`**，至少确认：

1. **`JWT_SECRET`**  
   不能为空，且不能以 `change-me` 开头，否则后端会拒绝启动（安全策略）。本地可用随机串，例如：
   ```bash
   openssl rand -hex 32
   ```
   将输出写入 `.env` 中的 `JWT_SECRET=...`。

2. **`DB_HOST`**（后端跑在你电脑上，不在 Compose 网络里时）  
   使用 **`localhost`**，不要用 `mysql`。  
   `mysql` 仅在后端将来作为 **Docker 服务与 MySQL 同一 Compose 网络** 时使用。

3. **`DB_PORT`**  
   与 `docker-compose.yml` 映射到主机的端口一致（默认 **3306**，若主机端口冲突可改 `.env` 里 `DB_PORT` 与 compose 映射）。

根目录 `.env` **不会**被 Gradle/npm 自动加载；后端进程只会读到「当前 Shell 里的环境变量」。请使用下文 **`scripts/run-backend-with-env.sh`**，或手动：

```bash
set -a && source .env && set +a
```

后再启动后端。

## 3. 启动 MySQL（Docker Compose）

```bash
docker compose up -d
docker compose ps
```

等待 `mysql` 为 **healthy**（首次会执行 `database/schema.sql` 与 `seed.sql`）。

验证数据库：

```bash
docker compose exec mysql mysql -u careerpilot -p"${MYSQL_PASSWORD:-careerpilot_password}" careerpilot -e "SHOW TABLES;"
```

密码以你 `.env` 里 **`MYSQL_PASSWORD`** / **`DB_PASSWORD`** 为准。

### 首次初始化失败或改了 schema 想重来

初始化脚本只在 **空数据卷** 首次挂载时执行。需要从头重建：

```bash
docker compose down -v
docker compose up -d
```

**注意：`down -v` 会删除 MySQL 容器数据卷，本地数据清空。**

## 4. 启动后端

```bash
./scripts/run-backend-with-env.sh
```

或在已 `source .env` 的终端中：

```bash
cd backend
./gradlew run
```

默认监听 **`http://localhost:8080`**（可用 `.env` 中 `BACKEND_PORT` 修改）。

快速验证：

```bash
curl -s http://localhost:8080/health
curl -s http://localhost:8080/health/db
```

`/health/db` 返回 `connected` 表示已连上 MySQL。

## 5. 启动前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 **`http://localhost:5173`**。

前端默认把 API 当作 **`http://localhost:8080`**（见 `App.tsx` 中 fallback）。若要改地址，可在 **`frontend/`** 下新建 `.env.local`（可自行复制 `frontend/.env.example`）：

```bash
VITE_API_BASE_URL=http://localhost:8080
```

## 6. 注册第一个账号（可选）

种子数据里有演示用户邮箱，未必能直接登录（密码未随仓库配置）。本地可自行注册：

```bash
curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your-secure-password","displayName":"You"}'
```

更多接口见 **`backend/README.md`**。

## 7. 常见问题

| 现象 | 处理 |
|------|------|
| 后端启动报 `JWT_SECRET is required` | 编辑 `.env`，设置非空且非 `change-me*` 的 `JWT_SECRET` |
| `Communications link failure` / 连不上 DB | 确认 MySQL 已起、`DB_HOST=localhost`、`DB_PORT` 与 compose 映射一致 |
| 改 schema 后表结构没变 | 需要 `docker compose down -v` 后重新 `up`（会丢库），或以后接 Flyway 等迁移 |
| 时区与「今日提醒」不对 | 后端使用 **JVM 默认时区**（多为本机/Docker 宿主 `TZ`）；需要时在运行环境里设置 `TZ` |

---

更完整的 API 说明：**`backend/README.md`**；数据库表说明：**`docs/database-schema.md`**。
