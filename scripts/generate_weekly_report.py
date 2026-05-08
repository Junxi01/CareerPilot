from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from scripts.common.config import default_env_path, load_env

try:
    from scripts.common.db import ensure_connection, query
except ModuleNotFoundError as e:  # pragma: no cover
    # Allow --help to work even if optional deps aren't installed yet.
    if "mysql" in str(e).lower():
        ensure_connection = None  # type: ignore[assignment]
        query = None  # type: ignore[assignment]
    else:
        raise


@dataclass(frozen=True)
class WeekWindow:
    label: str
    start: datetime
    end: datetime
    start_date: date
    end_date: date  # exclusive


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _ensure_reports_dir(repo_root: Path) -> Path:
    p = repo_root / "reports"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _week_window(which: str) -> WeekWindow:
    today = date.today()
    # Monday = 0 ... Sunday = 6
    monday = today - timedelta(days=today.weekday())
    if which == "previous":
        monday = monday - timedelta(days=7)
    start_date = monday
    end_date = start_date + timedelta(days=7)
    start = datetime.combine(start_date, datetime.min.time())
    end = datetime.combine(end_date, datetime.min.time())
    label = "current" if which == "current" else "previous"
    return WeekWindow(label=label, start=start, end=end, start_date=start_date, end_date=end_date)


def _get_user_id(conn, *, user_id_env: Optional[str], email_env: Optional[str]) -> int:
    if user_id_env:
        try:
            return int(user_id_env)
        except Exception:
            raise RuntimeError("SCRIPTS_USER_ID must be an integer")

    email = (email_env or "").strip()
    if not email:
        raise RuntimeError(
            "Missing user context for DB reporting.\n"
            f"- Expected repo-root .env at: {default_env_path()}\n"
            "- Provide SCRIPTS_USER_ID, OR provide SCRIPTS_EMAIL so we can look up the user id.\n"
        )

    r = query(conn, "SELECT id FROM users WHERE email = %s LIMIT 1", (email,))
    if not r.rows:
        raise RuntimeError(f"User not found in DB for email={email!r}. Did you register/login at least once?")
    return int(r.rows[0]["id"])


def _fmt_dt(dt: Optional[datetime]) -> str:
    if not dt:
        return ""
    return dt.strftime("%Y-%m-%d %H:%M")


