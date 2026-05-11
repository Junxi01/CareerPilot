from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

if __package__ is None or __package__ == "":
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from scripts.common.config import get_db_config, load_env

# With --compress: mysqldump stdout is piped into gzip; only .sql.gz is written (no temporary .sql).
MIN_PLAUSIBLE_PLAIN_SQL_BYTES = (
    200  # rejects near-empty or error-only output that still exited 0 unexpectedly
)


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def _ensure_backups_dir(backups_parent: Path) -> None:
    backups_parent.mkdir(parents=True, exist_ok=True)


def _safe_unlink(path: Path) -> None:
    try:
        path.unlink()
    except FileNotFoundError:
        pass


def _docker_mysqldump_inner_command() -> str:
    """Credentials expand only inside container (Compose env)."""
    return (
        "mysqldump --single-transaction --quick --skip-lock-tables "
        '-u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
    )


def _docker_restore_inner_sql_client() -> str:
    return 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'


def build_docker_dump_cmd(*, service: str) -> list[str]:
    return [
        "docker",
        "compose",
        "exec",
        "-T",
        service,
        "sh",
        "-lc",
        _docker_mysqldump_inner_command(),
    ]


def restore_command_lines(*, out_rel_to_repo: str, compressed: bool, service: str) -> tuple[str, str]:
    compose = "docker compose -f docker-compose.yml"
    inner = _docker_restore_inner_sql_client()
    if compressed:
        one = (
            f'gzip -dc "{out_rel_to_repo}" | {compose} exec -T {service} '
            f"sh -lc '{inner}'"
        )
    else:
        one = (
            f'cat "{out_rel_to_repo}" | {compose} exec -T {service} '
            f"sh -lc '{inner}'"
        )
    two = (
        "(Credentials come from MYSQL_USER / MYSQL_PASSWORD / MYSQL_DATABASE in the "
        "Compose service environment — from `.env`; do not print secrets.)"
    )
    return one, two


def print_restore_hints(*, out_rel_to_repo: str, compressed: bool, service: str, compose_dir: Path) -> None:
    cmd, hint = restore_command_lines(
        out_rel_to_repo=out_rel_to_repo,
        compressed=compressed,
        service=service,
    )
    print("\nRestore (from directory that contains docker-compose.yml):")
    print(f"  cd {compose_dir}")
    print(f"  {cmd}")
    print(hint)


def print_dry_run_summary(*, service: str, compose_dir: Path, out_file: Path, compressed: bool) -> None:
    print(f"[backup_database] (dry-run) compose dir: {compose_dir}")
    print(f"[backup_database] (dry-run) service: {service}")
    print(
        "[backup_database] (dry-run) docker compose exec … mysqldump "
        "(uses $MYSQL_USER / $MYSQL_PASSWORD / $MYSQL_DATABASE inside container only)"
    )
    print(f"[backup_database] (dry-run) output file: {out_file}")
    if compressed:
        print("[backup_database] (dry-run) gzip: single .sql.gz file (streamed pipeline).")


def print_local_restore_hint(*, compressed: bool, out_rel: str) -> None:
    print("\nRestore (host mysql; set MYSQL_PWD yourself — never log it):")
    if compressed:
        print(f'  gzip -dc "{out_rel}" | mysql -h <DB_HOST> -P <DB_PORT> -u <DB_USER> <DB_NAME>')
    else:
        print(f'  mysql -h <DB_HOST> -P <DB_PORT> -u <DB_USER> <DB_NAME> < "{out_rel}"')
    print("(Use DB_HOST / DB_PORT / DB_USER / DB_NAME from `.env`.)")


def _hint_from_stderr(raw: bytes) -> str:
    if not raw.strip():
        return ""
    low = raw.decode("utf-8", errors="replace").lower()
    if "access denied" in low:
        return (
            "Likely wrong password or insufficient privileges for this MySQL user.\n"
            "- Confirm MYSQL_USER/MYSQL_PASSWORD in `.env` match the running container.\n"
            "- mysqldump requires SELECT (and often SHOW VIEW, TRIGGER, etc.) on the database; "
            "use a user with those grants or use root for dumps (dev only)."
        )
    if "can't connect" in low or "connection refused" in low:
        return (
            "Cannot reach MySQL.\n"
            "- Docker: run `docker compose ps` and ensure mysql is healthy.\n"
            "- `--no-docker`: verify DB_HOST is `localhost` when backend runs on host.\n"
        )
    return ""


def _stderr_first_line(raw: bytes, limit: int = 200) -> str:
    line = raw.decode("utf-8", errors="replace").strip().split("\n", 1)[0].strip()
    return line[:limit] + ("…" if len(line) > limit else "")


