from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from scripts.common.api_client import ApiError, get_client
from scripts.common.config import load_env


@dataclass(frozen=True)
class FollowUpRow:
    kind: str
    due_sort: datetime
    due_display: str
    title: str
    application_id: Optional[int]
    company_name: Optional[str]
    role_title: Optional[str]
    status: Optional[str]
    applied_date: Optional[str]
    days_since_applied: Optional[int]
    job_url: Optional[str]
    suggested_action: str


def _parse_iso_date(s: str) -> Optional[date]:
    s = (s or "").strip()
    if not s:
        return None
    try:
        return date.fromisoformat(s)
    except Exception:
        return None


def _parse_iso_instant(s: str) -> Optional[datetime]:
    s = (s or "").strip()
    if not s:
        return None
    # Accept "Z" suffix as UTC.
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    try:
        dt = datetime.fromisoformat(s)
    except Exception:
        return None
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def _compute_suggested_action(*, kind: str, status: Optional[str], days_since_applied: Optional[int]) -> str:
    st = (status or "").upper()
    d = days_since_applied
    if kind == "reminder":
        return "Do the reminder action (or reschedule if not relevant)."
    if st in {"REJECTED", "ARCHIVED"}:
        return "No follow-up needed (closed)."
    if st == "OFFER":
        return "Follow up on offer decision timeline / next steps."
    if st == "GHOSTED":
        return "Send a final follow-up, then consider archiving."
    if st == "SAVED":
        return "Apply if still interested, or archive if not."
    if st in {"INTERVIEW", "ONLINE_ASSESSMENT"}:
        return "Prep / confirm interview steps; send a brief update if needed."
    # APPLIED or unknown
    if d is not None and d >= 10:
        return "Send a follow-up email/message (10+ days since applied)."
    if d is not None and d >= 5:
        return "Consider a light follow-up (5+ days since applied)."
    return "Monitor; follow up when appropriate."


def _ensure_reports_dir(repo_root: Path) -> Path:
    p = repo_root / "reports"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _repo_root() -> Path:
    # scripts/check_followups.py -> scripts -> repo root
    return Path(__file__).resolve().parents[1]


def _coerce_int(v: Any) -> Optional[int]:
    if v is None:
        return None
    try:
        return int(v)
    except Exception:
        return None


def _is_due_today_or_overdue(item: Dict[str, Any], today: date) -> bool:
    kind = str(item.get("kind") or "")
    due = str(item.get("due") or "")
    if kind == "application_follow_up":
        due_d = _parse_iso_date(due)
        return bool(due_d and due_d <= today)
    # reminder due is an instant string
    due_dt = _parse_iso_instant(due)
    if not due_dt:
        return False
    return due_dt.date() <= today


def _fetch_application(api, app_id: int) -> Optional[Dict[str, Any]]:
    try:
        data = api.get(f"/api/applications/{app_id}")
        return data if isinstance(data, dict) else None
    except ApiError:
        return None


def _build_rows(
    followups: Iterable[Dict[str, Any]],
    *,
    api,
    today: date,
) -> List[FollowUpRow]:
    # Fetch applications once per id.
    app_ids: List[int] = []
    for it in followups:
        aid = _coerce_int(it.get("application_id"))
        if aid is not None:
            app_ids.append(aid)
    unique_ids = sorted(set(app_ids))
    apps: Dict[int, Dict[str, Any]] = {}
    for aid in unique_ids:
        app = _fetch_application(api, aid)
        if app:
            apps[aid] = app

    rows: List[FollowUpRow] = []
    for it in followups:
        kind = str(it.get("kind") or "")
        title = str(it.get("title") or "")
        due_raw = str(it.get("due") or "")
        company_name = it.get("company_name")
        application_id = _coerce_int(it.get("application_id"))

        app = apps.get(application_id) if application_id is not None else None
        role_title = (app or {}).get("role_title") if isinstance(app, dict) else None
        status = (app or {}).get("status") if isinstance(app, dict) else None
        applied_date = (app or {}).get("applied_date") if isinstance(app, dict) else None
        job_url = (app or {}).get("job_url") if isinstance(app, dict) else None

        days_since_applied: Optional[int] = None
        ad = _parse_iso_date(str(applied_date or ""))
        if ad:
            days_since_applied = (today - ad).days

        # Sort key + display formatting
        due_sort: datetime
        due_display: str
        if kind == "application_follow_up":
            d = _parse_iso_date(due_raw)
            # treat as local date; use midnight UTC for stable sort
            due_sort = datetime(d.year, d.month, d.day, tzinfo=timezone.utc) if d else datetime.now(timezone.utc)
            due_display = due_raw
        else:
            dt = _parse_iso_instant(due_raw)
            due_sort = dt if dt else datetime.now(timezone.utc)
            due_display = dt.astimezone().isoformat(timespec="minutes") if dt else due_raw

        suggested = _compute_suggested_action(kind=kind, status=str(status) if status is not None else None, days_since_applied=days_since_applied)

        rows.append(
            FollowUpRow(
                kind=kind,
                due_sort=due_sort,
                due_display=due_display,
                title=title,
                application_id=application_id,
                company_name=str(company_name) if company_name is not None else None,
                role_title=str(role_title) if role_title is not None else None,
                status=str(status) if status is not None else None,
                applied_date=str(applied_date) if applied_date is not None else None,
                days_since_applied=days_since_applied,
                job_url=str(job_url) if job_url is not None else None,
                suggested_action=suggested,
            )
        )

    rows.sort(key=lambda r: r.due_sort)
    return rows


