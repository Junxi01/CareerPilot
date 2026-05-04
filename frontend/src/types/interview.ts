/** Mirrors `com.careerpilot.interviews.InterviewDto` & requests. */
export type InterviewDto = {
  id: number
  application_id: number
  round_name?: string | null
  scheduled_at?: string | null
  status: string
  notes?: string | null
}

export type CreateInterviewRequest = {
  round_name?: string | null
  scheduled_at?: string | null
  status?: string
  notes?: string | null
}

export type PatchInterviewRequest = {
  round_name?: string | null
  scheduled_at?: string | null
  status?: string | null
  notes?: string | null
}
