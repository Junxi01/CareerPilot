from __future__ import annotations

"""
Generate an AI interview prep plan for a job lead, persist ai_interview_plans + prep_tasks (MySQL),
and write a Markdown preview under reports/.

Requires: backend auth (SCRIPTS_API_TOKEN or SCRIPTS_EMAIL/PASSWORD), DB_* in .env for writes.
"""

import argparse
import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from scripts.common.ai_provider import AiProviderError, MissingApiKeyError, get_ai_provider
from scripts.common.api_client import ApiClient, ApiError, get_client
from scripts.common.config import load_env
from scripts.common.db import connect, execute, query

MAX_JOB_DESCRIPTION_CHARS = 12000


_DETERMINISTIC_MOCK_PLAN: Dict[str, Any] = {
    "summary": "Mock mode: deterministic interview prep skeleton (no API tokens used).",
    "match_score_reasoning": "Aligned with configured keywords and role title from the job lead.",
    "required_skills": ["Communication", "Problem solving"],
    "nice_to_have_skills": ["Domain knowledge"],
    "interview_topics": ["Background", "Technical depth", "Motivation"],
    "seven_day_plan": [
        "Day 1: Research company + role",
        "Day 2: Draft STAR stories",
        "Day 3: Technical review",
        "Day 4: Mock answers",
        "Day 5: Weak-area drills",
        "Day 6: Logistics + questions for them",
        "Day 7: Light review + rest",
    ],
    "technical_questions": ["Explain a recent technical tradeoff you made."],
    "behavioral_questions": ["Tell me about a conflict you resolved."],
    "project_talking_points": ["Pick one flagship project with measurable outcome."],
    "prep_tasks": [
        {"label": "Company research notes", "due_day_offset": 0},
        {"label": "STAR story outline", "due_day_offset": 1},
        {"label": "Mock interview (voice)", "due_day_offset": 4},
    ],
}


PLAN_KEYS = (
    "summary",
    "match_score_reasoning",
    "required_skills",
    "nice_to_have_skills",
    "interview_topics",
    "seven_day_plan",
    "technical_questions",
    "behavioral_questions",
    "project_talking_points",
    "prep_tasks",
)

LIST_PLAN_KEYS = {
    "required_skills",
    "nice_to_have_skills",
    "interview_topics",
    "seven_day_plan",
    "technical_questions",
    "behavioral_questions",
    "project_talking_points",
}


class PlanSchemaError(RuntimeError):
    """Raised when AI output does not match the expected interview plan schema."""


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _ensure_reports_dir() -> Path:
    p = _repo_root() / "reports"
    p.mkdir(parents=True, exist_ok=True)
    return p


def _normalize_interview_plan(raw: Dict[str, Any]) -> Dict[str, Any]:
    out: Dict[str, Any] = {}
    for k in PLAN_KEYS:
        v = raw.get(k)
        if k in (
            "required_skills",
            "nice_to_have_skills",
            "interview_topics",
            "seven_day_plan",
            "technical_questions",
            "behavioral_questions",
            "project_talking_points",
        ):
            out[k] = list(v) if isinstance(v, list) else []
        elif k == "prep_tasks":
            out[k] = _normalize_prep_tasks(v)
        else:
            out[k] = str(v).strip() if v is not None else ""
    return out


def _validate_interview_plan_schema(raw: Dict[str, Any]) -> None:
    errors: List[str] = []
    if not isinstance(raw, dict):
        raise PlanSchemaError("AI output root must be a JSON object.")

    for k in PLAN_KEYS:
        if k not in raw:
            errors.append(f"missing key: {k}")
            continue
        v = raw[k]
        if k in ("summary", "match_score_reasoning"):
            if not isinstance(v, str) or not v.strip():
                errors.append(f"{k} must be a non-empty string")
        elif k in LIST_PLAN_KEYS:
            if not isinstance(v, list) or not v or not all(isinstance(x, str) and x.strip() for x in v):
                errors.append(f"{k} must be a non-empty string[]")
        elif k == "prep_tasks":
            if not isinstance(v, list) or not v:
                errors.append("prep_tasks must be a non-empty array")
            else:
                for i, it in enumerate(v):
                    if not isinstance(it, dict):
                        errors.append(f"prep_tasks[{i}] must be an object")
                        continue
                    label = it.get("label") or it.get("task") or it.get("title")
                    if not isinstance(label, str) or not label.strip():
                        errors.append(f"prep_tasks[{i}].label must be a non-empty string")
                    off = it.get("due_day_offset", it.get("day"))
                    if not isinstance(off, int) or off < 0 or off > 6:
                        errors.append(f"prep_tasks[{i}].due_day_offset must be an integer 0..6")

    if errors:
        preview = "; ".join(errors[:8])
        more = "" if len(errors) <= 8 else f"; +{len(errors) - 8} more"
        raise PlanSchemaError(f"AI output failed interview plan schema validation: {preview}{more}")


