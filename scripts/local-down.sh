#!/usr/bin/env bash
# Stop backend started by local-up.sh; optionally stop MySQL.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="${ROOT}/.logs/backend.pid"

if [[ -f "$PID_FILE" ]]; then
  p="$(cat "$PID_FILE" 2>/dev/null || true)"
  if [[ -n "${p:-}" ]] && kill -0 "$p" 2>/dev/null; then
    echo "Stopping backend PID $p …"
    kill "$p" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "Backend stopped."
else
  echo "No .logs/backend.pid — nothing to stop."
fi

if [[ "${1:-}" == "--all" ]]; then
  cd "$ROOT"
  docker compose stop
  echo "Docker Compose services stopped (mysql)."
fi
