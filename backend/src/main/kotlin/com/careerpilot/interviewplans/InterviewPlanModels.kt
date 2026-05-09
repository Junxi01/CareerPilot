package com.careerpilot.interviewplans

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class PrepTaskUpsertDto(
    val label: String,
    val due_day_offset: Int? = null,
    /** ISO date `YYYY-MM-DD`; takes precedence over [due_day_offset] when both set. */
    val due_date: String? = null,
    val description: String? = null,
)

/**
 * Submit an AI-generated plan from the CLI (`scripts/ai_interview_planner.py`) or any client.
 * The backend does **not** spawn Python — stable contract for automation.
 */
@Serializable
data class UpsertInterviewPlanRequest(
    val plan_json: JsonObject,
    val plan_markdown: String,
    val prompt_json: JsonObject? = null,
    val provider_mode: String = "external",
    val prep_tasks: List<PrepTaskUpsertDto> = emptyList(),
)

@Serializable
data class PrepTaskDto(
    val id: Long,
    val ai_interview_plan_id: Long,
    val application_id: Long,
    val label: String,
    val description: String? = null,
    val due_date: String? = null,
    val status: String,
)

@Serializable
data class InterviewPlanDetailDto(
    val id: Long,
    val application_id: Long,
    val job_lead_id: Long? = null,
    val provider_mode: String,
    val plan_json: JsonElement,
    val plan_markdown: String? = null,
    val prompt_json: JsonElement? = null,
    val prep_tasks: List<PrepTaskDto> = emptyList(),
    val created_at: String,
    val updated_at: String,
)
