import { apiGet } from './client'
import type { SettingsStatusDto } from '../types/settings'

export function getSettingsStatus(): Promise<SettingsStatusDto> {
  return apiGet<SettingsStatusDto>('/api/settings/status')
}
