from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Optional


def default_env_path() -> Path:
    # scripts/common/config.py -> scripts/common -> scripts -> repo root
    return Path(__file__).resolve().parents[2] / ".env"


def _parse_dotenv_text(text: str) -> Dict[str, str]:
    out: Dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        k = k.strip()
        v = v.strip()
        if not k:
            continue
        # Strip optional quotes
        if len(v) >= 2 and ((v[0] == v[-1] == '"') or (v[0] == v[-1] == "'")):
            v = v[1:-1]
        out[k] = v
    return out


def load_env(env_path: Optional[Path] = None, *, override: bool = False) -> Dict[str, str]:
    """
    Load key=value pairs from repo-root `.env` into process env.
    This is intentionally tiny and dependency-free (compatible with our simple .env files).
    """
    if env_path is None:
        env_path = default_env_path()
    if not env_path.exists():
        return {}
    pairs = _parse_dotenv_text(env_path.read_text(encoding="utf-8"))
    for k, v in pairs.items():
        if override or os.environ.get(k) is None:
            os.environ[k] = v
    return pairs


@dataclass(frozen=True)
class DbConfig:
    host: str
    port: int
    name: str
    user: str
    password: str


@dataclass(frozen=True)
class ApiConfig:
    base_url: str
    token: Optional[str]
    email: Optional[str]
    password: Optional[str]


def get_db_config() -> DbConfig:
    load_env()
    host = os.environ.get("DB_HOST", "localhost")
    port = int(os.environ.get("DB_PORT", "3306"))
    name = os.environ.get("DB_NAME", os.environ.get("MYSQL_DATABASE", "careerpilot"))
    user = os.environ.get("DB_USER", os.environ.get("MYSQL_USER", "careerpilot"))
    password = os.environ.get("DB_PASSWORD", os.environ.get("MYSQL_PASSWORD", ""))
    return DbConfig(host=host, port=port, name=name, user=user, password=password)


def get_api_config() -> ApiConfig:
    load_env()
    base = os.environ.get("VITE_API_BASE_URL") or os.environ.get("API_BASE_URL") or "http://localhost:8080"
    token = os.environ.get("SCRIPTS_API_TOKEN") or os.environ.get("API_TOKEN")
    email = os.environ.get("SCRIPTS_EMAIL") or os.environ.get("EMAIL")
    password = os.environ.get("SCRIPTS_PASSWORD") or os.environ.get("PASSWORD")
    return ApiConfig(base_url=base.rstrip("/"), token=token, email=email, password=password)

