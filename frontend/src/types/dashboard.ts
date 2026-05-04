import type { InterviewDto } from './interview'
import type { JobLeadDto } from './jobLead'

/** Mirrors `com.careerpilot.dashboard.DashboardModels`. */
export type DashboardStatsDto = {
  total_applications: number
  applications_this_week: number
  interviews_count: number
  offers_count: number
  rejections_count: number
  response_rate: number
  follow_ups_due: number
  job_leads_discovered_this_week: number
  prep_tasks_due_today: number
}

export type DashboardFollowUpDto = {
  kind: string
  id: number
  application_id?: number | null
  title: string
  due: string
  company_name?: string | null
}

export type PrepSummaryItemDto = {
  id: number
  application_id: number
  company_name: string
  role_title: string
  label: string
  due_date?: string | null
  status: string
}

export type DashboardPrepSummaryDto = {
  items: PrepSummaryItemDto[]
}

export type DashboardRecentJobLeadsDto = {
  items: JobLeadDto[]
}

export type DashboardUpcomingInterviewsDto = {
  items: InterviewDto[]
}
