/** Mirrors `com.careerpilot.reminders.ReminderModels`. */
export type ReminderType = 'FOLLOW_UP' | 'INTERVIEW_PREP' | 'CUSTOM'

export type ReminderDto = {
  id: number
  application_id?: number | null
  type: ReminderType
  due_at: string
  message: string
  done: boolean
}

export type CreateReminderRequest = {
  type?: ReminderType
  due_at: string
  message: string
}
