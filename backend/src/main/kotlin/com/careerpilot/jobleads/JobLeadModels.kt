package com.careerpilot.jobleads

import kotlinx.serialization.Serializable

@Serializable
data class JobLeadDto(
    val id: Long,
    val company_id: Long,
    val company_name: String,
    val role_title: String,
    val job_url: String,
    val location: String? = null,
    val raw_description: String? = null,
    val matched_keywords: List<String> = emptyList(),
    val match_score: Double? = null,
    val discovered_at: String,
    val saved_to_applications: Boolean,
)

@Serializable
data class CreateJobLeadRequest(
    val company_id: Long,
    val role_title: String,
    val job_url: String,
    val location: String? = null,
    val raw_description: String? = null,
    val matched_keywords: List<String> = emptyList(),
    val match_score: Double? = null,
    val discovered_at: String? = null,
    val saved_to_applications: Boolean = false,
)

@Serializable
data class PatchJobLeadRequest(
    val role_title: String? = null,
    val job_url: String? = null,
    val location: String? = null,
    val raw_description: String? = null,
    val matched_keywords: List<String>? = null,
    val match_score: Double? = null,
    val discovered_at: String? = null,
    val saved_to_applications: Boolean? = null,
)

data class JobLeadRecord(
    val id: Long,
    val companyId: Long,
    val companyName: String,
    val roleTitle: String,
    val jobUrl: String,
    val location: String?,
    val rawDescription: String?,
    val matchedKeywords: List<String>,
    val matchScore: Double?,
    val discoveredAtIso: String,
    val savedToApplications: Boolean,
) {
    fun toDto(): JobLeadDto =
        JobLeadDto(
            id = id,
            company_id = companyId,
            company_name = companyName,
            role_title = roleTitle,
            job_url = jobUrl,
            location = location,
            raw_description = rawDescription,
            matched_keywords = matchedKeywords,
            match_score = matchScore,
            discovered_at = discoveredAtIso,
            saved_to_applications = savedToApplications,
        )
}

data class JobLeadPatch(
    val roleTitle: String? = null,
    val jobUrl: String? = null,
    val location: String? = null,
    val rawDescription: String? = null,
    val matchedKeywords: List<String>? = null,
    val matchScore: Double? = null,
    val discoveredAtIso: String? = null,
    val savedToApplications: Boolean? = null,
)

