package com.careerpilot.targetcompanies

import kotlinx.serialization.Serializable

@Serializable
data class TargetCompanyDto(
    val id: Long,
    val name: String,
    val careers_url: String,
    val keywords: List<String>,
    val locations: List<String>,
    val active: Boolean,
    val notes: String? = null,
)

@Serializable
data class CreateTargetCompanyRequest(
    val name: String,
    val careers_url: String,
    val keywords: List<String>,
    val locations: List<String> = emptyList(),
    val active: Boolean = true,
    val notes: String? = null,
)

@Serializable
data class PatchTargetCompanyRequest(
    val name: String? = null,
    val careers_url: String? = null,
    val keywords: List<String>? = null,
    val locations: List<String>? = null,
    val active: Boolean? = null,
    val notes: String? = null,
)

data class TargetCompanyRecord(
    val id: Long,
    val userId: Long,
    val name: String,
    val careersUrl: String,
    val keywords: List<String>,
    val locations: List<String>,
    val active: Boolean,
    val notes: String?,
) {
    fun toDto(): TargetCompanyDto =
        TargetCompanyDto(
            id = id,
            name = name,
            careers_url = careersUrl,
            keywords = keywords,
            locations = locations,
            active = active,
            notes = notes,
        )
}

data class TargetCompanyPatch(
    val name: String? = null,
    val careersUrl: String? = null,
    val keywords: List<String>? = null,
    val locations: List<String>? = null,
    val active: Boolean? = null,
    val notes: String? = null,
)

