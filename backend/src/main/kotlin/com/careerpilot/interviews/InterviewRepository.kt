package com.careerpilot.interviews

import com.careerpilot.applications.ApplicationRepository
import com.careerpilot.db.DatabaseModule
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

class InterviewRepository(
    private val db: DatabaseModule,
    private val applications: ApplicationRepository,
) {
    fun listForUser(userId: Long): List<InterviewRecord> {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT i.id, i.application_id, i.round_name, i.scheduled_at, i.status, i.notes
                FROM interviews i
                JOIN applications a ON a.id = i.application_id
                WHERE a.user_id = ?
                ORDER BY i.scheduled_at IS NULL, i.scheduled_at DESC, i.id DESC
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<InterviewRecord>()
                    while (rs.next()) out += readRecord(rs)
                    return out
                }
            }
        }
    }

    fun findById(userId: Long, interviewId: Long): InterviewRecord? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT i.id, i.application_id, i.round_name, i.scheduled_at, i.status, i.notes
                FROM interviews i
                JOIN applications a ON a.id = i.application_id
                WHERE a.user_id = ? AND i.id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, interviewId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return readRecord(rs)
                }
            }
        }
    }

    fun insert(
        userId: Long,
        applicationId: Long,
        roundName: String?,
        scheduledAt: Instant?,
        status: String,
        notes: String?,
    ): InterviewRecord? {
        if (!applications.isOwnedByUser(userId, applicationId)) return null
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO interviews (application_id, round_name, scheduled_at, status, notes)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, applicationId)
                ps.setString(2, roundName)
                if (scheduledAt == null) ps.setNull(3, java.sql.Types.TIMESTAMP) else ps.setTimestamp(3, Timestamp.from(scheduledAt))
                ps.setString(4, status)
                ps.setString(5, notes)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    if (!keys.next()) error("insert interview: no key")
                    val id = keys.getLong(1)
                    return InterviewRecord(
                        id = id,
                        applicationId = applicationId,
                        roundName = roundName,
                        scheduledAtIso = scheduledAt?.toString(),
                        status = status,
                        notes = notes,
                    )
                }
            }
        }
    }

    fun update(userId: Long, interviewId: Long, patch: PatchInterviewRequest): InterviewRecord? {
        val existing = findById(userId, interviewId) ?: return null
        val setClauses = mutableListOf<String>()
        val binders = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()
        patch.round_name?.let { name ->
            setClauses += "round_name = ?"
            binders += { ps, idx -> ps.setString(idx, name.takeIf { it.isNotBlank() }); idx + 1 }
        }
        patch.scheduled_at?.let { raw ->
            val ins = InterviewValidation.parseInstant(raw)
            setClauses += "scheduled_at = ?"
            binders += { ps, idx ->
                if (ins == null) ps.setNull(idx, java.sql.Types.TIMESTAMP) else ps.setTimestamp(idx, Timestamp.from(ins))
                idx + 1
            }
        }
        patch.status?.let {
            setClauses += "status = ?"
            binders += { ps, idx -> ps.setString(idx, it); idx + 1 }
        }
        patch.notes?.let {
            setClauses += "notes = ?"
            binders += { ps, idx -> ps.setString(idx, it); idx + 1 }
        }
        if (setClauses.isEmpty()) return existing

        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE interviews
                SET ${setClauses.joinToString(", ")}
                WHERE id = ?
                  AND application_id IN (SELECT id FROM applications WHERE user_id = ?)
                """.trimIndent(),
            ).use { ps ->
                var idx = 1
                for (b in binders) idx = b(ps, idx)
                ps.setLong(idx++, interviewId)
                ps.setLong(idx, userId)
                if (ps.executeUpdate() == 0) return null
            }
        }
        return findById(userId, interviewId)
    }

    fun delete(userId: Long, interviewId: Long): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                DELETE FROM interviews
                WHERE id = ?
                  AND application_id IN (SELECT id FROM applications WHERE user_id = ?)
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, interviewId)
                ps.setLong(2, userId)
                return ps.executeUpdate() > 0
            }
        }
    }

    private fun readRecord(rs: java.sql.ResultSet): InterviewRecord {
        val ts = rs.getTimestamp("scheduled_at")
        val scheduledIso = ts?.toInstant()?.toString()
        return InterviewRecord(
            id = rs.getLong("id"),
            applicationId = rs.getLong("application_id"),
            roundName = rs.getString("round_name"),
            scheduledAtIso = scheduledIso,
            status = rs.getString("status"),
            notes = rs.getString("notes"),
        )
    }
}
