import { apiGet, apiJson } from './client'
import type {
  CreateTargetCompanyRequest,
  PatchTargetCompanyRequest,
  TargetCompanyDto,
} from '../types/targetCompany'

export function listTargetCompanies(): Promise<TargetCompanyDto[]> {
  return apiGet<TargetCompanyDto[]>('/api/target-companies')
}

export function createTargetCompany(req: CreateTargetCompanyRequest): Promise<TargetCompanyDto> {
  return apiJson<TargetCompanyDto>('/api/target-companies', { method: 'POST', body: req })
}

export function patchTargetCompany(id: number, req: PatchTargetCompanyRequest): Promise<TargetCompanyDto> {
  return apiJson<TargetCompanyDto>(`/api/target-companies/${id}`, { method: 'PATCH', body: req })
}

/** Permanently deletes the target company (`PATCH active:false` to deactivate without removing). Uses POST so proxies/clients mishandling DELETE still work. */
export function deleteTargetCompany(id: number): Promise<void> {
  return apiJson<void>(`/api/target-companies/${id}/delete`, { method: 'POST', body: {} })
}
