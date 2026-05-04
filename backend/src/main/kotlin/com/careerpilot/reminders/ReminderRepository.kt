package com.careerpilot.reminders

import com.careerpilot.applications.ApplicationRepository
import com.careerpilot.db.DatabaseModule
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReminderRepository(
    private val db: DatabaseModule,
    private val applications: ApplicationRepository,
) {
    fun listForUser(userId: Long): List<ReminderRecord> {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, application_id, reminder_type, due_at, message, done
                FROM reminders
                WHERE user_id = ?
                ORDER BY due_at ASC, id ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ReminderRecord>()
                    while (rs.next()) out += readRecord(rs)
                    return out
                }
            }
        }
    }

    /**
     * Reminders whose `due_at` falls on the **current calendar day in the JVM default timezone**
     * (usually the host OS zone — see README). Window is [start of day, start of next day).
     */
    fun listDueTodayServerLocal(userId: Long): List<ReminderRecord> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant()
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, application_id, reminder_type, due_at, message, done
                FROM reminders
                WHERE user_id = ?
                  AND due_at >= ? AND due_at < ?
                ORDER BY due_at ASC, id ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setTimestamp(2, Timestamp.from(start))
                ps.setTimestamp(3, Timestamp.from(end))
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ReminderRecord>()
                    while (rs.next()) out += readRecord(rs)
                    return out
                }
            }
        }
    }

    fun findById(userId: Long, reminderId: Long): ReminderRecord? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, application_id, reminder_type, due_at, message, done
                FROM reminders
                WHERE user_id = ? AND id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, reminderId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return readRecord(rs)
                }
            }
        }
    }

    fun insertForApplication(
        userId: Long,
        applicationId: Long,
        type: ReminderType,
        dueAt: Instant,
        message: String,
    ): ReminderRecord? {
        if (!applications.isOwnedByUser(userId, applicationId)) return null
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO reminders (user_id, application_id, reminder_type, due_at, message, done)
                VALUES (?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, applicationId)
                ps.setString(3, type.name)
                ps.setTimestamp(4, Timestamp.from(dueAt))
                ps.setString(5, message)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    if (!keys.next()) error("insert reminder: no key")
                    val id = keys.getLong(1)
                    return ReminderRecord(
                        id = id,
                        applicationId = applicationId,
                        type = type,
                        dueAtIso = dueAt.toString(),
                        message = message,
                        done = false,
                    )
                }
            }
        }
    }

    fun setDone(userId: Long, reminderId: Long): ReminderRecord? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE reminders SET done = 1 WHERE user_id = ? AND id = ? AND done = 0
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, reminderId)
                if (ps.executeUpdate() == 0) {
                    val existing = findById(userId, reminderId) ?: return null
                    return existing
                }
            }
        }
        return findById(userId, reminderId)
    }

    fun delete(userId: Long, reminderId: Long): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement("DELETE FROM reminders WHERE user_id = ? AND id = ?").use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, reminderId)
                return ps.executeUpdate() > 0
            }
        }
    }

    private fun readRecord(rs: java.sql.ResultSet): ReminderRecord {
        val appId = rs.getLong("application_id")
        val applicationId = if (rs.wasNull()) null else appId
        val typeStr = rs.getString("reminder_type")
        val type =
            try {
                ReminderType.valueOf(typeStr.uppercase())
            } catch (_: Exception) {
                ReminderType.CUSTOM
            }
        return ReminderRecord(
            id = rs.getLong("id"),
            applicationId = applicationId,
            type = type,
            dueAtIso = rs.getTimestamp("due_at").toInstant().toString(),
            message = rs.getString("message"),
            done = rs.getBoolean("done"),
        )
    }
}
