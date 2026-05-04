/**
 * Backend origin; no trailing slash. Matches `backend` default when unset.
 * Vite only exposes env vars prefixed with `VITE_` to client code — use `VITE_API_BASE_URL` in `.env` / `.env.local`.
 */
export function getApiBaseUrl(): string {
  const raw = import.meta.env.VITE_API_BASE_URL?.trim()
  const base = raw && raw.length > 0 ? raw : 'http://localhost:8080'
  return base.replace(/\/+$/, '')
}
