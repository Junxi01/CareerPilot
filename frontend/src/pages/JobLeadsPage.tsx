import React, { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import type { JobLeadsFilters } from '../api/jobLeads'
import * as jlApi from '../api/jobLeads'
import * as tcApi from '../api/targetCompanies'
import type { TargetCompanyDto } from '../types/targetCompany'
import type { CreateJobLeadRequest, JobLeadDto } from '../types/jobLead'
import { ErrorMessage } from '../components/ui/ErrorMessage'
import { LoadingState } from '../components/ui/LoadingState'
import { TextInput } from '../components/ui/TextInput'
import { TextArea } from '../components/ui/TextArea'
import { TagInput } from '../components/ui/TagInput'
import { InterviewPlanSection } from '../components/interviewPlan/InterviewPlanSection'

type SavedFilter = 'all' | 'saved' | 'unsaved'

/** Backend uses boolean; coerce common JSON/db edge cases so inactive rows are not mistaken for active. */
function isActiveCompanyDto(c: TargetCompanyDto): boolean {
  const v = (c as { active?: unknown }).active
  if (v === false || v === 'false' || v === 0 || v === '0') return false
  return true
}

function preview(raw: string | null | undefined, max = 260): string {
  const s = (raw ?? '').trim()
  if (!s) return ''
  return s.length <= max ? s : `${s.slice(0, max)}…`
}

function parseNum(raw: string): number | null {
  const s = raw.trim()
  if (!s) return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

function boolFromSavedFilter(v: SavedFilter): boolean | undefined {
  if (v === 'saved') return true
  if (v === 'unsaved') return false
  return undefined
}

export function JobLeadsPage() {
  const [companies, setCompanies] = useState<TargetCompanyDto[]>([])
  const [items, setItems] = useState<JobLeadDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  /** Full unfiltered snapshot for empty-state explanations (same user as JWT). Refreshed on lead mutations / Refresh. */
  const [baseline, setBaseline] = useState<JobLeadDto[]>([])
  const [baselineLoaded, setBaselineLoaded] = useState(false)

  const [companyId, setCompanyId] = useState<string>('') // select value
  const [keyword, setKeyword] = useState<string>('')
  const [minScore, setMinScore] = useState<string>('')
  const [savedFilter, setSavedFilter] = useState<SavedFilter>('all')

  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [detail, setDetail] = useState<JobLeadDto | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)

  const [savingAsApp, setSavingAsApp] = useState(false)
  const [saveAsAppMsg, setSaveAsAppMsg] = useState<string | null>(null)
  const [automationRunning, setAutomationRunning] = useState<'discover' | 'refresh' | null>(null)
  const [automationMsg, setAutomationMsg] = useState<string | null>(null)

  // Manual create form (for testing)
  const [createOpen, setCreateOpen] = useState(false)
  const [createSaving, setCreateSaving] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [createFields, setCreateFields] = useState<{
    company_id: string
    role_title: string
    job_url: string
    location: string
    matched_keywords: string[]
    match_score: string
    raw_description: string
  }>({
    company_id: '',
    role_title: '',
    job_url: '',
    location: '',
    matched_keywords: [],
    match_score: '',
    raw_description: '',
  })

  const activeCompaniesOnly = useMemo(() => companies.filter((c) => isActiveCompanyDto(c)), [companies])

  useEffect(() => {
    if (!companyId.trim()) return
    const id = Number(companyId.trim())
    if (!Number.isFinite(id)) return
    if (!companies.some((c) => Number(c.id) === id)) setCompanyId('')
  }, [companies, companyId])

  const filters = useMemo((): JobLeadsFilters => {
    const trimmedCo = companyId.trim()
    const cidNum = trimmedCo === '' ? Number.NaN : Number(trimmedCo)
    const company_id = Number.isFinite(cidNum) ? cidNum : undefined
    const minParsed = parseNum(minScore)
    return {
      company_id,
      keyword: keyword.trim() || undefined,
      min_match_score: minParsed ?? undefined,
      saved_to_applications: boolFromSavedFilter(savedFilter),
    }
  }, [companyId, keyword, minScore, savedFilter])

  const selectedCompanyLabel = useMemo(() => {
    if (!companyId.trim()) return null
    const id = Number(companyId.trim())
    if (!Number.isFinite(id)) return null
    const c = companies.find((x) => Number(x.id) === id)
    return c?.name ?? `#${companyId}`
  }, [companyId, companies])

  const filtersQueryDebug = useMemo(() => jlApi.jobLeadsFiltersQueryString(filters), [filters])

  const emptyListHelp = useMemo(() => {
    if (loading || !baselineLoaded || items.length > 0) return null
    const hasExtraFilter =
      Boolean(filters.keyword?.trim()) || filters.min_match_score != null || filters.saved_to_applications != null

    if (baseline.length === 0) {
      return (
        'This signed-in account has no job leads in the database. Choose a target company above, click Manual create—' +
        'the company field follows your dropdown—and save. (Demo seed roles in database/seed.sql only attach to demo@careerpilot.local.)'
      )
    }

    if (filters.company_id != null && !hasExtraFilter) {
      const onCompany = baseline.filter((l) => Number(l.company_id) === Number(filters.company_id))
      if (onCompany.length === 0) {
        const quick = [...new Map(baseline.map((l) => [Number(l.company_id), l.company_name])).entries()]
          .map(([id, n]) => `${n} (company_id ${id})`)
          .slice(0, 8)
          .join('; ')
        return (
          `No rows are stored under company "${selectedCompanyLabel ?? '?'}" (id ${filters.company_id}). You already ` +
          `have ${baseline.length} lead(s) on this account—for example under: ${quick}. Pick that company above, ` +
          'or Manual create here to attach a new lead to the company you filtered.'
        )
      }
    }

    if (hasExtraFilter) {
      return 'No lead matches company + keyword + score + saved all at once. Use “Clear keyword / score / saved”, then refine step by step: company shows every lead there; keyword then hides rows that lack that text (title, URL, JD, matched keywords JSON).'
    }

    return null
  }, [loading, baselineLoaded, baseline, items.length, filters, selectedCompanyLabel])

  const appliedFilterSummary = useMemo(() => {
    const parts: string[] = []
    if (selectedCompanyLabel) parts.push(`Company → ${selectedCompanyLabel}`)
    if (keyword.trim()) parts.push(`Keyword → ${keyword.trim()}`)
    const ms = parseNum(minScore)
    if (ms != null) parts.push(`Match score ≥ ${ms} (null scores excluded)`)
    if (savedFilter === 'saved') parts.push(`Saved → yes only`)
    if (savedFilter === 'unsaved') parts.push(`Saved → no only`)
    return parts
  }, [selectedCompanyLabel, keyword, minScore, savedFilter])

  function resetSecondaryFilters(): void {
    setKeyword('')
    setMinScore('')
    setSavedFilter('all')
  }

  function resetAllFilters(): void {
    setCompanyId('')
    resetSecondaryFilters()
  }

  async function loadCompanies() {
    try {
      const data = await tcApi.listTargetCompanies()
      setCompanies(data)
    } catch {
      // Non-blocking for job lead list; filter dropdown will just be empty.
      setCompanies([])
    }
  }

  async function loadBaseline(): Promise<void> {
    try {
      const rows = await jlApi.listJobLeads({})
      setBaseline(rows)
    } catch {
      setBaseline([])
    } finally {
      setBaselineLoaded(true)
    }
  }

  async function fetchJobLeads(payload: JobLeadsFilters): Promise<void> {
    setLoading(true)
    setError(null)
    try {
      const data = await jlApi.listJobLeads(payload)
      setItems(data)
      setSelectedId((sid) => {
        if (sid != null && !data.some((x) => x.id === sid)) return null
        return sid
      })
      setDetail((d) => {
        if (d != null && !data.some((x) => x.id === d.id)) return null
        return d
      })
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to load job leads.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  async function reloadFiltersAndLeads() {
    await loadCompanies()
    await loadBaseline()
    await fetchJobLeads(filters)
  }

  useEffect(() => {
    void loadCompanies()
    void loadBaseline()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** While Manual create is open, keep target company synced with the filter dropdown. */
  useEffect(() => {
    if (!createOpen) return
    setCreateFields((s) => ({ ...s, company_id: companyId.trim() }))
  }, [createOpen, companyId])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await jlApi.listJobLeads(filters)
        if (cancelled) return
        setItems(data)
        setSelectedId((sid) => {
          if (sid != null && !data.some((x) => x.id === sid)) return null
          return sid
        })
        setDetail((d) => {
          if (d != null && !data.some((x) => x.id === d.id)) return null
          return d
        })
      } catch (e) {
        if (!cancelled) {
          const msg = e instanceof ApiClientError ? e.message : 'Failed to load job leads.'
          setError(msg)
        }
      } finally {
        if (!cancelled) setLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [filters])

  async function loadDetail(id: number) {
    setSelectedId(id)
    setDetail(null)
    setDetailError(null)
    setSaveAsAppMsg(null)
    setDetailLoading(true)
    try {
      const d = await jlApi.getJobLead(id)
      setDetail(d)
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to load job lead.'
      setDetailError(msg)
    } finally {
      setDetailLoading(false)
    }
  }

  async function onSaveAsApplication() {
    if (!detail) return
    setSavingAsApp(true)
    setSaveAsAppMsg(null)
    try {
      const app = await jlApi.saveLeadAsApplication(detail.id)
      setSaveAsAppMsg(`Saved as application (id ${app.id}).`)
      await fetchJobLeads(filters)
      await loadBaseline()
      await loadDetail(detail.id)
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to save lead as application.'
      setSaveAsAppMsg(msg)
    } finally {
      setSavingAsApp(false)
    }
  }

  function selectedCompanyIdOrNull(): number | null {
    const raw = companyId.trim()
    if (!raw) return null
    const id = Number(raw)
    return Number.isFinite(id) ? id : null
  }

  async function onDiscoverJobs(): Promise<void> {
    setAutomationRunning('discover')
    setAutomationMsg(null)
    setError(null)
    try {
      const selectedCompany = selectedCompanyIdOrNull()
      const minParsed = parseNum(minScore)
      const res = await jlApi.discoverJobLeads({
        company_id: selectedCompany,
        min_match_score: minParsed ?? 0,
        max_pages_per_company: 8,
        max_depth: 2,
      })
      setAutomationMsg(
        `Search finished: scanned ${res.companies_scanned} company page(s), found ${res.leads_found} possible job(s), added ${res.leads_created}. ${res.duplicates_skipped} duplicate(s), ${res.low_score_skipped} below score, ${res.fetch_errors} page fetch issue(s).`,
      )
      await reloadFiltersAndLeads()
      if (res.created_items.length > 0) await loadDetail(res.created_items[0]!.id)
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to search company career pages.'
      setAutomationMsg(msg)
    } finally {
      setAutomationRunning(null)
    }
  }

  async function onRefreshInvalidLinks(): Promise<void> {
    setAutomationRunning('refresh')
    setAutomationMsg(null)
    setError(null)
    try {
      const selectedCompany = selectedCompanyIdOrNull()
      const res = await jlApi.refreshInvalidJobLeads({
        company_id: selectedCompany,
        delete_saved: false,
      })
      setAutomationMsg(
        `Link refresh finished: checked ${res.checked}, removed ${res.deleted} closed/invalid link(s), kept ${res.kept}. ${res.skipped_saved} saved lead(s) were protected, ${res.uncertain} link(s) were left alone because the site response was unclear.`,
      )
      await reloadFiltersAndLeads()
      setDetail((d) => (d && res.deleted_items.some((x) => x.id === d.id) ? null : d))
      setSelectedId((id) => (id != null && res.deleted_items.some((x) => x.id === id) ? null : id))
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to refresh job links.'
      setAutomationMsg(msg)
    } finally {
      setAutomationRunning(null)
    }
  }

  function resetCreateForm() {
    setCreateError(null)
    setCreateFields({
      company_id: '',
      role_title: '',
      job_url: '',
      location: '',
      matched_keywords: [],
      match_score: '',
      raw_description: '',
    })
  }

  async function onCreateSubmit(e: React.FormEvent) {
    e.preventDefault()
    setCreateError(null)

    const cid = createFields.company_id.trim() ? Number(createFields.company_id) : NaN
    if (!Number.isFinite(cid)) return setCreateError('company_id is required')
    if (!createFields.role_title.trim()) return setCreateError('role_title is required')
    if (!createFields.job_url.trim()) return setCreateError('job_url is required')

    const score = parseNum(createFields.match_score)
    const req: CreateJobLeadRequest = {
      company_id: cid,
      role_title: createFields.role_title.trim(),
      job_url: createFields.job_url.trim(),
      location: createFields.location.trim() || null,
      raw_description: createFields.raw_description.trim() || null,
      matched_keywords: createFields.matched_keywords ?? [],
      match_score: score ?? null,
      saved_to_applications: false,
    }

    setCreateSaving(true)
    try {
      const created = await jlApi.createJobLead(req)
      setCreateOpen(false)
      resetCreateForm()
      await fetchJobLeads(filters)
      await loadBaseline()
      await loadDetail(created.id)
    } catch (e2) {
      const msg = e2 instanceof ApiClientError ? e2.message : 'Failed to create job lead.'
      setCreateError(msg)
    } finally {
      setCreateSaving(false)
    }
  }

  return (
    <div>
      <h1 className="cp-page-title">Job leads</h1>
      <p className="cp-muted">
        Add companies and career page URLs in <Link to="/target-companies">Target companies</Link>, then use this page
        to find matching jobs and keep saved links fresh.
      </p>

      {error ? <ErrorMessage message={error} onDismiss={() => setError(null)} /> : null}
      {automationMsg ? (
        <div className="cp-info">
          <span>{automationMsg}</span>
          <button type="button" onClick={() => setAutomationMsg(null)}>
            Dismiss
          </button>
        </div>
      ) : null}

      <div className="cp-card" style={{ marginBottom: '1rem' }}>
        <div className="cp-card__header">
          <div>
            <div className="cp-card__title">Find jobs from your target companies</div>
            <div className="cp-help">
              Uses each active company’s career page URL, keywords, and locations. If you select a company below, the
              search runs only for that company; otherwise it scans all active companies.
            </div>
          </div>
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            <button
              type="button"
              className="cp-btn cp-btn--primary"
              onClick={() => void onDiscoverJobs()}
              disabled={automationRunning !== null || activeCompaniesOnly.length === 0}
              title="Search active company career pages and add matching jobs to this list."
            >
              {automationRunning === 'discover' ? 'Finding jobs…' : 'Find jobs'}
            </button>
            <button
              type="button"
              className="cp-btn cp-btn--subtle"
              onClick={() => void onRefreshInvalidLinks()}
              disabled={automationRunning !== null || baseline.length === 0}
              title="Check existing unsaved leads and remove links that are clearly closed or invalid."
            >
              {automationRunning === 'refresh' ? 'Checking links…' : 'Remove closed links'}
            </button>
          </div>
        </div>
        <div className="cp-help">
          Closed-link cleanup only deletes unsaved leads when a site clearly says 404/410 or the posting is closed.
          Saved applications are protected.
        </div>
      </div>

      <div className="cp-card" style={{ marginBottom: '1rem' }}>
        <div className="cp-card__header">
          <div className="cp-card__title">Filters</div>
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            <button type="button" className="cp-btn cp-btn--subtle" onClick={resetSecondaryFilters} disabled={loading}>
              Clear keyword / score / saved
            </button>
            <button type="button" className="cp-btn cp-btn--subtle" onClick={resetAllFilters} disabled={loading}>
              Clear all filters
            </button>
            <button
              type="button"
              className="cp-btn cp-btn--subtle"
              onClick={() => void reloadFiltersAndLeads()}
              disabled={loading}
              title="Reload company list (for dropdowns) and current leads."
            >
              Refresh
            </button>
            <button
              type="button"
              className="cp-btn cp-btn--primary"
              onClick={() => {
                setCreateOpen((v) => !v)
                setCreateError(null)
              }}
            >
              {createOpen ? 'Close create form' : 'Manual create'}
            </button>
          </div>
        </div>

        <div className="cp-jobleads-filters">
          <div className="cp-field">
            <label htmlFor="jl-company">
              Company{' '}
              {companyId.trim() && Number.isFinite(Number(companyId.trim())) ? (
                <span className="cp-muted" style={{ fontWeight: 'normal' }}>
                  (id {companyId.trim()})
                </span>
              ) : null}
            </label>
            <select
              id="jl-company"
              value={companyId}
              onChange={(e) => setCompanyId(e.target.value)}
              className="cp-select"
            >
              <option value="">All companies (active)</option>
              {activeCompaniesOnly.map((c) => (
                <option key={c.id} value={String(c.id)}>
                  {c.name}
                </option>
              ))}
            </select>
            <div className="cp-help">Options = your active target companies; the API only returns leads whose company belongs to you.</div>
          </div>

          <TextInput
            id="jl-keyword"
            label="Keyword (inside that company’s leads)"
            value={keyword}
            onChange={setKeyword}
            placeholder="e.g. backend"
            helperText="Searches this lead’s role title, posting URL, JD text, and its matched_keywords—not the parent target company’s profile fields."
          />
          <TextInput
            id="jl-minscore"
            label="Min match score"
            value={minScore}
            onChange={setMinScore}
            placeholder="leave blank unless you mean it"
            type="number"
          />

          <div className="cp-field">
            <label htmlFor="jl-saved">Saved</label>
            <select
              id="jl-saved"
              value={savedFilter}
              onChange={(e) => setSavedFilter(e.target.value as SavedFilter)}
              className="cp-select"
            >
              <option value="all">All</option>
              <option value="unsaved">Not saved</option>
              <option value="saved">Saved</option>
            </select>
          </div>
        </div>
        {appliedFilterSummary.length ? (
          <div className="cp-help" style={{ marginTop: '0.65rem' }}>
            Currently applying — {appliedFilterSummary.join('; ')}
            {filtersQueryDebug ? (
              <span style={{ display: 'block', marginTop: '0.25rem', opacity: 0.85 }}>
                Request:{' '}
                <code>/api/job-leads?{filtersQueryDebug}</code>
              </span>
            ) : (
              <span style={{ display: 'block', marginTop: '0.25rem', opacity: 0.85 }}>
                Request: <code>/api/job-leads</code> (no filters)
              </span>
            )}
          </div>
        ) : (
          filtersQueryDebug && (
            <div className="cp-help" style={{ marginTop: '0.65rem' }}>
              Request:{' '}
              <code>/api/job-leads?{filtersQueryDebug}</code>
            </div>
          )
        )}
        <p className="cp-muted" style={{ marginTop: '0.65rem', marginBottom: 0 }}>
          Flow: Company shows every saved lead for <em>that</em> target company. Keyword narrows inside that subset (checks title, URL, JD text, matched keywords JSON). All filters combine with AND.
        </p>
      </div>

      {createOpen ? (
        <div className="cp-card" style={{ marginBottom: '1rem' }}>
          <div className="cp-card__header">
            <div className="cp-card__title">Manual create (testing)</div>
            <button
              type="button"
              className="cp-btn cp-btn--subtle"
              onClick={() => {
                setCreateOpen(false)
                resetCreateForm()
              }}
              disabled={createSaving}
            >
              Cancel
            </button>
          </div>
          {createError ? <ErrorMessage message={createError} onDismiss={() => setCreateError(null)} /> : null}
          <form className="cp-form" onSubmit={onCreateSubmit}>
            <div className="cp-field">
              <label htmlFor="jl-create-company">
                Company <span aria-hidden>*</span>
              </label>
              <select
                id="jl-create-company"
                className="cp-select"
                value={createFields.company_id}
                onChange={(e) => setCreateFields((s) => ({ ...s, company_id: e.target.value }))}
                required
                disabled={createSaving || activeCompaniesOnly.length === 0}
              >
                <option value="">Select an active target company…</option>
                {activeCompaniesOnly.map((c) => (
                  <option key={c.id} value={String(c.id)}>
                    {c.name}
                  </option>
                ))}
              </select>
              {activeCompaniesOnly.length === 0 ? (
                <div className="cp-help">Add an active target company first.</div>
              ) : null}
            </div>
            <TextInput
              id="jl-create-title"
              label="role_title"
              value={createFields.role_title}
              onChange={(v) => setCreateFields((s) => ({ ...s, role_title: v }))}
              placeholder="Backend Engineer"
              required
              disabled={createSaving}
            />
            <TextInput
              id="jl-create-url"
              label="job_url"
              value={createFields.job_url}
              onChange={(v) => setCreateFields((s) => ({ ...s, job_url: v }))}
              placeholder="https://jobs.example.com/role"
              required
              disabled={createSaving}
            />
            <TextInput
              id="jl-create-loc"
              label="location (optional)"
              value={createFields.location}
              onChange={(v) => setCreateFields((s) => ({ ...s, location: v }))}
              placeholder="Remote"
              disabled={createSaving}
            />
            <TagInput
              id="jl-create-mk"
              label="matched_keywords"
              value={createFields.matched_keywords}
              onChange={(v) => setCreateFields((s) => ({ ...s, matched_keywords: v }))}
              placeholder="kotlin, mysql"
              disabled={createSaving}
              helperText="Comma-separated."
            />
            <TextInput
              id="jl-create-score"
              label="match_score (optional)"
              value={createFields.match_score}
              onChange={(v) => setCreateFields((s) => ({ ...s, match_score: v }))}
              placeholder="90"
              type="number"
              disabled={createSaving}
            />
            <TextArea
              id="jl-create-desc"
              label="raw_description (optional)"
              value={createFields.raw_description}
              onChange={(v) => setCreateFields((s) => ({ ...s, raw_description: v }))}
              placeholder="Paste a JD snippet…"
              disabled={createSaving}
              rows={5}
            />
            <button type="submit" className="cp-btn cp-btn--primary" disabled={createSaving}>
              {createSaving ? 'Creating…' : 'Create job lead'}
            </button>
          </form>
        </div>
      ) : null}

      <div className="cp-jobleads-split">
        <section className="cp-card">
          <div className="cp-card__header">
            <div className="cp-card__title">Leads ({items.length})</div>
          </div>

          {loading ? <LoadingState label="Loading job leads…" /> : null}
          {!loading && items.length === 0 ? (
            <div>
              <p className="cp-muted">No leads match your filters.</p>
              {!error && emptyListHelp ? (
                <p className="cp-muted" style={{ marginTop: '0.65rem', lineHeight: 1.5 }}>
                  {emptyListHelp}
                </p>
              ) : null}
              {!error && filters.company_id != null ? (
                <button
                  type="button"
                  className="cp-btn cp-btn--subtle"
                  style={{ marginTop: '0.75rem' }}
                  onClick={() => setCompanyId('')}
                  disabled={loading}
                >
                  Show leads for all active companies
                </button>
              ) : null}
            </div>
          ) : null}

          {!loading && items.length > 0 ? (
            <div className="cp-table">
              {items.map((l) => (
                <button
                  key={l.id}
                  type="button"
                  className={`cp-jl-item${selectedId === l.id ? ' cp-jl-item--active' : ''}`}
                  onClick={() => void loadDetail(l.id)}
                >
                  <div className="cp-jl-item__top">
                    <div className="cp-jl-item__title">{l.role_title}</div>
                    <span className={`cp-badge${l.saved_to_applications ? ' cp-badge--on' : ''}`}>
                      {l.saved_to_applications ? 'Saved' : 'Not saved'}
                    </span>
                  </div>
                  <div className="cp-jl-item__meta">
                    {l.company_name} <span style={{ opacity: 0.8 }}>(id {String(l.company_id)})</span>
                  </div>
                  {l.match_score != null ? (
                    <div className="cp-jl-item__meta">Match score: {l.match_score}</div>
                  ) : null}
                  {l.matched_keywords?.length ? (
                    <div className="cp-jl-item__meta">Keywords: {l.matched_keywords.join(', ')}</div>
                  ) : null}
                </button>
              ))}
            </div>
          ) : null}
        </section>

        <section className="cp-card">
          <div className="cp-card__header">
            <div className="cp-card__title">Detail</div>
          </div>

          {selectedId == null ? <p className="cp-muted">Select a job lead to view details.</p> : null}
          {detailLoading ? <LoadingState label="Loading detail…" /> : null}
          {detailError ? <ErrorMessage message={detailError} onDismiss={() => setDetailError(null)} /> : null}

          {!detailLoading && detail ? (
            <div>
              <div className="cp-jl-detail__title">
                {detail.role_title}{' '}
                <span className={`cp-badge${detail.saved_to_applications ? ' cp-badge--on' : ''}`}>
                  {detail.saved_to_applications ? 'Saved' : 'Not saved'}
                </span>
              </div>
              <div className="cp-jl-detail__meta">{detail.company_name}</div>
              <div className="cp-jl-detail__meta">
                <a href={detail.job_url} target="_blank" rel="noreferrer">
                  {detail.job_url}
                </a>
              </div>
              {detail.location ? <div className="cp-jl-detail__meta">Location: {detail.location}</div> : null}
              {detail.match_score != null ? <div className="cp-jl-detail__meta">Match score: {detail.match_score}</div> : null}

              {detail.matched_keywords?.length ? (
                <div className="cp-jl-detail__block">
                  <div className="cp-jl-detail__label">Matched keywords</div>
                  <div>{detail.matched_keywords.join(', ')}</div>
                </div>
              ) : null}

              {detail.raw_description ? (
                <div className="cp-jl-detail__block">
                  <div className="cp-jl-detail__label">Raw description (preview)</div>
                  <div className="cp-jl-detail__preview">{preview(detail.raw_description)}</div>
                </div>
              ) : null}

              <InterviewPlanSection jobLeadId={detail.id} />

              <div className="cp-jl-detail__actions">
                <button
                  type="button"
                  className="cp-btn cp-btn--primary"
                  onClick={() => void onSaveAsApplication()}
                  disabled={savingAsApp || detail.saved_to_applications}
                  title={detail.saved_to_applications ? 'Already saved' : 'Create an application and mark this lead saved'}
                >
                  {detail.saved_to_applications ? 'Saved as application' : savingAsApp ? 'Saving…' : 'Save as Application'}
                </button>
              </div>

              {saveAsAppMsg ? <div className="cp-help" style={{ marginTop: '0.5rem' }}>{saveAsAppMsg}</div> : null}
            </div>
          ) : null}
        </section>
      </div>
    </div>
  )
}
