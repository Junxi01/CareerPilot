package com.careerpilot.targetcompanies

import java.net.URI

data class ValidationFailure(
    val code: String,
    val message: String,
)

object TargetCompanyValidation {
    fun validateCreate(req: CreateTargetCompanyRequest): ValidationFailure? {
        if (req.name.isBlank()) return ValidationFailure("invalid_name", "Name is required")
        if (!isValidCareersUrl(req.careers_url)) return ValidationFailure("invalid_careers_url", "careers_url must be a valid http(s) URL")
        val keywords = normalizeList(req.keywords)
        if (keywords.isEmpty()) return ValidationFailure("invalid_keywords", "keywords must be a non-empty array of strings")
        if (keywords.any { it.isBlank() }) return ValidationFailure("invalid_keywords", "keywords must not contain blank items")
        return null
    }

    fun validatePatch(req: PatchTargetCompanyRequest): ValidationFailure? {
        if (req.name != null && req.name.isBlank()) return ValidationFailure("invalid_name", "name must not be blank")
        if (req.careers_url != null && !isValidCareersUrl(req.careers_url)) return ValidationFailure("invalid_careers_url", "careers_url must be a valid http(s) URL")
        if (req.keywords != null) {
            val keywords = normalizeList(req.keywords)
            if (keywords.isEmpty()) return ValidationFailure("invalid_keywords", "keywords must be a non-empty array of strings")
            if (keywords.any { it.isBlank() }) return ValidationFailure("invalid_keywords", "keywords must not contain blank items")
        }
        if (req.locations != null) {
            // allow empty, but disallow blanks
            val locations = normalizeList(req.locations)
            if (locations.any { it.isBlank() }) return ValidationFailure("invalid_locations", "locations must not contain blank items")
        }
        return null
    }

    fun normalizeCreate(req: CreateTargetCompanyRequest): CreateTargetCompanyRequest =
        req.copy(
            name = req.name.trim(),
            careers_url = req.careers_url.trim(),
            keywords = normalizeList(req.keywords).map { it.trim() },
            locations = normalizeList(req.locations).map { it.trim() },
            notes = req.notes?.trim()?.takeIf { it.isNotBlank() },
        )

    fun normalizePatch(req: PatchTargetCompanyRequest): TargetCompanyPatch =
        TargetCompanyPatch(
            name = req.name?.trim()?.takeIf { it.isNotBlank() },
            careersUrl = req.careers_url?.trim(),
            keywords = req.keywords?.let { normalizeList(it).map(String::trim) },
            locations = req.locations?.let { normalizeList(it).map(String::trim) },
            active = req.active,
            notes = req.notes?.trim(),
        )

    private fun normalizeList(items: List<String>): List<String> = items.map { it.trim() }.filter { it.isNotBlank() }

    private fun isValidCareersUrl(raw: String): Boolean {
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

