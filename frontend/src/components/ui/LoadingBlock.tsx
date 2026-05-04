import React from 'react'

export function LoadingBlock({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="cp-loading" role="status" aria-live="polite">
      <span className="cp-spinner" aria-hidden />
      <span>{label}</span>
    </div>
  )
}
