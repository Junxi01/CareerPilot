"""Mock AI provider (no network)."""

import pytest

from scripts.common.ai_provider import MissingApiKeyError, MockAiProvider, get_ai_provider


@pytest.fixture(autouse=True)
def _isolate_ai_env(monkeypatch: pytest.MonkeyPatch) -> None:
    """Avoid reading repo-root `.env`, which would override intentional test env vars."""
    monkeypatch.setattr("scripts.common.ai_provider.load_env", lambda: None)
    monkeypatch.delenv("AI_API_KEY", raising=False)
    monkeypatch.delenv("GEMINI_API_KEY", raising=False)
    monkeypatch.delenv("GOOGLE_API_KEY", raising=False)


def test_mock_ai_provider_shape() -> None:
    p = MockAiProvider()
    out = p.complete_json(system="sys", user="usr")
    assert out["provider"] == "mock"
    assert out["deterministic"] is True
    assert "summary" in out["result"]
    assert out["debug"]["system_chars"] == len("sys")
    assert out["debug"]["user_chars"] == len("usr")


def test_get_ai_provider_mock_env(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "mock")
    monkeypatch.delenv("AI_MODE", raising=False)
    prov = get_ai_provider()
    assert isinstance(prov, MockAiProvider)


def test_get_ai_provider_fallback_ai_mode(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("AI_PROVIDER", raising=False)
    monkeypatch.setenv("AI_MODE", "mock")
    prov = get_ai_provider()
    assert isinstance(prov, MockAiProvider)


def test_get_ai_provider_openai_requires_key(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_PROVIDER", "openai")
    monkeypatch.delenv("AI_API_KEY", raising=False)
    with pytest.raises(MissingApiKeyError, match="AI_API_KEY"):
        get_ai_provider()
