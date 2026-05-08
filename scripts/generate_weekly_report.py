from __future__ import annotations

import argparse
import sys
from datetime import datetime

from scripts.common.api_client import ApiError, get_client
from scripts.common.config import load_env


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(description="Generate a simple weekly report from dashboard endpoints.")
    p.add_argument("--dry-run", action="store_true", help="No side effects (read-only).")
    p.add_argument("--format", choices=["md", "text"], default="md", help="Output format.")
    args = p.parse_args()

    if args.dry_run:
        print("[weekly_report] dry-run: ON", file=sys.stderr)

    try:
        api = get_client()
        stats = api.get("/api/dashboard/stats") or {}
        followups = api.get("/api/dashboard/follow-ups") or {}
        recent = api.get("/api/dashboard/recent-job-leads") or {}
        upcoming = api.get("/api/dashboard/upcoming-interviews") or {}
        prep = api.get("/api/dashboard/prep-summary") or {}
    except ApiError as e:
        print(f"[weekly_report] ERROR: {e}", file=sys.stderr)
        return 2

    now = datetime.now().strftime("%Y-%m-%d")
    if args.format == "md":
        print(f"# Weekly report ({now})\n")
        print("## Dashboard stats")
        for k in [
            "total_applications",
            "applications_this_week",
            "interviews_count",
            "offers_count",
            "rejections_count",
            "response_rate",
            "follow_ups_due",
            "job_leads_discovered_this_week",
            "prep_tasks_due_today",
        ]:
            if k in stats:
                print(f"- **{k}**: {stats.get(k)}")
        print("\n## Follow-ups due")
        items = followups.get("items", []) if isinstance(followups, dict) else (followups or [])
        if not items:
            print("- (none)")
        else:
            for it in items[:20]:
                print(f"- {it.get('due_at','')} — {it.get('message','')}")
        print("\n## Recent job leads")
        r = recent.get("items", []) if isinstance(recent, dict) else (recent or [])
        if not r:
            print("- (none)")
        else:
            for it in r[:10]:
                print(f"- {it.get('discovered_at','')} — {it.get('company_name','')} — {it.get('role_title','')}")
        print("\n## Upcoming interviews")
        u = upcoming.get("items", []) if isinstance(upcoming, dict) else (upcoming or [])
        if not u:
            print("- (none)")
        else:
            for it in u[:10]:
                print(f"- {it.get('scheduled_at','')} — {it.get('company_name','')} — {it.get('round_name','')}")
        print("\n## Prep summary")
        for k, v in (prep.items() if isinstance(prep, dict) else []):
            print(f"- **{k}**: {v}")
    else:
        print(f"Weekly report ({now})")
        print("Stats:", stats)
        print("Follow-ups:", followups)
        print("Recent leads:", recent)
        print("Upcoming interviews:", upcoming)
        print("Prep summary:", prep)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

