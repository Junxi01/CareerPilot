import { apiGet, apiJson } from './client'
import type { ApplicationDto, CreateApplicationRequest, PatchApplicationRequest } from '../types/application'

export type ApplicationFilters = {
  status?: string
  company_id?: number
  keyword?: string
}

function toQuery(filters: ApplicationFilters): string {
  const p = new URLSearchParams()
  if (filters.status) p.set('status', filters.status)
  if (filters.company_id != null) p.set('company_id', String(filters.company_id))
  if (filters.keyword) p.set('keyword', filters.keyword)
  const s = p.toString()
  return s ? `?${s}` : ''
}

export function listApplications(filters: ApplicationFilters = {}): Promise<ApplicationDto[]> {
  return apiGet<ApplicationDto[]>(`/api/applications${toQuery(filters)}`)
}

export function getApplication(id: number): Promise<ApplicationDto> {
  return apiGet<ApplicationDto>(`/api/applications/${id}`)
}

export function createApplication(req: CreateApplicationRequest): Promise<ApplicationDto> {
  return apiJson<ApplicationDto>('/api/applications', { method: 'POST', body: req })
}

export function patchApplication(id: number, req: PatchApplicationRequest): Promise<ApplicationDto> {
  return apiJson<ApplicationDto>(`/api/applications/${id}`, { method: 'PATCH', body: req })
}

export function deleteApplication(id: number): Promise<void> {
  return apiJson<void>(`/api/applications/${id}`, { method: 'DELETE' })
}

