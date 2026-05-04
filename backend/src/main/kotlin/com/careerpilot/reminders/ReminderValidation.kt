package com.careerpilot.reminders

import java.time.Instant

data class ReminderValidationFailure(val code: String, val message: String)

object ReminderValidation {
    fun validateCreate(req: CreateReminderRequest): ReminderValidationFailure? {
        if (req.message.isBlank()) return ReminderValidationFailure("invalid_message", "message is required")
        if (parseInstant(req.due_at) == null) return ReminderValidationFailure("invalid_due_at", "due_at must be ISO-8601")
        return null
    }

    fun normalizeCreate(req: CreateReminderRequest): CreateReminderRequest =
        req.copy(
            message = req.message.trim(),
            due_at = req.due_at.trim(),
        )

    fun parseInstant(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: Exception) {
            null
        }
}
