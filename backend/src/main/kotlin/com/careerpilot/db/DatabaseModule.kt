package com.careerpilot.db

import com.careerpilot.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection

/**
 * Day 5 decision: Direct schema.sql compatibility.
 *
 * Schema is applied externally (e.g. Docker MySQL init scripts mounting `database/schema.sql`).
 * The backend connects and queries, but does NOT migrate yet (no Flyway).
 */
class DatabaseModule(private val cfg: DbConfig) {
    private var dataSource: HikariDataSource? = null

    fun openConnection(): Connection {
        val ds = dataSource ?: createDataSource().also { dataSource = it }
        return ds.connection
    }

    /** Runs work on a single connection with commit/rollback (used for multi-step writes). */
    fun <T> transaction(block: (Connection) -> T): T {
        openConnection().use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (t: Throwable) {
                conn.rollback()
                throw t
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }
    }

    fun ping(): DbPingResult {
        return try {
            openConnection().use { conn ->
                conn.createStatement().use { st ->
                    st.execute("SELECT 1")
                }
            }
            DbPingResult(ok = true)
        } catch (t: Throwable) {
            DbPingResult(ok = false, error = t.message ?: t::class.java.simpleName)
        }
    }

    fun close() {
        dataSource?.close()
        dataSource = null
    }

    private fun createDataSource(): HikariDataSource {
        val hc = HikariConfig().apply {
            jdbcUrl = cfg.jdbcUrlOverride ?: jdbcUrl()
            username = cfg.user
            password = cfg.password
            maximumPoolSize = 10
            minimumIdle = 0
            connectionTimeout = 5_000
            validationTimeout = 2_000
            idleTimeout = 60_000
            maxLifetime = 10 * 60_000
            poolName = "careerpilot-hikari"
        }
        return HikariDataSource(hc)
    }

    private fun jdbcUrl(): String {
        // MySQL 8.x defaults, local-friendly.
        val params = listOf(
            "useSSL=false",
            "allowPublicKeyRetrieval=true",
            "serverTimezone=UTC",
        ).joinToString("&")
        return "jdbc:mysql://${cfg.host}:${cfg.port}/${cfg.name}?$params"
    }
}

data class DbPingResult(
    val ok: Boolean,
    val error: String? = null,
)

