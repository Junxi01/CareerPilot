import React from 'react'
import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { LoadingBlock } from '../ui/LoadingBlock'

export function ProtectedRoute() {
  const { user, ready } = useAuth()
  const location = useLocation()

  if (!ready) {
    return <LoadingBlock label="Restoring session…" />
  }

  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }

  return <Outlet />
}
