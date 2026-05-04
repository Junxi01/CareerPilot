#!/usr/bin/env bash
# Load repo-root .env into the environment, then run the Ktor backend.
# Usage (from anywhere):  ./scripts/run-backend-with-env.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT}/.env"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "Missing ${ENV_FILE}. Copy .env.example to .env and set JWT_SECRET (see docs/local-setup.md)." >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

cd "${ROOT}/backend"
exec ./gradlew run --no-daemon
