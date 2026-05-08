from __future__ import annotations

import argparse
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from scripts.common.config import load_env


def main() -> int:
    load_env()
    p = argparse.ArgumentParser(description="Backup MySQL database using docker compose exec + mysqldump.")
    p.add_argument("--dry-run", action="store_true", help="Print commands only; do not run.")
    p.add_argument("--output", default="", help="Output .sql path (default: backups/<db>-<timestamp>.sql)")
    p.add_argument("--compose-dir", default="", help="Directory containing docker-compose.yml (default: repo root)")
    args = p.parse_args()

    repo_root = Path(__file__).resolve().parents[1]
    compose_dir = Path(args.compose_dir).expanduser() if args.compose_dir else repo_root
    db = os.environ.get("MYSQL_DATABASE") or os.environ.get("DB_NAME") or "careerpilot"
    user = os.environ.get("MYSQL_USER") or os.environ.get("DB_USER") or "careerpilot"
    pw = os.environ.get("MYSQL_PASSWORD") or os.environ.get("DB_PASSWORD") or ""

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    out = Path(args.output).expanduser() if args.output else (repo_root / "backups" / f"{db}-{ts}.sql")
    out.parent.mkdir(parents=True, exist_ok=True)

    cmd = [
        "docker",
        "compose",
        "exec",
        "-T",
        "mysql",
        "sh",
        "-c",
        f"mysqldump -u {user} -p\"{pw}\" {db}",
    ]

    print(f"[backup_database] compose dir: {compose_dir}")
    print(f"[backup_database] output: {out}")
    if args.dry_run:
        print("[backup_database] dry-run: ON")
        print("[backup_database] command:", " ".join(cmd))
        return 0

    try:
        with out.open("wb") as f:
            subprocess.run(cmd, cwd=str(compose_dir), check=True, stdout=f)
    except subprocess.CalledProcessError as e:
        print(f"[backup_database] ERROR: command failed ({e.returncode})", file=sys.stderr)
        return 2

    print("[backup_database] done")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

