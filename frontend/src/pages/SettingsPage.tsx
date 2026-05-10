import React, { useEffect, useState } from 'react'
import { ApiClientError } from '../api/client'
import { getApiBaseUrl } from '../config/env'
import * as settingsApi from '../api/settings'
import type { SettingsStatusDto } from '../types/settings'
import { ErrorMessage } from '../components/ui/ErrorMessage'
import { LoadingState } from '../components/ui/LoadingState'

function boolLabel(ok: boolean): string {
  return ok ? 'Yes' : 'No'
}

export function SettingsPage() {
  const apiBase = getApiBaseUrl()
  const viteAi = import.meta.env.VITE_AI_PROVIDER?.trim()

  const [status, setStatus] = useState<SettingsStatusDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    ;(async () => {
      try {
        const s = await settingsApi.getSettingsStatus()
        if (!cancelled) setStatus(s)
      } catch (e) {
        if (cancelled) return
        const msg =
          e instanceof ApiClientError
            ? `${e.message}${e.code ? ` (${e.code})` : ''}`
            : 'Could not load server settings.'
        setError(msg)
        setStatus(null)
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div>
      <h1 className="cp-page-title">Settings</h1>
      <p className="cp-muted">
        Environment and integration status for this browser session and the backend process. For setup details see{' '}
        <code>docs/ai-setup.md</code> in the repository.
      </p>

      {error ? <ErrorMessage message={error} onDismiss={() => setError(null)} /> : null}

      <div className="cp-card" style={{ marginBottom: '1rem' }}>
        <div className="cp-card__header">
          <div className="cp-card__title">Frontend</div>
        </div>
        <dl className="cp-settings-dl">
          <div>
            <dt>API base URL</dt>
            <dd>
              <code>{apiBase}</code> <span className="cp-muted">(from VITE_API_BASE_URL)</span>
            </dd>
          </div>
          <div>
            <dt>Frontend AI hint</dt>
            <dd>
              {viteAi ? (
                <>
                  <code>VITE_AI_PROVIDER={viteAi}</code> <span className="cp-muted">(optional UI banner only)</span>
                </>
              ) : (
                <span className="cp-muted">Not set — optional dev mirror for mock banners.</span>
              )}
            </dd>
          </div>
        </dl>
      </div>

      <div className="cp-card" style={{ marginBottom: '1rem' }}>
        <div className="cp-card__header">
          <div className="cp-card__title">Backend (signed-in)</div>
        </div>
        {loading ? <LoadingState label="Loading server status…" /> : null}
        {!loading && status ? (
          <dl className="cp-settings-dl">
            <div>
              <dt>App</dt>
              <dd>
                {status.app_name} <span className="cp-muted">v{status.app_version}</span>
              </dd>
            </div>
            <div>
              <dt>Database</dt>
              <dd>
                <span className={status.db_status === 'connected' ? 'cp-settings-ok' : 'cp-settings-bad'}>
                  {status.db_status === 'connected' ? 'Connected' : 'Down'}
                </span>
                {status.db_error ? (
                  <span className="cp-muted">
                    {' '}
                    — <code>{status.db_error}</code>
                  </span>
                ) : null}
              </dd>
            </div>
            <div>
              <dt>AI provider (server env)</dt>
              <dd>
                <code>{status.ai_provider}</code>{' '}
                <span className="cp-muted">
                  (scripts / <code>scripts/common/ai_provider.py</code>; backend does not send prompts to OpenAI by default)
                </span>
              </dd>
            </div>
            <div>
              <dt>AI model (reported)</dt>
              <dd>{status.ai_model ? <code>{status.ai_model}</code> : <span className="cp-muted">Not set in env</span>}</dd>
            </div>
            <div>
              <dt>OpenAI API key</dt>
              <dd>
                Configured in server env: <strong>{boolLabel(status.openai_api_key_configured)}</strong>
                <span className="cp-muted"> — value never leaves the server or appears in this UI.</span>
              </dd>
            </div>
            <div>
              <dt>Gemini API key</dt>
              <dd>
                Configured in server env: <strong>{boolLabel(status.gemini_api_key_configured)}</strong>
                <span className="cp-muted"> — same as above.</span>
              </dd>
            </div>
          </dl>
        ) : null}
        {!loading && !status ? (
          <dl className="cp-settings-dl">
            <div>
              <dt>Server status</dt>
              <dd>
                <span className="cp-settings-bad">Unavailable</span>{' '}
                <span className="cp-muted">
                  The frontend is still usable, but backend environment details could not be loaded.
                </span>
              </dd>
            </div>
            <div>
              <dt>AI provider</dt>
              <dd>
                <span className="cp-muted">Unknown — check the backend process environment or repo-root .env.</span>
              </dd>
            </div>
          </dl>
        ) : null}
      </div>

      <div className="cp-card">
        <div className="cp-card__header">
          <div className="cp-card__title">Privacy & keys</div>
        </div>
        <ul className="cp-settings-notes">
          <li>
            API keys and database passwords live only in server and script <strong>.env</strong> files — not in the React bundle.
          </li>
          <li>
            In <strong>openai</strong> / <strong>gemini</strong> modes, Python tools may send job descriptions and prompts to the
            vendor; see <code>docs/ai-setup.md</code>.
          </li>
          <li>
            Use <strong>mock</strong> mode for offline or local testing without sending data to external APIs.
          </li>
        </ul>
      </div>
    </div>
  )
}
