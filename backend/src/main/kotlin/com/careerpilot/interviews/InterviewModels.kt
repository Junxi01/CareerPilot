package com.careerpilot.interviews

import kotlinx.serialization.Serializable

@Serializable
data class InterviewDto(
    val id: Long,
    val application_id: Long,
    val round_name: String? = null,
    /** ISO-8601 instant (UTC), e.g. `2026-05-10T15:00:00Z` */
    val scheduled_at: String? = null,
    val status: String,
    val notes: String? = null,
)

@Serializable
data class CreateInterviewRequest(
    val round_name: String? = null,
    val scheduled_at: String? = null,
    val status: String = "scheduled",
    val notes: String? = null,
)

@Serializable
data class PatchInterviewRequest(
    val round_name: String? = null,
    val scheduled_at: String? = null,
    val status: String? = null,
    val notes: String? = null,
)

data class InterviewRecord(
    val id: Long,
    val applicationId: Long,
    val roundName: String?,
    val scheduledAtIso: String?,
    val status: String,
    val notes: String?,
) {
    fun toDto(): InterviewDto =
        InterviewDto(
            id = id,
            application_id = applicationId,
            round_name = roundName,
            scheduled_at = scheduledAtIso,
            status = status,
            notes = notes,
        )
}
