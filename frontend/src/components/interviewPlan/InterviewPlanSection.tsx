import React, { useEffect, useState } from 'react'
import { ApiClientError } from '../../api/client'
import * as interviewApi from '../../api/interviewPlan'
import type { InterviewPlanDetailDto } from '../../types/interviewPlan'
import { ErrorMessage } from '../ui/ErrorMessage'
import { LoadingState } from '../ui/LoadingState'

function stringArray(v: unknown): string[] {
  if (!Array.isArray(v)) return []
  return v.filter((x): x is string => typeof x === 'string' && x.trim().length > 0)
}

function formatDue(iso: string | null): string | null {
  if (!iso) return null
  try {
    const d = /^\d{4}-\d{2}-\d{2}$/.test(iso) ? new Date(`${iso}T12:00:00`) : new Date(iso)
    if (Number.isNaN(d.getTime())) return iso
    return d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })
  } catch {
    return iso
  }
}

export function InterviewPlanSection(props: { jobLeadId: number }) {
  const { jobLeadId } = props
  const [plan, setPlan] = useState<InterviewPlanDetailDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [genHelpOpen, setGenHelpOpen] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [taskBusyId, setTaskBusyId] = useState<number | null>(null)

  const envAiProvider = import.meta.env.VITE_AI_PROVIDER?.trim().toLowerCase()
  const showEnvMockNotice = envAiProvider === 'mock'

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    ;(async () => {
      try {
        const p = await interviewApi.getInterviewPlanForJobLead(jobLeadId)
        if (!cancelled) setPlan(p)
      } catch (e) {
        if (cancelled) return
        if (e instanceof ApiClientError && e.status === 404) {
          setPlan(null)
          setError(null)
        } else {
          const msg =
            e instanceof ApiClientError
              ? `${e.message}${e.code ? ` (${e.code})` : ''}`
              : 'Could not load interview plan.'
          setError(msg)
          setPlan(null)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [jobLeadId])

  async function onRefresh() {
    setRefreshing(true)
    setError(null)
    try {
      const p = await interviewApi.getInterviewPlanForJobLead(jobLeadId)
      setPlan(p)
    } catch (e) {
      if (e instanceof ApiClientError && e.status === 404) {
        setPlan(null)
        setError(null)
      } else {
        const msg =
          e instanceof ApiClientError
            ? `${e.message}${e.code ? ` (${e.code})` : ''}`
            : 'Could not refresh interview plan.'
        setError(msg)
      }
    } finally {
      setRefreshing(false)
    }
  }

  async function onCompleteTask(taskId: number) {
    setTaskBusyId(taskId)
    setError(null)
    try {
      const updated = await interviewApi.completePrepTask(taskId)
      setPlan((p) => {
        if (!p) return p
        return { ...p, prep_tasks: p.prep_tasks.map((t) => (t.id === taskId ? updated : t)) }
      })
    } catch (e) {
      const msg =
        e instanceof ApiClientError
          ? `${e.message}${e.code ? ` (${e.code})` : ''}`
          : 'Could not update prep task.'
      setError(msg)
    } finally {
      setTaskBusyId(null)
    }
  }

  const pj = plan?.plan_json ?? {}
  const summary = typeof pj.summary === 'string' ? pj.summary : ''
  const matchReason = typeof pj.match_score_reasoning === 'string' ? pj.match_score_reasoning : ''
  const requiredSkills = stringArray(pj.required_skills)
  const niceToHaveSkills = stringArray(pj.nice_to_have_skills)
  const interviewTopics = stringArray(pj.interview_topics)
  const sevenDay = stringArray(pj.seven_day_plan)
  const technicalQs = stringArray(pj.technical_questions)
  const behavioralQs = stringArray(pj.behavioral_questions)
  const projectPoints = stringArray(pj.project_talking_points)
  const hasStructuredPlanContent =
    Boolean(summary || matchReason) ||
    requiredSkills.length > 0 ||
    niceToHaveSkills.length > 0 ||
    interviewTopics.length > 0 ||
    sevenDay.length > 0 ||
    technicalQs.length > 0 ||
    behavioralQs.length > 0 ||
    projectPoints.length > 0 ||
    Boolean(plan?.prep_tasks.length)

  const showPlanMockNotice = plan?.provider_mode?.toLowerCase() === 'mock'
  const showMockBanner = showEnvMockNotice || showPlanMockNotice

  return (
    <div className="cp-interview-plan">
      <div className="cp-jl-detail__block">
        <div className="cp-jl-detail__label">Interview plan</div>
        <div className="cp-interview-plan__toolbar">
          <button
            type="button"
            className="cp-btn cp-btn--primary"
            onClick={() => setGenHelpOpen((v) => !v)}
            aria-expanded={genHelpOpen}
            disabled={loading || refreshing}
          >
            {genHelpOpen ? 'Hide generation steps' : 'Generate Interview Plan'}
          </button>
          <button
            type="button"
            className="cp-btn cp-btn--subtle"
            onClick={() => void onRefresh()}
            disabled={loading || refreshing}
          >
            {refreshing ? 'Refreshing…' : 'Refresh plan'}
          </button>
        </div>

        {genHelpOpen ? (
          <div className="cp-interview-plan__cli cp-info" style={{ marginTop: '0.75rem', display: 'block' }}>
            <p style={{ marginTop: 0, marginBottom: '0.65rem' }}>
              The API does not run the AI inside the server. Generate a plan with the CLI (from the repo root), then use{' '}
              <strong>Refresh plan</strong> to load it here.
            </p>
            <ol style={{ margin: '0 0 0.65rem 1.1rem', padding: 0 }}>
              <li>
                Configure <code>.env</code> with backend URL and <code>SCRIPTS_API_TOKEN</code> or{' '}
                <code>SCRIPTS_EMAIL</code> / <code>SCRIPTS_PASSWORD</code>.
              </li>
              <li>
                Run:{' '}
                <code className="cp-interview-plan__code">
                  python scripts/ai_interview_planner.py {jobLeadId}
                </code>
              </li>
              <li>
                Optional: <code className="cp-interview-plan__code">AI_PROVIDER=mock</code> uses the deterministic mock plan (no API keys).
              </li>
              <li>
                Or POST JSON to <code>/api/job-leads/{jobLeadId}/interview-plan</code> — see{' '}
                <code>docs/interview-plan-api.md</code>.
              </li>
            </ol>
          </div>
        ) : null}

        {showMockBanner ? (
          <div className="cp-interview-plan__mock" role="status">
            <strong>Mock mode:</strong>{' '}
            {showPlanMockNotice
              ? 'This plan was saved with provider_mode “mock” (deterministic placeholder interview content). '
              : null}
            {showEnvMockNotice ? (
              <span>
                The UI env has <code>VITE_AI_PROVIDER=mock</code> (optional dev mirror of CLI{' '}
                <code>AI_PROVIDER=mock</code>).
              </span>
            ) : null}
          </div>
        ) : null}

        {error ? (
          <div style={{ marginTop: '0.75rem' }}>
            <ErrorMessage message={error} onDismiss={() => setError(null)} />
          </div>
        ) : null}

        {loading ? <LoadingState label="Loading interview plan…" /> : null}

        {!loading && !plan ? (
          <p className="cp-muted" style={{ marginTop: '0.65rem', marginBottom: 0 }}>
            No saved plan for this lead yet. Expand <strong>Generate Interview Plan</strong> for CLI steps, run the script,
            then click <strong>Refresh plan</strong>.
          </p>
        ) : null}

        {!loading && plan ? (
          <div className="cp-interview-plan__body">
            {!hasStructuredPlanContent && !plan.plan_markdown?.trim() ? (
              <p className="cp-muted">This plan is saved, but it does not contain displayable plan content yet.</p>
            ) : null}

            {summary ? (
              <section className="cp-interview-plan__section">
                <h3 className="cp-interview-plan__h">AI summary</h3>
                <p className="cp-interview-plan__prose">{summary}</p>
                {matchReason ? (
                  <p className="cp-interview-plan__sub cp-muted">
                    <span className="cp-interview-plan__inline-label">Match rationale: </span>
                    {matchReason}
                  </p>
                ) : null}
              </section>
            ) : (
              <p className="cp-muted">No summary field in plan_json.</p>
            )}

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Required skills</h3>
              {requiredSkills.length ? (
                <ul className="cp-interview-plan__ul">
                  {requiredSkills.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Nice-to-have skills</h3>
              {niceToHaveSkills.length ? (
                <ul className="cp-interview-plan__ul">
                  {niceToHaveSkills.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Interview topics</h3>
              {interviewTopics.length ? (
                <ul className="cp-interview-plan__ul">
                  {interviewTopics.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">7-day prep plan</h3>
              {sevenDay.length ? (
                <ol className="cp-interview-plan__ol">
                  {sevenDay.map((line, i) => (
                    <li key={`${i}-${line.slice(0, 24)}`}>{line}</li>
                  ))}
                </ol>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Technical questions</h3>
              {technicalQs.length ? (
                <ul className="cp-interview-plan__ul">
                  {technicalQs.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Behavioral questions</h3>
              {behavioralQs.length ? (
                <ul className="cp-interview-plan__ul">
                  {behavioralQs.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Project talking points</h3>
              {projectPoints.length ? (
                <ul className="cp-interview-plan__ul">
                  {projectPoints.map((s) => (
                    <li key={s}>{s}</li>
                  ))}
                </ul>
              ) : (
                <p className="cp-muted">—</p>
              )}
            </section>

            <section className="cp-interview-plan__section">
              <h3 className="cp-interview-plan__h">Prep tasks</h3>
              {plan.prep_tasks.length ? (
                <ul className="cp-interview-plan__tasks">
                  {plan.prep_tasks.map((t) => {
                    const done = t.status === 'done'
                    const due = formatDue(t.due_date)
                    const busy = taskBusyId === t.id
                    return (
                      <li key={t.id} className="cp-interview-plan__task">
                        <label className="cp-interview-plan__task-label">
                          <input
                            type="checkbox"
                            checked={done}
                            disabled={done || busy}
                            onChange={() => void onCompleteTask(t.id)}
                          />
                          <span className={done ? 'cp-interview-plan__task-done' : undefined}>{t.label}</span>
                          {due ? <span className="cp-interview-plan__due">{due}</span> : null}
                        </label>
                        {busy ? <span className="cp-muted cp-interview-plan__task-saving">Saving…</span> : null}
                      </li>
                    )
                  })}
                </ul>
              ) : (
                <p className="cp-muted">No prep tasks.</p>
              )}
            </section>

            {plan.plan_markdown?.trim() ? (
              <details className="cp-interview-plan__details">
                <summary>Full markdown report</summary>
                <pre className="cp-interview-plan__md">{plan.plan_markdown.trim()}</pre>
              </details>
            ) : null}
          </div>
        ) : null}
      </div>
    </div>
  )
}
