import React, { useEffect, useState } from 'react'
import * as dashboardApi from '../api/dashboard'
import { ApiClientError } from '../api/client'
import type { DashboardStatsDto } from '../types/dashboard'
import { ErrorAlert } from '../components/ui/ErrorAlert'
import { LoadingBlock } from '../components/ui/LoadingBlock'

const statKeys: { key: keyof DashboardStatsDto; label: string }[] = [
  { key: 'total_applications', label: 'Applications' },
  { key: 'applications_this_week', label: 'This week' },
  { key: 'interviews_count', label: 'Interviews' },
  { key: 'offers_count', label: 'Offers' },
  { key: 'rejections_count', label: 'Rejections' },
  { key: 'response_rate', label: 'Response %' },
  { key: 'follow_ups_due', label: 'Follow-ups due' },
  { key: 'job_leads_discovered_this_week', label: 'Leads this week' },
  { key: 'prep_tasks_due_today', label: 'Prep due today' },
]

export function DashboardPage() {
  const [stats, setStats] = useState<DashboardStatsDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    dashboardApi
      .fetchDashboardStats()
      .then((data) => {
        if (!cancelled) setStats(data)
      })
      .catch((err) => {
        if (!cancelled) {
          const msg = err instanceof ApiClientError ? err.message : 'Failed to load dashboard stats.'
          setError(msg)
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div>
      <h1 className="cp-page-title">Dashboard</h1>
      <p className="cp-muted">
        Overview metrics from <code>/api/dashboard/stats</code>. More widgets can land here later.
      </p>
      {error ? <ErrorAlert message={error} onDismiss={() => setError(null)} /> : null}
      {loading ? <LoadingBlock label="Loading stats…" /> : null}
      {!loading && stats ? (
        <div className="cp-stat-grid">
          {statKeys.map(({ key, label }) => (
            <div key={key} className="cp-stat">
              <div className="cp-stat__label">{label}</div>
              <div className="cp-stat__value">
                {key === 'response_rate' ? stats[key].toFixed(2) : String(stats[key])}
              </div>
            </div>
          ))}
        </div>
      ) : null}
    </div>
  )
}
