import { apiGet, apiJson } from './client'
import type { ApplicationDto } from '../types/application'
import type { CreateJobLeadRequest, JobLeadDto } from '../types/jobLead'

export type JobLeadsFilters = {
  company_id?: number
  keyword?: string
  min_match_score?: number
  saved_to_applications?: boolean
}

function toQuery(filters: JobLeadsFilters): string {
  const p = new URLSearchParams()
  if (filters.company_id != null) p.set('company_id', String(filters.company_id))
  if (filters.keyword) p.set('keyword', filters.keyword)
  if (filters.min_match_score != null) p.set('min_match_score', String(filters.min_match_score))
  if (filters.saved_to_applications != null) p.set('saved_to_applications', String(filters.saved_to_applications))
  const s = p.toString()
  return s ? `?${s}` : ''
}

/** URL query fragment for debugging/UI, without leading `?` (may be empty). */
export function jobLeadsFiltersQueryString(filters: JobLeadsFilters): string {
  const p = new URLSearchParams()
  if (filters.company_id != null) p.set('company_id', String(filters.company_id))
  if (filters.keyword) p.set('keyword', filters.keyword)
  if (filters.min_match_score != null) p.set('min_match_score', String(filters.min_match_score))
  if (filters.saved_to_applications != null) p.set('saved_to_applications', String(filters.saved_to_applications))
  return p.toString()
}

export function listJobLeads(filters: JobLeadsFilters = {}): Promise<JobLeadDto[]> {
  return apiGet<JobLeadDto[]>(`/api/job-leads${toQuery(filters)}`)
}

export function getJobLead(id: number): Promise<JobLeadDto> {
  return apiGet<JobLeadDto>(`/api/job-leads/${id}`)
}

export function createJobLead(req: CreateJobLeadRequest): Promise<JobLeadDto> {
  return apiJson<JobLeadDto>('/api/job-leads', { method: 'POST', body: req })
}

export function saveLeadAsApplication(id: number): Promise<ApplicationDto> {
  return apiJson<ApplicationDto>(`/api/job-leads/${id}/save-as-application`, { method: 'POST', body: {} })
}