def _normalize_prep_tasks(raw: Any) -> List[Dict[str, Any]]:
    items = raw if isinstance(raw, list) else []
    out: List[Dict[str, Any]] = []
    for i, it in enumerate(items):
        if isinstance(it, str):
            out.append({"label": it[:255], "due_day_offset": min(i, 6)})
        elif isinstance(it, dict):
            lab = str(it.get("label") or it.get("task") or it.get("title") or "").strip()
            if not lab:
                continue
            try:
                off = int(it.get("due_day_offset", it.get("day", i)))
            except (TypeError, ValueError):
                off = i
            off = max(0, min(6, off))
            out.append({"label": lab[:255], "due_day_offset": off})
    return out


def _build_system_prompt() -> str:
    return (
        "You are an interview preparation assistant for job seekers. "
        "Reply with a single JSON object only (no markdown fences). "
        "Use these exact top-level keys (arrays must be JSON arrays of strings unless noted):\n"
        "- summary: string\n"
        "- match_score_reasoning: string\n"
        "- required_skills: string[]\n"
        "- nice_to_have_skills: string[]\n"
        "- interview_topics: string[]\n"
        "- seven_day_plan: string[] (7 short entries, day 1 … day 7)\n"
        "- technical_questions: string[]\n"
        "- behavioral_questions: string[]\n"
        "- project_talking_points: string[]\n"
        "- prep_tasks: array of objects with keys label (string) and due_day_offset "
        "(integer 0–6, days from today)\n"
        "Be concrete and tailored to the role and description."
    )


def _build_user_payload(
    *,
    lead: Dict[str, Any],
    user: Dict[str, Any],
    job_description: str,
) -> str:
    parts = [
        "=== Job lead ===",
        f"Company: {lead.get('company_name', '')}",
        f"Role title: {lead.get('role_title', '')}",
        f"Job URL: {lead.get('job_url', '')}",
        f"Location: {lead.get('location') or ''}",
        f"Matched keywords (from tracker): {lead.get('matched_keywords') or []}",
        f"Tracker match score: {lead.get('match_score')}",
        "",
        "=== Job description / posting text ===",
        job_description.strip() or "(none)",
        "",
        "=== Candidate profile ===",
        f"Name: {user.get('display_name') or ''}",
        f"Email: {user.get('email', '')}",
    ]
    return "\n".join(parts)


def _fetch_lead(client: ApiClient, job_lead_id: int) -> Dict[str, Any]:
    data = client.get(f"/api/job-leads/{job_lead_id}")
    if not isinstance(data, dict):
        raise RuntimeError("Unexpected job lead response")
    return data


def _fetch_latest_unsaved(client: ApiClient) -> Dict[str, Any]:
    data = client.get("/api/job-leads", {"saved_to_applications": "false"})
    if not isinstance(data, list) or not data:
        raise RuntimeError("No unsaved job leads found for this user.")
    return data[0]


def _fetch_me(client: ApiClient) -> Dict[str, Any]:
    data = client.get("/api/me")
    if not isinstance(data, dict):
        raise RuntimeError("Unexpected /api/me response")
    u = data.get("user")
    if not isinstance(u, dict):
        raise RuntimeError("/api/me missing user")
    return u


def _resolve_job_description(lead: Dict[str, Any], file_path: Optional[Path]) -> Tuple[str, Optional[str]]:
    if file_path is not None:
        text = file_path.read_text(encoding="utf-8", errors="replace")
        source = str(file_path)
    else:
        bits = []
        if lead.get("raw_description"):
            bits.append(str(lead["raw_description"]))
        bits.append(str(lead.get("role_title") or ""))
        text = "\n\n".join(b for b in bits if b.strip())
        source = "job lead raw_description"

    if len(text) <= MAX_JOB_DESCRIPTION_CHARS:
        return text, None

    warning = (
        f"Job description from {source} was {len(text)} characters; "
        f"truncated to {MAX_JOB_DESCRIPTION_CHARS} characters before sending to AI."
    )
    return text[:MAX_JOB_DESCRIPTION_CHARS], warning


