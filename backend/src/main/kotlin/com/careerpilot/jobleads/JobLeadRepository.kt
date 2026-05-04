package com.careerpilot.jobleads

import com.careerpilot.db.DatabaseModule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

class JobLeadRepository(private val db: DatabaseModule) {
    private val json = Json

    fun listByUser(
        userId: Long,
        companyId: Long? = null,
        keyword: String? = null,
        minMatchScore: Double? = null,
        savedToApplications: Boolean? = null,
    ): List<JobLeadRecord> {
        val where = mutableListOf("tc.user_id = ?")
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()
        binders += { ps, idx -> ps.setLong(idx, userId); idx + 1 }

        if (companyId != null) {
            where += "jl.company_id = ?"
            binders += { ps, idx -> ps.setLong(idx, companyId); idx + 1 }
        }
        if (minMatchScore != null) {
            where += "jl.match_score IS NOT NULL AND jl.match_score >= ?"
            binders += { ps, idx -> ps.setDouble(idx, minMatchScore); idx + 1 }
        }
        if (savedToApplications != null) {
            where += "jl.saved_to_applications = ?"
            binders += { ps, idx -> ps.setBoolean(idx, savedToApplications); idx + 1 }
        }
        if (!keyword.isNullOrBlank()) {
            // DB-agnostic containment check (works for MySQL JSON and H2 TEXT test schema)
            where += "jl.matched_keywords_json LIKE ?"
            val needle = "%\"${keyword.trim()}\"%"
            binders += { ps, idx -> ps.setString(idx, needle); idx + 1 }
        }

        val sql =
            """
            SELECT jl.id, jl.company_id, tc.name AS company_name, jl.title, jl.url, jl.location, jl.raw_description,
                   jl.matched_keywords_json, jl.match_score, jl.discovered_at, jl.saved_to_applications
            FROM job_leads jl
            JOIN target_companies tc ON tc.id = jl.company_id
            WHERE ${where.joinToString(" AND ")}
            ORDER BY jl.discovered_at DESC, jl.id DESC
            """.trimIndent()

        db.openConnection().use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                for (b in binders) idx = b(ps, idx)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<JobLeadRecord>()
                    while (rs.next()) out += readRecord(rs)
                    return out
                }
            }
        }
    }

    /** Newest first; `user_id` enforced via `target_companies`. */
    fun listRecentForDashboard(userId: Long, limit: Int = 10): List<JobLeadRecord> {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT jl.id, jl.company_id, tc.name AS company_name, jl.title, jl.url, jl.location, jl.raw_description,
                       jl.matched_keywords_json, jl.match_score, jl.discovered_at, jl.saved_to_applications
                FROM job_leads jl
                JOIN target_companies tc ON tc.id = jl.company_id
                WHERE tc.user_id = ?
                ORDER BY jl.discovered_at DESC, jl.id DESC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setInt(2, limit)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<JobLeadRecord>()
                    while (rs.next()) out += readRecord(rs)
                    return out
                }
            }
        }
    }

    fun findById(userId: Long, id: Long): JobLeadRecord? =
        db.openConnection().use { conn -> findById(conn, userId, id) }

    fun findById(conn: Connection, userId: Long, id: Long): JobLeadRecord? {
        conn.prepareStatement(
            """
            SELECT jl.id, jl.company_id, tc.name AS company_name, jl.title, jl.url, jl.location, jl.raw_description,
                   jl.matched_keywords_json, jl.match_score, jl.discovered_at, jl.saved_to_applications
            FROM job_leads jl
            JOIN target_companies tc ON tc.id = jl.company_id
            WHERE tc.user_id = ? AND jl.id = ?
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

    fun insert(
        userId: Long,
        companyId: Long,
        roleTitle: String,
        jobUrl: String,
        location: String?,
        rawDescription: String?,
        matchedKeywords: List<String>,
        matchScore: Double?,
        discoveredAtIso: String,
        savedToApplications: Boolean,
    ): InsertResult {
        // Ensure company belongs to user
        val companyName = companyNameForUser(userId, companyId) ?: return InsertResult.CompanyNotFound

        // De-dupe by job_url per user (across all of user's companies)
        if (existsByJobUrl(userId, jobUrl)) return InsertResult.DuplicateJobUrl

        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO job_leads (
                  company_id, title, url, location, raw_description,
                  matched_keywords_json, match_score, discovered_at, saved_to_applications
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, companyId)
                ps.setString(2, roleTitle)
                ps.setString(3, jobUrl)
                ps.setString(4, location)
                ps.setString(5, rawDescription)
                ps.setString(6, encodeStringList(matchedKeywords))
                if (matchScore == null) ps.setNull(7, java.sql.Types.DECIMAL) else ps.setDouble(7, matchScore)
                ps.setTimestamp(8, Timestamp.from(Instant.parse(discoveredAtIso)))
                ps.setBoolean(9, savedToApplications)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    if (!keys.next()) error("Insert job lead: missing generated key")
                    val id = keys.getLong(1)
                    val record =
                        JobLeadRecord(
                            id = id,
                            companyId = companyId,
                            companyName = companyName,
                            roleTitle = roleTitle,
                            jobUrl = jobUrl,
                            location = location,
                            rawDescription = rawDescription,
                            matchedKeywords = matchedKeywords,
                            matchScore = matchScore,
                            discoveredAtIso = discoveredAtIso,
                            savedToApplications = savedToApplications,
                        )
                    return InsertResult.Created(record)
                }
            }
        }
    }

    fun update(userId: Long, id: Long, patch: JobLeadPatch): UpdateResult {
        val existing = findById(userId, id) ?: return UpdateResult.NotFound

        // If job_url changes, enforce per-user de-dupe
        val newUrl = patch.jobUrl?.trim()
        if (!newUrl.isNullOrBlank() && newUrl != existing.jobUrl && existsByJobUrl(userId, newUrl)) {
            return UpdateResult.DuplicateJobUrl
        }

        val setClauses = mutableListOf<String>()
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()
        fun <T> set(col: String, value: T?, binder: (java.sql.PreparedStatement, Int, T) -> Unit) {
            if (value == null) return
            setClauses += "$col = ?"
            binders += { ps, idx -> binder(ps, idx, value); idx + 1 }
        }

        set("title", patch.roleTitle) { ps, i, v -> ps.setString(i, v) }
        set("url", patch.jobUrl) { ps, i, v -> ps.setString(i, v) }
        if (patch.location != null) {
            setClauses += "location = ?"
            binders += { ps, idx -> ps.setString(idx, patch.location); idx + 1 }
        }
        if (patch.rawDescription != null) {
            setClauses += "raw_description = ?"
            binders += { ps, idx -> ps.setString(idx, patch.rawDescription); idx + 1 }
        }
        if (patch.matchedKeywords != null) {
            setClauses += "matched_keywords_json = ?"
            val encoded = encodeStringList(patch.matchedKeywords)
            binders += { ps, idx -> ps.setString(idx, encoded); idx + 1 }
        }
        if (patch.matchScore != null) {
            setClauses += "match_score = ?"
            binders += { ps, idx -> ps.setDouble(idx, patch.matchScore); idx + 1 }
        }
        if (patch.discoveredAtIso != null) {
            setClauses += "discovered_at = ?"
            val ts = Timestamp.from(Instant.parse(patch.discoveredAtIso))
            binders += { ps, idx -> ps.setTimestamp(idx, ts); idx + 1 }
        }
        if (patch.savedToApplications != null) {
            setClauses += "saved_to_applications = ?"
            binders += { ps, idx -> ps.setBoolean(idx, patch.savedToApplications); idx + 1 }
        }

        if (setClauses.isEmpty()) return UpdateResult.Updated(existing)

        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE job_leads
                SET ${setClauses.joinToString(", ")}
                WHERE id = ?
                  AND company_id IN (SELECT id FROM target_companies WHERE user_id = ?)
                """.trimIndent(),
            ).use { ps ->
                var idx = 1
                for (b in binders) idx = b(ps, idx)
                ps.setLong(idx++, id)
                ps.setLong(idx, userId)
                val updated = ps.executeUpdate()
                if (updated == 0) return UpdateResult.NotFound
            }
        }
        return UpdateResult.Updated(findById(userId, id)!!)
    }

    fun delete(userId: Long, id: Long): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM job_leads
                WHERE id = ?
                  AND company_id IN (SELECT id FROM target_companies WHERE user_id = ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, id)
                ps.setLong(2, userId)
                return ps.executeUpdate() > 0
            }
        }
    }

    /** Marks the job lead as saved to applications (used when converting to an application). */
    fun markSavedToApplications(userId: Long, jobLeadId: Long): Boolean =
        db.openConnection().use { conn -> markSavedToApplications(conn, userId, jobLeadId) }

    fun markSavedToApplications(conn: Connection, userId: Long, jobLeadId: Long): Boolean {
        conn.prepareStatement(
            """
            UPDATE job_leads jl
            SET saved_to_applications = 1
            WHERE jl.id = ?
              AND jl.company_id IN (SELECT id FROM target_companies WHERE user_id = ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setLong(1, jobLeadId)
            ps.setLong(2, userId)
            return ps.executeUpdate() > 0
        }
    }

    private fun companyNameForUser(userId: Long, companyId: Long): String? {
        db.openConnection().use { conn ->
            conn.prepareStatement("SELECT name FROM target_companies WHERE id = ? AND user_id = ? LIMIT 1").use { ps ->
                ps.setLong(1, companyId)
                ps.setLong(2, userId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return rs.getString(1)
                }
            }
        }
    }

    private fun existsByJobUrl(userId: Long, jobUrl: String): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT 1
                FROM job_leads jl
                JOIN target_companies tc ON tc.id = jl.company_id
                WHERE tc.user_id = ? AND jl.url = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setString(2, jobUrl)
                ps.executeQuery().use { rs -> return rs.next() }
            }
        }
    }

    private fun readRecord(rs: java.sql.ResultSet): JobLeadRecord =
        JobLeadRecord(
            id = rs.getLong("id"),
            companyId = rs.getLong("company_id"),
            companyName = rs.getString("company_name"),
            roleTitle = rs.getString("title"),
            jobUrl = rs.getString("url"),
            location = rs.getString("location"),
            rawDescription = rs.getString("raw_description"),
            matchedKeywords = decodeStringList(rs.getString("matched_keywords_json")),
            matchScore = rs.getBigDecimal("match_score")?.toDouble(),
            discoveredAtIso = rs.getString("discovered_at"),
            savedToApplications = rs.getBoolean("saved_to_applications"),
        )

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
}

sealed class InsertResult {
    data class Created(val record: JobLeadRecord) : InsertResult()
    data object DuplicateJobUrl : InsertResult()
    data object CompanyNotFound : InsertResult()
}

sealed class UpdateResult {
    data class Updated(val record: JobLeadRecord) : UpdateResult()
    data object DuplicateJobUrl : UpdateResult()
    data object NotFound : UpdateResult()
}

private val StringListSerializer = ListSerializer(String.serializer())

