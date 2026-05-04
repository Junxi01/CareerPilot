import React from 'react'

export function ErrorAlert({ message, onDismiss }: { message: string; onDismiss?: () => void }) {
  return (
    <div className="cp-alert cp-alert--error" role="alert">
      <span>{message}</span>
      {onDismiss ? (
        <button type="button" className="cp-alert__dismiss" onClick={onDismiss}>
          Dismiss
        </button>
      ) : null}
    </div>
  )
}
