from __future__ import annotations

"""
AI provider abstraction for scripts (external API vs deterministic mock).

Environment (repo-root `.env` via load_env):
- AI_PROVIDER: `openai` | `gemini` | `mock` (if unset, falls back to AI_MODE for compatibility)
- AI_API_KEY: required when AI_PROVIDER=openai
- AI_MODEL: model id for OpenAI (e.g. gpt-4o-mini); defaults used if empty
- Optional: AI_API_BASE_URL — override OpenAI API base (default https://api.openai.com/v1)
- GEMINI_API_KEY or GOOGLE_API_KEY: required when AI_PROVIDER=gemini
- GEMINI_MODEL: Gemini model id; defaults to gemini-2.5-flash
- Optional: GEMINI_API_BASE_URL — default https://generativelanguage.googleapis.com/v1beta

No keys are hardcoded. Interview planning is out of scope here.
"""

import json
import os
import time
from abc import ABC, abstractmethod
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Optional, Tuple

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from scripts.common.config import load_env

DEFAULT_OPENAI_BASE = "https://api.openai.com/v1"
DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
DEFAULT_GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta"
DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
DEFAULT_TIMEOUT_SEC = 120.0
DEFAULT_CONNECT_TIMEOUT_SEC = 15.0
CONNECT_RETRY_ATTEMPTS = 3  # after urllib3 HTTP retries; extra tries for timeouts/DNS blips
BACKOFF_BASE_SEC = 1.5
DEBUG_DIR_NAME = "reports"


class MissingApiKeyError(RuntimeError):
    """Raised when AI_PROVIDER=openai but AI_API_KEY is missing or blank."""


class AiProviderError(RuntimeError):
    """Raised when the remote API returns an error or unusable response."""


class AiProvider(ABC):
    """Minimal contract: structured JSON from system + user prompts."""

    @abstractmethod
    def complete_json(self, *, system: str, user: str) -> Dict[str, Any]:
        ...


class MockAiProvider(AiProvider):
    """
    Deterministic JSON — same shape every call (no network).
    Includes short previews of prompts for debugging without leaking full secrets.
    """

    def complete_json(self, *, system: str, user: str) -> Dict[str, Any]:
        return {
            "provider": "mock",
            "version": 1,
            "deterministic": True,
            "result": {
                "summary": "CareerPilot mock AI response (no external call).",
                "confidence": 1.0,
                "items": [
                    {"id": "mock-1", "label": "sample_item", "score": 0.0},
                ],
            },
            "debug": {
                "system_chars": len(system),
                "user_chars": len(user),
            },
        }


