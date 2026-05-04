/** Mirrors `com.careerpilot.jobleads.JobLeadDto` & related requests. */
export type JobLeadDto = {
  id: number
  company_id: number
  company_name: string
  role_title: string
  job_url: string
  location?: string | null
  raw_description?: string | null
  matched_keywords: string[]
  match_score?: number | null
  discovered_at: string
  saved_to_applications: boolean
}

export type CreateJobLeadRequest = {
  company_id: number
  role_title: string
  job_url: string
  location?: string | null
  raw_description?: string | null
  matched_keywords?: string[]
  match_score?: number | null
  discovered_at?: string | null
  saved_to_applications?: boolean
}

export type PatchJobLeadRequest = {
  role_title?: string | null
  job_url?: string | null
  location?: string | null
  raw_description?: string | null
  matched_keywords?: string[] | null
  match_score?: number | null
  discovered_at?: string | null
  saved_to_applications?: boolean | null
}
