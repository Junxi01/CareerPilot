import React, { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiClientError } from '../api/client'
import { ErrorAlert } from '../components/ui/ErrorAlert'

export function RegisterPage() {
  const { register } = useAuth()
  const navigate = useNavigate()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await register(email.trim(), password, displayName.trim() || undefined)
      navigate('/dashboard', { replace: true })
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Could not register. Try again.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="cp-auth-page">
      <div className="cp-auth-card">
        <h1>Create account</h1>
        <p className="cp-muted">Password must be at least 8 characters.</p>
        {error ? <ErrorAlert message={error} onDismiss={() => setError(null)} /> : null}
        <form className="cp-form" onSubmit={onSubmit}>
          <div className="cp-field">
            <label htmlFor="reg-email">Email</label>
            <input
              id="reg-email"
              name="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="cp-field">
            <label htmlFor="reg-display">Display name (optional)</label>
            <input
              id="reg-display"
              name="displayName"
              type="text"
              autoComplete="name"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </div>
          <div className="cp-field">
            <label htmlFor="reg-password">Password</label>
            <input
              id="reg-password"
              name="password"
              type="password"
              autoComplete="new-password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <button type="submit" className="cp-btn cp-btn--primary" disabled={loading}>
            {loading ? 'Creating…' : 'Register'}
          </button>
        </form>
        <p className="cp-auth-footer">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </div>
  )
}