def _render_md(
    *,
    window: WeekWindow,
    applications_sent: List[Dict[str, Any]],
    interviews: List[Dict[str, Any]],
    rejections: List[Dict[str, Any]],
    offers: List[Dict[str, Any]],
    followups: List[Dict[str, Any]],
    new_leads: List[Dict[str, Any]],
    prep_completed: List[Dict[str, Any]],
) -> Tuple[str, str]:
    # Filename date: week start (Monday)
    filename_date = window.start_date.isoformat()

    lines: List[str] = []
    lines.append(f"# Weekly report — week of {window.start_date.isoformat()}")
    lines.append("")
    lines.append(f"Window: `{window.start_date.isoformat()}` → `{(window.end_date - timedelta(days=1)).isoformat()}`")
    lines.append(f"Generated at: `{datetime.now().isoformat(timespec='seconds')}`")
    lines.append("")

    def section(title: str, items: List[Dict[str, Any]], empty: str) -> None:
        lines.append(f"## {title}")
        lines.append("")
        if not items:
            lines.append(empty)
            lines.append("")
            return
        lines.append(f"Total: **{len(items)}**")
        lines.append("")

    section("Applications sent this week", applications_sent, "_No applications sent this week._")
    for a in applications_sent[:50]:
        lines.append(
            f"- **{a.get('company_name','')}** — {a.get('role_title','')} "
            f"(`{a.get('status','')}`) — {a.get('job_url','')}"
        )
    if applications_sent:
        lines.append("")

    section("Interviews scheduled", interviews, "_No interviews scheduled this week._")
    for iv in interviews[:50]:
        lines.append(
            f"- `{_fmt_dt(iv.get('scheduled_at'))}` — **{iv.get('company_name','')}** — {iv.get('role_title','')} — {iv.get('round_name','')}"
        )
    if interviews:
        lines.append("")

    section("Rejections", rejections, "_No rejections recorded this week._")
    for a in rejections[:50]:
        lines.append(f"- **{a.get('company_name','')}** — {a.get('role_title','')} — {a.get('job_url','')}")
    if rejections:
        lines.append("")

    section("Offers", offers, "_No offers recorded this week._")
    for a in offers[:50]:
        lines.append(f"- **{a.get('company_name','')}** — {a.get('role_title','')} — {a.get('job_url','')}")
    if offers:
        lines.append("")

    section("Follow-ups needed", followups, "_No follow-ups needed in this window._")
    for fu in followups[:50]:
        company = str(fu.get("company_name") or "")
        role_title = str(fu.get("role_title") or "")
        title = str(fu.get("title") or "")
        job_url = str(fu.get("job_url") or "")
        if job_url:
            lines.append(f"- `{fu.get('due','')}` — **{company}** — {role_title} — {title} — {job_url}")
        else:
            lines.append(f"- `{fu.get('due','')}` — **{company}** — {role_title} — {title}")
    if followups:
        lines.append("")

    section("New job leads", new_leads, "_No new job leads discovered this week._")
    for jl in new_leads[:50]:
        lines.append(
            f"- `{_fmt_dt(jl.get('discovered_at'))}` — **{jl.get('company_name','')}** — {jl.get('title','')} — {jl.get('url','')}"
        )
    if new_leads:
        lines.append("")

    section("Prep tasks completed", prep_completed, "_No prep tasks completed this week._")
    for pt in prep_completed[:50]:
        lines.append(f"- **{pt.get('company_name','')}** — {pt.get('role_title','')} — {pt.get('label','')}")
    if prep_completed:
        lines.append("")

    # Suggested focus: simple heuristic
    lines.append("## Suggested focus for next week")
    lines.append("")
    focus: List[str] = []
    if followups:
        focus.append(f"- Clear **{len(followups)}** follow-ups first (highest ROI).")
    if not applications_sent:
        focus.append("- Increase outbound volume: set a target (e.g. 5–10 applications) and batch apply.")
    if interviews:
        focus.append("- Prioritize interview prep for scheduled rounds (prep tasks + mock answers).")
    if new_leads:
        focus.append("- Triage new leads: save top matches and archive noise quickly.")
    if not focus:
        focus.append("- Keep steady: apply, track, and schedule follow-ups; add 1–2 target companies if pipeline is thin.")
    lines.extend(focus)
    lines.append("")

    return filename_date, "\n".join(lines) + "\n"


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(description="Generate a weekly markdown report from the MySQL database.")
    p.add_argument("--week", choices=["current", "previous"], default="current", help="Which week to report on.")
    p.add_argument("--dry-run", action="store_true", help="Print a preview but do not write the report file.")
    args = p.parse_args()

    if ensure_connection is None or query is None:
        print(
            "[weekly_report] ERROR: missing dependency 'mysql-connector-python'.\n"
            "Install it with: pip install -r scripts/requirements.txt",
            file=sys.stderr,
        )
        return 2

    if args.dry_run:
        print("[weekly_report] dry-run: ON", file=sys.stderr)

    window = _week_window(args.week)

    try:
        conn = ensure_connection()
    except Exception as e:
        print(f"[weekly_report] ERROR: failed to connect to MySQL: {e}", file=sys.stderr)
        return 2

    try:
        user_id = _get_user_id(
            conn,
            user_id_env=os.environ.get("SCRIPTS_USER_ID"),
            email_env=os.environ.get("SCRIPTS_EMAIL") or os.environ.get("EMAIL"),
        )

        # Applications created in window
        applications_sent = query(
            conn,
            """
            SELECT a.id, tc.name AS company_name, a.role_title, a.status, a.job_url, a.created_at
            FROM applications a
            JOIN target_companies tc ON tc.id = a.company_id
            WHERE a.user_id = %s
              AND a.created_at >= %s AND a.created_at < %s
            ORDER BY a.created_at DESC, a.id DESC
            """.strip(),
            (user_id, window.start, window.end),
        ).rows

        # Interviews scheduled in window
        interviews = query(
            conn,
            """
            SELECT i.id, i.round_name, i.scheduled_at, tc.name AS company_name, a.role_title
            FROM interviews i
            JOIN applications a ON a.id = i.application_id
            JOIN target_companies tc ON tc.id = a.company_id
            WHERE a.user_id = %s
              AND i.scheduled_at IS NOT NULL
              AND i.scheduled_at >= %s AND i.scheduled_at < %s
            ORDER BY i.scheduled_at ASC, i.id ASC
            """.strip(),
            (user_id, window.start, window.end),
        ).rows

        # Status transitions in window (use updated_at as "recorded")
        rejections = query(
            conn,
            """
            SELECT a.id, tc.name AS company_name, a.role_title, a.job_url, a.updated_at
            FROM applications a
            JOIN target_companies tc ON tc.id = a.company_id
            WHERE a.user_id = %s
              AND a.status = 'REJECTED'
              AND a.updated_at >= %s AND a.updated_at < %s
            ORDER BY a.updated_at DESC, a.id DESC
            """.strip(),
            (user_id, window.start, window.end),
        ).rows

        offers = query(
            conn,
            """
            SELECT a.id, tc.name AS company_name, a.role_title, a.job_url, a.updated_at
            FROM applications a
            JOIN target_companies tc ON tc.id = a.company_id
            WHERE a.user_id = %s
              AND a.status = 'OFFER'
              AND a.updated_at >= %s AND a.updated_at < %s
            ORDER BY a.updated_at DESC, a.id DESC
            """.strip(),
            (user_id, window.start, window.end),
        ).rows

        # Follow-ups needed in window:
        # - reminders due in window and not done
        # - application next_follow_up_date in [start_date, end_date)
        followups = query(
            conn,
            """
            SELECT 'reminder' AS kind,
                   r.id AS id,
                   r.application_id AS application_id,
                   r.message AS title,
                   r.due_at AS due,
                   tc.name AS company_name,
                   a.role_title AS role_title,
                   a.job_url AS job_url
            FROM reminders r
            LEFT JOIN applications a ON a.id = r.application_id
            LEFT JOIN target_companies tc ON tc.id = a.company_id
            WHERE r.user_id = %s AND r.done = 0 AND r.due_at >= %s AND r.due_at < %s
            UNION ALL
            SELECT 'application_follow_up' AS kind,
                   a.id AS id,
                   a.id AS application_id,
                   'Follow up' AS title,
                   a.next_follow_up_date AS due,
                   tc.name AS company_name,
                   a.role_title AS role_title,
                   a.job_url AS job_url
            FROM applications a
            JOIN target_companies tc ON tc.id = a.company_id
            WHERE a.user_id = %s
              AND a.next_follow_up_date IS NOT NULL
              AND a.next_follow_up_date >= %s AND a.next_follow_up_date < %s
            """.strip(),
            (user_id, window.start, window.end, user_id, window.start_date, window.end_date),
        ).rows
        # sort followups by due
        followups.sort(key=lambda r: (str(r.get("due") or ""), int(r.get("id") or 0)))

        # New job leads discovered in window
        new_leads = query(
            conn,
            """
            SELECT jl.id, tc.name AS company_name, jl.title, jl.url, jl.discovered_at
            FROM job_leads jl
            JOIN target_companies tc ON tc.id = jl.company_id
            WHERE tc.user_id = %s
              AND jl.discovered_at >= %s AND jl.discovered_at < %s
            ORDER BY jl.discovered_at DESC, jl.id DESC
            """.strip(),
            (user_id, window.start, window.end),
        ).rows

        # Prep tasks completed in window (status='done', updated_at in window)
        prep_completed = query(
            conn,
            """
            SELECT pt.id, pt.label, pt.updated_at,
                   tc.name AS company_name, a.role_title
            FROM prep_tasks pt
            JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
            JOIN applications a ON a.id = p.application_id
            JOIN target_companies tc ON tc.id = a.company_id
            WHERE a.user_id = %s
              AND pt.status = 'done'
              AND pt.updated_at >= %s AND pt.updated_at < %s
            ORDER BY pt.updated_at DESC, pt.id DESC
            """.strip(),
            (user_id, window.start, window.end),
        ).rows

        filename_date, md = _render_md(
            window=window,
            applications_sent=applications_sent,
            interviews=interviews,
            rejections=rejections,
            offers=offers,
            followups=followups,
            new_leads=new_leads,
            prep_completed=prep_completed,
        )

        repo_root = _repo_root()
        reports_dir = _ensure_reports_dir(repo_root)
        out_path = reports_dir / f"weekly_report_{filename_date}.md"

        if args.dry_run:
            print(f"[weekly_report] dry-run: would write {out_path}")
            print("[weekly_report] dry-run: markdown preview (first 80 lines):")
            print("\n".join(md.splitlines()[:80]))
        else:
            out_path.write_text(md, encoding="utf-8")
            print(f"[weekly_report] wrote: {out_path}")
    except Exception as e:
        print(f"[weekly_report] ERROR: {e}", file=sys.stderr)
        return 2
    finally:
        try:
            conn.close()
        except Exception:
            pass

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

