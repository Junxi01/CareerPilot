from __future__ import annotations

import argparse
import csv
import sys
import re
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from scripts.common.api_client import ApiError, get_client
from scripts.common.config import load_env


def norm(s: Optional[str]) -> Optional[str]:
    if s is None:
        return None
    v = s.strip()
    return v if v else None


REQUIRED_COLS = {"company", "role_title", "status", "applied_date", "job_url", "notes"}
OPTIONAL_COLS = {"location", "tech_stack", "salary_range", "follow_up_date"}
ALL_COLS = REQUIRED_COLS | OPTIONAL_COLS

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _normalize_status(raw: str) -> str:
    s = raw.strip()
    if not s:
        return "SAVED"
    s = s.upper().replace("-", "_").replace(" ", "_")
    return s


def _parse_tech_stack(raw: str) -> List[str]:
    return [x.strip() for x in (raw or "").split(",") if x.strip()]


def _load_existing_job_urls(api) -> Set[str]:
    existing = set()
    try:
        apps = api.get("/api/applications") or []
    except ApiError:
        return existing
    if isinstance(apps, list):
        for a in apps:
            if isinstance(a, dict):
                u = (a.get("job_url") or "").strip()
                if u:
                    existing.add(u)
    return existing


def _validate_headers(fieldnames: Optional[List[str]]) -> Tuple[bool, str]:
    if not fieldnames:
        return False, "CSV has no header row."
    got = {h.strip() for h in fieldnames if h}
    missing = sorted(REQUIRED_COLS - got)
    if missing:
        return False, f"Missing required columns: {', '.join(missing)}"
    extra = sorted(got - ALL_COLS)
    if extra:
        return True, f"Warning: unknown columns will be ignored: {', '.join(extra)}"
    return True, ""


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(description="Import applications from CSV via POST /api/applications.")
    p.add_argument("csv_path", help="Path to CSV")
    p.add_argument("--dry-run", action="store_true", help="Parse and print what would be created.")
    args = p.parse_args()

    path = Path(args.csv_path).expanduser()
    if not path.exists():
        print(f"[import_csv] ERROR: missing file: {path}", file=sys.stderr)
        return 2

    rows: List[Dict[str, str]] = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        ok, msg = _validate_headers(reader.fieldnames)
        if not ok:
            print(f"[import_csv] ERROR: {msg}", file=sys.stderr)
            return 2
        if msg:
            print(f"[import_csv] {msg}", file=sys.stderr)
        for r in reader:
            rows.append({k: (v or "") for k, v in r.items()})

    print(f"[import_csv] rows: {len(rows)}")
    if len(rows) == 0:
        return 0

    api = None
    existing_urls: Set[str] = set()
    if args.dry_run:
        # For dry-run, we can proceed without API credentials (we'll only de-dupe within the CSV).
        try:
            api = get_client()
            existing_urls = _load_existing_job_urls(api)
        except Exception as e:
            print(f"[import_csv] warning: cannot reach backend for de-dupe (dry-run still works): {e}", file=sys.stderr)
            api = None
            existing_urls = set()
    else:
        try:
            api = get_client()
        except Exception as e:
            print(f"[import_csv] ERROR: {e}", file=sys.stderr)
            return 2
        existing_urls = _load_existing_job_urls(api)
    seen_urls: Set[str] = set()

    created = 0
    skipped = 0
    failed = 0
    for idx, r in enumerate(rows, start=1):
        company = norm(r.get("company")) or ""
        role_title = norm(r.get("role_title")) or ""
        job_url = norm(r.get("job_url")) or ""
        status = _normalize_status(norm(r.get("status")) or "SAVED")
        applied_date = norm(r.get("applied_date"))
        notes = norm(r.get("notes"))

        if not company or not role_title or not job_url or not applied_date:
            skipped += 1
            print(f"[import_csv] skip row {idx}: missing required value (company, role_title, job_url, applied_date)", file=sys.stderr)
            continue

        if not DATE_RE.match(applied_date):
            failed += 1
            print(
                f"[import_csv] ERROR row {idx}: applied_date must be YYYY-MM-DD (got {applied_date!r})",
                file=sys.stderr,
            )
            continue

        follow = norm(r.get("follow_up_date"))
        if follow and not DATE_RE.match(follow):
            failed += 1
            print(
                f"[import_csv] ERROR row {idx}: follow_up_date must be YYYY-MM-DD (got {follow!r})",
                file=sys.stderr,
            )
            continue

        if job_url in existing_urls:
            skipped += 1
            print(f"[import_csv] skip row {idx}: duplicate job_url already exists in backend", file=sys.stderr)
            continue
        if job_url in seen_urls:
            skipped += 1
            print(f"[import_csv] skip row {idx}: duplicate job_url within CSV", file=sys.stderr)
            continue
        seen_urls.add(job_url)

        body = {
            "company_name": company,
            "role_title": role_title,
            "job_url": job_url,
            "status": status,
            "tech_stack": _parse_tech_stack(r.get("tech_stack") or ""),
            "salary_range": norm(r.get("salary_range")),
            "applied_date": applied_date,
            "follow_up_date": follow,
            "notes": notes,
        }

        # Optional CSV fields not supported by API yet; keep them by appending into notes if present.
        loc = norm(r.get("location"))
        if loc:
            body["notes"] = (body.get("notes") or "") + (f"\n\n[import] location: {loc}" if body.get("notes") else f"[import] location: {loc}")

        if args.dry_run:
            print(f"[import_csv] dry-run row {idx}: {body}")
            created += 1
            continue

        try:
            assert api is not None
            api.post("/api/applications", body)
            created += 1
            print(f"[import_csv] created row {idx}: {body.get('company_name') or '?'} — {body['role_title']}")
        except ApiError as e:
            failed += 1
            print(f"[import_csv] ERROR row {idx}: {e}", file=sys.stderr)
            # keep going

    print(f"[import_csv] done. created={created} skipped={skipped} failed={failed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

