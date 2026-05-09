package com.careerpilot.interviewplans

import com.careerpilot.applications.ApplicationRepository
import com.careerpilot.db.DatabaseModule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

class InterviewPlanRepository(
    private val db: DatabaseModule,
    private val applications: ApplicationRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Latest plan for this job lead when an application exists; does **not** create an application. */
    fun findLatestForJobLead(userId: Long, jobLeadId: Long): InterviewPlanDetailDto? {
        val app = applications.findByJobLeadId(userId, jobLeadId) ?: return null
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT p.id, p.application_id, p.provider_mode, p.prompt_json, p.plan_json, p.plan_markdown,
                       p.created_at, p.updated_at, a.job_lead_id
                FROM ai_interview_plans p
                JOIN applications a ON a.id = p.application_id
                WHERE p.application_id = ? AND a.user_id = ?
                ORDER BY p.created_at DESC, p.id DESC
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, app.id)
                ps.setLong(2, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val planId = rs.getLong("id")
                    val jlRaw = rs.getLong("job_lead_id")
                    val jobLeadIdVal = if (rs.wasNull()) null else jlRaw
                    return readPlanDetail(
                        conn,
                        userId,
                        planId,
                        rs.getLong("application_id"),
                        jobLeadIdVal,
                        rs.getString("provider_mode"),
                        rs.getString("prompt_json"),
                        rs.getString("plan_json"),
                        rs.getString("plan_markdown"),
                        rs.getTimestamp("created_at"),
                        rs.getTimestamp("updated_at"),
                    )
                }
            }
        }
    }

    fun findByIdForUser(userId: Long, planId: Long): InterviewPlanDetailDto? =
        db.openConnection().use { conn -> findByIdForUser(conn, userId, planId) }

    fun upsertForJobLead(userId: Long, jobLeadId: Long, req: UpsertInterviewPlanRequest): InterviewPlanDetailDto {
        val app =
            applications.ensureApplicationForJobLead(userId, jobLeadId)
                ?: error("ensureApplicationForJobLead: missing lead or insert failed")
        val providerMode = req.provider_mode.trim().take(16).ifBlank { "external" }
        val planJsonStr = json.encodeToString(JsonElement.serializer(), req.plan_json)
        val promptStr = req.prompt_json?.let { json.encodeToString(JsonElement.serializer(), it) }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)

        return db.transaction { conn ->
            deletePlansForApplication(conn, app.id)

            val prepInputs = normalizePrepTasks(req, today)

            conn.prepareStatement(
                """
                INSERT INTO ai_interview_plans (application_id, provider_mode, prompt_json, plan_json, plan_markdown)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, app.id)
                ps.setString(2, providerMode)
                if (promptStr == null) ps.setNull(3, java.sql.Types.LONGVARCHAR) else ps.setString(3, promptStr)
                ps.setString(4, planJsonStr)
                ps.setString(5, req.plan_markdown)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    if (!keys.next()) error("insert ai_interview_plans: no key")
                    val planId = keys.getLong(1)
                    for (t in prepInputs) {
                        conn.prepareStatement(
                            """
                            INSERT INTO prep_tasks (ai_interview_plan_id, label, description, due_date, status)
                            VALUES (?, ?, ?, ?, 'todo')
                            """.trimIndent(),
                        ).use { pt ->
                            pt.setLong(1, planId)
                            pt.setString(2, t.label.take(255))
                            if (t.description == null) pt.setNull(3, java.sql.Types.LONGVARCHAR) else pt.setString(3, t.description)
                            if (t.dueDate == null) pt.setNull(4, java.sql.Types.DATE) else pt.setDate(4, java.sql.Date.valueOf(t.dueDate))
                            pt.executeUpdate()
                        }
                    }
                    return@transaction findByIdForUser(conn, userId, planId)
                        ?: error("reload plan after insert")
                }
            }
        }
    }

    fun deleteForUser(userId: Long, planId: Long): Boolean {
        return db.transaction { conn ->
            conn.prepareStatement(
                """
                DELETE FROM prep_tasks
                WHERE ai_interview_plan_id IN (
                    SELECT p.id
                    FROM ai_interview_plans p
                    JOIN applications a ON a.id = p.application_id
                    WHERE p.id = ? AND a.user_id = ?
                )
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, planId)
                ps.setLong(2, userId)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM ai_interview_plans
                WHERE id = ?
                  AND application_id IN (SELECT id FROM applications WHERE user_id = ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, planId)
                ps.setLong(2, userId)
                ps.executeUpdate() > 0
            }
        }
    }

    fun listPrepTasks(userId: Long, applicationId: Long?, status: String?): List<PrepTaskDto> {
        val where = mutableListOf("a.user_id = ?")
        val bind = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()
        bind += { ps, idx -> ps.setLong(idx, userId); idx + 1 }
        if (applicationId != null) {
            where += "a.id = ?"
            bind += { ps, idx -> ps.setLong(idx, applicationId); idx + 1 }
        }
        if (!status.isNullOrBlank()) {
            where += "pt.status = ?"
            bind += { ps, idx -> ps.setString(idx, status.trim()); idx + 1 }
        }
        val sql =
            """
            SELECT pt.id, pt.ai_interview_plan_id, pt.label, pt.description, pt.due_date, pt.status,
                   a.id AS application_id
            FROM prep_tasks pt
            JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
            JOIN applications a ON a.id = p.application_id
            WHERE ${where.joinToString(" AND ")}
            ORDER BY pt.due_date ASC, pt.id ASC
            """.trimIndent()

        db.openConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (b in bind) idx = b(ps, idx)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<PrepTaskDto>()
                    while (rs.next()) {
                        val due = rs.getDate("due_date")?.toLocalDate()?.toString()
                        out +=
                            PrepTaskDto(
                                id = rs.getLong("id"),
                                ai_interview_plan_id = rs.getLong("ai_interview_plan_id"),
                                application_id = rs.getLong("application_id"),
                                label = rs.getString("label"),
                                description = rs.getString("description"),
                                due_date = due,
                                status = rs.getString("status"),
                            )
                    }
                    return out
                }
            }
        }
    }

    fun listPrepTasksDueToday(userId: Long): List<PrepTaskDto> {
        val today = LocalDate.now(ZoneId.systemDefault())
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT pt.id, pt.ai_interview_plan_id, pt.label, pt.description, pt.due_date, pt.status,
                       a.id AS application_id
                FROM prep_tasks pt
                JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
                JOIN applications a ON a.id = p.application_id
                WHERE a.user_id = ? AND pt.due_date = ? AND pt.status <> 'done'
                ORDER BY pt.label ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setDate(2, java.sql.Date.valueOf(today))
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<PrepTaskDto>()
                    while (rs.next()) {
                        val due = rs.getDate("due_date")?.toLocalDate()?.toString()
                        out +=
                            PrepTaskDto(
                                id = rs.getLong("id"),
                                ai_interview_plan_id = rs.getLong("ai_interview_plan_id"),
                                application_id = rs.getLong("application_id"),
                                label = rs.getString("label"),
                                description = rs.getString("description"),
                                due_date = due,
                                status = rs.getString("status"),
                            )
                    }
                    return out
                }
            }
        }
    }

    fun completePrepTask(userId: Long, taskId: Long): PrepTaskDto? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE prep_tasks pt
                SET pt.status = 'done', pt.updated_at = CURRENT_TIMESTAMP
                WHERE pt.id = ?
                  AND EXISTS (
                    SELECT 1 FROM ai_interview_plans p
                    INNER JOIN applications a ON a.id = p.application_id
                    WHERE p.id = pt.ai_interview_plan_id AND a.user_id = ?
                  )
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, taskId)
                ps.setLong(2, userId)
                if (ps.executeUpdate() == 0) return null
            }
            conn.prepareStatement(
                """
                SELECT pt.id, pt.ai_interview_plan_id, pt.label, pt.description, pt.due_date, pt.status,
                       a.id AS application_id
                FROM prep_tasks pt
                JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
                JOIN applications a ON a.id = p.application_id
                WHERE pt.id = ? AND a.user_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, taskId)
                ps.setLong(2, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    val due = rs.getDate("due_date")?.toLocalDate()?.toString()
                    return PrepTaskDto(
                        id = rs.getLong("id"),
                        ai_interview_plan_id = rs.getLong("ai_interview_plan_id"),
                        application_id = rs.getLong("application_id"),
                        label = rs.getString("label"),
                        description = rs.getString("description"),
                        due_date = due,
                        status = rs.getString("status"),
                    )
                }
            }
        }
    }

    private fun findByIdForUser(conn: Connection, userId: Long, planId: Long): InterviewPlanDetailDto? {
        conn.prepareStatement(
            """
            SELECT p.id, p.application_id, p.provider_mode, p.prompt_json, p.plan_json, p.plan_markdown,
                   p.created_at, p.updated_at, a.job_lead_id
            FROM ai_interview_plans p
            JOIN applications a ON a.id = p.application_id
            WHERE p.id = ? AND a.user_id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, planId)
            ps.setLong(2, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                val jlRaw = rs.getLong("job_lead_id")
                val jobLeadIdVal = if (rs.wasNull()) null else jlRaw
                return readPlanDetail(
                    conn,
                    userId,
                    rs.getLong("id"),
                    rs.getLong("application_id"),
                    jobLeadIdVal,
                    rs.getString("provider_mode"),
                    rs.getString("prompt_json"),
                    rs.getString("plan_json"),
                    rs.getString("plan_markdown"),
                    rs.getTimestamp("created_at"),
                    rs.getTimestamp("updated_at"),
                )
            }
        }
    }

    private data class NormalizedPrepInput(val label: String, val description: String?, val dueDate: LocalDate?)

    private fun normalizePrepTasks(req: UpsertInterviewPlanRequest, today: LocalDate): List<NormalizedPrepInput> {
        if (req.prep_tasks.isNotEmpty()) {
            return req.prep_tasks.mapNotNull { t -> mapPrepTaskUpsert(t, today) }
        }
        val embedded = (req.plan_json as? JsonObject)?.get("prep_tasks") ?: return emptyList()
        return try {
            val arr = json.decodeFromJsonElement(ListSerializer(PrepTaskUpsertDto.serializer()), embedded)
            arr.mapNotNull { t -> mapPrepTaskUpsert(t, today) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun mapPrepTaskUpsert(t: PrepTaskUpsertDto, today: LocalDate): NormalizedPrepInput? {
        val label = t.label.trim().take(255)
        if (label.isBlank()) return null
        val due =
            when {
                !t.due_date.isNullOrBlank() -> LocalDate.parse(t.due_date.trim())
                t.due_day_offset != null -> today.plusDays(t.due_day_offset.toLong())
                else -> today
            }
        return NormalizedPrepInput(label, t.description?.trim()?.takeIf { it.isNotBlank() }, due)
    }

    private fun deletePlansForApplication(conn: Connection, applicationId: Long) {
        conn.prepareStatement(
            """
            DELETE FROM prep_tasks
            WHERE ai_interview_plan_id IN (
                SELECT id FROM ai_interview_plans WHERE application_id = ?
            )
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, applicationId)
            ps.executeUpdate()
        }
        conn.prepareStatement("DELETE FROM ai_interview_plans WHERE application_id = ?").use { ps ->
            ps.setLong(1, applicationId)
            ps.executeUpdate()
        }
    }

    private fun readPlanDetail(
        conn: Connection,
        userId: Long,
        planId: Long,
        applicationId: Long,
        jobLeadId: Long?,
        providerMode: String,
        promptJsonRaw: String?,
        planJsonRaw: String,
        planMarkdown: String?,
        createdAt: Timestamp,
        updatedAt: Timestamp,
    ): InterviewPlanDetailDto {
        val planEl = json.parseToJsonElement(planJsonRaw)
        val promptEl: JsonElement? =
            if (promptJsonRaw.isNullOrBlank()) null else json.parseToJsonElement(promptJsonRaw)

        val tasks = loadPrepTasks(conn, userId, planId)
        return InterviewPlanDetailDto(
            id = planId,
            application_id = applicationId,
            job_lead_id = jobLeadId,
            provider_mode = providerMode,
            plan_json = planEl,
            plan_markdown = planMarkdown,
            prompt_json = promptEl,
            prep_tasks = tasks,
            created_at = createdAt.toInstant().toString(),
            updated_at = updatedAt.toInstant().toString(),
        )
    }

    private fun loadPrepTasks(conn: Connection, userId: Long, planId: Long): List<PrepTaskDto> {
        conn.prepareStatement(
            """
            SELECT pt.id, pt.ai_interview_plan_id, pt.label, pt.description, pt.due_date, pt.status,
                   a.id AS application_id
            FROM prep_tasks pt
            JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
            JOIN applications a ON a.id = p.application_id
            WHERE pt.ai_interview_plan_id = ? AND a.user_id = ?
            ORDER BY pt.due_date ASC, pt.id ASC
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, planId)
            ps.setLong(2, userId)
            ps.executeQuery().use { rs ->
                val out = mutableListOf<PrepTaskDto>()
                while (rs.next()) {
                    val due = rs.getDate("due_date")?.toLocalDate()?.toString()
                    out +=
                        PrepTaskDto(
                            id = rs.getLong("id"),
                            ai_interview_plan_id = rs.getLong("ai_interview_plan_id"),
                            application_id = rs.getLong("application_id"),
                            label = rs.getString("label"),
                            description = rs.getString("description"),
                            due_date = due,
                            status = rs.getString("status"),
                        )
                }
                return out
            }
        }
    }
}
