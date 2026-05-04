import { getApiBaseUrl } from '../config/env'
import { clearAuthToken, getAuthToken } from '../lib/authToken'
import { emitSessionExpired } from '../lib/sessionEvents'
import type { ApiResponse } from '../types/api'

export class ApiClientError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiClientError'
  }
}

type JsonOpts = {
  method?: string
  body?: unknown
  /** Do not send `Authorization` (login/register). */
  skipAuth?: boolean
}

function mergeHeaders(base: HeadersInit | undefined, extra: Record<string, string>): Headers {
  const h = new Headers(base)
  for (const [k, v] of Object.entries(extra)) {
    if (v !== undefined && v !== '') h.set(k, v)
  }
  return h
}

async function readJson(res: Response): Promise<unknown> {
  const text = await res.text()
  if (!text) return null
  try {
    return JSON.parse(text) as unknown
  } catch {
    throw new ApiClientError(res.status, 'invalid_json', 'Response was not valid JSON')
  }
}

function clearSessionIfUnauthorized(res: Response, skipAuth?: boolean): void {
  if (skipAuth || res.status !== 401) return
  clearAuthToken()
  emitSessionExpired()
}

/**
 * Typed fetch against the backend `ApiResponse<T>` envelope.
 */
export async function apiJson<T>(path: string, opts: JsonOpts = {}): Promise<T> {
  const url = `${getApiBaseUrl()}${path.startsWith('/') ? path : `/${path}`}`
  const headers: Record<string, string> = {}
  if (!opts.skipAuth) {
    const t = getAuthToken()
    if (t) headers.Authorization = `Bearer ${t}`
  }
  if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  const res = await fetch(url, {
    method: opts.method ?? 'GET',
    headers: mergeHeaders(undefined, headers),
    body: opts.body !== undefined ? JSON.stringify(opts.body) : undefined,
  })

  let raw: unknown
  try {
    raw = await readJson(res)
  } catch (err) {
    clearSessionIfUnauthorized(res, opts.skipAuth)
    throw err
  }
  clearSessionIfUnauthorized(res, opts.skipAuth)

  const envelope = raw as ApiResponse<T>

  if (!envelope || typeof envelope !== 'object' || typeof envelope.success !== 'boolean') {
    const preview =
      raw === null || raw === undefined
        ? String(raw)
        : (() => {
            try {
              return JSON.stringify(raw).slice(0, 400)
            } catch {
              return String(raw)
            }
          })()
    throw new ApiClientError(
      res.status,
      'bad_envelope',
      `Unexpected response shape (HTTP ${res.status}). Body preview: ${preview}`,
    )
  }

  if (!envelope.success || envelope.error) {
    const code = envelope.error?.code ?? 'request_failed'
    const message = envelope.error?.message ?? 'Request failed'
    throw new ApiClientError(res.status, code, message)
  }

  return envelope.data as T
}

export function apiGet<T>(path: string, skipAuth?: boolean): Promise<T> {
  return apiJson<T>(path, { method: 'GET', skipAuth })
}

export function apiPost<T>(path: string, body: unknown, skipAuth?: boolean): Promise<T> {
  return apiJson<T>(path, { method: 'POST', body, skipAuth })
}
