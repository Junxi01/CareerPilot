import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import * as authApi from '../api/auth'
import { clearAuthToken, getAuthToken, setAuthToken } from '../lib/authToken'
import { SESSION_EXPIRED_EVENT } from '../lib/sessionEvents'
import type { PublicUser } from '../types/auth'

type AuthContextValue = {
  user: PublicUser | null
  /** Session bootstrap finished (token validated or absent). */
  ready: boolean
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName?: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<PublicUser | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    const onSessionExpired = () => {
      setUser(null)
    }
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired)
  }, [])

  useEffect(() => {
    let cancelled = false
    const run = async () => {
      const token = getAuthToken()
      if (!token) {
        if (!cancelled) {
          setUser(null)
          setReady(true)
        }
        return
      }
      try {
        const me = await authApi.fetchMe()
        if (!cancelled) setUser(me.user)
      } catch {
        clearAuthToken()
        if (!cancelled) setUser(null)
      } finally {
        if (!cancelled) setReady(true)
      }
    }
    void run()
    return () => {
      cancelled = true
    }
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const payload = await authApi.login({ email, password })
    setAuthToken(payload.token)
    setUser(payload.user)
  }, [])

  const register = useCallback(async (email: string, password: string, displayName?: string) => {
    const payload = await authApi.register({
      email,
      password,
      displayName: displayName?.trim() || null,
    })
    setAuthToken(payload.token)
    setUser(payload.user)
  }, [])

  const logout = useCallback(() => {
    clearAuthToken()
    setUser(null)
  }, [])

  const value = useMemo(
    () => ({
      user,
      ready,
      login,
      register,
      logout,
    }),
    [user, ready, login, register, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}