def _markdown_report(
    *,
    job_lead_id: int,
    lead: Dict[str, Any],
    plan: Dict[str, Any],
    dry_run: bool,
    input_warning: Optional[str] = None,
) -> str:
    lines = [
        f"# Interview prep plan — {lead.get('company_name', '')} / {lead.get('role_title', '')}",
        "",
        f"- Job lead id: **{job_lead_id}**",
        f"- Generated: {datetime.now().isoformat(timespec='seconds')}",
        f"- Mode: **{'dry-run (no DB writes)' if dry_run else 'saved'}**",
        *([f"- Input note: **{input_warning}**"] if input_warning else []),
        "",
        "## Summary",
        plan.get("summary") or "",
        "",
        "## Match score reasoning",
        plan.get("match_score_reasoning") or "",
        "",
        "## Required skills",
        *[f"- {x}" for x in plan.get("required_skills") or []],
        "",
        "## Nice-to-have skills",
        *[f"- {x}" for x in plan.get("nice_to_have_skills") or []],
        "",
        "## Interview topics",
        *[f"- {x}" for x in plan.get("interview_topics") or []],
        "",
        "## Seven-day plan",
        *[f"{i + 1}. {x}" for i, x in enumerate(plan.get("seven_day_plan") or [])],
        "",
        "## Technical questions",
        *[f"- {x}" for x in plan.get("technical_questions") or []],
        "",
        "## Behavioral questions",
        *[f"- {x}" for x in plan.get("behavioral_questions") or []],
        "",
        "## Project talking points",
        *[f"- {x}" for x in plan.get("project_talking_points") or []],
        "",
        "## Prep tasks",
        *[f"- {t.get('label', '')} (day +{t.get('due_day_offset', 0)})" for t in plan.get("prep_tasks") or []],
        "",
    ]
    return "\n".join(lines)


def _provider_mode_label() -> str:
    load_env()
    import os

    return (os.environ.get("AI_PROVIDER") or os.environ.get("AI_MODE") or "mock").strip() or "mock"


def _ensure_application_id(conn: Any, user_id: int, lead: Dict[str, Any]) -> int:
    jlid = int(lead["id"])
    job_url = str(lead["job_url"]).strip()
    company_id = int(lead["company_id"])

    r = query(
        conn,
        "SELECT id FROM applications WHERE user_id = %s AND job_url = %s LIMIT 1",
        (user_id, job_url),
    )
    if r.rows:
        return int(r.rows[0]["id"])

    r2 = query(
        conn,
        "SELECT id FROM applications WHERE user_id = %s AND job_lead_id = %s LIMIT 1",
        (user_id, jlid),
    )
    if r2.rows:
        return int(r2.rows[0]["id"])

    cur = conn.cursor()
    cur.execute(
        """
        INSERT INTO applications (
          user_id, company_id, job_lead_id, role_title, job_url, status,
          tech_stack_json, salary_range, applied_at, next_follow_up_date, notes
        ) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
        """,
        (
            user_id,
            company_id,
            jlid,
            str(lead.get("role_title") or "")[:255],
            job_url[:2048],
            "SAVED",
            "[]",
            None,
            None,
            None,
            None,
        ),
    )
    new_id = cur.lastrowid
    cur.close()
    if not new_id:
        raise RuntimeError("Insert application failed (no lastrowid)")
    return int(new_id)


def _delete_existing_plans(conn: Any, application_id: int) -> int:
    r = query(conn, "SELECT id FROM ai_interview_plans WHERE application_id = %s", (application_id,))
    for row in r.rows:
        execute(conn, "DELETE FROM prep_tasks WHERE ai_interview_plan_id = %s", (int(row["id"]),))
    execute(conn, "DELETE FROM ai_interview_plans WHERE application_id = %s", (application_id,))
    return len(r.rows)


