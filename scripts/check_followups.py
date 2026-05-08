from __future__ import annotations

import argparse
import sys
from datetime import datetime

from scripts.common.api_client import ApiError, get_client
from scripts.common.config import load_env


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(description="Check follow-ups due (dashboard follow-ups).")
    p.add_argument("--dry-run", action="store_true", help="No side effects (this script is read-only anyway).")
    args = p.parse_args()

    print(f"[check_followups] started at {datetime.now().isoformat(timespec='seconds')}")
    if args.dry_run:
        print("[check_followups] dry-run: ON")

    try:
        api = get_client()
        data = api.get("/api/dashboard/follow-ups")
    except ApiError as e:
        print(f"[check_followups] ERROR: {e}", file=sys.stderr)
        return 2

    items = (data or {}).get("items") if isinstance(data, dict) else data
    items = items or []
    print(f"[check_followups] follow-ups due: {len(items)}")
    for i, it in enumerate(items[:50], start=1):
        msg = it.get("message") if isinstance(it, dict) else str(it)
        due = it.get("due_at") if isinstance(it, dict) else ""
        print(f"  {i:02d}. due_at={due}  {msg}")
    if len(items) > 50:
        print(f"  ... ({len(items) - 50} more)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

