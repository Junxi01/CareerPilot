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

export type DiscoverJobLeadsRequest = {
  company_id?: number | null
  min_match_score?: number
  max_pages_per_company?: number
  max_depth?: number
}

export type DiscoverJobLeadsResponse = {
  companies_scanned: number
  leads_found: number
  leads_created: number
  duplicates_skipped: number
  low_score_skipped: number
  fetch_errors: number
  created_items: JobLeadDto[]
}

export type RefreshInvalidJobLeadsRequest = {
  company_id?: number | null
  delete_saved?: boolean
}

export type RefreshInvalidJobLeadsResponse = {
  checked: number
  deleted: number
  kept: number
  skipped_saved: number
  uncertain: number
  deleted_items: JobLeadDto[]
}
