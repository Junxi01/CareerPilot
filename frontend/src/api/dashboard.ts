import { apiGet } from './client'
import type {
  DashboardFollowUpDto,
  DashboardPrepSummaryDto,
  DashboardRecentJobLeadsDto,
  DashboardStatsDto,
  DashboardUpcomingInterviewsDto,
} from '../types/dashboard'

export function fetchDashboardStats(): Promise<DashboardStatsDto> {
  return apiGet<DashboardStatsDto>('/api/dashboard/stats')
}

export function fetchDashboardFollowUps(): Promise<DashboardFollowUpDto[]> {
  return apiGet<DashboardFollowUpDto[]>('/api/dashboard/follow-ups')
}

export function fetchDashboardRecentJobLeads(): Promise<DashboardRecentJobLeadsDto> {
  return apiGet<DashboardRecentJobLeadsDto>('/api/dashboard/recent-job-leads')
}

export function fetchDashboardUpcomingInterviews(): Promise<DashboardUpcomingInterviewsDto> {
  return apiGet<DashboardUpcomingInterviewsDto>('/api/dashboard/upcoming-interviews')
}

export function fetchDashboardPrepSummary(): Promise<DashboardPrepSummaryDto> {
  return apiGet<DashboardPrepSummaryDto>('/api/dashboard/prep-summary')
}
