import React, { useEffect, useMemo, useState } from 'react'
import * as appApi from '../api/applications'
import { ApiClientError } from '../api/client'
import type { ApplicationDto, ApplicationStatus } from '../types/application'
import { ErrorMessage } from '../components/ui/ErrorMessage'
import { LoadingState } from '../components/ui/LoadingState'

const columns: { status: ApplicationStatus; label: string }[] = [
  { status: 'SAVED', label: 'Saved' },
  { status: 'APPLIED', label: 'Applied' },
  { status: 'ONLINE_ASSESSMENT', label: 'Online assessment' },
  { status: 'INTERVIEW', label: 'Interview' },
  { status: 'OFFER', label: 'Offer' },
  { status: 'REJECTED', label: 'Rejected' },
  { status: 'GHOSTED', label: 'Ghosted' },
  { status: 'ARCHIVED', label: 'Archived' },
]

const order: ApplicationStatus[] = columns.map((c) => c.status)

function colIndex(status: ApplicationStatus): number {
  const i = order.indexOf(status)
  return i < 0 ? 0 : i
}

function fmtDateLabel(v: string | null | undefined): string {
  const s = (v ?? '').trim()
  return s ? s : '—'
}

export function KanbanPage() {
  const [items, setItems] = useState<ApplicationDto[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [updatingId, setUpdatingId] = useState<number | null>(null)

  const grouped = useMemo(() => {
    const map = new Map<ApplicationStatus, ApplicationDto[]>()
    for (const c of columns) map.set(c.status, [])
    for (const a of items) {
      const s = a.status
      const arr = map.get(s) ?? []
      arr.push(a)
      map.set(s, arr)
    }
    return map
  }, [items])

  async function refresh(): Promise<void> {
    setLoading(true)
    setError(null)
    try {
      const data = await appApi.listApplications({})
      setItems(data)
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to load applications.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function setStatus(id: number, next: ApplicationStatus): Promise<void> {
    setUpdatingId(id)
    try {
      const updated = await appApi.patchApplication(id, { status: next })
      setItems((prev) => prev.map((x) => (x.id === id ? updated : x)))
    } catch (e) {
      const msg = e instanceof ApiClientError ? e.message : 'Failed to update status.'
      setError(msg)
    } finally {
      setUpdatingId(null)
    }
  }

  function moveLeft(a: ApplicationDto): void {
    const idx = colIndex(a.status)
    if (idx <= 0) return
    void setStatus(a.id, order[idx - 1]!)
  }

  function moveRight(a: ApplicationDto): void {
    const idx = colIndex(a.status)
    if (idx >= order.length - 1) return
    void setStatus(a.id, order[idx + 1]!)
  }

  const [draggingId, setDraggingId] = useState<number | null>(null)

  function onDragStart(e: React.DragEvent, a: ApplicationDto) {
    setDraggingId(a.id)
    try {
      e.dataTransfer.setData('text/plain', String(a.id))
      e.dataTransfer.effectAllowed = 'move'
    } catch {
      // ignore; button fallback still works
    }
  }

  function onDragEnd() {
    setDraggingId(null)
  }

  function onDropToStatus(e: React.DragEvent, status: ApplicationStatus) {
    e.preventDefault()
    const raw = e.dataTransfer.getData('text/plain')
    const id = Number(raw)
    if (!Number.isFinite(id)) return
    const a = items.find((x) => x.id === id)
    if (!a || a.status === status) return
    void setStatus(id, status)
  }

  function onDragOver(e: React.DragEvent) {
    e.preventDefault()
    e.dataTransfer.dropEffect = 'move'
  }

  return (
    <div>
      <h1 className="cp-page-title">Kanban</h1>
      <p className="cp-muted">Drag cards between columns, or use arrows to change status.</p>

      {error ? <ErrorMessage message={error} onDismiss={() => setError(null)} /> : null}

      <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', marginBottom: '0.75rem' }}>
        <button type="button" className="cp-btn cp-btn--subtle" onClick={() => void refresh()} disabled={loading}>
          Refresh
        </button>
        {updatingId != null ? <span className="cp-help">Updating application #{updatingId}…</span> : null}
      </div>

      {loading ? <LoadingState label="Loading applications…" /> : null}

      {!loading ? (
        <div className="cp-kanban">
          {columns.map((c) => {
            const list = grouped.get(c.status) ?? []
            return (
              <section
                key={c.status}
                className="cp-kanban-col"
                onDragOver={onDragOver}
                onDrop={(e) => onDropToStatus(e, c.status)}
              >
                <div className="cp-kanban-col__header">
                  <div className="cp-kanban-col__title">{c.label}</div>
                  <div className="cp-kanban-col__count">{list.length}</div>
                </div>
                <div className="cp-kanban-col__body">
                  {list.length === 0 ? <div className="cp-kanban-empty">—</div> : null}
                  {list.map((a) => (
                    <article
                      key={a.id}
                      className={`cp-kanban-card${draggingId === a.id ? ' cp-kanban-card--dragging' : ''}`}
                      draggable
                      onDragStart={(e) => onDragStart(e, a)}
                      onDragEnd={onDragEnd}
                      title={`#${a.id}`}
                    >
                      <div className="cp-kanban-card__top">
                        <div className="cp-kanban-card__company">{a.company_name}</div>
                        <div className="cp-kanban-card__actions">
                          <button
                            type="button"
                            className="cp-btn cp-btn--subtle"
                            onClick={() => moveLeft(a)}
                            disabled={updatingId === a.id || colIndex(a.status) === 0}
                            title="Move left"
                          >
                            ←
                          </button>
                          <button
                            type="button"
                            className="cp-btn cp-btn--subtle"
                            onClick={() => moveRight(a)}
                            disabled={updatingId === a.id || colIndex(a.status) === order.length - 1}
                            title="Move right"
                          >
                            →
                          </button>
                        </div>
                      </div>
                      <div className="cp-kanban-card__role">{a.role_title}</div>
                      <div className="cp-kanban-card__meta">
                        <span>Applied: {fmtDateLabel(a.applied_date)}</span>
                        <span>Follow-up: {fmtDateLabel(a.follow_up_date)}</span>
                      </div>
                    </article>
                  ))}
                </div>
              </section>
            )
          })}
        </div>
      ) : null}
    </div>
  )
}

