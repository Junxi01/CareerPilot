package com.careerpilot.applications

import java.net.URI

data class ApplicationValidationFailure(
    val code: String,
    val message: String,
)

object ApplicationValidation {
    fun validateCreate(req: CreateApplicationRequest): ApplicationValidationFailure? {
        if (req.company_id != null && req.company_id <= 0) {
            return ApplicationValidationFailure("invalid_company_id", "company_id must be positive")
        }
        val hasId = req.company_id != null && req.company_id > 0
        val hasName = !req.company_name.isNullOrBlank()
        if (!hasId && !hasName) {
            return ApplicationValidationFailure("invalid_company", "Provide company_id or company_name")
        }
        if (hasId && hasName) {
            return ApplicationValidationFailure("invalid_company", "Provide only one of company_id or company_name")
        }
        if (req.role_title.isBlank()) return ApplicationValidationFailure("invalid_role_title", "role_title is required")
        if (!isValidHttpUrl(req.job_url)) return ApplicationValidationFailure("invalid_job_url", "job_url must be a valid http(s) URL")
        if (req.tech_stack.any { it.isBlank() }) {
            return ApplicationValidationFailure("invalid_tech_stack", "tech_stack must not contain blank items")
        }
        req.applied_date?.let { if (parseLocalDate(it) == null) return ApplicationValidationFailure("invalid_applied_date", "applied_date must be YYYY-MM-DD") }
        req.follow_up_date?.let { if (parseLocalDate(it) == null) return ApplicationValidationFailure("invalid_follow_up_date", "follow_up_date must be YYYY-MM-DD") }
        return null
    }

    fun validatePatch(req: PatchApplicationRequest): ApplicationValidationFailure? {
        if (req.company_id != null && req.company_id <= 0) {
            return ApplicationValidationFailure("invalid_company_id", "company_id must be positive")
        }
        if (req.role_title != null && req.role_title.isBlank()) {
            return ApplicationValidationFailure("invalid_role_title", "role_title must not be blank")
        }
        if (req.job_url != null && !isValidHttpUrl(req.job_url)) {
            return ApplicationValidationFailure("invalid_job_url", "job_url must be a valid http(s) URL")
        }
        if (req.tech_stack != null && req.tech_stack.any { it.isBlank() }) {
            return ApplicationValidationFailure("invalid_tech_stack", "tech_stack must not contain blank items")
        }
        req.applied_date?.let { if (parseLocalDate(it) == null) return ApplicationValidationFailure("invalid_applied_date", "applied_date must be YYYY-MM-DD") }
        req.follow_up_date?.let { if (parseLocalDate(it) == null) return ApplicationValidationFailure("invalid_follow_up_date", "follow_up_date must be YYYY-MM-DD") }
        return null
    }

    fun validateNormalizedPatch(patch: ApplicationPatch): ApplicationValidationFailure? {
        if (patch.companyId != null && patch.companyId <= 0) {
            return ApplicationValidationFailure("invalid_company_id", "company_id must be positive")
        }
        if (patch.roleTitle != null && patch.roleTitle.isBlank()) {
            return ApplicationValidationFailure("invalid_role_title", "role_title must not be blank")
        }
        if (patch.jobUrl != null && !isValidHttpUrl(patch.jobUrl)) {
            return ApplicationValidationFailure("invalid_job_url", "job_url must be a valid http(s) URL")
        }
        if (patch.techStack != null && patch.techStack.any { it.isBlank() }) {
            return ApplicationValidationFailure("invalid_tech_stack", "tech_stack must not contain blank items")
        }
        patch.appliedDate?.let {
            if (parseLocalDate(it) == null) return ApplicationValidationFailure("invalid_applied_date", "applied_date must be YYYY-MM-DD")
        }
        patch.followUpDate?.let {
            if (parseLocalDate(it) == null) return ApplicationValidationFailure("invalid_follow_up_date", "follow_up_date must be YYYY-MM-DD")
        }
        return null
    }

    fun normalizeCreate(req: CreateApplicationRequest): CreateApplicationRequest =
        req.copy(
            company_name = req.company_name?.trim()?.takeIf { it.isNotBlank() },
            role_title = req.role_title.trim(),
            job_url = req.job_url.trim(),
            tech_stack = req.tech_stack.map { it.trim() }.filter { it.isNotBlank() },
            salary_range = req.salary_range?.trim()?.takeIf { it.isNotBlank() },
            notes = req.notes?.trim()?.takeIf { it.isNotBlank() },
        )

    fun normalizePatch(req: PatchApplicationRequest): ApplicationPatch =
        ApplicationPatch(
            companyId = req.company_id,
            roleTitle = req.role_title?.trim()?.takeIf { it.isNotBlank() },
            jobUrl = req.job_url?.trim(),
            status = req.status,
            techStack = req.tech_stack?.map { it.trim() }?.filter { it.isNotBlank() },
            salaryRange = req.salary_range?.trim(),
            appliedDate = req.applied_date?.trim(),
            followUpDate = req.follow_up_date?.trim(),
            notes = req.notes?.trim(),
        )

    private fun parseLocalDate(s: String): java.time.LocalDate? =
        try {
            java.time.LocalDate.parse(s.trim())
        } catch (_: Exception) {
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
