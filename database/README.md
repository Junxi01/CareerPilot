# Database

- **`schema.sql`** — canonical MySQL 8 schema (applied on **first** Docker Compose MySQL init via `docker-compose.yml`).
- **`seed.sql`** — demo user + companies + job leads (runs after schema on same first init).

## Demo account (after fresh volume)

| | |
|--|--|
| Email | `demo@careerpilot.local` |
| Password | `demo12345` |

**Security:** for **local demo only**. Change or delete this user in real deployments.

## Reset everything (Compose)

```bash
docker compose down -v   # removes mysql_data volume — all DB data lost
docker compose up --build -d
```

Re-run **`seed.sql`** manually only if you need to refresh demo rows without recreating the volume — see **`docs/demo.md`**.
