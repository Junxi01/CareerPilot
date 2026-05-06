import React, { useEffect, useMemo, useState } from 'react'
import type { ApplicationFilters } from '../api/applications'
import * as appApi from '../api/applications'
import { ApiClientError } from '../api/client'
import * as tcApi from '../api/targetCompanies'
import type { ApplicationDto, ApplicationStatus, CreateApplicationRequest, PatchApplicationRequest } from '../types/application'
import type { TargetCompanyDto } from '../types/targetCompany'
import { ErrorMessage } from '../components/ui/ErrorMessage'
import { LoadingState } from '../components/ui/LoadingState'
import { TextArea } from '../components/ui/TextArea'
import { TextInput } from '../components/ui/TextInput'
import { TagInput } from '../components/ui/TagInput'

const statuses: ApplicationStatus[] = [
  'SAVED',
  'APPLIED',
  'ONLINE_ASSESSMENT',
  'INTERVIEW',
  'OFFER',
  'REJECTED',
  'GHOSTED',
  'ARCHIVED',
]

type SavedCompanyMode = 'select' | 'name'

type CreateState = {
  companyMode: SavedCompanyMode
  company_id: string
  company_name: string
  role_title: string
  job_url: string
  status: ApplicationStatus
  tech_stack: string[]
  salary_range: string
  applied_date: string
  follow_up_date: string
  notes: string
}

type EditState = {
  role_title: string
  job_url: string
  status: ApplicationStatus
  tech_stack: string[]
  salary_range: string
  applied_date: string
  follow_up_date: string
  notes: string
}

function emptyCreate(): CreateState {
  return {
    companyMode: 'select',
    company_id: '',
    company_name: '',
    role_title: '',
    job_url: '',
    status: 'SAVED',
    tech_stack: [],
    salary_range: '',
    applied_date: '',
    follow_up_date: '',
    notes: '',
  }
}

function toEdit(a: ApplicationDto): EditState {
  return {
    role_title: a.role_title ?? '',
    job_url: a.job_url ?? '',
    status: a.status,
    tech_stack: a.tech_stack ?? [],
    salary_range: a.salary_range ?? '',
    applied_date: a.applied_date ?? '',
    follow_up_date: a.follow_up_date ?? '',
    notes: a.notes ?? '',
  }
}

