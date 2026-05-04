package com.careerpilot.dashboard

import com.careerpilot.interviews.InterviewDto
import com.careerpilot.jobleads.JobLeadDto
import kotlinx.serialization.Serializable

/**
 * Aggregated metrics for the authenticated user (server-local week/day boundaries; see README).
 *
 * **response_rate**: Among applications in an active post-submit pipeline
 * (`APPLIED`, `ONLINE_ASSESSMENT`, `INTERVIEW`, `OFFER`, `REJECTED`, `GHOSTED`),
 * the percentage that received a substantive employer signal
 * (`ONLINE_ASSESSMENT`, `INTERVIEW`, `OFFER`, or `REJECTED`). `0` when the denominator is zero.
 */
@Serializable
data class DashboardStatsDto(
    val total_applications: Long,
    val applications_this_week: Long,
    val interviews_count: Long,
    val offers_count: Long,
    val rejections_count: Long,
    val response_rate: Double,
    val follow_ups_due: Long,
    val job_leads_discovered_this_week: Long,
    val prep_tasks_due_today: Long,
)

@Serializable
data class DashboardFollowUpDto(
    /** `application_follow_up` or `reminder` */
    val kind: String,
    val id: Long,
    val application_id: Long? = null,
    val title: String,
    /** ISO date `YYYY-MM-DD` or ISO-8601 instant string */
    val due: String,
    val company_name: String? = null,
)

@Serializable
data class PrepSummaryItemDto(
    val id: Long,
    val application_id: Long,
    val company_name: String,
    val role_title: String,
    val label: String,
    val due_date: String?,
    val status: String,
)

@Serializable
data class DashboardPrepSummaryDto(
    val items: List<PrepSummaryItemDto>,
)

@Serializable
data class DashboardRecentJobLeadsDto(
    val items: List<JobLeadDto>,
)

@Serializable
data class DashboardUpcomingInterviewsDto(
    val items: List<InterviewDto>,
)
