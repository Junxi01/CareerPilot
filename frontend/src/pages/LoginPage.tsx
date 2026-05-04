import React, { useState } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../context/AuthContext'
import { ApiClientError } from '../api/client'
import { ErrorAlert } from '../components/ui/ErrorAlert'

export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/dashboard'

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await login(email.trim(), password)
      navigate(from, { replace: true })
    } catch (err) {
      const msg = err instanceof ApiClientError ? err.message : 'Could not sign in. Try again.'
      setError(msg)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="cp-auth-page">
      <div className="cp-auth-card">
        <h1>Sign in</h1>
        <p className="cp-muted">Use your CareerPilot account.</p>
        {error ? <ErrorAlert message={error} onDismiss={() => setError(null)} /> : null}
        <form className="cp-form" onSubmit={onSubmit}>
          <div className="cp-field">
            <label htmlFor="login-email">Email</label>
            <input
              id="login-email"
              name="email"
              type="email"
              autoComplete="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
          <div className="cp-field">
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              minLength={8}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
          <button type="submit" className="cp-btn cp-btn--primary" disabled={loading}>
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="cp-auth-footer">
          No account? <Link to="/register">Create one</Link>
        </p>
      </div>
    </div>
  )
}
