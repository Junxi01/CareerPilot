import React from 'react'

type Props = {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  disabled?: boolean
  rows?: number
  error?: string | null
  helperText?: string
}

export function TextArea({ id, label, value, onChange, placeholder, disabled, rows = 4, error, helperText }: Props) {
  return (
    <div className="cp-field">
      <label htmlFor={id}>{label}</label>
      <textarea
        id={id}
        value={value}
        placeholder={placeholder}
        disabled={disabled}
        rows={rows}
        aria-invalid={Boolean(error) || undefined}
        onChange={(e) => onChange(e.target.value)}
      />
      {helperText ? <div className="cp-help">{helperText}</div> : null}
      {error ? <div className="cp-error">{error}</div> : null}
    </div>
  )
}
