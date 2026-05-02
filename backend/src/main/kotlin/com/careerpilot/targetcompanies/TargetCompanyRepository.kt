package com.careerpilot.targetcompanies

import com.careerpilot.db.DatabaseModule
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.serializer
import java.sql.Statement

class TargetCompanyRepository(private val db: DatabaseModule) {
    private val json = Json

    fun listByUser(userId: Long): List<TargetCompanyRecord> {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, name, careers_page_url, active,
                       locations_json, tech_keywords_json, notes
                FROM target_companies
                WHERE user_id = ? AND active = 1
                ORDER BY id DESC
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<TargetCompanyRecord>()
                    while (rs.next()) {
                        out +=
                            TargetCompanyRecord(
                                id = rs.getLong("id"),
                                userId = rs.getLong("user_id"),
                                name = rs.getString("name"),
                                careersUrl = rs.getString("careers_page_url"),
                                active = rs.getBoolean("active"),
                                locations = decodeStringList(rs.getString("locations_json")),
                                keywords = decodeStringList(rs.getString("tech_keywords_json")),
                                notes = rs.getString("notes"),
                            )
                    }
                    return out
                }
            }
        }
    }

    fun findById(userId: Long, id: Long): TargetCompanyRecord? {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                SELECT id, user_id, name, careers_page_url, active,
                       locations_json, tech_keywords_json, notes
                FROM target_companies
                WHERE user_id = ? AND id = ?
                LIMIT 1
                """.trimIndent(),
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, id)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return TargetCompanyRecord(
                        id = rs.getLong("id"),
                        userId = rs.getLong("user_id"),
                        name = rs.getString("name"),
                        careersUrl = rs.getString("careers_page_url"),
                        active = rs.getBoolean("active"),
                        locations = decodeStringList(rs.getString("locations_json")),
                        keywords = decodeStringList(rs.getString("tech_keywords_json")),
                        notes = rs.getString("notes"),
                    )
                }
            }
        }
    }

    fun insert(
        userId: Long,
        name: String,
        careersUrl: String,
        keywords: List<String>,
        locations: List<String>,
        active: Boolean,
        notes: String?,
    ): TargetCompanyRecord {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO target_companies (
                  user_id, name, careers_page_url, active,
                  locations_json, tech_keywords_json, notes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setString(2, name)
                ps.setString(3, careersUrl)
                ps.setBoolean(4, active)
                ps.setString(5, encodeStringList(locations))
                ps.setString(6, encodeStringList(keywords))
                ps.setString(7, notes)
                ps.executeUpdate()
                ps.generatedKeys.use { keys ->
                    if (!keys.next()) error("Insert target company: missing generated key")
                    val id = keys.getLong(1)
                    return TargetCompanyRecord(
                        id = id,
                        userId = userId,
                        name = name,
                        careersUrl = careersUrl,
                        keywords = keywords,
                        locations = locations,
                        active = active,
                        notes = notes,
                    )
                }
            }
        }
    }

    fun update(userId: Long, id: Long, patch: TargetCompanyPatch): TargetCompanyRecord? {
        val setClauses = mutableListOf<String>()
        val params = mutableListOf<(java.sql.PreparedStatement, Int) -> Int>()

        fun <T> addSet(column: String, value: T?, binder: (java.sql.PreparedStatement, Int, T) -> Unit) {
            if (value == null) return
            setClauses += "$column = ?"
            params += { ps, idx -> binder(ps, idx, value); idx + 1 }
        }

        addSet("name", patch.name) { ps, i, v -> ps.setString(i, v) }
        addSet("careers_page_url", patch.careersUrl) { ps, i, v -> ps.setString(i, v) }
        addSet("tech_keywords_json", patch.keywords?.let { encodeStringList(it) }) { ps, i, v -> ps.setString(i, v) }
        addSet("locations_json", patch.locations?.let { encodeStringList(it) }) { ps, i, v -> ps.setString(i, v) }
        addSet("active", patch.active) { ps, i, v -> ps.setBoolean(i, v) }
        // notes: allow explicit null (clear) by differentiating "provided but null" isn't possible in JSON with explicitNulls=false.
        // For now: if notes is provided as empty string, store empty string; if omitted, no change.
        if (patch.notes != null) {
            setClauses += "notes = ?"
            params += { ps, idx -> ps.setString(idx, patch.notes); idx + 1 }
        }

        if (setClauses.isEmpty()) return findById(userId, id)

        db.openConnection().use { conn ->
            conn.prepareStatement(
                """
                UPDATE target_companies
                SET ${setClauses.joinToString(", ")}
                WHERE user_id = ? AND id = ?
                """.trimIndent(),
            ).use { ps ->
                var idx = 1
                for (p in params) idx = p(ps, idx)
                ps.setLong(idx++, userId)
                ps.setLong(idx, id)
                val updated = ps.executeUpdate()
                if (updated == 0) return null
            }
        }
        return findById(userId, id)
    }

    /**
     * Soft delete strategy: mark company inactive (active=false).
     * Keeping the record avoids accidental data loss and preserves referential integrity for related data.
     */
    fun softDelete(userId: Long, id: Long): Boolean {
        db.openConnection().use { conn ->
            conn.prepareStatement(
                "UPDATE target_companies SET active = 0 WHERE user_id = ? AND id = ?",
            ).use { ps ->
                ps.setLong(1, userId)
                ps.setLong(2, id)
                return ps.executeUpdate() > 0
            }
        }
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
}

private val StringListSerializer = ListSerializer(String.serializer())

