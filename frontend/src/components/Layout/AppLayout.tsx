import React from 'react'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../../context/AuthContext'
import './AppLayout.css'

const nav = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/target-companies', label: 'Target companies' },
  { to: '/job-leads', label: 'Job leads' },
  { to: '/applications', label: 'Applications' },
  { to: '/kanban', label: 'Kanban' },
  { to: '/prep', label: 'Prep' },
  { to: '/settings', label: 'Settings' },
] as const

export function AppLayout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()

  return (
    <div className="cp-shell">
      <aside className="cp-sidebar">
        <div className="cp-brand">CareerPilot</div>
        <nav className="cp-nav" aria-label="Main">
          {nav.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => `cp-nav__link${isActive ? ' cp-nav__link--active' : ''}`}
            >
              {item.label}
            </NavLink>
          ))}
        </nav>
        <div className="cp-sidebar__footer">
          <div className="cp-user" title={user?.email}>
            {user?.displayName || user?.email || 'Account'}
          </div>
          <button
            type="button"
            className="cp-btn cp-btn--ghost"
            onClick={() => {
              logout()
              navigate('/login', { replace: true })
            }}
          >
            Log out
          </button>
        </div>
      </aside>
      <main className="cp-main">
        <Outlet />
      </main>
    </div>
  )
}
