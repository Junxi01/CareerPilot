/** Mirrors `com.careerpilot.applications.ApplicationStatus` & DTOs. */
export type ApplicationStatus =
  | 'SAVED'
  | 'APPLIED'
  | 'ONLINE_ASSESSMENT'
  | 'INTERVIEW'
  | 'OFFER'
  | 'REJECTED'
  | 'GHOSTED'
  | 'ARCHIVED'

export type ApplicationDto = {
  id: number
  company_id: number
  company_name: string
  job_lead_id?: number | null
  role_title: string
  job_url: string
  status: ApplicationStatus
  tech_stack?: string[]
  salary_range?: string | null
  applied_date?: string | null
  follow_up_date?: string | null
  notes?: string | null
}

export type CreateApplicationRequest = {
  company_id?: number | null
  company_name?: string | null
  role_title: string
  job_url: string
  status?: ApplicationStatus
  tech_stack?: string[]
  salary_range?: string | null
  applied_date?: string | null
  follow_up_date?: string | null
  notes?: string | null
}

export type PatchApplicationRequest = {
  company_id?: number | null
  role_title?: string | null
  job_url?: string | null
  status?: ApplicationStatus | null
  tech_stack?: string[] | null
  salary_range?: string | null
  applied_date?: string | null
  follow_up_date?: string | null
  notes?: string | null
}
