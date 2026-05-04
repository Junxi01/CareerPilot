#!/usr/bin/env bash
# One-command local dev: MySQL (Docker) + backend (Gradle) + frontend (Vite, foreground).
# Prerequisites: Docker, JDK 21, Node 18+, openssl (usually preinstalled on macOS).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ENV_FILE="${ROOT}/.env"
EXAMPLE="${ROOT}/.env.example"
LOG_DIR="${ROOT}/.logs"
PID_FILE="${LOG_DIR}/backend.pid"
LOG_FILE="${LOG_DIR}/backend.log"

mkdir -p "$LOG_DIR"

if [[ ! -f "$EXAMPLE" ]]; then
  echo "Missing .env.example — are you in the repo root?" >&2
  exit 1
fi

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Creating .env from .env.example …"
  cp "$EXAMPLE" "$ENV_FILE"
fi

# --- Normalize .env for “run backend on host” (portable in-place edit) ---
perl -i -pe 's/^DB_HOST=mysql\s*$/DB_HOST=localhost/' "$ENV_FILE" 2>/dev/null || true

# Fix accidental one-line paste: DB_HOST=localhostDB_PORT=3306
if grep -q '^DB_HOST=localhostDB_PORT=' "$ENV_FILE" 2>/dev/null; then
  perl -i -pe 's/^DB_HOST=localhostDB_PORT=(\d+)$/DB_HOST=localhost\nDB_PORT=$1/' "$ENV_FILE"
  echo "Fixed merged DB_HOST/DB_PORT line in .env."
fi

JWT_LINE="$(grep '^JWT_SECRET=' "$ENV_FILE" | tail -n1 || true)"
JWT_VAL="${JWT_LINE#JWT_SECRET=}"
NEED_JWT=0
if [[ -z "${JWT_VAL// /}" ]]; then
  NEED_JWT=1
elif [[ "$JWT_VAL" == replace-with-openssl-rand-hex-32 ]]; then
  NEED_JWT=1
elif [[ "$JWT_VAL" == change-me-please ]] || [[ "$JWT_VAL" == change-me* ]]; then
  NEED_JWT=1
fi

if [[ "$NEED_JWT" -eq 1 ]]; then
  if ! command -v openssl >/dev/null 2>&1; then
    echo "JWT_SECRET must be set in .env (openssl not found to auto-generate)." >&2
    exit 1
  fi
  NEW_SECRET="$(openssl rand -hex 32)"
  if grep -q '^JWT_SECRET=' "$ENV_FILE"; then
    perl -i -pe "s/^JWT_SECRET=.*/JWT_SECRET=${NEW_SECRET}/" "$ENV_FILE"
  else
    echo "JWT_SECRET=${NEW_SECRET}" >> "$ENV_FILE"
  fi
  echo "Wrote a new JWT_SECRET into .env (local dev only)."
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

BACKEND_PORT="${BACKEND_PORT:-8080}"
export BACKEND_PORT

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed or not in PATH." >&2
  exit 1
fi

echo "Starting MySQL (docker compose) …"
docker compose -f "${ROOT}/docker-compose.yml" up -d

echo "Waiting for MySQL to be healthy …"
for _ in $(seq 1 90); do
  ST="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}' careerpilot-mysql 2>/dev/null || echo unknown)"
  if [[ "$ST" == "healthy" ]]; then
    break
  fi
  sleep 1
done
ST="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}unknown{{end}}' careerpilot-mysql 2>/dev/null || echo unknown)"
if [[ "$ST" != "healthy" ]]; then
  echo "MySQL did not become healthy in time. Check: docker compose ps && docker compose logs mysql" >&2
  exit 1
fi

cleanup_backend() {
  if [[ -f "$PID_FILE" ]]; then
    local p
    p="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "${p:-}" ]] && kill -0 "$p" 2>/dev/null; then
      echo "Stopping backend (PID $p) …"
      kill "$p" 2>/dev/null || true
      wait "$p" 2>/dev/null || true
    fi
    rm -f "$PID_FILE"
  fi
}

trap cleanup_backend EXIT INT TERM

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE" 2>/dev/null)" 2>/dev/null; then
  echo "Stopping previous backend from .logs/backend.pid …"
  cleanup_backend
  trap cleanup_backend EXIT INT TERM
fi

echo "Starting backend on port ${BACKEND_PORT} (logs: ${LOG_FILE}) …"
nohup env bash -c '
  set -euo pipefail
  set -a
  # shellcheck disable=SC1090
  source "'"$ENV_FILE"'"
  set +a
  cd "'"$ROOT"'/backend"
  exec ./gradlew run --no-daemon
' >"$LOG_FILE" 2>&1 &
echo $! >"$PID_FILE"

echo "Waiting for http://127.0.0.1:${BACKEND_PORT}/health (first run can take many minutes — Gradle download/build — see ${LOG_FILE}) …"
READY=0
# Up to ~15 minutes; first Gradle run on a machine is often slow.
for i in $(seq 1 900); do
  CODE="$(
    curl -sS --connect-timeout 2 --max-time 5 -o /dev/null -w '%{http_code}' \
      "http://127.0.0.1:${BACKEND_PORT}/health" 2>/dev/null || echo 000
  )"
  if [[ "$CODE" == "200" ]]; then
    READY=1
    break
  fi
  if ! kill -0 "$(cat "$PID_FILE" 2>/dev/null)" 2>/dev/null; then
    echo "Backend exited early. Last lines of log:" >&2
    tail -n 40 "$LOG_FILE" >&2 || true
    exit 1
  fi
  if (( i % 30 == 0 )); then
    echo "… still waiting (${i}s, last HTTP ${CODE}). Tip: tail -f ${LOG_FILE}"
  fi
  sleep 1
done

if [[ "$READY" -ne 1 ]]; then
  echo "Backend did not return HTTP 200 on /health within 15 minutes." >&2
  echo "Check port: lsof -iTCP:${BACKEND_PORT} -sTCP:LISTEN" >&2
  tail -n 60 "$LOG_FILE" >&2 || true
  exit 1
fi

echo "Backend OK. Starting frontend (Ctrl+C stops Vite and the backend started here) …"
cd "${ROOT}/frontend"
if [[ ! -d node_modules ]]; then
  echo "npm install (first run) …"
  npm install
fi
# Do not use exec — keep this shell alive so EXIT/INT traps stop the Gradle backend.
npm run dev