class OpenAiProvider(AiProvider):
    """OpenAI Chat Completions with JSON object output."""

    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str,
        timeout: Tuple[float, float],
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._base = base_url.rstrip("/")
        self._timeout = timeout
        self._session = self._build_session()

    @staticmethod
    def _debug_dir() -> Path:
        root = Path(__file__).resolve().parents[2]
        p = root / DEBUG_DIR_NAME
        p.mkdir(parents=True, exist_ok=True)
        return p

    @classmethod
    def _redact_secret(cls, text: str, secret: str) -> str:
        if not secret:
            return text
        return text.replace(secret, "[REDACTED_API_KEY]")

    @classmethod
    def _write_debug_response(
        cls,
        *,
        reason: str,
        status_code: Optional[int],
        model: str,
        base_url: str,
        raw_response: str,
        api_key: str,
    ) -> Path:
        """
        Save raw model/API output for JSON debugging.
        Deliberately excludes request headers and API key.
        """
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        path = cls._debug_dir() / f"ai_provider_debug_{ts}.json"
        payload = {
            "reason": reason,
            "status_code": status_code,
            "model": model,
            "base_url": base_url,
            "raw_response": cls._redact_secret(raw_response, api_key),
        }
        path.write_text(json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8")
        return path

    @classmethod
    def _http_error_message(cls, status_code: int, body: str, api_key: str) -> str:
        snippet = cls._redact_secret(body or "", api_key)[:500]
        if status_code == 401:
            return (
                "OpenAI API error HTTP 401: authentication failed. "
                "Check that AI_API_KEY is correct and active. "
                f"Response: {snippet}"
            )
        if status_code == 429:
            return (
                "OpenAI API error HTTP 429: rate limit or quota limit. "
                "Wait and retry, or check billing/quota. "
                f"Response: {snippet}"
            )
        return f"OpenAI API error HTTP {status_code}: {snippet}"

    @staticmethod
    def _build_session() -> requests.Session:
        retry = Retry(
            total=3,
            connect=3,
            read=3,
            backoff_factor=0.8,
            status_forcelist=(429, 502, 503, 504),
            allowed_methods=frozenset({"POST"}),
        )
        adapter = HTTPAdapter(max_retries=retry)
        s = requests.Session()
        s.mount("https://", adapter)
        s.mount("http://", adapter)
        return s

    def complete_json(self, *, system: str, user: str) -> Dict[str, Any]:
        url = f"{self._base}/chat/completions"
        payload: Dict[str, Any] = {
            "model": self._model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "temperature": 0.2,
            "response_format": {"type": "json_object"},
        }
        headers = {
            "Authorization": f"Bearer {self._api_key}",
            "Content-Type": "application/json",
        }
        last_net: Optional[Exception] = None
        for attempt in range(CONNECT_RETRY_ATTEMPTS):
            try:
                r = self._session.post(
                    url,
                    headers=headers,
                    json=payload,
                    timeout=self._timeout,
                )
                if r.status_code >= 400:
                    raise AiProviderError(self._http_error_message(r.status_code, r.text or "", self._api_key))
                try:
                    data = r.json()
                except ValueError as e:
                    path = self._write_debug_response(
                        reason="api_response_json_parse_failed",
                        status_code=r.status_code,
                        model=self._model,
                        base_url=self._base,
                        raw_response=r.text or "",
                        api_key=self._api_key,
                    )
                    raise AiProviderError(
                        f"OpenAI response was not valid JSON. Raw response saved to {path}",
                    ) from e
                choices = data.get("choices") or []
                if not choices:
                    raise AiProviderError("OpenAI response missing choices[]")
                content = (
                    (choices[0].get("message") or {}).get("content")
                    or ""
                ).strip()
                if not content:
                    raise AiProviderError("OpenAI returned empty message content")
                try:
                    parsed = json.loads(content)
                except json.JSONDecodeError as e:
                    path = self._write_debug_response(
                        reason="model_content_json_parse_failed",
                        status_code=r.status_code,
                        model=self._model,
                        base_url=self._base,
                        raw_response=content,
                        api_key=self._api_key,
                    )
                    raise AiProviderError(
                        f"Model returned non-JSON string. Raw response saved to {path}. ({e})",
                    ) from e
                if not isinstance(parsed, dict):
                    raise AiProviderError("JSON root must be an object")
                return parsed
            except AiProviderError:
                raise
            except requests.RequestException as e:
                last_net = e
                if attempt < CONNECT_RETRY_ATTEMPTS - 1:
                    time.sleep(BACKOFF_BASE_SEC * (2**attempt))
                    continue
                raise AiProviderError(
                    f"OpenAI network failure after {CONNECT_RETRY_ATTEMPTS} attempts: {e}",
                ) from e
        assert last_net is not None
        raise AiProviderError(str(last_net))


class GeminiProvider(AiProvider):
    """Gemini generateContent REST API with JSON output."""

    def __init__(
        self,
        *,
        api_key: str,
        model: str,
        base_url: str,
        timeout: Tuple[float, float],
    ) -> None:
        self._api_key = api_key
        self._model = model
        self._base = base_url.rstrip("/")
        self._timeout = timeout
        self._session = OpenAiProvider._build_session()

    def complete_json(self, *, system: str, user: str) -> Dict[str, Any]:
        url = f"{self._base}/models/{self._model}:generateContent"
        payload: Dict[str, Any] = {
            "systemInstruction": {
                "parts": [{"text": system}],
            },
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": user}],
                },
            ],
            "generationConfig": {
                "temperature": 0.2,
                "responseMimeType": "application/json",
            },
        }
        headers = {
            "x-goog-api-key": self._api_key,
            "Content-Type": "application/json",
        }
        last_net: Optional[Exception] = None
        for attempt in range(CONNECT_RETRY_ATTEMPTS):
            try:
                r = self._session.post(
                    url,
                    headers=headers,
                    json=payload,
                    timeout=self._timeout,
                )
                if r.status_code >= 400:
                    raise AiProviderError(self._http_error_message(r.status_code, r.text or "", self._api_key))
                try:
                    data = r.json()
                except ValueError as e:
                    path = OpenAiProvider._write_debug_response(
                        reason="gemini_api_response_json_parse_failed",
                        status_code=r.status_code,
                        model=self._model,
                        base_url=self._base,
                        raw_response=r.text or "",
                        api_key=self._api_key,
                    )
                    raise AiProviderError(
                        f"Gemini response was not valid JSON. Raw response saved to {path}",
                    ) from e

                content = self._extract_text(data)
                if not content:
                    raise AiProviderError("Gemini response missing candidates[0].content.parts[].text")
                try:
                    parsed = json.loads(content)
                except json.JSONDecodeError as e:
                    path = OpenAiProvider._write_debug_response(
                        reason="gemini_model_content_json_parse_failed",
                        status_code=r.status_code,
                        model=self._model,
                        base_url=self._base,
                        raw_response=content,
                        api_key=self._api_key,
                    )
                    raise AiProviderError(
                        f"Gemini returned non-JSON string. Raw response saved to {path}. ({e})",
                    ) from e
                if not isinstance(parsed, dict):
                    raise AiProviderError("JSON root must be an object")
                return parsed
            except AiProviderError:
                raise
            except requests.RequestException as e:
                last_net = e
                if attempt < CONNECT_RETRY_ATTEMPTS - 1:
                    time.sleep(BACKOFF_BASE_SEC * (2**attempt))
                    continue
                raise AiProviderError(
                    f"Gemini network failure after {CONNECT_RETRY_ATTEMPTS} attempts: {e}",
                ) from e
        assert last_net is not None
        raise AiProviderError(str(last_net))

    @classmethod
    def _http_error_message(cls, status_code: int, body: str, api_key: str) -> str:
        snippet = OpenAiProvider._redact_secret(body or "", api_key)[:500]
        if status_code in (400, 401, 403):
            return (
                f"Gemini API error HTTP {status_code}: authentication or API permission failed. "
                "Check GEMINI_API_KEY/GOOGLE_API_KEY and Generative Language API access. "
                f"Response: {snippet}"
            )
        if status_code == 429:
            return (
                "Gemini API error HTTP 429: rate limit or quota limit. "
                "Wait and retry, or check Google AI Studio / Cloud quota. "
                f"Response: {snippet}"
            )
        return f"Gemini API error HTTP {status_code}: {snippet}"

    @staticmethod
    def _extract_text(data: Dict[str, Any]) -> str:
        candidates = data.get("candidates") or []
        if not candidates:
            return ""
        content = candidates[0].get("content") or {}
        parts = content.get("parts") or []
        texts = [str(p.get("text") or "") for p in parts if isinstance(p, dict)]
        return "\n".join(t for t in texts if t).strip()