def _render_markdown(rows: List[FollowUpRow], *, today: date) -> str:
    lines: List[str] = []
    lines.append(f"# Follow-ups due — {today.isoformat()}")
    lines.append("")
    lines.append(f"Generated at: `{datetime.now().isoformat(timespec='seconds')}`")
    lines.append("")
    if not rows:
        lines.append("No follow-ups due today or overdue.")
        lines.append("")
        return "\n".join(lines)

    lines.append(f"Total: **{len(rows)}**")
    lines.append("")
    lines.append("## Items")
    lines.append("")
    for idx, r in enumerate(rows, start=1):
        header = f"{idx}. **{(r.company_name or 'Unknown company')}** — {(r.role_title or r.title)}"
        lines.append(header)
        lines.append("")
        lines.append(f"   - **Due**: `{r.due_display}` ({r.kind})")
        if r.status:
            lines.append(f"   - **Status**: `{r.status}`")
        if r.applied_date:
            dsa = f"{r.days_since_applied} days" if r.days_since_applied is not None else "n/a"
            lines.append(f"   - **Applied**: `{r.applied_date}` (since: {dsa})")
        if r.job_url:
            lines.append(f"   - **Job URL**: {r.job_url}")
        if r.application_id is not None:
            lines.append(f"   - **Application ID**: `{r.application_id}`")
        lines.append(f"   - **Suggested action**: {r.suggested_action}")
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(
        description=(
            "Check follow-ups due today or overdue.\n"
            "Prefers backend API (/api/dashboard/follow-ups + per-application details)."
        )
    )
    p.add_argument("--dry-run", action="store_true", help="Print output but do not write reports/followups_today.md")
    args = p.parse_args()

    print(f"[check_followups] started at {datetime.now().isoformat(timespec='seconds')}")
    if args.dry_run:
        print("[check_followups] dry-run: ON")

    try:
        api = get_client()
        data = api.get("/api/dashboard/follow-ups")
    except ApiError as e:
        print(f"[check_followups] ERROR (API): {e}", file=sys.stderr)
        return 2
    except Exception as e:
        print("[check_followups] ERROR: failed to connect to backend API.", file=sys.stderr)
        print(f"[check_followups] Details: {e}", file=sys.stderr)
        print(
            "[check_followups] Hint: start the backend (e.g. ./scripts/local-up.sh) and ensure VITE_API_BASE_URL points to it (default http://localhost:8080).",
            file=sys.stderr,
        )
        return 2

    items_raw = (data or {}).get("items") if isinstance(data, dict) else data
    items_all = items_raw if isinstance(items_raw, list) else []

    today = date.today()
    due_items: List[Dict[str, Any]] = [
        it for it in items_all if isinstance(it, dict) and _is_due_today_or_overdue(it, today)
    ]

    rows = _build_rows(due_items, api=api, today=today)
    print(f"[check_followups] follow-ups due today/overdue: {len(rows)} (from {len(items_all)} in 14-day window)")
    for i, r in enumerate(rows[:50], start=1):
        url = f" url={r.job_url}" if r.job_url else ""
        dsa = f" dsa={r.days_since_applied}" if r.days_since_applied is not None else ""
        st = f" status={r.status}" if r.status else ""
        print(f"  {i:02d}. due={r.due_display}{st}{dsa}{url}  {r.suggested_action}")
    if len(rows) > 50:
        print(f"  ... ({len(rows) - 50} more)")

    md = _render_markdown(rows, today=today)
    repo_root = _repo_root()
    reports_dir = _ensure_reports_dir(repo_root)
    out_path = reports_dir / "followups_today.md"
    if args.dry_run:
        print(f"[check_followups] dry-run: would write {out_path}")
        print("[check_followups] dry-run: markdown preview (first 40 lines):")
        preview = "\n".join(md.splitlines()[:40])
        print(preview)
    else:
        out_path.write_text(md + "\n", encoding="utf-8")
        print(f"[check_followups] wrote: {out_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

