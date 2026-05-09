package com.careerpilot.applications

import com.careerpilot.db.DatabaseModule
import com.careerpilot.jobleads.JobLeadRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Date
import java.sql.Statement
import java.time.LocalDate
import java.net.URI

class ApplicationRepository(
    private val db: DatabaseModule,
    private val jobLeads: JobLeadRepository,
) {
    private val json = Json
    private val targetCompanies = com.careerpilot.targetcompanies.TargetCompanyRepository(db)

    fun listByUser(
        userId: Long,
        status: ApplicationStatus? = null,
        companyId: Long? = null,
        keyword: String? = null,
    ): List<ApplicationRecord> {
        val where = mutableListOf("a.user_id = ?")
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()
        binders += { ps, idx -> ps.setLong(idx, userId); idx + 1 }

        if (status != null) {
            where += "a.status = ?"
            binders += { ps, idx -> ps.setString(idx, status.name); idx + 1 }
        }
        if (companyId != null) {
            where += "a.company_id = ?"
            binders += { ps, idx -> ps.setLong(idx, companyId); idx + 1 }
        }
        if (!keyword.isNullOrBlank()) {
            where += "(a.role_title LIKE ? OR a.notes LIKE ? OR a.job_url LIKE ?)"
            val needle = "%${keyword.trim()}%"
            repeat(3) {
                binders += { ps, idx -> ps.setString(idx, needle); idx + 1 }
            }
        }

        val sql =
            """
            SELECT a.id, a.user_id, a.company_id, tc.name AS company_name, a.job_lead_id,
                   a.role_title, a.job_url, a.status, a.tech_stack_json, a.salary_range,
                   a.applied_at, a.next_follow_up_date, a.notes
            FROM applications a
            JOIN target_companies tc ON tc.id = a.company_id AND tc.user_id = a.user_id
            WHERE ${where.joinToString(" AND ")}
            ORDER BY a.updated_at DESC, a.id DESC
            """.trimIndent()

        db.openConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (b in binders) idx = b(ps, idx)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ApplicationRecord>()
                    while (rs.next()) out += readRecord(rs)
                    return out
                }
            }
        }
    }

    /** Cheap ownership check (no join to companies). */
    fun isOwnedByUser(userId: Long, applicationId: Long): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT 1 FROM applications WHERE id = ? AND user_id = ? LIMIT 1",
            ).use { ps ->
                ps.setLong(1, applicationId)
                ps.setLong(2, userId)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    fun findById(userId: Long, id: Long): ApplicationRecord? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT a.id, a.user_id, a.company_id, tc.name AS company_name, a.job_lead_id,
                       a.role_title, a.job_url, a.status, a.tech_stack_json, a.salary_range,
                       a.applied_at, a.next_follow_up_date, a.notes
                FROM applications a
                JOIN target_companies tc ON tc.id = a.company_id AND tc.user_id = a.user_id
                WHERE a.user_id = ? AND a.id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return readRecord(rs)
                }
            }
        }
    }

    fun findByJobLeadId(userId: Long, jobLeadId: Long): ApplicationRecord? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT a.id, a.user_id, a.company_id, tc.name AS company_name, a.job_lead_id,
                       a.role_title, a.job_url, a.status, a.tech_stack_json, a.salary_range,
                       a.applied_at, a.next_follow_up_date, a.notes
                FROM applications a
                JOIN target_companies tc ON tc.id = a.company_id AND tc.user_id = a.user_id
                WHERE a.user_id = ? AND a.job_lead_id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, jobLeadId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return readRecord(rs)
                }
            }
        }
    }

    fun findByJobUrl(userId: Long, jobUrl: String): ApplicationRecord? =
        db.openConnection().use { conn -> findByJobUrl(conn, userId, jobUrl) }

    /**
     * Ensures an [applications] row exists for interview-plan attachment.
     * Does **not** toggle `saved_to_applications` on the job lead (unlike [saveFromJobLead]).
     */
    fun ensureApplicationForJobLead(userId: Long, jobLeadId: Long): ApplicationRecord? {
        val lead = jobLeads.findById(userId, jobLeadId) ?: return null
        findByJobLeadId(userId, jobLeadId)?.let { return it }
        findByJobUrl(userId, lead.jobUrl)?.let { return it }
        return when (
            val res =
                insert(
                    userId = userId,
                    companyId = lead.companyId,
                    companyName = lead.companyName,
                    jobLeadId = jobLeadId,
                    roleTitle = lead.roleTitle,
                    jobUrl = lead.jobUrl,
                    status = ApplicationStatus.SAVED,
                    techStack = lead.matchedKeywords,
                    salaryRange = null,
                    appliedDate = null,
                    followUpDate = null,
                    notes = null,
                )
        ) {
            is InsertApplicationResult.Created -> res.record
            InsertApplicationResult.DuplicateJobUrl -> findByJobUrl(userId, lead.jobUrl)
        }
    }

    fun insert(
        userId: Long,
        companyId: Long,
        companyName: String,
        jobLeadId: Long?,
        roleTitle: String,
        jobUrl: String,
        status: ApplicationStatus,
        techStack: List<String>,
        salaryRange: String?,
        appliedDate: LocalDate?,
        followUpDate: LocalDate?,
        notes: String?,
    ): InsertApplicationResult {
        if (existsByJobUrl(userId, jobUrl)) return InsertApplicationResult.DuplicateJobUrl
        db.openConnection().use { conn ->
            return insertUsingConnection(
                conn,
                userId,
                companyId,
                companyName,
                jobLeadId,
                roleTitle,
                jobUrl,
                status,
                techStack,
                salaryRange,
                appliedDate,
                followUpDate,
                notes,
            )
        }
    }

    private fun insertUsingConnection(
        conn: Connection,
        userId: Long,
        companyId: Long,
        companyName: String,
        jobLeadId: Long?,
        roleTitle: String,
        jobUrl: String,
        status: ApplicationStatus,
        techStack: List<String>,
        salaryRange: String?,
        appliedDate: LocalDate?,
        followUpDate: LocalDate?,
        notes: String?,
    ): InsertApplicationResult {
        conn.prepareStatement(
            """
            INSERT INTO applications (
              user_id, company_id, job_lead_id, role_title, job_url, status,
              tech_stack_json, salary_range, applied_at, next_follow_up_date, notes
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setLong(2, companyId)
            if (jobLeadId == null) ps.setNull(3, java.sql.Types.BIGINT) else ps.setLong(3, jobLeadId)
            ps.setString(4, roleTitle)
            ps.setString(5, jobUrl)
            ps.setString(6, status.name)
            ps.setString(7, encodeStringList(techStack))
            ps.setString(8, salaryRange)
            if (appliedDate == null) ps.setNull(9, java.sql.Types.DATE) else ps.setDate(9, Date.valueOf(appliedDate))
            if (followUpDate == null) ps.setNull(10, java.sql.Types.DATE) else ps.setDate(10, Date.valueOf(followUpDate))
            ps.setString(11, notes)
            ps.executeUpdate()
            ps.generatedKeys.use { keys ->
                if (!keys.next()) error("Insert application: missing generated key")
                val id = keys.getLong(1)
                val record =
                    ApplicationRecord(
                        id = id,
                        userId = userId,
                        companyId = companyId,
                        companyName = companyName,
                        jobLeadId = jobLeadId,
                        roleTitle = roleTitle,
                        jobUrl = jobUrl,
                        status = status,
                        techStack = techStack,
                        salaryRange = salaryRange,
                        appliedDate = appliedDate?.toString(),
                        followUpDate = followUpDate?.toString(),
                        notes = notes,
                    )
                return InsertApplicationResult.Created(record)
            }
        }
    }

    fun update(userId: Long, id: Long, patch: ApplicationPatch): UpdateApplicationResult {
        val existing = findById(userId, id) ?: return UpdateApplicationResult.NotFound

        val newUrl = patch.jobUrl?.trim()
        if (!newUrl.isNullOrBlank() && newUrl != existing.jobUrl && existsByJobUrl(userId, newUrl, excludeId = id)) {
            return UpdateApplicationResult.DuplicateJobUrl
        }

        var resolvedCompanyId = existing.companyId
        var resolvedCompanyName = existing.companyName
        if (patch.companyId != null && patch.companyId != existing.companyId) {
            val co = findCompany(userId, patch.companyId)
                ?: return UpdateApplicationResult.CompanyNotFound
            resolvedCompanyId = co.id
            resolvedCompanyName = co.name
        }

        val setClauses = mutableListOf<String>()
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()

        if (patch.companyId != null) {
            setClauses += "company_id = ?"
            binders += { ps, idx -> ps.setLong(idx, resolvedCompanyId); idx + 1 }
        }
        patch.roleTitle?.let {
            setClauses += "role_title = ?"
            binders += { ps, idx -> ps.setString(idx, it); idx + 1 }
        }
        patch.jobUrl?.let {
            setClauses += "job_url = ?"
            binders += { ps, idx -> ps.setString(idx, it); idx + 1 }
        }
        patch.status?.let {
            setClauses += "status = ?"
            binders += { ps, idx -> ps.setString(idx, it.name); idx + 1 }
        }
        if (patch.techStack != null) {
            setClauses += "tech_stack_json = ?"
            binders += { ps, idx -> ps.setString(idx, encodeStringList(patch.techStack)); idx + 1 }
        }
        patch.salaryRange?.let { range ->
            setClauses += "salary_range = ?"
            binders += { ps, idx ->
                ps.setString(idx, range.takeIf { it.isNotBlank() })
                idx + 1
            }
        }
        patch.appliedDate?.let { d ->
            val ld = LocalDate.parse(d.trim())
            setClauses += "applied_at = ?"
            binders += { ps, idx -> ps.setDate(idx, Date.valueOf(ld)); idx + 1 }
        }
        patch.followUpDate?.let { d ->
            val ld = LocalDate.parse(d.trim())
            setClauses += "next_follow_up_date = ?"
            binders += { ps, idx -> ps.setDate(idx, Date.valueOf(ld)); idx + 1 }
        }
        // Allow clearing dates with explicit empty string — not in API; skip

        if (patch.notes != null) {
            setClauses += "notes = ?"
            binders += { ps, idx -> ps.setString(idx, patch.notes); idx + 1 }
        }

        if (setClauses.isEmpty()) return UpdateApplicationResult.Updated(existing.copy(companyName = resolvedCompanyName))

        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE applications a
                SET ${setClauses.joinToString(", ")}
                WHERE a.id = ? AND a.user_id = ?
                """.trimIndent(),
            ).use { ps ->
                var idx = 1
                for (b in binders) idx = b(ps, idx)
                ps.setLong(idx++, id)
                ps.setLong(idx, userId)
                val updated = ps.executeUpdate()
                if (updated == 0) return UpdateApplicationResult.NotFound
            }
        }
        return UpdateApplicationResult.Updated(findById(userId, id)!!)
    }

    fun delete(userId: Long, id: Long): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement("DELETE FROM applications WHERE id = ? AND user_id = ?").use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, userId)
                return ps.executeUpdate() > 0
            }
        }
    }

    /**
     * Resolve the `target_companies` row used by `applications.company_id`.
     *
     * If `company_name` is provided and no target company exists yet, we create a minimal target company row so users can
     * start tracking applications without first configuring target companies.
     */
    fun resolveCompanyForCreate(
        userId: Long,
        companyId: Long?,
        companyName: String?,
        jobUrl: String? = null,
    ): ResolvedCompany? {
        if (companyId != null && companyId > 0) return findCompany(userId, companyId)
        val name = companyName?.trim()?.takeIf { it.isNotBlank() } ?: return null

        findCompanyByName(userId, name)?.let { return it }

        val careersUrl = inferCareersUrl(jobUrl) ?: "https://careerpilot.local/placeholder"
        val created =
            targetCompanies.insert(
                userId = userId,
                name = name,
                careersUrl = careersUrl,
                keywords = emptyList(),
                locations = emptyList(),
                active = true,
                notes = null,
            )
        return ResolvedCompany(created.id, created.name)
    }

    private fun inferCareersUrl(jobUrl: String?): String? {
        val raw = jobUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val u = URI(raw)
            val scheme = u.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val host = u.host ?: return null
            val port = u.port
            val origin = if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
            origin
        } catch (_: Throwable) {
            null
        }
    }

    fun saveFromJobLead(userId: Long, jobLeadId: Long): SaveFromLeadResult =
        db.transaction { conn ->
            val lead = jobLeads.findById(conn, userId, jobLeadId) ?: return@transaction SaveFromLeadResult.NotFound

            // Idempotent: already converted — return existing application (no duplicate DB work).
            if (lead.savedToApplications) {
                findByJobUrl(conn, userId, lead.jobUrl)?.let { existing ->
                    return@transaction SaveFromLeadResult.AlreadySaved(existing)
                }
            }

            if (existsByJobUrl(conn, userId, lead.jobUrl)) {
                return@transaction SaveFromLeadResult.DuplicateJobUrl
            }

            val inserted =
                insertUsingConnection(
                    conn = conn,
                    userId = userId,
                    companyId = lead.companyId,
                    companyName = lead.companyName,
                    jobLeadId = jobLeadId,
                    roleTitle = lead.roleTitle,
                    jobUrl = lead.jobUrl,
                    status = ApplicationStatus.SAVED,
                    techStack = lead.matchedKeywords,
                    salaryRange = null,
                    appliedDate = null,
                    followUpDate = null,
                    notes = null,
                )
            when (inserted) {
                is InsertApplicationResult.Created -> {
                    jobLeads.markSavedToApplications(conn, userId, jobLeadId)
                    SaveFromLeadResult.Created(inserted.record)
                }
                InsertApplicationResult.DuplicateJobUrl -> SaveFromLeadResult.DuplicateJobUrl
            }
        }

    private fun findByJobUrl(conn: Connection, userId: Long, jobUrl: String): ApplicationRecord? {
        conn.prepareStatement(
            """
            SELECT a.id, a.user_id, a.company_id, tc.name AS company_name, a.job_lead_id,
                   a.role_title, a.job_url, a.status, a.tech_stack_json, a.salary_range,
                   a.applied_at, a.next_follow_up_date, a.notes
            FROM applications a
            JOIN target_companies tc ON tc.id = a.company_id AND tc.user_id = a.user_id
            WHERE a.user_id = ? AND a.job_url = ?
            LIMIT 1
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, jobUrl)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return null
                return readRecord(rs)
            }
        }
    }

    private fun existsByJobUrl(conn: Connection, userId: Long, jobUrl: String, excludeId: Long? = null): Boolean {
        val sql =
            if (excludeId == null) {
                "SELECT 1 FROM applications WHERE user_id = ? AND job_url = ? LIMIT 1"
            } else {
                "SELECT 1 FROM applications WHERE user_id = ? AND job_url = ? AND id <> ? LIMIT 1"
            }
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, userId)
            ps.setString(2, jobUrl)
            if (excludeId != null) ps.setLong(3, excludeId)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    private fun findCompany(userId: Long, companyId: Long): ResolvedCompany? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                "SELECT id, name FROM target_companies WHERE user_id = ? AND id = ? LIMIT 1",
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, companyId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return ResolvedCompany(rs.getLong(1), rs.getString(2))
                }
            }
        }
    }

    private fun findCompanyByName(userId: Long, name: String): ResolvedCompany? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, name FROM target_companies
                WHERE user_id = ? AND LOWER(name) = LOWER(?)
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setString(2, name)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return ResolvedCompany(rs.getLong(1), rs.getString(2))
                }
            }
        }
    }

    private fun existsByJobUrl(userId: Long, jobUrl: String, excludeId: Long? = null): Boolean =
        db.openConnection().use { conn ->
            existsByJobUrl(conn, userId, jobUrl, excludeId)
        }

    private fun readRecord(rs: java.sql.ResultSet): ApplicationRecord {
        val applied = rs.getDate("applied_at")
        val follow = rs.getDate("next_follow_up_date")
        val statusStr = rs.getString("status")
        val status =
            try {
                ApplicationStatus.valueOf(statusStr)
            } catch (_: Exception) {
                ApplicationStatus.SAVED
            }
        val jlRaw = rs.getLong("job_lead_id")
        val jobLeadId = if (rs.wasNull()) null else jlRaw
        return ApplicationRecord(
            id = rs.getLong("id"),
            userId = rs.getLong("user_id"),
            companyId = rs.getLong("company_id"),
            companyName = rs.getString("company_name"),
            jobLeadId = jobLeadId,
            roleTitle = rs.getString("role_title"),
            jobUrl = rs.getString("job_url"),
            status = status,
            techStack = decodeStringList(rs.getString("tech_stack_json")),
            salaryRange = rs.getString("salary_range"),
            appliedDate = applied?.toLocalDate()?.toString(),
            followUpDate = follow?.toLocalDate()?.toString(),
            notes = rs.getString("notes"),
        )
    }

    private fun encodeStringList(items: List<String>): String = json.encodeToString(StringListSerializer, items)

    private fun decodeStringList(raw: String?): List<String> {
        if (raw == null) return emptyList()
        val s = raw.trim()
        if (s.isEmpty()) return emptyList()
        return try {
            json.decodeFromString(StringListSerializer, s)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private val StringListSerializer = ListSerializer(String.serializer())

    data class ResolvedCompany(val id: Long, val name: String)
}

sealed class InsertApplicationResult {
    data class Created(val record: ApplicationRecord) : InsertApplicationResult()
    data object DuplicateJobUrl : InsertApplicationResult()
}

sealed class UpdateApplicationResult {
    data class Updated(val record: ApplicationRecord) : UpdateApplicationResult()
    data object NotFound : UpdateApplicationResult()
    data object DuplicateJobUrl : UpdateApplicationResult()
    data object CompanyNotFound : UpdateApplicationResult()
}

sealed class SaveFromLeadResult {
    data class Created(val record: ApplicationRecord) : SaveFromLeadResult()
    /** Same job URL already has an application (repeat save-as-application). */
    data class AlreadySaved(val record: ApplicationRecord) : SaveFromLeadResult()
    data object NotFound : SaveFromLeadResult()
    data object DuplicateJobUrl : SaveFromLeadResult()
}