function parseId(raw: string): number | null {
  const s = raw.trim()
  if (!s) return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

function badgeClass(status: ApplicationStatus): string {
  if (status === 'OFFER') return 'cp-badge cp-badge--on'
  if (status === 'REJECTED') return 'cp-badge'
  if (status === 'APPLIED' || status === 'INTERVIEW' || status === 'ONLINE_ASSESSMENT') return 'cp-badge cp-badge--on'
  return 'cp-badge'
}

export function ApplicationsPage() {
  const [companies, setCompanies] = useState<TargetCompanyDto[]>([])
  const [items, setItems] = useState<ApplicationDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [statusFilter, setStatusFilter] = useState<string>('') // empty = all
  const [companyId, setCompanyId] = useState<string>('') // empty = all
  const [keyword, setKeyword] = useState<string>('')

  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [detail, setDetail] = useState<ApplicationDto | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [detailError, setDetailError] = useState<string | null>(null)

  const [editOpen, setEditOpen] = useState(false)
  const [editSaving, setEditSaving] = useState(false)
  const [editError, setEditError] = useState<string | null>(null)
  const [edit, setEdit] = useState<EditState | null>(null)

  const [createOpen, setCreateOpen] = useState(false)
  const [createSaving, setCreateSaving] = useState(false)
  const [createError, setCreateError] = useState<string | null>(null)
  const [create, setCreate] = useState<CreateState>(() => emptyCreate())

  const activeCompaniesOnly = useMemo(() => companies.filter((c) => Boolean(c.active)), [companies])

  const filters = useMemo((): ApplicationFilters => {
    const cid = parseId(companyId)
    return {
      status: statusFilter.trim() || undefined,
      company_id: cid ?? undefined,
      keyword: keyword.trim() || undefined,
    }
  }, [companyId, keyword, statusFilter])

  async function loadCompanies(): Promise<void> {
    try {
      const data = await tcApi.listTargetCompanies()
      setCompanies(data)
    } catch {
      setCompanies([])
    }
  }

  async function loadList(payload: ApplicationFilters): Promise<void> {
    setLoading(true)
    setError(null)
    try {
      const data = await appApi.listApplications(payload)
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
      const msg = e instanceof ApiClientError ? e.message : 'Failed to load applications.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadCompanies()
  }, [])

  useEffect(() => {
    let cancelled = false
    ;(async () => {
      setLoading(true)
      setError(null)
      try {
        const data = await appApi.listApplications(filters)
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
          const msg = e instanceof ApiClientError ? e.message : 'Failed to load applications.'
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

  async function loadDetail(id: number): Promise<void> {
    setSelectedId(id)
    setDetail(null)
    setDetailError(null)
    setEditError(null)
    setEditOpen(false)
    setDetailLoading(true)
    try {
      const d = await appApi.getApplication(id)
      setDetail(d)
      setEdit(toEdit(d))
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to load application.'
      setDetailError(msg)
      setEdit(null)
    } finally {
      setDetailLoading(false)
    }
  }

  function resetCreate(): void {
    setCreateOpen(false)
    setCreateSaving(false)
    setCreateError(null)
    setCreate(emptyCreate())
  }

  function resetEdit(): void {
    setEditOpen(false)
    setEditSaving(false)
    setEditError(null)
    setEdit(detail ? toEdit(detail) : null)
  }

  async function onCreateSubmit(e: React.FormEvent) {
    e.preventDefault()
    setCreateError(null)

    if (!create.role_title.trim()) return setCreateError('role_title is required')
    if (!create.job_url.trim()) return setCreateError('job_url is required')

    const companyIdNum = create.companyMode === 'select' ? parseId(create.company_id) : null
    const companyNameRaw = create.companyMode === 'name' ? create.company_name.trim() : ''
    if (create.companyMode === 'select' && !companyIdNum) return setCreateError('company is required')
    if (create.companyMode === 'name' && !companyNameRaw) return setCreateError('company_name is required')

    const req: CreateApplicationRequest = {
      company_id: companyIdNum ?? null,
      company_name: companyNameRaw ? companyNameRaw : null,
      role_title: create.role_title.trim(),
      job_url: create.job_url.trim(),
      status: create.status,
      tech_stack: create.tech_stack ?? [],
      salary_range: create.salary_range.trim() || null,
      applied_date: create.applied_date.trim() || null,
      follow_up_date: create.follow_up_date.trim() || null,
      notes: create.notes.trim() || null,
    }

    setCreateSaving(true)
    try {
      const created = await appApi.createApplication(req)
      resetCreate()
      await loadCompanies()
      await loadList(filters)
      await loadDetail(created.id)
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Create failed.'
      setCreateError(msg)
    } finally {
      setCreateSaving(false)
    }
  }

  async function onEditSave(): Promise<void> {
    if (!detail || !edit) return
    setEditError(null)

    const patch: PatchApplicationRequest = {
      role_title: edit.role_title.trim() || null,
      job_url: edit.job_url.trim() || null,
      status: edit.status,
      tech_stack: edit.tech_stack ?? [],
      salary_range: edit.salary_range.trim() || null,
      applied_date: edit.applied_date.trim() || null,
      follow_up_date: edit.follow_up_date.trim() || null,
      notes: edit.notes.trim() || null,
    }

    setEditSaving(true)
    try {
      const updated = await appApi.patchApplication(detail.id, patch)
      setDetail(updated)
      setEdit(toEdit(updated))
      setEditOpen(false)
      await loadList(filters)
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Save failed.'
      setEditError(msg)
    } finally {
      setEditSaving(false)
    }
  }

  async function onDelete(item: ApplicationDto): Promise<void> {
    if (!confirm(`Delete application for "${item.role_title}" at ${item.company_name}?`)) return
    setError(null)
    try {
      await appApi.deleteApplication(item.id)
      if (selectedId === item.id) {
        setSelectedId(null)
        setDetail(null)
        setEdit(null)
        setEditOpen(false)
      }
      await loadList(filters)
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Delete failed.'
      setError(msg)
    }
  }

  function clearFilters(): void {
    setStatusFilter('')
    setCompanyId('')
    setKeyword('')
  }

  return (
    <div>
      <h1 className="cp-page-title">Applications</h1>
      <p className="cp-muted">Daily job tracking: status, dates, follow-ups, and notes.</p>

      {error ? <ErrorMessage message={error} onDismiss={() => setError(null)} /> : null}

      <div className="cp-card" style={{ marginBottom: '1rem' }}>
        <div className="cp-card__header">
          <div className="cp-card__title">Filters</div>
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            <button type="button" className="cp-btn cp-btn--subtle" onClick={clearFilters} disabled={loading}>
              Clear filters
            </button>
            <button type="button" className="cp-btn cp-btn--subtle" onClick={() => void loadList(filters)} disabled={loading}>
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
              {createOpen ? 'Close create form' : 'New application'}
            </button>
          </div>
        </div>

        <div className="cp-jobleads-filters">
          <div className="cp-field">
            <label htmlFor="app-status">Status</label>
            <select
              id="app-status"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              className="cp-select"
            >
              <option value="">All</option>
              {statuses.map((s) => (
                <option key={s} value={s}>
                  {s.replace('_', ' ')}
                </option>
              ))}
            </select>
          </div>

          <div className="cp-field">
            <label htmlFor="app-company">Company</label>
            <select
              id="app-company"
              value={companyId}
              onChange={(e) => setCompanyId(e.target.value)}
              className="cp-select"
            >
              <option value="">All active companies</option>
              {activeCompaniesOnly.map((c) => (
                <option key={c.id} value={String(c.id)}>
                  {c.name}
                </option>
              ))}
            </select>
          </div>

          <TextInput
            id="app-keyword"
            label="Keyword"
            value={keyword}
            onChange={setKeyword}
            placeholder="e.g. staff, platform, referral"
            helperText="Matches role title, notes, or job URL."
          />
        </div>
      </div>

      {createOpen ? (
        <div className="cp-card" style={{ marginBottom: '1rem' }}>
          <div className="cp-card__header">
            <div className="cp-card__title">Create application</div>
            <button type="button" className="cp-btn cp-btn--subtle" onClick={resetCreate} disabled={createSaving}>
              Cancel
            </button>
          </div>

          {createError ? <ErrorMessage message={createError} onDismiss={() => setCreateError(null)} /> : null}

          <form className="cp-form" onSubmit={onCreateSubmit}>
            <div className="cp-field">
              <label htmlFor="app-create-company-mode">Company mode</label>
              <select
                id="app-create-company-mode"
                className="cp-select"
                value={create.companyMode}
                onChange={(e) => setCreate((s) => ({ ...s, companyMode: e.target.value as SavedCompanyMode }))}
                disabled={createSaving}
              >
                <option value="select">Pick an existing target company</option>
                <option value="name">Type a company name (will match/create)</option>
              </select>
              <div className="cp-help">
                You can track an application against a target company you already saved, or type a company name (backend will try to
                match by name).
              </div>
            </div>

            {create.companyMode === 'select' ? (
              <div className="cp-field">
                <label htmlFor="app-create-company">Company</label>
                <select
                  id="app-create-company"
                  className="cp-select"
                  value={create.company_id}
                  onChange={(e) => setCreate((s) => ({ ...s, company_id: e.target.value }))}
                  required
                  disabled={createSaving || activeCompaniesOnly.length === 0}
                >
                  <option value="">Select a target company…</option>
                  {activeCompaniesOnly.map((c) => (
                    <option key={c.id} value={String(c.id)}>
                      {c.name}
                    </option>
                  ))}
                </select>
              </div>
            ) : (
              <TextInput
                id="app-create-company-name"
                label="company_name"
                value={create.company_name}
                onChange={(v) => setCreate((s) => ({ ...s, company_name: v }))}
                placeholder="e.g. Acme Corp"
                required
                disabled={createSaving}
              />
            )}

            <TextInput
              id="app-create-role"
              label="role_title"
              value={create.role_title}
              onChange={(v) => setCreate((s) => ({ ...s, role_title: v }))}
              placeholder="Senior Backend Engineer"
              required
              disabled={createSaving}
            />
            <TextInput
              id="app-create-url"
              label="job_url"
              value={create.job_url}
              onChange={(v) => setCreate((s) => ({ ...s, job_url: v }))}
              placeholder="https://jobs.example.com/role"
              required
              disabled={createSaving}
            />

            <div className="cp-field">
              <label htmlFor="app-create-status">status</label>
              <select
                id="app-create-status"
                className="cp-select"
                value={create.status}
                onChange={(e) => setCreate((s) => ({ ...s, status: e.target.value as ApplicationStatus }))}
                disabled={createSaving}
              >
                {statuses.map((s) => (
                  <option key={s} value={s}>
                    {s.replace('_', ' ')}
                  </option>
                ))}
              </select>
            </div>

            <TagInput
              id="app-create-tech"
              label="tech_stack"
              value={create.tech_stack}
              onChange={(v) => setCreate((s) => ({ ...s, tech_stack: v }))}
              placeholder="kotlin, mysql, react"
              disabled={createSaving}
              helperText="Optional. Comma-separated."
            />

            <TextInput
              id="app-create-salary"
              label="salary_range (optional)"
              value={create.salary_range}
              onChange={(v) => setCreate((s) => ({ ...s, salary_range: v }))}
              placeholder="e.g. 160k–220k + equity"
              disabled={createSaving}
            />

            <TextInput
              id="app-create-applied"
              label="applied_date (optional)"
              value={create.applied_date}
              onChange={(v) => setCreate((s) => ({ ...s, applied_date: v }))}
              type="date"
              disabled={createSaving}
            />
            <TextInput
              id="app-create-follow"
              label="follow_up_date (optional)"
              value={create.follow_up_date}
              onChange={(v) => setCreate((s) => ({ ...s, follow_up_date: v }))}
              type="date"
              disabled={createSaving}
              helperText="Set this and keep status = APPLIED to manage daily follow-ups."
            />

            <TextArea
              id="app-create-notes"
              label="notes (optional)"
              value={create.notes}
              onChange={(v) => setCreate((s) => ({ ...s, notes: v }))}
              placeholder="Recruiter name, referral, timeline, next steps…"
              disabled={createSaving}
              rows={5}
            />

            <button type="submit" className="cp-btn cp-btn--primary" disabled={createSaving}>
              {createSaving ? 'Creating…' : 'Create application'}
            </button>
          </form>
        </div>
      ) : null}

      <div className="cp-jobleads-split">
        <section className="cp-card">
          <div className="cp-card__header">
            <div className="cp-card__title">Applications ({items.length})</div>
          </div>

          {loading ? <LoadingState label="Loading applications…" /> : null}
          {!loading && items.length === 0 ? <p className="cp-muted">No applications match your filters.</p> : null}

          {!loading && items.length > 0 ? (
            <div className="cp-table">
              {items.map((a) => (
                <button
                  key={a.id}
                  type="button"
                  className={`cp-jl-item${selectedId === a.id ? ' cp-jl-item--active' : ''}`}
                  onClick={() => void loadDetail(a.id)}
                >
                  <div className="cp-jl-item__top">
                    <div className="cp-jl-item__title">{a.role_title}</div>
                    <span className={badgeClass(a.status)}>{a.status.replace('_', ' ')}</span>
                  </div>
                  <div className="cp-jl-item__meta">{a.company_name}</div>
                  {a.follow_up_date ? <div className="cp-jl-item__meta">Follow-up: {a.follow_up_date}</div> : null}
                  {a.applied_date ? <div className="cp-jl-item__meta">Applied: {a.applied_date}</div> : null}
                </button>
              ))}
            </div>
          ) : null}
        </section>

        <section className="cp-card">
          <div className="cp-card__header">
            <div className="cp-card__title">Detail</div>
            {detail ? (
              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button
                  type="button"
                  className="cp-btn cp-btn--subtle"
                  onClick={() => {
                    setEditOpen((v) => !v)
                    setEditError(null)
                    setEdit(detail ? toEdit(detail) : null)
                  }}
                  disabled={detailLoading || editSaving}
                >
                  {editOpen ? 'Close edit' : 'Edit'}
                </button>
                <button
                  type="button"
                  className="cp-btn cp-btn--danger"
                  onClick={() => void onDelete(detail)}
                  disabled={detailLoading || editSaving}
                >
                  Delete
                </button>
              </div>
            ) : null}
          </div>

          {selectedId == null ? <p className="cp-muted">Select an application to view details.</p> : null}
          {detailLoading ? <LoadingState label="Loading detail…" /> : null}
          {detailError ? <ErrorMessage message={detailError} onDismiss={() => setDetailError(null)} /> : null}

          {!detailLoading && detail ? (
            <div>
              <div className="cp-jl-detail__title">
                {detail.role_title}{' '}
                <span className={badgeClass(detail.status)} style={{ marginLeft: '0.5rem' }}>
                  {detail.status.replace('_', ' ')}
                </span>
              </div>
              <div className="cp-jl-detail__meta">{detail.company_name}</div>
              <div className="cp-jl-detail__meta">
                <a href={detail.job_url} target="_blank" rel="noreferrer">
                  {detail.job_url}
                </a>
              </div>

              <div className="cp-jl-detail__block">
                <div className="cp-jl-detail__label">Tracking</div>
                <div>
                  {detail.applied_date ? <div>Applied: {detail.applied_date}</div> : <div>Applied: —</div>}
                  {detail.follow_up_date ? <div>Follow-up: {detail.follow_up_date}</div> : <div>Follow-up: —</div>}
                </div>
              </div>

              {detail.tech_stack?.length ? (
                <div className="cp-jl-detail__block">
                  <div className="cp-jl-detail__label">Tech stack</div>
                  <div>{detail.tech_stack.join(', ')}</div>
                </div>
              ) : null}

              {detail.salary_range ? (
                <div className="cp-jl-detail__block">
                  <div className="cp-jl-detail__label">Salary</div>
                  <div>{detail.salary_range}</div>
                </div>
              ) : null}

              {detail.notes ? (
                <div className="cp-jl-detail__block">
                  <div className="cp-jl-detail__label">Notes</div>
                  <div style={{ whiteSpace: 'pre-wrap' }}>{detail.notes}</div>
                </div>
              ) : null}

              {editOpen && edit ? (
                <div className="cp-jl-detail__block">
                  <div className="cp-jl-detail__label">Edit</div>
                  {editError ? <ErrorMessage message={editError} onDismiss={() => setEditError(null)} /> : null}

                  <div className="cp-form" style={{ marginTop: '0.75rem' }}>
                    <TextInput
                      id="app-edit-role"
                      label="role_title"
                      value={edit.role_title}
                      onChange={(v) => setEdit((s) => (s ? { ...s, role_title: v } : s))}
                      disabled={editSaving}
                    />
                    <TextInput
                      id="app-edit-url"
                      label="job_url"
                      value={edit.job_url}
                      onChange={(v) => setEdit((s) => (s ? { ...s, job_url: v } : s))}
                      disabled={editSaving}
                    />
                    <div className="cp-field">
                      <label htmlFor="app-edit-status">status</label>
                      <select
                        id="app-edit-status"
                        className="cp-select"
                        value={edit.status}
                        onChange={(e) => setEdit((s) => (s ? { ...s, status: e.target.value as ApplicationStatus } : s))}
                        disabled={editSaving}
                      >
                        {statuses.map((s) => (
                          <option key={s} value={s}>
                            {s.replace('_', ' ')}
                          </option>
                        ))}
                      </select>
                    </div>
                    <TagInput
                      id="app-edit-tech"
                      label="tech_stack"
                      value={edit.tech_stack}
                      onChange={(v) => setEdit((s) => (s ? { ...s, tech_stack: v } : s))}
                      disabled={editSaving}
                    />
                    <TextInput
                      id="app-edit-salary"
                      label="salary_range"
                      value={edit.salary_range}
                      onChange={(v) => setEdit((s) => (s ? { ...s, salary_range: v } : s))}
                      disabled={editSaving}
                      placeholder="e.g. 160k–220k + equity"
                    />
                    <TextInput
                      id="app-edit-applied"
                      label="applied_date"
                      value={edit.applied_date}
                      onChange={(v) => setEdit((s) => (s ? { ...s, applied_date: v } : s))}
                      disabled={editSaving}
                      type="date"
                    />
                    <TextInput
                      id="app-edit-follow"
                      label="follow_up_date"
                      value={edit.follow_up_date}
                      onChange={(v) => setEdit((s) => (s ? { ...s, follow_up_date: v } : s))}
                      disabled={editSaving}
                      type="date"
                    />
                    <TextArea
                      id="app-edit-notes"
                      label="notes"
                      value={edit.notes}
                      onChange={(v) => setEdit((s) => (s ? { ...s, notes: v } : s))}
                      disabled={editSaving}
                      rows={5}
                    />

                    <div style={{ display: 'flex', gap: '0.5rem' }}>
                      <button type="button" className="cp-btn cp-btn--primary" onClick={() => void onEditSave()} disabled={editSaving}>
                        {editSaving ? 'Saving…' : 'Save changes'}
                      </button>
                      <button type="button" className="cp-btn cp-btn--subtle" onClick={resetEdit} disabled={editSaving}>
                        Reset
                      </button>
                    </div>
                  </div>
                </div>
              ) : null}
            </div>
          ) : null}
        </section>
      </div>
    </div>
  )
}

