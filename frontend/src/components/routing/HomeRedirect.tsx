import React from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { LoadingBlock } from '../ui/LoadingBlock'

/** `/` and unknown paths: send guests to login, users to dashboard. */
export function HomeRedirect() {
  const { user, ready } = useAuth()

  if (!ready) {
    return <LoadingBlock />
  }

  if (user) {
    return <Navigate to="/dashboard" replace />
  }

  return <Navigate to="/login" replace />
}