def _persist_plan(
    conn: Any,
    *,
    application_id: int,
    prompt_blob: Dict[str, Any],
    plan: Dict[str, Any],
) -> Tuple[int, int]:
    mode = _provider_mode_label()[:16]
    cur = conn.cursor()
    cur.execute(
        """
        INSERT INTO ai_interview_plans (application_id, provider_mode, prompt_json, plan_json)
        VALUES (%s, %s, %s, %s)
        """,
        (
            application_id,
            mode,
            json.dumps(prompt_blob, ensure_ascii=False),
            json.dumps(plan, ensure_ascii=False),
        ),
    )
    plan_id = int(cur.lastrowid)
    cur.close()

    today = date.today()
    n_tasks = 0
    for t in plan.get("prep_tasks") or []:
        lab = str(t.get("label") or "")[:255]
        if not lab:
            continue
        off = int(t.get("due_day_offset", 0))
        due = today + timedelta(days=off)
        execute(
            conn,
            """
            INSERT INTO prep_tasks (ai_interview_plan_id, label, description, due_date, status)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (plan_id, lab, None, due, "todo"),
        )
        n_tasks += 1
    return plan_id, n_tasks


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Generate AI interview prep from a job lead; save plan + prep tasks to MySQL.",
    )
    g = p.add_mutually_exclusive_group(required=True)
    g.add_argument("job_lead_id", nargs="?", type=int, help="Job lead id from /api/job-leads")
    g.add_argument("--latest-unsaved", action="store_true", help="Use most recent unsaved job lead")
    p.add_argument(
        "--from-file",
        type=str,
        default="",
        help="Use this file as job description text instead of lead raw_description (still needs lead id).",
    )
    p.add_argument("--dry-run", action="store_true", help="Do not write MySQL; write *_dry_run.md report")
    return p.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    load_env()
    args = parse_args(argv)

    file_path: Optional[Path] = None
    if str(args.from_file).strip():
        file_path = Path(args.from_file).expanduser().resolve()
        if not file_path.is_file():
            print(f"[ai_interview_planner] File not found: {file_path}", file=sys.stderr)
            return 2

    client = get_client()
    user = _fetch_me(client)
    user_id = int(user["id"])

    try:
        if args.latest_unsaved:
            lead = _fetch_latest_unsaved(client)
        else:
            assert args.job_lead_id is not None
            lead = _fetch_lead(client, int(args.job_lead_id))
    except ApiError as e:
        print(f"[ai_interview_planner] API error: {e}", file=sys.stderr)
        return 1
    except RuntimeError as e:
        print(f"[ai_interview_planner] {e}", file=sys.stderr)
        return 1

    job_lead_id = int(lead["id"])
    job_description, input_warning = _resolve_job_description(lead, file_path)
    if input_warning:
        print(f"[ai_interview_planner] WARNING: {input_warning}")

    provider: Any
    try:
        provider = get_ai_provider()
    except MissingApiKeyError as e:
        print(f"[ai_interview_planner] {e}", file=sys.stderr)
        return 2

    system = _build_system_prompt()
    user_prompt = _build_user_payload(lead=lead, user=user, job_description=job_description)

    prompt_blob = {
        "job_lead_id": job_lead_id,
        "role_title": lead.get("role_title"),
        "company_name": lead.get("company_name"),
        "used_from_file": str(file_path) if file_path else None,
        "job_description_chars_sent": len(job_description),
        "input_warning": input_warning,
    }

    print(f"[ai_interview_planner] Generating plan for job_lead_id={job_lead_id} …")
    try:
        raw = provider.complete_json(system=system, user=user_prompt)
    except AiProviderError as e:
        print(f"[ai_interview_planner] AI error: {e}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"[ai_interview_planner] {type(e).__name__}: {e}", file=sys.stderr)
        return 1

    merged = dict(raw) if isinstance(raw, dict) else {}
    if _provider_mode_label().lower() in ("mock", "none", "off", ""):
        merged = {**_DETERMINISTIC_MOCK_PLAN, **merged}
    try:
        _validate_interview_plan_schema(merged)
    except PlanSchemaError as e:
        print(f"[ai_interview_planner] Schema error: {e}", file=sys.stderr)
        return 1
    plan = _normalize_interview_plan(merged)

    reports = _ensure_reports_dir()
    suffix = "_dry_run" if args.dry_run else ""
    md_path = reports / f"interview_plan_{job_lead_id}{suffix}.md"
    md_body = _markdown_report(
        job_lead_id=job_lead_id,
        lead=lead,
        plan=plan,
        dry_run=args.dry_run,
        input_warning=input_warning,
    )
    md_path.write_text(md_body, encoding="utf-8")
    print(f"[ai_interview_planner] Wrote {md_path}")

    if args.dry_run:
        print("[ai_interview_planner] dry-run: skipped MySQL persist.")
        return 0

    conn = connect()
    try:
        conn.autocommit = False
        app_id = _ensure_application_id(conn, user_id, lead)
        deleted_plans = _delete_existing_plans(conn, app_id)
        if deleted_plans:
            print(
                f"[ai_interview_planner] Replacing {deleted_plans} existing interview plan(s) "
                f"for application_id={app_id}.",
            )
        plan_id, n_tasks = _persist_plan(conn, application_id=app_id, prompt_blob=prompt_blob, plan=plan)
        conn.commit()
        print(
            f"[ai_interview_planner] Saved ai_interview_plans id={plan_id} "
            f"application_id={app_id} prep_tasks={n_tasks}",
        )
    except Exception as e:
        conn.rollback()
        print(
            f"[ai_interview_planner] DB error: rolled back without saving partial plan. "
            f"{type(e).__name__}: {e}",
            file=sys.stderr,
        )
        return 1
    finally:
        conn.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
