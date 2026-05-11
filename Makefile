# CareerPilot Local — aggregate quality & convenience (Day 29)
# Repo root expected: careerpilot-local/
ROOT := $(abspath .)
export GRADLE_USER_HOME := $(ROOT)/backend/.gradle-local
PYTHON := $(ROOT)/scripts/.venv-test/bin/python
PIP := $(ROOT)/scripts/.venv-test/bin/pip

.PHONY: default test test-backend test-frontend test-python \
	build build-backend build-frontend dev backup report lint lint-python lint-frontend python-deps

default: test

test-backend:
	cd "$(ROOT)/backend" && ./gradlew test --no-daemon

test-frontend:
	cd "$(ROOT)/frontend" && npm ci && npm run typecheck && npm run lint && npm run build

# Syntax-check scripts; lint + pytest smoke tests (`tests/` imports `scripts.*`)
python-deps:
	@test -x "$(PYTHON)" || python3 -m venv "$(ROOT)/scripts/.venv-test"
	"$(PIP)" install -r "$(ROOT)/requirements-dev.txt"

test-python: python-deps
	cd "$(ROOT)" && "$(PYTHON)" -m compileall -q scripts
	cd "$(ROOT)" && "$(PYTHON)" -m pytest -q
	cd "$(ROOT)" && "$(PYTHON)" -m ruff check tests

test: test-backend test-frontend test-python

build-backend:
	cd "$(ROOT)/backend" && ./gradlew installDist --no-daemon

build-frontend:
	cd "$(ROOT)/frontend" && npm ci && npm run build

build: build-backend build-frontend

# MySQL container + Gradle + Vite on host — see README
dev:
	"$(ROOT)/scripts/local-up.sh"

backup:
	cd "$(ROOT)" && python3 scripts/backup_database.py

report:
	cd "$(ROOT)" && python3 scripts/generate_weekly_report.py

lint-frontend:
	cd "$(ROOT)/frontend" && npm run lint

lint-python:
	cd "$(ROOT)" && python3 -m ruff check tests

lint: lint-frontend lint-python