def _resolve_provider_name() -> str:
    load_env()
    raw = (os.environ.get("AI_PROVIDER") or "").strip().lower()
    if raw:
        return raw
    # Backward compatibility with .env.example AI_MODE=
    return (os.environ.get("AI_MODE") or "mock").strip().lower()


def _require_openai_key() -> str:
    load_env()
    key = (os.environ.get("AI_API_KEY") or "").strip()
    if not key:
        raise MissingApiKeyError(
            "AI_API_KEY is not set or is empty. "
            "For AI_PROVIDER=openai, set AI_API_KEY in careerpilot-local/.env "
            "(or export it in your shell). Never commit real keys.",
        )
    return key


def _require_gemini_key() -> str:
    load_env()
    key = (os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or "").strip()
    if not key:
        raise MissingApiKeyError(
            "GEMINI_API_KEY / GOOGLE_API_KEY is not set or is empty. "
            "For AI_PROVIDER=gemini, set a Gemini API key in careerpilot-local/.env "
            "(or export it in your shell). Never commit real keys.",
        )
    return key


def get_ai_provider() -> AiProvider:
    """
    Factory: returns MockAiProvider, OpenAiProvider, or GeminiProvider based on AI_PROVIDER / AI_MODE.
    """
    name = _resolve_provider_name()
    if name in ("mock", "none", "off", ""):
        return MockAiProvider()
    if name == "openai":
        key = _require_openai_key()
        load_env()
        model = (os.environ.get("AI_MODEL") or "").strip() or DEFAULT_OPENAI_MODEL
        base = (os.environ.get("AI_API_BASE_URL") or "").strip().rstrip("/") or DEFAULT_OPENAI_BASE
        timeout = (
            float(os.environ.get("AI_CONNECT_TIMEOUT_SEC") or DEFAULT_CONNECT_TIMEOUT_SEC),
            float(os.environ.get("AI_TIMEOUT_SEC") or DEFAULT_TIMEOUT_SEC),
        )
        return OpenAiProvider(
            api_key=key,
            model=model,
            base_url=base,
            timeout=timeout,
        )
    if name in ("gemini", "google"):
        key = _require_gemini_key()
        load_env()
        model = (os.environ.get("GEMINI_MODEL") or os.environ.get("AI_MODEL") or "").strip() or DEFAULT_GEMINI_MODEL
        base = (os.environ.get("GEMINI_API_BASE_URL") or "").strip().rstrip("/") or DEFAULT_GEMINI_BASE
        timeout = (
            float(os.environ.get("AI_CONNECT_TIMEOUT_SEC") or DEFAULT_CONNECT_TIMEOUT_SEC),
            float(os.environ.get("AI_TIMEOUT_SEC") or DEFAULT_TIMEOUT_SEC),
        )
        return GeminiProvider(
            api_key=key,
            model=model,
            base_url=base,
            timeout=timeout,
        )
    raise ValueError(
        f"Unsupported AI_PROVIDER={name!r}. Use 'mock', 'openai', or 'gemini'.",
    )
