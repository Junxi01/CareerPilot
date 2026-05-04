package com.careerpilot.reminders

import kotlinx.serialization.Serializable

@Serializable
enum class ReminderType {
    FOLLOW_UP,
    INTERVIEW_PREP,
    CUSTOM,
}

@Serializable
data class ReminderDto(
    val id: Long,
    val application_id: Long? = null,
    val type: ReminderType,
    /** ISO-8601 instant (UTC), e.g. `2026-05-10T15:00:00Z` */
    val due_at: String,
    val message: String,
    val done: Boolean,
)

@Serializable
data class CreateReminderRequest(
    val type: ReminderType = ReminderType.CUSTOM,
    val due_at: String,
    val message: String,
)

data class ReminderRecord(
    val id: Long,
    val applicationId: Long?,
    val type: ReminderType,
    val dueAtIso: String,
    val message: String,
    val done: Boolean,
) {
    fun toDto(): ReminderDto =
        ReminderDto(
            id = id,
            application_id = applicationId,
            type = type,
            due_at = dueAtIso,
            message = message,
            done = done,
        )
}
