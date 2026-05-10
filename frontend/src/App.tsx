import React from 'react'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { ProtectedRoute } from './components/routing/ProtectedRoute'
import { GuestRoute } from './components/routing/GuestRoute'
import { HomeRedirect } from './components/routing/HomeRedirect'
import { AppLayout } from './components/Layout/AppLayout'
import { LoginPage } from './pages/LoginPage'
import { RegisterPage } from './pages/RegisterPage'
import { DashboardPage } from './pages/DashboardPage'
import { TargetCompaniesPage } from './pages/TargetCompaniesPage'
import { JobLeadsPage } from './pages/JobLeadsPage'
import { ApplicationsPage } from './pages/ApplicationsPage'
import { KanbanPage } from './pages/KanbanPage'
import { PlaceholderPage } from './pages/PlaceholderPage'
import { SettingsPage } from './pages/SettingsPage'

export function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<HomeRedirect />} />

          <Route element={<GuestRoute />}>
            <Route path="/login" element={<LoginPage />} />
            <Route path="/register" element={<RegisterPage />} />
          </Route>

          <Route element={<ProtectedRoute />}>
            <Route element={<AppLayout />}>
              <Route path="/dashboard" element={<DashboardPage />} />
              <Route path="/target-companies" element={<TargetCompaniesPage />} />
              <Route path="/job-leads" element={<JobLeadsPage />} />
              <Route path="/applications" element={<ApplicationsPage />} />
              <Route path="/kanban" element={<KanbanPage />} />
              <Route
                path="/prep"
                element={<PlaceholderPage title="Interview prep" description="Prep tasks tied to interview plans will show here." />}
              />
              <Route path="/settings" element={<SettingsPage />} />
            </Route>
          </Route>

          <Route path="*" element={<HomeRedirect />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
