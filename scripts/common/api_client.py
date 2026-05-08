from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Dict, Optional

import requests

from .config import ApiConfig, default_env_path, get_api_config, load_env


class ApiError(RuntimeError):
    def __init__(self, status: int, code: str, message: str, *, response_preview: str = ""):
        msg = f"HTTP {status} {code}: {message}"
        if response_preview:
            msg = f"{msg}\nResponse preview: {response_preview}"
        super().__init__(msg)
        self.status = status
        self.code = code
        self.message = message
        self.response_preview = response_preview


@dataclass
class ApiClient:
    base_url: str
    token: str

    def _headers(self) -> Dict[str, str]:
        return {"Authorization": f"Bearer {self.token}", "Content-Type": "application/json"}

    def get(self, path: str, params: Optional[Dict[str, Any]] = None) -> Any:
        r = requests.get(f"{self.base_url}{path}", headers=self._headers(), params=params, timeout=30)
        return _unwrap(r)

    def post(self, path: str, body: Any) -> Any:
        r = requests.post(f"{self.base_url}{path}", headers=self._headers(), data=json.dumps(body), timeout=30)
        return _unwrap(r)

    def patch(self, path: str, body: Any) -> Any:
        r = requests.patch(f"{self.base_url}{path}", headers=self._headers(), data=json.dumps(body), timeout=30)
        return _unwrap(r)

    def delete(self, path: str) -> Any:
        r = requests.delete(f"{self.base_url}{path}", headers=self._headers(), timeout=30)
        return _unwrap(r)


def _unwrap(resp: requests.Response) -> Any:
    preview = (resp.text or "")[:800]
    try:
        payload = resp.json()
    except Exception:
        raise ApiError(resp.status_code, "invalid_json", "Response was not JSON", response_preview=preview)

    if not isinstance(payload, dict) or "success" not in payload:
        raise ApiError(resp.status_code, "bad_envelope", "Unexpected response envelope", response_preview=preview)

    if not payload.get("success") or payload.get("error"):
        err = payload.get("error") or {}
        raise ApiError(
            resp.status_code,
            err.get("code", "request_failed"),
            err.get("message", "Request failed"),
            response_preview=preview,
        )

    return payload.get("data")


def login_and_get_token(cfg: ApiConfig) -> str:
    if not cfg.email or not cfg.password:
        raise RuntimeError(
            "Missing credentials for scripts.\n"
            f"- Expected repo-root .env at: {default_env_path()}\n"
            "- Provide SCRIPTS_API_TOKEN (recommended), OR SCRIPTS_EMAIL + SCRIPTS_PASSWORD.\n"
            "- You can also export them in your shell environment.\n"
        )
    r = requests.post(
        f"{cfg.base_url}/api/auth/login",
        headers={"Content-Type": "application/json"},
        data=json.dumps({"email": cfg.email, "password": cfg.password}),
        timeout=30,
    )
    data = _unwrap(r)
    token = (data or {}).get("token")
    if not token:
        raise RuntimeError("Login succeeded but token missing")
    return str(token)


def get_client() -> ApiClient:
    load_env()
    cfg = get_api_config()
    token = cfg.token or login_and_get_token(cfg)
    return ApiClient(base_url=cfg.base_url, token=token)

