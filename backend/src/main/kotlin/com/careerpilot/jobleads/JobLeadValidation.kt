package com.careerpilot.jobleads

import java.net.URI
import java.time.Instant

data class JobLeadValidationFailure(
    val code: String,
    val message: String,
)

object JobLeadValidation {
    fun validateCreate(req: CreateJobLeadRequest): JobLeadValidationFailure? {
        if (req.company_id <= 0) return JobLeadValidationFailure("invalid_company_id", "company_id must be a positive integer")
        if (req.role_title.isBlank()) return JobLeadValidationFailure("invalid_role_title", "role_title is required")
        if (!isValidHttpUrl(req.job_url)) return JobLeadValidationFailure("invalid_job_url", "job_url must be a valid http(s) URL")
        if (req.matched_keywords.any { it.isBlank() }) return JobLeadValidationFailure("invalid_matched_keywords", "matched_keywords must not contain blank items")
        if (req.match_score != null && (req.match_score < 0.0 || req.match_score > 100.0)) {
            return JobLeadValidationFailure("invalid_match_score", "match_score must be between 0 and 100")
        }
        if (req.discovered_at != null && parseInstant(req.discovered_at) == null) {
            return JobLeadValidationFailure("invalid_discovered_at", "discovered_at must be an ISO-8601 timestamp")
        }
        return null
    }

    fun validatePatch(req: PatchJobLeadRequest): JobLeadValidationFailure? {
        if (req.role_title != null && req.role_title.isBlank()) return JobLeadValidationFailure("invalid_role_title", "role_title must not be blank")
        if (req.job_url != null && !isValidHttpUrl(req.job_url)) return JobLeadValidationFailure("invalid_job_url", "job_url must be a valid http(s) URL")
        if (req.matched_keywords != null && req.matched_keywords.any { it.isBlank() }) {
            return JobLeadValidationFailure("invalid_matched_keywords", "matched_keywords must not contain blank items")
        }
        if (req.match_score != null && (req.match_score < 0.0 || req.match_score > 100.0)) {
            return JobLeadValidationFailure("invalid_match_score", "match_score must be between 0 and 100")
        }
        if (req.discovered_at != null && parseInstant(req.discovered_at) == null) {
            return JobLeadValidationFailure("invalid_discovered_at", "discovered_at must be an ISO-8601 timestamp")
        }
        return null
    }

    fun normalizeCreate(req: CreateJobLeadRequest): CreateJobLeadRequest =
        req.copy(
            role_title = req.role_title.trim(),
            job_url = req.job_url.trim(),
            location = req.location?.trim()?.takeIf { it.isNotBlank() },
            raw_description = req.raw_description?.trim()?.takeIf { it.isNotBlank() },
            matched_keywords = req.matched_keywords.map { it.trim() }.filter { it.isNotBlank() },
        )

    fun normalizePatch(req: PatchJobLeadRequest): JobLeadPatch =
        JobLeadPatch(
            roleTitle = req.role_title?.trim()?.takeIf { it.isNotBlank() },
            jobUrl = req.job_url?.trim(),
            location = req.location?.trim(),
            rawDescription = req.raw_description?.trim(),
            matchedKeywords = req.matched_keywords?.map { it.trim() }?.filter { it.isNotBlank() },
            matchScore = req.match_score,
            discoveredAtIso = req.discovered_at,
            savedToApplications = req.saved_to_applications,
        )

    fun defaultDiscoveredAtIso(req: CreateJobLeadRequest): String =
        req.discovered_at ?: Instant.now().toString()

    private fun parseInstant(raw: String): Instant? =
        try {
            Instant.parse(raw)
        } catch (_: Throwable) {
            null
        }

    private fun isValidHttpUrl(raw: String): Boolean {
        val s = raw.trim()
        return try {
            val uri = URI(s)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host
            (scheme == "http" || scheme == "https") && !host.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }
    }
}

