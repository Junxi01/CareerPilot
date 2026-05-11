package com.careerpilot

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicApiSmokeTest {
    private fun env(dbName: String): Map<String, String> =
        mapOf(
            "DB_JDBC_URL" to "jdbc:h2:mem:$dbName;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "DB_USER" to "sa",
            "DB_PASSWORD" to "",
            "JWT_SECRET" to "test-secret",
            "APP_NAME" to "test-backend",
            "APP_VERSION" to "9.9.9-ci",
        )

    @Test
    fun `api version is public JSON`() = testApplication {
        application { moduleWithEnv(env("api_version_route")) }

        val resp = client.get("/api/version")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("\"success\":true"))
        assertTrue(body.contains("\"name\":\"test-backend\""))
        assertTrue(body.contains("\"version\":\"9.9.9-ci\""))
    }

    @Test
    fun `health db responds connected for in-memory h2`() = testApplication {
        application { moduleWithEnv(env("health_db_h2_public")) }

        val resp = client.get("/health/db")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"status\":\"connected\""))
    }
}
