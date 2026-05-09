/** Mirrors backend `PrepTaskDto`. */
export type PrepTaskDto = {
  id: number
  ai_interview_plan_id: number
  application_id: number
  label: string
  description: string | null
  due_date: string | null
  status: string
}

/** Mirrors backend `InterviewPlanDetailDto`; `plan_json` is the AI schema from `ai_interview_planner.py`. */
export type InterviewPlanDetailDto = {
  id: number
  application_id: number
  job_lead_id: number | null
  provider_mode: string
  plan_json: Record<string, unknown>
  plan_markdown: string | null
  prompt_json: unknown | null
  prep_tasks: PrepTaskDto[]
  created_at: string
  updated_at: string
}
