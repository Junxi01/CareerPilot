import React, { useEffect, useMemo, useState } from 'react'

type Props = {
  id: string
  label: string
  value: string[]
  onChange: (v: string[]) => void
  placeholder?: string
  disabled?: boolean
  error?: string | null
  helperText?: string
  /** When true, disallow empty array. */
  required?: boolean
}

function parseCommaList(raw: string): string[] {
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

export function TagInput({ id, label, value, onChange, placeholder, disabled, error, helperText, required }: Props) {
  const initial = useMemo(() => value.join(', '), [value])
  const [text, setText] = useState(initial)

  useEffect(() => {
    setText(value.join(', '))
  }, [value])

  const localError =
    error ??
    (required && parseCommaList(text).length === 0 ? `${label.toLowerCase()} is required` : null)

  return (
    <div className="cp-field">
      <label htmlFor={id}>
        {label}
        {required ? ' *' : ''}
      </label>
      <input
        id={id}
        value={text}
        placeholder={placeholder ?? 'comma,separated,tags'}
        disabled={disabled}
        aria-invalid={Boolean(localError) || undefined}
        onChange={(e) => setText(e.target.value)}
        onBlur={() => onChange(parseCommaList(text))}
      />
      {helperText ? <div className="cp-help">{helperText}</div> : null}
      {localError ? <div className="cp-error">{localError}</div> : null}
    </div>
  )
}
