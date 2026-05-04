package com.careerpilot.dashboard

import com.careerpilot.db.DatabaseModule
import java.sql.Connection
import java.sql.Date
import java.sql.Timestamp
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

class DashboardRepository(private val db: DatabaseModule) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    /** Inclusive start of the current ISO week in the JVM default zone: Monday 00:00 through `now` for week-scoped counts. */
    private fun weekStartInstant(): Instant {
        val today = LocalDate.now(zone)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return monday.atStartOfDay(zone).toInstant()
    }

    private fun endOfTodayExclusive(): Instant =
        LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant()

    private fun todaySqlDate(): Date = Date.valueOf(LocalDate.now(zone))

    private fun horizon14EndSqlDate(): Date = Date.valueOf(LocalDate.now(zone).plusDays(14))

    private fun horizon14EndInstant(): Instant =
        LocalDate.now(zone).plusDays(14).plusDays(1).atStartOfDay(zone).toInstant()

    fun loadStats(userId: Long): DashboardStatsDto {
        val weekStart = Timestamp.from(weekStartInstant())
        val weekEnd = Timestamp.from(Instant.now())
        val reminderDueBefore = Timestamp.from(endOfTodayExclusive())
        val today = todaySqlDate()

        db.openConnection().use { conn ->
            val totalApplications = scalarLong(conn, "SELECT COUNT(*) FROM applications WHERE user_id = ?", userId)
            val applicationsThisWeek =
                scalarLongBetween(
                    conn,
                    "SELECT COUNT(*) FROM applications WHERE user_id = ? AND created_at >= ? AND created_at <= ?",
                    userId,
                    weekStart,
                    weekEnd,
                )
            val interviewsCount =
                scalarLong(
                    conn,
                    """
                    SELECT COUNT(*) FROM interviews i
                    JOIN applications a ON a.id = i.application_id
                    WHERE a.user_id = ?
                    """.trimIndent(),
                    userId,
                )
            val offersCount =
                scalarLong(conn, "SELECT COUNT(*) FROM applications WHERE user_id = ? AND status = 'OFFER'", userId)
            val rejectionsCount =
                scalarLong(conn, "SELECT COUNT(*) FROM applications WHERE user_id = ? AND status = 'REJECTED'", userId)

            val pipelineDen =
                scalarLong(
                    conn,
                    """
                    SELECT COUNT(*) FROM applications
                    WHERE user_id = ?
                      AND status IN ('APPLIED','ONLINE_ASSESSMENT','INTERVIEW','OFFER','REJECTED','GHOSTED')
                    """.trimIndent(),
                    userId,
                )
            val pipelineNum =
                scalarLong(
                    conn,
                    """
                    SELECT COUNT(*) FROM applications
                    WHERE user_id = ?
                      AND status IN ('ONLINE_ASSESSMENT','INTERVIEW','OFFER','REJECTED')
                    """.trimIndent(),
                    userId,
                )
            val responseRateRaw = if (pipelineDen == 0L) 0.0 else pipelineNum * 100.0 / pipelineDen
            val responseRate = String.format(Locale.US, "%.2f", responseRateRaw).toDouble()

            val remindersDue =
                scalarLong(
                    conn,
                    "SELECT COUNT(*) FROM reminders WHERE user_id = ? AND done = 0 AND due_at < ?",
                    userId,
                    reminderDueBefore,
                )
            val appFollowUpsDue =
                scalarLong(
                    conn,
                    """
                    SELECT COUNT(*) FROM applications
                    WHERE user_id = ? AND next_follow_up_date IS NOT NULL AND next_follow_up_date <= ?
                    """.trimIndent(),
                    userId,
                    today,
                )
            val followUpsDue = remindersDue + appFollowUpsDue

            val leadsWeek =
                scalarLongBetween(
                    conn,
                    """
                    SELECT COUNT(*) FROM job_leads jl
                    JOIN target_companies tc ON tc.id = jl.company_id
                    WHERE tc.user_id = ? AND jl.discovered_at >= ? AND jl.discovered_at <= ?
                    """.trimIndent(),
                    userId,
                    weekStart,
                    weekEnd,
                )

            val prepDue =
                scalarLong(
                    conn,
                    """
                    SELECT COUNT(*) FROM prep_tasks pt
                    JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
                    JOIN applications a ON a.id = p.application_id
                    WHERE a.user_id = ? AND pt.due_date = ? AND pt.status <> 'done'
                    """.trimIndent(),
                    userId,
                    today,
                )

            return DashboardStatsDto(
                total_applications = totalApplications,
                applications_this_week = applicationsThisWeek,
                interviews_count = interviewsCount,
                offers_count = offersCount,
                rejections_count = rejectionsCount,
                response_rate = responseRate,
                follow_ups_due = followUpsDue,
                job_leads_discovered_this_week = leadsWeek,
                prep_tasks_due_today = prepDue,
            )
        }
    }

    /**
     * Actionable follow-ups in the next 14 days: applications with `next_follow_up_date` on or before that horizon,
     * plus open reminders with `due_at` before the exclusive instant at start of day 15 (sorted soonest first).
     * This is a planning window; [loadStats].follow_ups_due counts only items due on or before today (see README).
     */
    fun listFollowUps(userId: Long, limit: Int = 50): List<DashboardFollowUpDto> {
        val horizonEnd = horizon14EndSqlDate()
        val horizonEndTs = Timestamp.from(horizon14EndInstant())

        val out = mutableListOf<Pair<Instant, DashboardFollowUpDto>>()

        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT a.id, a.id AS application_id,
                       CONCAT(COALESCE(a.role_title, ''), ' — follow up') AS title,
                       a.next_follow_up_date AS due,
                       tc.name AS company_name
                FROM applications a
                JOIN target_companies tc ON tc.id = a.company_id
                WHERE a.user_id = ?
                  AND a.next_follow_up_date IS NOT NULL
                  AND a.next_follow_up_date <= ?
                ORDER BY a.next_follow_up_date ASC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setDate(2, horizonEnd)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val dueDate = rs.getDate("due")?.toLocalDate() ?: continue
                        val dueStr = dueDate.toString()
                        val sort = dueDate.atStartOfDay(zone).toInstant()
                        out +=
                            sort to
                                DashboardFollowUpDto(
                                    kind = "application_follow_up",
                                    id = rs.getLong("id"),
                                    application_id = rs.getLong("application_id"),
                                    title = rs.getString("title"),
                                    due = dueStr,
                                    company_name = rs.getString("company_name"),
                                )
                    }
                }
            }

            conn.prepareStatement(
                """
                SELECT r.id, r.application_id, r.message, r.due_at
                FROM reminders r
                WHERE r.user_id = ? AND r.done = 0 AND r.due_at < ?
                ORDER BY r.due_at ASC
                LIMIT ?
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setTimestamp(2, horizonEndTs)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val dueTs = rs.getTimestamp("due_at") ?: continue
                        val ins = dueTs.toInstant()
                        val appIdRaw = rs.getLong("application_id")
                        val appId = if (rs.wasNull()) null else appIdRaw
                        out +=
                            ins to
                                DashboardFollowUpDto(
                                    kind = "reminder",
                                    id = rs.getLong("id"),
                                    application_id = appId,
                                    title = rs.getString("message") ?: "Reminder",
                                    due = ins.toString(),
                                    company_name = null,
                                )
                    }
                }
            }
        }

        return out.sortedBy { it.first }.map { it.second }.take(limit)
    }

    fun listPrepSummary(userId: Long): DashboardPrepSummaryDto {
        val today = todaySqlDate()
        val items = mutableListOf<PrepSummaryItemDto>()
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT pt.id, a.id AS application_id, tc.name AS company_name, a.role_title,
                       pt.label, pt.due_date AS due_date, pt.status
                FROM prep_tasks pt
                JOIN ai_interview_plans p ON p.id = pt.ai_interview_plan_id
                JOIN applications a ON a.id = p.application_id
                JOIN target_companies tc ON tc.id = a.company_id
                WHERE a.user_id = ? AND pt.due_date = ? AND pt.status <> 'done'
                ORDER BY pt.label ASC
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setDate(2, today)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val due = rs.getDate("due_date")?.toLocalDate()?.toString()
                        items +=
                            PrepSummaryItemDto(
                                id = rs.getLong("id"),
                                application_id = rs.getLong("application_id"),
                                company_name = rs.getString("company_name"),
                                role_title = rs.getString("role_title"),
                                label = rs.getString("label"),
                                due_date = due,
                                status = rs.getString("status"),
                            )
                    }
                }
            }
        }
        return DashboardPrepSummaryDto(items = items)
    }

    private fun scalarLong(conn: Connection, sql: String, userId: Long): Long {
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, userId)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return 0L
                val v = rs.getLong(1)
                return if (rs.wasNull()) 0L else v
            }
        }
    }

    private fun scalarLong(conn: Connection, sql: String, userId: Long, p2: Timestamp): Long {
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, userId)
            ps.setTimestamp(2, p2)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return 0L
                val v = rs.getLong(1)
                return if (rs.wasNull()) 0L else v
            }
        }
    }

    private fun scalarLong(conn: Connection, sql: String, userId: Long, p2: Date): Long {
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, userId)
            ps.setDate(2, p2)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return 0L
                val v = rs.getLong(1)
                return if (rs.wasNull()) 0L else v
            }
        }
    }

    private fun scalarLongBetween(conn: Connection, sql: String, userId: Long, start: Timestamp, end: Timestamp): Long {
        conn.prepareStatement(sql).use { ps ->
            ps.setLong(1, userId)
            ps.setTimestamp(2, start)
            ps.setTimestamp(3, end)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return 0L
                val v = rs.getLong(1)
                return if (rs.wasNull()) 0L else v
            }
        }
    }
}
