import React, { useEffect, useMemo, useState } from 'react'
import { ApiClientError } from '../api/client'
import * as tcApi from '../api/targetCompanies'
import type { CreateTargetCompanyRequest, TargetCompanyDto } from '../types/targetCompany'
import { ErrorMessage } from '../components/ui/ErrorMessage'
import { LoadingState } from '../components/ui/LoadingState'
import { TextInput } from '../components/ui/TextInput'
import { TextArea } from '../components/ui/TextArea'
import { TagInput } from '../components/ui/TagInput'

type FormState = {
  name: string
  careers_url: string
  keywords: string[]
  locations: string[]
  notes: string
  active: boolean
}

function emptyForm(): FormState {
  return {
    name: '',
    careers_url: '',
    keywords: [],
    locations: [],
    notes: '',
    active: true,
  }
}

function toForm(c: TargetCompanyDto): FormState {
  return {
    name: c.name ?? '',
    careers_url: c.careers_url ?? '',
    keywords: c.keywords ?? [],
    locations: c.locations ?? [],
    notes: c.notes ?? '',
    active: Boolean(c.active),
  }
}

function isValidHttpUrl(raw: string): boolean {
  try {
    const u = new URL(raw)
    return u.protocol === 'http:' || u.protocol === 'https:'
  } catch {
    return false
  }
}