def assert_backup_nonempty(path: Path, *, compressed: bool) -> None:
    sz = path.stat().st_size
    if sz == 0:
        raise RuntimeError("Backup file is empty (0 bytes). Mysqldump produced no data.")
    if compressed:
        data = path.read_bytes()[:3]
        if data[:2] != b"\x1f\x8b":
            raise RuntimeError("Backup does not look like gzip (missing gzip magic header).")
        return
    if sz < MIN_PLAUSIBLE_PLAIN_SQL_BYTES:
        raise RuntimeError(f"Plain .sql backup is unusually small ({sz} bytes); refusing as likely failure.")


def run_docker_mysqldump(cmd: list[str], *, cwd: str, outfile: Path, gzip_output: bool) -> bytes:
    """Returns stderr bytes from mysqldump side (for hints). File is outfile only."""
    if not gzip_output:
        with outfile.open("wb") as fout:
            proc = subprocess.run(cmd, cwd=cwd, stdout=fout, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            raise subprocess.CalledProcessError(proc.returncode or 1, cmd, stderr=proc.stderr)
        return proc.stderr or b""

    dump_p = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert dump_p.stdout is not None
    with outfile.open("wb") as gz_target:
        gz_p = subprocess.Popen(
            ["gzip", "-c"],
            stdin=dump_p.stdout,
            stdout=gz_target,
            stderr=subprocess.PIPE,
        )
        dump_p.stdout.close()
        gz_err = b""
        if gz_p.stderr:
            gz_err = gz_p.stderr.read()
        gz_rc = gz_p.wait()
    stderr_dump = dump_p.stderr.read() if dump_p.stderr else b""
    dump_rc = dump_p.wait()
    if gz_rc != 0:
        raise subprocess.CalledProcessError(gz_rc, ["gzip", "-c"], stderr=gz_err)
    if dump_rc != 0:
        raise subprocess.CalledProcessError(dump_rc, cmd, stderr=stderr_dump)
    return stderr_dump


def run_local_mysqldump(cfg, *, outfile: Path, gzip_output: bool) -> bytes:
    args = [
        "mysqldump",
        f"--host={cfg.host}",
        f"--port={cfg.port}",
        f"--user={cfg.user}",
        "--single-transaction",
        "--quick",
        "--skip-lock-tables",
        cfg.name,
    ]
    env = os.environ.copy()
    if cfg.password:
        env["MYSQL_PWD"] = cfg.password

    if not gzip_output:
        with outfile.open("wb") as fout:
            proc = subprocess.run(args, env=env, stdout=fout, stderr=subprocess.PIPE)
        if proc.returncode != 0:
            raise subprocess.CalledProcessError(proc.returncode or 1, args, stderr=proc.stderr)
        return proc.stderr or b""

    dump_p = subprocess.Popen(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert dump_p.stdout is not None
    with outfile.open("wb") as gz_target:
        gz_p = subprocess.Popen(
            ["gzip", "-c"],
            stdin=dump_p.stdout,
            stdout=gz_target,
            stderr=subprocess.PIPE,
        )
        dump_p.stdout.close()
        gz_err = gz_p.stderr.read() if gz_p.stderr else b""
        gz_rc = gz_p.wait()
    err_dump = dump_p.stderr.read() if dump_p.stderr else b""
    dump_rc = dump_p.wait()
    if gz_rc != 0:
        raise subprocess.CalledProcessError(gz_rc, ["gzip", "-c"], stderr=gz_err)
    if dump_rc != 0:
        raise subprocess.CalledProcessError(dump_rc, args, stderr=err_dump)
    return err_dump


def _handle_dump_failure(exc: subprocess.CalledProcessError, *, label: str) -> None:
    raw = b""
    if exc.stderr:
        raw = exc.stderr if isinstance(exc.stderr, bytes) else str(exc.stderr).encode()
    elif hasattr(exc, "stderr") and getattr(exc, "stderr", None) is not None:
        raw = getattr(exc, "stderr", b"") or b""  # type: ignore[arg-type]
    snip = _stderr_first_line(raw) if raw else ""
    if snip:
        print(f"[backup_database] {label} stderr (first line): {snip}", file=sys.stderr)
    hi = _hint_from_stderr(raw)
    if hi:
        print(f"[backup_database]\n{hi}", file=sys.stderr)


def compute_out_path(
    *,
    args_output: str,
    compressed: bool,
    backups_root: Path,
    db_name: str,
    ts: str,
) -> Path:
    if not args_output.strip():
        _ensure_backups_dir(backups_root)
        suf = ".sql.gz" if compressed else ".sql"
        return (backups_root / f"{db_name}_{ts}{suf}").resolve()

    out = Path(args_output).expanduser()
    out = out.resolve() if out.is_absolute() else (Path.cwd() / out).resolve()

    if compressed:
        if str(out).endswith(".sql.gz"):
            return out
        return out.with_suffix(".sql.gz").resolve()

    return out


def _rel_to_repo(path: Path, repo_root: Path) -> str:
    try:
        return str(path.resolve().relative_to(repo_root.resolve()))
    except ValueError:
        return str(path)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Logical MySQL backup via mysqldump (Docker Compose or host).")
    p.add_argument("--dry-run", action="store_true", help="Show plan and restore templates; do not write a file.")
    p.add_argument(
        "--compress",
        nargs="?",
        const="gzip",
        default=None,
        choices=["gzip"],
        help="Stream output through gzip; write only .sql.gz (optional value: gzip).",
    )
    p.add_argument("--output", default="", help="Output path (default: backups/<db>_<timestamp>.sql[.gz]).")
    p.add_argument(
        "--no-docker",
        action="store_true",
        help="Use host mysqldump (DB_* from .env). If mysqldump is missing, falls back to Docker.",
    )
    p.add_argument(
        "--compose-service",
        default=os.environ.get("BACKUP_COMPOSE_SERVICE", "mysql"),
        help="Compose service name for mysqldump (default: env BACKUP_COMPOSE_SERVICE or mysql).",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    load_env()
    args = parse_args(argv)
    repo_root = _repo_root()
    compose_dir = repo_root
    backups_root = repo_root / "backups"
    cfg = get_db_config()
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    compressed = args.compress is not None
    out_path = compute_out_path(
        args_output=args.output,
        compressed=compressed,
        backups_root=backups_root,
        db_name=cfg.name,
        ts=ts,
    )

    use_docker = not args.no_docker
    if args.no_docker and shutil.which("mysqldump") is None:
        print(
            "[backup_database] host mysqldump not found on PATH; falling back to Docker Compose.",
            file=sys.stderr,
        )
        use_docker = True

    service = args.compose_service
    cmd = build_docker_dump_cmd(service=service)

    if args.dry_run:
        if use_docker:
            print_dry_run_summary(service=service, compose_dir=compose_dir, out_file=out_path, compressed=compressed)
        else:
            print(f"[backup_database] (dry-run) host mysqldump → {out_path}")
            if compressed:
                print("[backup_database] (dry-run) gzip: single .sql.gz file (streamed pipeline).")
        rel = _rel_to_repo(out_path, repo_root)
        if use_docker:
            print_restore_hints(out_rel_to_repo=rel, compressed=compressed, service=service, compose_dir=compose_dir)
        else:
            print_local_restore_hint(compressed=compressed, out_rel=rel)
        return 0

    stderr_note = b""
    try:
        if use_docker:
            stderr_note = run_docker_mysqldump(cmd, cwd=str(compose_dir), outfile=out_path, gzip_output=compressed)
        else:
            stderr_note = run_local_mysqldump(cfg, outfile=out_path, gzip_output=compressed)
    except subprocess.CalledProcessError as e:
        _safe_unlink(out_path)
        _handle_dump_failure(e, label="mysqldump")
        return 1
    except FileNotFoundError as e:
        _safe_unlink(out_path)
        print(f"[backup_database] Failed to run subprocess: {e}", file=sys.stderr)
        exe = str(getattr(e, "filename", "") or "")
        if "gzip" in exe.lower() or (e.args and "gzip" in str(e.args[0]).lower()):
            print(
                "[backup_database] Hint: compression needs the `gzip` binary on PATH.",
                file=sys.stderr,
            )
        return 1

    try:
        assert_backup_nonempty(out_path, compressed=compressed)
    except RuntimeError as e:
        _safe_unlink(out_path)
        print(f"[backup_database] {e}", file=sys.stderr)
        return 1

    if compressed:
        print(f"[backup_database] OK: wrote compressed backup only ({out_path.name}); no separate .sql file.")
    print(f"[backup_database] OK: {out_path} ({out_path.stat().st_size} bytes)")
    snip = _stderr_first_line(stderr_note)
    if snip and "warning" in snip.lower():
        print(f"[backup_database] mysqldump notice: {snip}")

    rel = _rel_to_repo(out_path, repo_root)
    if use_docker:
        print_restore_hints(out_rel_to_repo=rel, compressed=compressed, service=service, compose_dir=compose_dir)
    else:
        print_local_restore_hint(compressed=compressed, out_rel=rel)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
