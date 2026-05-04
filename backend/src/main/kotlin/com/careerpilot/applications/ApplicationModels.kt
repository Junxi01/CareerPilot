package com.careerpilot.applications

import kotlinx.serialization.Serializable

@Serializable
enum class ApplicationStatus {
    SAVED,
    APPLIED,
    ONLINE_ASSESSMENT,
    INTERVIEW,
    OFFER,
    REJECTED,
    GHOSTED,
    ARCHIVED,
}

@Serializable
data class ApplicationDto(
    val id: Long,
    val company_id: Long,
    val company_name: String,
    val job_lead_id: Long? = null,
    val role_title: String,
    val job_url: String,
    val status: ApplicationStatus,
    val tech_stack: List<String> = emptyList(),
    val salary_range: String? = null,
    val applied_date: String? = null,
    val follow_up_date: String? = null,
    val notes: String? = null,
)

@Serializable
data class CreateApplicationRequest(
    val company_id: Long? = null,
    val company_name: String? = null,
    val role_title: String,
    val job_url: String,
    @Serializable(with = ApplicationStatusJsonSerializer::class)
    val status: ApplicationStatus = ApplicationStatus.SAVED,
    val tech_stack: List<String> = emptyList(),
    val salary_range: String? = null,
    val applied_date: String? = null,
    val follow_up_date: String? = null,
    val notes: String? = null,
)

@Serializable
data class PatchApplicationRequest(
    val company_id: Long? = null,
    val role_title: String? = null,
    val job_url: String? = null,
    @Serializable(with = ApplicationStatusJsonSerializer::class)
    val status: ApplicationStatus? = null,
    val tech_stack: List<String>? = null,
    val salary_range: String? = null,
    val applied_date: String? = null,
    val follow_up_date: String? = null,
    val notes: String? = null,
)

data class ApplicationRecord(
    val id: Long,
    val userId: Long,
    val companyId: Long,
    val companyName: String,
    val jobLeadId: Long?,
    val roleTitle: String,
    val jobUrl: String,
    val status: ApplicationStatus,
    val techStack: List<String>,
    val salaryRange: String?,
    val appliedDate: String?,
    val followUpDate: String?,
    val notes: String?,
) {
    fun toDto(): ApplicationDto =
        ApplicationDto(
            id = id,
            company_id = companyId,
            company_name = companyName,
            job_lead_id = jobLeadId,
            role_title = roleTitle,
            job_url = jobUrl,
            status = status,
            tech_stack = techStack,
            salary_range = salaryRange,
            applied_date = appliedDate,
            follow_up_date = followUpDate,
            notes = notes,
        )
}

data class ApplicationPatch(
    val companyId: Long? = null,
    val roleTitle: String? = null,
    val jobUrl: String? = null,
    val status: ApplicationStatus? = null,
    val techStack: List<String>? = null,
    val salaryRange: String? = null,
    val appliedDate: String? = null,
    val followUpDate: String? = null,
    val notes: String? = null,
)
