package com.careerpilot.jobleads

import kotlinx.serialization.Serializable

@Serializable
data class DiscoverJobLeadsRequest(
    val company_id: Long? = null,
    val min_match_score: Double = 0.0,
    val max_pages_per_company: Int = 8,
    val max_depth: Int = 2,
)

@Serializable
data class DiscoverJobLeadsResponse(
    val companies_scanned: Int,
    val leads_found: Int,
    val leads_created: Int,
    val duplicates_skipped: Int,
    val low_score_skipped: Int,
    val fetch_errors: Int,
    val created_items: List<JobLeadDto>,
)

@Serializable
data class RefreshInvalidJobLeadsRequest(
    val company_id: Long? = null,
    val delete_saved: Boolean = false,
)

@Serializable
data class RefreshInvalidJobLeadsResponse(
    val checked: Int,
    val deleted: Int,
    val kept: Int,
    val skipped_saved: Int,
    val uncertain: Int,
    val deleted_items: List<JobLeadDto>,
)
