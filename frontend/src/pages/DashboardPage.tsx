import React, { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import * as dashboardApi from '../api/dashboard'
import { ApiClientError } from '../api/client'
import type { DashboardFollowUpDto, DashboardStatsDto, PrepSummaryItemDto } from '../types/dashboard'
import type { InterviewDto } from '../types/interview'
import type { JobLeadDto } from '../types/jobLead'
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

type WidgetState<T> = {
  data: T | null
  loading: boolean
  error: string | null
}

type WidgetCardProps<T> = {
  title: string
  state: WidgetState<T[]>
  emptyMessage: string
  renderItems: (items: T[]) => React.ReactNode
}

function initialWidgetState<T>(): WidgetState<T> {
  return { data: null, loading: true, error: null }
}

function errorMessage(err: unknown, fallback: string): string {
  return err instanceof ApiClientError ? err.message : fallback
}

function formatKind(raw: string): string {
  return raw.replaceAll('_', ' ')
}

function formatDateish(raw: string | null | undefined): string {
  const value = raw?.trim()
  if (!value) return '—'

  try {
    const normalized = /^\d{4}-\d{2}-\d{2}$/.test(value)
      ? `${value}T12:00:00`
      : value.replace(' ', 'T')
    const d = new Date(normalized)
    if (Number.isNaN(d.getTime())) return value
    if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
      return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
    }
    return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
  } catch {
    return value
  }
}

function WidgetCard<T>({ title, state, emptyMessage, renderItems }: WidgetCardProps<T>) {
  return (
    <section className="cp-card">
      <div className="cp-card__header">
        <div className="cp-card__title">{title}</div>
      </div>

      {state.loading ? <LoadingBlock label={`Loading ${title.toLowerCase()}…`} /> : null}
      {!state.loading && state.error ? <ErrorAlert message={state.error} /> : null}
      {!state.loading && !state.error && state.data?.length === 0 ? <p className="cp-muted">{emptyMessage}</p> : null}
      {!state.loading && !state.error && state.data && state.data.length > 0 ? renderItems(state.data) : null}
    </section>
  )
}

export function DashboardPage() {
  const [stats, setStats] = useState<DashboardStatsDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [followUps, setFollowUps] = useState<WidgetState<DashboardFollowUpDto[]>>(() => initialWidgetState())
  const [recentJobLeads, setRecentJobLeads] = useState<WidgetState<JobLeadDto[]>>(() => initialWidgetState())
  const [upcomingInterviews, setUpcomingInterviews] = useState<WidgetState<InterviewDto[]>>(() => initialWidgetState())
  const [prepSummary, setPrepSummary] = useState<WidgetState<PrepSummaryItemDto[]>>(() => initialWidgetState())

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

  useEffect(() => {
    let cancelled = false

    async function loadWidget<T>(
      fetcher: () => Promise<T[]>,
      setState: React.Dispatch<React.SetStateAction<WidgetState<T[]>>>,
      fallback: string,
    ): Promise<void> {
      setState({ data: null, loading: true, error: null })
      try {
        const data = await fetcher()
        if (!cancelled) setState({ data, loading: false, error: null })
      } catch (err) {
        if (!cancelled) setState({ data: null, loading: false, error: errorMessage(err, fallback) })
      }
    }

    void loadWidget(
      dashboardApi.fetchDashboardFollowUps,
      setFollowUps,
      'Failed to load follow-ups.',
    )
    void loadWidget(
      async () => (await dashboardApi.fetchDashboardRecentJobLeads()).items,
      setRecentJobLeads,
      'Failed to load recent job leads.',
    )
    void loadWidget(
      async () => (await dashboardApi.fetchDashboardUpcomingInterviews()).items,
      setUpcomingInterviews,
      'Failed to load upcoming interviews.',
    )
    void loadWidget(
      async () => (await dashboardApi.fetchDashboardPrepSummary()).items,
      setPrepSummary,
      'Failed to load prep summary.',
    )

    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div>
      <h1 className="cp-page-title">Dashboard</h1>
      <p className="cp-muted">
        Overview metrics, follow-ups, recent leads, upcoming interviews, and prep due today.
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

      <div className="cp-dashboard-widgets">
        <WidgetCard
          title="Follow-ups"
          state={followUps}
          emptyMessage="No follow-ups due in the current dashboard window."
          renderItems={(items) => (
            <div className="cp-table">
              {items.map((item) => (
                <div key={`${item.kind}-${item.id}`} className="cp-row cp-dashboard-row">
                  <div className="cp-row__main">
                    <div className="cp-row__title">
                      {item.application_id ? <Link to="/applications">{item.title}</Link> : <span>{item.title}</span>}
                      <span className="cp-badge">{formatKind(item.kind)}</span>
                    </div>
                    <div className="cp-row__meta">
                      {item.company_name ?? 'No company'} · Due {formatDateish(item.due)}
                      {item.application_id ? ` · Application #${item.application_id}` : ''}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        />

        <WidgetCard
          title="Recent job leads"
          state={recentJobLeads}
          emptyMessage="No recent job leads yet."
          renderItems={(items) => (
            <div className="cp-table">
              {items.map((lead) => (
                <div key={lead.id} className="cp-row cp-dashboard-row">
                  <div className="cp-row__main">
                    <div className="cp-row__title">
                      <Link to="/job-leads">{lead.role_title}</Link>
                      {lead.match_score != null ? <span className="cp-badge">Score {lead.match_score}</span> : null}
                    </div>
                    <div className="cp-row__meta">
                      {lead.company_name} · Discovered {formatDateish(lead.discovered_at)}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        />

        <WidgetCard
          title="Upcoming interviews"
          state={upcomingInterviews}
          emptyMessage="No upcoming interviews scheduled."
          renderItems={(items) => (
            <div className="cp-table">
              {items.map((interview) => (
                <div key={interview.id} className="cp-row cp-dashboard-row">
                  <div className="cp-row__main">
                    <div className="cp-row__title">
                      <Link to="/applications">{interview.round_name || `Interview #${interview.id}`}</Link>
                      <span className="cp-badge">{interview.status}</span>
                    </div>
                    <div className="cp-row__meta">
                      {formatDateish(interview.scheduled_at)} · Application #{interview.application_id}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        />

        <WidgetCard
          title="Prep due today"
          state={prepSummary}
          emptyMessage="No prep tasks due today."
          renderItems={(items) => (
            <div className="cp-table">
              {items.map((task) => (
                <div key={task.id} className="cp-row cp-dashboard-row">
                  <div className="cp-row__main">
                    <div className="cp-row__title">
                      <Link to="/job-leads">{task.label}</Link>
                      <span className="cp-badge">{task.status}</span>
                    </div>
                    <div className="cp-row__meta">
                      {task.company_name} · {task.role_title} · Due {formatDateish(task.due_date)}
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
        />
      </div>
    </div>
  )
}