export function TargetCompaniesPage() {
  const [items, setItems] = useState<TargetCompanyDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [showInactive, setShowInactive] = useState(false)

  const [saving, setSaving] = useState(false)
  const [formError, setFormError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})

  const [editingId, setEditingId] = useState<number | null>(null)
  const [form, setForm] = useState<FormState>(() => emptyForm())

  const activeCount = useMemo(() => items.filter((i) => i.active).length, [items])
  const visibleItems = useMemo(() => (showInactive ? items : items.filter((i) => i.active)), [items, showInactive])

  function resetForm() {
    setEditingId(null)
    setForm(emptyForm())
    setFormError(null)
    setFieldErrors({})
  }

  async function refresh() {
    setLoading(true)
    setError(null)
    try {
      const data = await tcApi.listTargetCompanies()
      setItems(data)
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to load target companies.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function validateLocal(next: FormState): Record<string, string> {
    const fe: Record<string, string> = {}
    if (!next.name.trim()) fe.name = 'Name is required'
    if (!next.careers_url.trim()) fe.careers_url = 'Careers URL is required'
    else if (!isValidHttpUrl(next.careers_url.trim())) fe.careers_url = 'Must be a valid http(s) URL'
    if (!next.keywords || next.keywords.length === 0) fe.keywords = 'At least one keyword is required'
    if (next.keywords?.some((k) => !k.trim())) fe.keywords = 'Keywords must not contain blanks'
    if (next.locations?.some((l) => !l.trim())) fe.locations = 'Locations must not contain blanks'
    return fe
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setFormError(null)

    const next = {
      ...form,
      name: form.name.trim(),
      careers_url: form.careers_url.trim(),
      keywords: form.keywords.map((k) => k.trim()).filter((k) => k.length > 0),
      locations: form.locations.map((l) => l.trim()).filter((l) => l.length > 0),
      notes: form.notes.trim(),
    }

    const fe = validateLocal(next)
    setFieldErrors(fe)
    if (Object.keys(fe).length > 0) return

    setSaving(true)
    try {
      if (editingId == null) {
        const payload: CreateTargetCompanyRequest = {
          name: next.name,
          careers_url: next.careers_url,
          keywords: next.keywords,
          locations: next.locations,
          active: next.active,
          notes: next.notes ? next.notes : null,
        }
        const created = await tcApi.createTargetCompany(payload)
        setItems((prev) => [created, ...prev])
        resetForm()
      } else {
        const updated = await tcApi.patchTargetCompany(editingId, {
          name: next.name,
          careers_url: next.careers_url,
          keywords: next.keywords,
          locations: next.locations,
          active: next.active,
          notes: next.notes,
        })
        setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)))
        resetForm()
      }
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Save failed.'
      setFormError(msg)
    } finally {
      setSaving(false)
    }
  }

  async function onToggleActive(c: TargetCompanyDto) {
    setError(null)
    try {
      const updated = await tcApi.patchTargetCompany(c.id, { active: !c.active })
      setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)))
      if (editingId === c.id) {
        setForm(toForm(updated))
      }
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Failed to update company.'
      setError(msg)
    }
  }

  async function onDelete(c: TargetCompanyDto) {
    if (!confirm(`Permanently delete ${c.name}? This cannot be undone.\n(Job leads tied to this company are removed.)`))
      return
    setError(null)
    try {
      await tcApi.deleteTargetCompany(c.id)
      await refresh()
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Delete failed.'
      setError(msg)
    }
  }

  function startEdit(c: TargetCompanyDto) {
    setEditingId(c.id)
    setForm(toForm(c))
    setFormError(null)
    setFieldErrors({})
  }

  return (
    <div>
      <h1 className="cp-page-title">Target companies</h1>
      <p className="cp-muted">
        Manage companies and the public careers pages you want CareerPilot to track. ({activeCount} active)
      </p>

      {error ? <ErrorMessage message={error} onDismiss={() => setError(null)} /> : null}

      <div className="cp-split">
        <section className="cp-card">
          <div className="cp-card__header">
            <div className="cp-card__title">{editingId == null ? 'Add company' : 'Edit company'}</div>
            {editingId != null ? (
              <button type="button" className="cp-btn cp-btn--subtle" onClick={resetForm} disabled={saving}>
                Cancel
              </button>
            ) : null}
          </div>

          {formError ? <ErrorMessage message={formError} onDismiss={() => setFormError(null)} /> : null}

          <form className="cp-form" onSubmit={onSubmit}>
            <TextInput
              id="tc-name"
              label="Name"
              required
              value={form.name}
              onChange={(v) => setForm((s) => ({ ...s, name: v }))}
              placeholder="Acme Corp"
              disabled={saving}
              error={fieldErrors.name}
            />
            <TextInput
              id="tc-url"
              label="Careers URL"
              required
              value={form.careers_url}
              onChange={(v) => setForm((s) => ({ ...s, careers_url: v }))}
              placeholder="https://example.com/careers"
              disabled={saving}
              error={fieldErrors.careers_url}
            />
            <TagInput
              id="tc-keywords"
              label="Keywords"
              required
              value={form.keywords}
              onChange={(v) => setForm((s) => ({ ...s, keywords: v }))}
              placeholder="kotlin, backend, platform"
              disabled={saving}
              error={fieldErrors.keywords}
              helperText="Comma-separated. Used to match roles/tech you care about."
            />
            <TagInput
              id="tc-locations"
              label="Locations"
              value={form.locations}
              onChange={(v) => setForm((s) => ({ ...s, locations: v }))}
              placeholder="SF, Remote"
              disabled={saving}
              error={fieldErrors.locations}
              helperText="Optional. Comma-separated."
            />
            <TextArea
              id="tc-notes"
              label="Notes"
              value={form.notes}
              onChange={(v) => setForm((s) => ({ ...s, notes: v }))}
              placeholder="Anything you want to remember about this company…"
              disabled={saving}
            />

            <label className="cp-check">
              <input
                type="checkbox"
                checked={form.active}
                disabled={saving}
                onChange={(e) => setForm((s) => ({ ...s, active: e.target.checked }))}
              />
              Active
            </label>

            <button type="submit" className="cp-btn cp-btn--primary" disabled={saving}>
              {saving ? 'Saving…' : editingId == null ? 'Add company' : 'Save changes'}
            </button>
          </form>
        </section>

        <section className="cp-card">
          <div className="cp-card__header">
            <div className="cp-card__title">Companies</div>
            <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
              <label className="cp-check" style={{ color: '#0f172a' }}>
                <input
                  type="checkbox"
                  checked={showInactive}
                  onChange={(e) => setShowInactive(e.target.checked)}
                  disabled={loading}
                />
                Show inactive
              </label>
              <button type="button" className="cp-btn cp-btn--subtle" onClick={() => void refresh()} disabled={loading}>
                Refresh
              </button>
            </div>
          </div>

          {loading ? <LoadingState label="Loading companies…" /> : null}

          {!loading && visibleItems.length === 0 ? (
            <p className="cp-muted">No companies yet. Add your first one on the left.</p>
          ) : null}

          {!loading && visibleItems.length > 0 ? (
            <div className="cp-table">
              {visibleItems.map((c) => (
                <div key={c.id} className={`cp-row${c.active ? '' : ' cp-row--inactive'}`}>
                  <div className="cp-row__main">
                    <div className="cp-row__title">
                      {c.name}
                      <span className={`cp-badge${c.active ? ' cp-badge--on' : ''}`}>{c.active ? 'Active' : 'Inactive'}</span>
                    </div>
                    <div className="cp-row__meta">
                      <a href={c.careers_url} target="_blank" rel="noreferrer">
                        {c.careers_url}
                      </a>
                    </div>
                    {c.keywords?.length ? <div className="cp-row__tags">Keywords: {c.keywords.join(', ')}</div> : null}
                    {c.locations?.length ? <div className="cp-row__tags">Locations: {c.locations.join(', ')}</div> : null}
                    {c.notes ? <div className="cp-row__notes">{c.notes}</div> : null}
                  </div>
                  <div className="cp-row__actions">
                    <button type="button" className="cp-btn cp-btn--subtle" onClick={() => startEdit(c)}>
                      Edit
                    </button>
                    <button type="button" className="cp-btn cp-btn--subtle" onClick={() => void onToggleActive(c)}>
                      {c.active ? 'Deactivate' : 'Activate'}
                    </button>
                    <button
                      type="button"
                      className="cp-btn cp-btn--danger"
                      onClick={() => void onDelete(c)}
                      title="Removes this company record from the database (distinct from Deactivate)."
                    >
                      Remove
                    </button>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
        </section>
      </div>
    </div>
  )
}
