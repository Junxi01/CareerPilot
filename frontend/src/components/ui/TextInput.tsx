import React from 'react'

type Props = {
  id: string
  label: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
  type?: React.HTMLInputTypeAttribute
  required?: boolean
  disabled?: boolean
  error?: string | null
  helperText?: string
}

export function TextInput({
  id,
  label,
  value,
  onChange,
  placeholder,
  type = 'text',
  required,
  disabled,
  error,
  helperText,
}: Props) {
  return (
    <div className="cp-field">
      <label htmlFor={id}>
        {label}
        {required ? ' *' : ''}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        placeholder={placeholder}
        required={required}
        disabled={disabled}
        aria-invalid={Boolean(error) || undefined}
        onChange={(e) => onChange(e.target.value)}
      />
      {helperText ? <div className="cp-help">{helperText}</div> : null}
      {error ? <div className="cp-error">{error}</div> : null}
    </div>
  )
}
