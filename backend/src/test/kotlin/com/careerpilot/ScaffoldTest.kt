package com.careerpilot

import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScaffoldTest {
    private fun env(dbName: String): Map<String, String> =
        mapOf(
            "DB_JDBC_URL" to "jdbc:h2:mem:$dbName;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "DB_USER" to "sa",
            "DB_PASSWORD" to "",
            "JWT_SECRET" to "test-secret",
        )

    @Test
    fun `health endpoint returns ok`() = testApplication {
        application { moduleWithEnv(env("health_ok")) }
        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("\"status\":\"ok\""))
    }

    @Test
    fun `db health returns down when unreachable`() = testApplication {
        application {
            moduleWithEnv(
                mapOf(
                    "DB_HOST" to "127.0.0.1",
                    "DB_PORT" to "65000",
                    "JWT_SECRET" to "test-secret",
                ),
            )
        }
        val resp = client.get("/health/db")
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
        assertTrue(resp.bodyAsText().contains("\"status\":\"down\""))
        assertTrue(resp.bodyAsText().contains("\"error\""))
    }

    @Test
    fun `register and login and me flow`() = testApplication {
        val e = env("auth_flow")
        application { moduleWithEnv(e) }

        // Create users table for H2 test DB
        java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE users (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      email VARCHAR(255) NOT NULL,
                      password_hash VARCHAR(255) NULL,
                      display_name VARCHAR(190) NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE UNIQUE INDEX uq_users_email ON users(email);")

                st.execute(
                    """
                    CREATE TABLE target_companies (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      name VARCHAR(190) NOT NULL,
                      careers_page_url VARCHAR(2048) NOT NULL,
                      active BOOLEAN NOT NULL DEFAULT TRUE,
                      locations_json TEXT NULL,
                      role_keywords_json TEXT NULL,
                      tech_keywords_json TEXT NULL,
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_target_companies_user_id ON target_companies(user_id);")
            }
        }

        val register = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.com","password":"password123","displayName":"A"}""")
        }
        assertEquals(HttpStatusCode.Created, register.status)
        val regBody = register.bodyAsText()
        assertTrue(regBody.contains("\"success\":true"))
        assertTrue(regBody.contains("\"token\""))
        // Verify password is not stored in plaintext
        java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT password_hash FROM users WHERE email='a@b.com'").use { rs ->
                    assertTrue(rs.next())
                    val hash = rs.getString(1)
                    assertTrue(hash != "password123")
                    assertTrue(hash.startsWith("$2"))
                }
            }
        }

        val dup = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        val badPw = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.com","password":"wrongpass"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, badPw.status)

        val login = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"a@b.com","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val loginBody = login.bodyAsText()
        val token =
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(loginBody)?.groupValues?.get(1)
                ?: error("Missing token in login response: $loginBody")

        val meNoToken = client.get("/api/me")
        assertEquals(HttpStatusCode.Unauthorized, meNoToken.status)

        val me = client.get("/api/me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, me.status)
        assertTrue(me.bodyAsText().contains("\"email\":\"a@b.com\""))
    }

    @Test
    fun `target companies CRUD and ownership isolation`() = testApplication {
        val e = env("target_companies")
        application { moduleWithEnv(e) }

        // Tables for H2 test DB (shared connection string)
        java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE users (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      email VARCHAR(255) NOT NULL,
                      password_hash VARCHAR(255) NULL,
                      display_name VARCHAR(190) NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE UNIQUE INDEX uq_users_email ON users(email);")
                st.execute(
                    """
                    CREATE TABLE target_companies (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      name VARCHAR(190) NOT NULL,
                      careers_page_url VARCHAR(2048) NOT NULL,
                      active BOOLEAN NOT NULL DEFAULT TRUE,
                      locations_json TEXT NULL,
                      role_keywords_json TEXT NULL,
                      tech_keywords_json TEXT NULL,
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_target_companies_user_id ON target_companies(user_id);")

                st.execute(
                    """
                    CREATE TABLE job_leads (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      company_id BIGINT NOT NULL,
                      title VARCHAR(255) NOT NULL,
                      url VARCHAR(2048) NOT NULL,
                      location VARCHAR(255) NULL,
                      raw_description TEXT NULL,
                      discovered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      match_score DECIMAL(5,2) NULL,
                      saved_to_applications BOOLEAN NOT NULL DEFAULT FALSE,
                      status VARCHAR(32) NOT NULL DEFAULT 'new',
                      source VARCHAR(64) NOT NULL DEFAULT 'career_page',
                      matched_keywords_json TEXT NULL,
                      raw_json TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
            }
        }

        suspend fun registerAndLogin(email: String): String {
            val reg = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123","displayName":"X"}""")
            }
            assertEquals(HttpStatusCode.Created, reg.status)
            val login = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val body = login.bodyAsText()
            return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: error("Missing token in login response: $body")
        }

        val tokenA = registerAndLogin("a@company.com")
        val tokenB = registerAndLogin("b@company.com")

        // Auth required
        val unauthList = client.get("/api/target-companies")
        assertEquals(HttpStatusCode.Unauthorized, unauthList.status)

        // Create A company
        val createA = client.post("/api/target-companies") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "name":"Acme",
                  "careers_url":"https://example.com/careers",
                  "keywords":["kotlin","ktor"],
                  "locations":["SF"],
                  "active":true,
                  "notes":"note-1"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createA.status)
        val createdBody = createA.bodyAsText()
        assertTrue(createdBody.contains("\"success\":true"))
        val idA = Regex("\"id\"\\s*:\\s*(\\d+)").find(createdBody)?.groupValues?.get(1)?.toLong()
            ?: error("Missing id in create response: $createdBody")

        // List for A includes it
        val listA = client.get("/api/target-companies") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listA.status)
        assertTrue(listA.bodyAsText().contains("\"id\":$idA"))

        // B cannot read A's company (404)
        val getAsB = client.get("/api/target-companies/$idA") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.NotFound, getAsB.status)

        // Patch A company
        val patchA = client.patch("/api/target-companies/$idA") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"active":false,"keywords":["kotlin","mysql"],"locations":["SF","Remote"]}""")
        }
        assertEquals(HttpStatusCode.OK, patchA.status)
        val patchedBody = patchA.bodyAsText()
        assertTrue(patchedBody.contains("\"active\":false"))
        assertTrue(patchedBody.contains("\"keywords\":[\"kotlin\",\"mysql\"]"))
        assertTrue(patchedBody.contains("\"locations\":[\"SF\",\"Remote\"]"))

        // DELETE is soft delete (sets active=false)
        val delA = client.delete("/api/target-companies/$idA") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, delA.status)

        val listAfterDelete = client.get("/api/target-companies") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listAfterDelete.status)
        assertTrue(!listAfterDelete.bodyAsText().contains("\"id\":$idA"))
    }

    @Test
    fun `job leads CRUD ownership dedupe and filters`() = testApplication {
        val e = env("job_leads")
        application { moduleWithEnv(e) }

        java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE users (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      email VARCHAR(255) NOT NULL,
                      password_hash VARCHAR(255) NULL,
                      display_name VARCHAR(190) NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE UNIQUE INDEX uq_users_email ON users(email);")
                st.execute(
                    """
                    CREATE TABLE target_companies (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      name VARCHAR(190) NOT NULL,
                      careers_page_url VARCHAR(2048) NOT NULL,
                      active BOOLEAN NOT NULL DEFAULT TRUE,
                      locations_json TEXT NULL,
                      role_keywords_json TEXT NULL,
                      tech_keywords_json TEXT NULL,
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_target_companies_user_id ON target_companies(user_id);")
                st.execute(
                    """
                    CREATE TABLE job_leads (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      company_id BIGINT NOT NULL,
                      title VARCHAR(255) NOT NULL,
                      url VARCHAR(2048) NOT NULL,
                      location VARCHAR(255) NULL,
                      raw_description TEXT NULL,
                      discovered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      match_score DECIMAL(5,2) NULL,
                      saved_to_applications BOOLEAN NOT NULL DEFAULT FALSE,
                      status VARCHAR(32) NOT NULL DEFAULT 'new',
                      source VARCHAR(64) NOT NULL DEFAULT 'career_page',
                      matched_keywords_json TEXT NULL,
                      raw_json TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_job_leads_company_id ON job_leads(company_id);")
            }
        }

        suspend fun registerAndLogin(email: String): String {
            val reg = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123","displayName":"X"}""")
            }
            assertEquals(HttpStatusCode.Created, reg.status)
            val login = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"$email","password":"password123"}""")
            }
            assertEquals(HttpStatusCode.OK, login.status)
            val body = login.bodyAsText()
            return Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?: error("Missing token in login response: $body")
        }

        val tokenA = registerAndLogin("a@jl.com")
        val tokenB = registerAndLogin("b@jl.com")

        // Create companies for each user directly
        val companyAId =
            java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
                c.prepareStatement(
                    "INSERT INTO target_companies (user_id, name, careers_page_url, active) VALUES (1, 'A', 'https://a.com/c', TRUE)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.executeUpdate()
                    ps.generatedKeys.use { k -> k.next(); k.getLong(1) }
                }
            }
        val companyBId =
            java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
                c.prepareStatement(
                    "INSERT INTO target_companies (user_id, name, careers_page_url, active) VALUES (2, 'B', 'https://b.com/c', TRUE)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.executeUpdate()
                    ps.generatedKeys.use { k -> k.next(); k.getLong(1) }
                }
            }

        // Auth required
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/job-leads").status)

        val create1 = client.post("/api/job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyAId,
                  "role_title":"Backend Engineer",
                  "job_url":"https://jobs.example.com/1",
                  "location":"Remote",
                  "matched_keywords":["kotlin","ktor"],
                  "match_score": 87.5,
                  "saved_to_applications": false
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, create1.status)
        val body1 = create1.bodyAsText()
        assertTrue(body1.contains("\"company_name\":\"A\""))
        val leadId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(body1)?.groupValues?.get(1)?.toLong()
                ?: error("Missing id: $body1")

        // B cannot read A lead
        val getAsB = client.get("/api/job-leads/$leadId") { header(HttpHeaders.Authorization, "Bearer $tokenB") }
        assertEquals(HttpStatusCode.NotFound, getAsB.status)

        // Dedupe by job_url per user (same url under any company) -> 409
        val dup = client.post("/api/job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyAId,
                  "role_title":"Backend Engineer II",
                  "job_url":"https://jobs.example.com/1",
                  "matched_keywords":["kotlin"],
                  "match_score": 80
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Conflict, dup.status)

        // But B can create same url for their own user
        val createB = client.post("/api/job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyBId,
                  "role_title":"Backend Engineer",
                  "job_url":"https://jobs.example.com/1",
                  "matched_keywords":["kotlin"],
                  "match_score": 50
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createB.status)

        // Patch saved_to_applications and keywords
        val patch = client.patch("/api/job-leads/$leadId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"saved_to_applications":true,"matched_keywords":["kotlin","mysql"],"match_score":92}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)
        assertTrue(patch.bodyAsText().contains("\"saved_to_applications\":true"))

        // Filters: company_id
        val listByCompany = client.get("/api/job-leads?company_id=$companyAId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listByCompany.status)
        assertTrue(listByCompany.bodyAsText().contains("\"id\":$leadId"))
        assertTrue(listByCompany.bodyAsText().contains("\"company_name\":\"A\""))

        // Filters: keyword
        val listByKeyword = client.get("/api/job-leads?keyword=mysql") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listByKeyword.status)
        assertTrue(listByKeyword.bodyAsText().contains("\"id\":$leadId"))

        // Filters: min_match_score
        val listByMinScore = client.get("/api/job-leads?min_match_score=90") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listByMinScore.status)
        assertTrue(listByMinScore.bodyAsText().contains("\"id\":$leadId"))

        // Filters: saved_to_applications
        val listBySaved = client.get("/api/job-leads?saved_to_applications=true") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listBySaved.status)
        assertTrue(listBySaved.bodyAsText().contains("\"id\":$leadId"))

        // Delete then 404
        val del = client.delete("/api/job-leads/$leadId") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
        assertEquals(HttpStatusCode.OK, del.status)
        val getAfter = client.get("/api/job-leads/$leadId") { header(HttpHeaders.Authorization, "Bearer $tokenA") }
        assertEquals(HttpStatusCode.NotFound, getAfter.status)
    }
}

