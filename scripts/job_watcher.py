from __future__ import annotations

"""
Placeholder for the future "job watcher" automation.

Planned behavior:
- Read target companies from DB or API
- Fetch each public careers page URL (no login-required sites)
- Parse listings and de-dupe by URL per user
- Store new items in job_leads and notify/print changes
"""

import argparse
from datetime import datetime

from scripts.common.config import load_env


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(description="(placeholder) Watch target company career pages for new job leads.")
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()

    print(f"[job_watcher] placeholder — {datetime.now().isoformat(timespec='seconds')}")
    print("[job_watcher] This script is not implemented yet.")
    print("[job_watcher] Next: fetch careers_page_url for each target company and create job_leads rows.")
    if args.dry_run:
        print("[job_watcher] dry-run: ON")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

