/** Mirrors backend `SettingsStatusDto`. */
export type SettingsStatusDto = {
  app_name: string
  app_version: string
  db_status: 'connected' | 'down'
  db_error: string | null
  ai_provider: string
  openai_api_key_configured: boolean
  gemini_api_key_configured: boolean
  ai_model: string | null
}
