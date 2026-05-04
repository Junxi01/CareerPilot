import React from 'react'
import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import { LoadingBlock } from '../ui/LoadingBlock'

/** Redirect authenticated users away from login/register. */
export function GuestRoute() {
  const { user, ready } = useAuth()

  if (!ready) {
    return <LoadingBlock />
  }

  if (user) {
    return <Navigate to="/dashboard" replace />
  }

  return <Outlet />
}
