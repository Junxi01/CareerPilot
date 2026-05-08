from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import mysql.connector
from mysql.connector import MySQLConnection

from .config import DbConfig, get_db_config, load_env


@dataclass(frozen=True)
class QueryResult:
    rows: List[Dict[str, Any]]


def connect(cfg: Optional[DbConfig] = None) -> MySQLConnection:
    load_env()
    cfg = cfg or get_db_config()
    return mysql.connector.connect(
        host=cfg.host,
        port=cfg.port,
        database=cfg.name,
        user=cfg.user,
        password=cfg.password,
        autocommit=True,
    )


def query(conn: MySQLConnection, sql: str, params: Optional[Sequence[Any]] = None) -> QueryResult:
    cur = conn.cursor(dictionary=True)
    cur.execute(sql, params or ())
    rows = list(cur.fetchall())
    cur.close()
    return QueryResult(rows=rows)


def execute(conn: MySQLConnection, sql: str, params: Optional[Sequence[Any]] = None) -> int:
    cur = conn.cursor()
    cur.execute(sql, params or ())
    n = cur.rowcount
    cur.close()
    return int(n)


def ensure_connection() -> MySQLConnection:
    """
    Helper for simple scripts: open connection and let caller close it.
    """
    return connect()

