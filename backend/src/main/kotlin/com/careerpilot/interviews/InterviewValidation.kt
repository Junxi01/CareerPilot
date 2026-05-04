package com.careerpilot.interviews

import java.time.Instant

data class InterviewValidationFailure(val code: String, val message: String)

object InterviewValidation {
    fun validateCreate(req: CreateInterviewRequest): InterviewValidationFailure? {
        req.scheduled_at?.let {
            if (parseInstant(it) == null) return InterviewValidationFailure("invalid_scheduled_at", "scheduled_at must be ISO-8601")
        }
        if (req.status.isBlank()) return InterviewValidationFailure("invalid_status", "status must not be blank")
        if (req.status.length > 32) return InterviewValidationFailure("invalid_status", "status too long")
        return null
    }

    fun validatePatch(req: PatchInterviewRequest): InterviewValidationFailure? {
        req.scheduled_at?.let {
            if (parseInstant(it) == null) return InterviewValidationFailure("invalid_scheduled_at", "scheduled_at must be ISO-8601")
        }
        req.status?.let {
            if (it.isBlank()) return InterviewValidationFailure("invalid_status", "status must not be blank")
            if (it.length > 32) return InterviewValidationFailure("invalid_status", "status too long")
        }
        return null
    }

    fun normalizeCreate(req: CreateInterviewRequest): CreateInterviewRequest =
        req.copy(
            round_name = req.round_name?.trim()?.takeIf { it.isNotBlank() },
            scheduled_at = req.scheduled_at?.trim()?.takeIf { it.isNotBlank() },
            status = req.status.trim().lowercase(),
            notes = req.notes?.trim()?.takeIf { it.isNotBlank() },
        )

    fun normalizePatch(req: PatchInterviewRequest): PatchInterviewRequest =
        PatchInterviewRequest(
            round_name = req.round_name?.trim(),
            scheduled_at = req.scheduled_at?.trim(),
            status = req.status?.trim()?.lowercase(),
            notes = req.notes?.trim(),
        )

    fun parseInstant(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: Exception) {
            null
        }
}
