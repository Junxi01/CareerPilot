import { apiGet, apiJson } from './client'
import type { InterviewPlanDetailDto, PrepTaskDto } from '../types/interviewPlan'

/** Latest interview plan for this job lead (404 if none). */
export function getInterviewPlanForJobLead(jobLeadId: number): Promise<InterviewPlanDetailDto> {
  return apiGet<InterviewPlanDetailDto>(`/api/job-leads/${jobLeadId}/interview-plan`)
}

export function completePrepTask(taskId: number): Promise<PrepTaskDto> {
  return apiJson<PrepTaskDto>(`/api/prep/tasks/${taskId}/complete`, { method: 'PATCH', body: {} })
}
