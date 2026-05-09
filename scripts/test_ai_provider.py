#!/usr/bin/env python3
"""
Smoke test for scripts.common.ai_provider.

Run from repo root:
  cd careerpilot-local
  python scripts/test_ai_provider.py

Or:
  python -m scripts.test_ai_provider

Uses AI_PROVIDER / AI_MODE from .env (defaults to mock).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

# Allow `python scripts/test_ai_provider.py` without PYTHONPATH=.
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from scripts.common.ai_provider import (  # noqa: E402
    MissingApiKeyError,
    get_ai_provider,
)


def main() -> int:
    try:
        provider = get_ai_provider()
    except MissingApiKeyError as e:
        print(f"[test_ai_provider] ERROR: {e}", file=sys.stderr)
        return 2
    except ValueError as e:
        print(f"[test_ai_provider] ERROR: {e}", file=sys.stderr)
        return 2

    system = (
        "You reply only with a single JSON object. "
        "Include keys: greeting (string), n (integer equals 42)."
    )
    user = 'Respond with JSON: {"greeting":"hello","n":42}'

    print("[test_ai_provider] Calling complete_json() …")
    try:
        out = provider.complete_json(system=system, user=user)
    except Exception as e:
        print(f"[test_ai_provider] FAILED: {type(e).__name__}: {e}", file=sys.stderr)
        return 1

    print(json.dumps(out, indent=2, ensure_ascii=False))

    if not isinstance(out, dict):
        print("[test_ai_provider] FAILED: response is not a dict", file=sys.stderr)
        return 1

    print("[test_ai_provider] OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
