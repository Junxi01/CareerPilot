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
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

        // Permanent remove via POST `{id}/delete` (preferred for browser fetch); DELETE `{id}` is equivalent.
        val delA = client.post("/api/target-companies/$idA/delete") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, delA.status)

        val listAfterDelete = client.get("/api/target-companies") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listAfterDelete.status)
        val bodyAfterDelete = listAfterDelete.bodyAsText()
        assertFalse(bodyAfterDelete.contains("\"id\":$idA"))

        val getDeleted = client.get("/api/target-companies/$idA") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.NotFound, getDeleted.status)
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
                  "role_title":"Product Manager",
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

        fun parseLeadId(respBody: String): Long =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(respBody)?.groupValues?.get(1)?.toLong()
                ?: error("Missing id in: $respBody")

        val createWwOnly = client.post("/api/job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyAId,
                  "role_title":"Marketing",
                  "job_url":"https://jobs.example.com/ww-slot",
                  "matched_keywords":["ww"],
                  "saved_to_applications": false
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createWwOnly.status)
        val leadWwId = parseLeadId(createWwOnly.bodyAsText())

        val createBackendOnly = client.post("/api/job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyAId,
                  "role_title":"Analyst",
                  "job_url":"https://jobs.example.com/backend-slot",
                  "matched_keywords":["backend"],
                  "saved_to_applications": false
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createBackendOnly.status)
        val leadBackendId = parseLeadId(createBackendOnly.bodyAsText())

        // Company + keyword: only the backend-keyword row survives
        val listCompanyAndKw =
            client.get("/api/job-leads?company_id=$companyAId&keyword=backend") {
                header(HttpHeaders.Authorization, "Bearer $tokenA")
            }
        assertEquals(HttpStatusCode.OK, listCompanyAndKw.status)
        val comboBody = listCompanyAndKw.bodyAsText()
        assertTrue(comboBody.contains("\"id\":$leadBackendId"))
        assertFalse(comboBody.contains("\"id\":$leadWwId"))
        assertFalse(comboBody.contains("\"id\":$leadId"))

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

    @Test
    fun `applications CRUD save from lead and filters`() = testApplication {
        val e = env("applications_day9")
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
                st.execute(
                    """
                    CREATE TABLE applications (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      company_id BIGINT NOT NULL,
                      job_lead_id BIGINT NULL,
                      role_title VARCHAR(255) NOT NULL,
                      job_url VARCHAR(2048) NOT NULL,
                      status VARCHAR(32) NOT NULL,
                      tech_stack_json TEXT NULL,
                      salary_range VARCHAR(255) NULL,
                      applied_at DATE NULL,
                      next_follow_up_date DATE NULL,
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE UNIQUE INDEX uq_app_user_url ON applications(user_id, job_url);")
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
                ?: error("Missing token: $body")
        }

        val tokenA = registerAndLogin("app@a.com")
        val tokenB = registerAndLogin("app@b.com")

        val companyAId =
            java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
                c.prepareStatement(
                    "INSERT INTO target_companies (user_id, name, careers_page_url, active) VALUES (1, 'Acme', 'https://acme.com/c', TRUE)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.executeUpdate()
                    ps.generatedKeys.use { k -> k.next(); k.getLong(1) }
                }
            }
        val companyBId =
            java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
                c.prepareStatement(
                    "INSERT INTO target_companies (user_id, name, careers_page_url, active) VALUES (2, 'Beta', 'https://beta.com/c', TRUE)",
                    java.sql.Statement.RETURN_GENERATED_KEYS,
                ).use { ps ->
                    ps.executeUpdate()
                    ps.generatedKeys.use { k -> k.next(); k.getLong(1) }
                }
            }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/applications").status)

        val createApp = client.post("/api/applications") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_name":"Acme",
                  "role_title":"Engineer",
                  "job_url":"https://jobs.example.com/manual-1",
                  "status":"applied",
                  "tech_stack":["kotlin"],
                  "salary_range":"100-120k",
                  "applied_date":"2026-05-01",
                  "notes":"hello"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createApp.status)
        assertTrue(createApp.bodyAsText().contains("\"company_name\":\"Acme\""))
        val appId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(createApp.bodyAsText())?.groupValues?.get(1)?.toLong()
                ?: error("no app id")

        // Creating with a new company_name should auto-create a minimal target_company (no prior setup required)
        val createNewCompany = client.post("/api/applications") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_name":"NewCo",
                  "role_title":"Backend Engineer",
                  "job_url":"https://newco.example.com/jobs/role-123",
                  "status":"SAVED"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createNewCompany.status)
        assertTrue(createNewCompany.bodyAsText().contains("\"company_name\":\"NewCo\""))

        val dupApp = client.post("/api/applications") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyAId,
                  "role_title":"Other",
                  "job_url":"https://jobs.example.com/manual-1",
                  "status":"SAVED"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Conflict, dupApp.status)

        val listApplied = client.get("/api/applications?status=applied") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listApplied.status)
        assertTrue(listApplied.bodyAsText().contains("\"id\":$appId"))

        val patchApp = client.patch("/api/applications/$appId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"notes":"updated-notes","follow_up_date":"2026-06-15"}""")
        }
        assertEquals(HttpStatusCode.OK, patchApp.status)
        assertTrue(patchApp.bodyAsText().contains("\"notes\":\"updated-notes\""))
        assertTrue(patchApp.bodyAsText().contains("\"follow_up_date\":\"2026-06-15\""))

        val createLead = client.post("/api/job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_id": $companyAId,
                  "role_title":"Staff Engineer",
                  "job_url":"https://jobs.example.com/lead-1",
                  "matched_keywords":["go","sql"],
                  "match_score": 90
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createLead.status)
        val leadBody = createLead.bodyAsText()
        val leadId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(leadBody)?.groupValues?.get(1)?.toLong()
                ?: error("no lead id")

        val saveAs = client.post("/api/job-leads/$leadId/save-as-application") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.Created, saveAs.status)
        assertTrue(saveAs.bodyAsText().contains("\"status\":\"SAVED\""))
        assertTrue(saveAs.bodyAsText().contains("\"job_url\":\"https://jobs.example.com/lead-1\""))
        val appFromLeadId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(saveAs.bodyAsText())?.groupValues?.get(1)
                ?: error("app id from save-as-application")

        val saveAsAgain = client.post("/api/job-leads/$leadId/save-as-application") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, saveAsAgain.status)
        assertTrue(saveAsAgain.bodyAsText().contains("\"id\":$appFromLeadId"))

        val leadAfter = client.get("/api/job-leads/$leadId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, leadAfter.status)
        assertTrue(leadAfter.bodyAsText().contains("\"saved_to_applications\":true"))

        val getAsB = client.get("/api/applications/$appId") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.NotFound, getAsB.status)

        val listByCompany = client.get("/api/applications?company_id=$companyAId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listByCompany.status)
        assertTrue(listByCompany.bodyAsText().contains("\"id\":$appId"))

        val listKw = client.get("/api/applications?keyword=Engineer") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listKw.status)
        assertTrue(listKw.bodyAsText().contains("\"role_title\":\"Engineer\""))
    }

    @Test
    fun `interviews and reminders APIs ownership ISO today and complete`() = testApplication {
        val e = env("interviews_reminders")
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
                st.execute(
                    """
                    CREATE TABLE applications (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      company_id BIGINT NOT NULL,
                      job_lead_id BIGINT NULL,
                      role_title VARCHAR(255) NOT NULL,
                      job_url VARCHAR(2048) NOT NULL,
                      status VARCHAR(32) NOT NULL,
                      tech_stack_json TEXT NULL,
                      salary_range VARCHAR(255) NULL,
                      applied_at DATE NULL,
                      next_follow_up_date DATE NULL,
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE UNIQUE INDEX uq_app_user_url ON applications(user_id, job_url);")
                st.execute(
                    """
                    CREATE TABLE interviews (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      application_id BIGINT NOT NULL,
                      round_name VARCHAR(190) NULL,
                      scheduled_at TIMESTAMP NULL,
                      status VARCHAR(32) NOT NULL DEFAULT 'scheduled',
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_interviews_application_id ON interviews(application_id);")
                st.execute(
                    """
                    CREATE TABLE reminders (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      application_id BIGINT NULL,
                      reminder_type VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',
                      due_at TIMESTAMP NOT NULL,
                      message VARCHAR(512) NOT NULL,
                      done BOOLEAN NOT NULL DEFAULT FALSE,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_reminders_user_id ON reminders(user_id);")
                st.execute("CREATE INDEX ix_reminders_due_at ON reminders(due_at);")
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
                ?: error("Missing token: $body")
        }

        val tokenA = registerAndLogin("iv@a.com")
        val tokenB = registerAndLogin("iv@b.com")

        java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
            c.prepareStatement(
                "INSERT INTO target_companies (user_id, name, careers_page_url, active) VALUES (1, 'Co', 'https://c.com', TRUE)",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.executeUpdate()
                ps.generatedKeys.use { k -> k.next(); k.getLong(1) }
            }
        }

        val createApp = client.post("/api/applications") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "company_name":"Co",
                  "role_title":"Engineer",
                  "job_url":"https://jobs.example.com/iv-1",
                  "status":"applied"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, createApp.status)
        val appId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(createApp.bodyAsText())?.groupValues?.get(1)?.toLong()
                ?: error("no app id")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/interviews").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/reminders/today").status)

        val scheduledIso = "2026-06-01T14:30:00Z"
        val postIv = client.post("/api/applications/$appId/interviews") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"round_name":"Phone screen","scheduled_at":"$scheduledIso","status":"scheduled","notes":"prep"}
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, postIv.status)
        val ivBody = postIv.bodyAsText()
        val ivId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(ivBody)?.groupValues?.get(1)?.toLong()
                ?: error("no interview id")
        assertTrue(ivBody.contains("\"scheduled_at\":\"$scheduledIso\""))

        val listIv = client.get("/api/interviews") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listIv.status)
        assertTrue(listIv.bodyAsText().contains("\"id\":$ivId"))

        val patchAsB = client.patch("/api/interviews/$ivId") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"completed"}""")
        }
        assertEquals(HttpStatusCode.NotFound, patchAsB.status)

        val patchIv = client.patch("/api/interviews/$ivId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"completed","notes":"done"}""")
        }
        assertEquals(HttpStatusCode.OK, patchIv.status)
        assertTrue(patchIv.bodyAsText().contains("\"status\":\"completed\""))

        val dueNow = Instant.now().toString()
        val postRm = client.post("/api/applications/$appId/reminders") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"type":"FOLLOW_UP","due_at":"$dueNow","message":"Call recruiter"}
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.Created, postRm.status)
        val rmBody = postRm.bodyAsText()
        val rmId =
            Regex("\"id\"\\s*:\\s*(\\d+)").find(rmBody)?.groupValues?.get(1)?.toLong()
                ?: error("no reminder id")
        assertTrue(rmBody.contains("\"type\":\"FOLLOW_UP\""))

        val completeAsB = client.patch("/api/reminders/$rmId/complete") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.NotFound, completeAsB.status)
        val deleteAsB = client.delete("/api/reminders/$rmId") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.NotFound, deleteAsB.status)

        val dueLater = Instant.now().plus(7, ChronoUnit.DAYS).toString()
        client.post("/api/applications/$appId/reminders") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"type":"INTERVIEW_PREP","due_at":"$dueLater","message":"Later"}
                """.trimIndent(),
            )
        }

        val today = client.get("/api/reminders/today") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, today.status)
        assertTrue(today.bodyAsText().contains("\"id\":$rmId"))
        assertTrue(!today.bodyAsText().contains("Later"))

        val complete = client.patch("/api/reminders/$rmId/complete") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, complete.status)
        assertTrue(complete.bodyAsText().contains("\"done\":true"))

        val listRmAfterComplete = client.get("/api/reminders") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, listRmAfterComplete.status)
        assertTrue(listRmAfterComplete.bodyAsText().contains("\"id\":$rmId"))
        assertTrue(listRmAfterComplete.bodyAsText().contains("\"done\":true"))

        val delIv = client.delete("/api/interviews/$ivId") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, delIv.status)

        val postIv404 = client.post("/api/applications/99999/interviews") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
            contentType(ContentType.Application.Json)
            setBody("""{"status":"scheduled"}""")
        }
        assertEquals(HttpStatusCode.NotFound, postIv404.status)
    }

    @Test
    fun dashboardAggregationStatsAndListsOwnership() = testApplication {
        val e = env("dashboard_agg")
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
                st.execute(
                    """
                    CREATE TABLE applications (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      company_id BIGINT NOT NULL,
                      job_lead_id BIGINT NULL,
                      role_title VARCHAR(255) NOT NULL,
                      job_url VARCHAR(2048) NOT NULL,
                      status VARCHAR(32) NOT NULL,
                      tech_stack_json TEXT NULL,
                      salary_range VARCHAR(255) NULL,
                      applied_at DATE NULL,
                      next_follow_up_date DATE NULL,
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE UNIQUE INDEX uq_app_user_url ON applications(user_id, job_url);")
                st.execute(
                    """
                    CREATE TABLE interviews (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      application_id BIGINT NOT NULL,
                      round_name VARCHAR(190) NULL,
                      scheduled_at TIMESTAMP NULL,
                      status VARCHAR(32) NOT NULL DEFAULT 'scheduled',
                      notes TEXT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_interviews_application_id ON interviews(application_id);")
                st.execute(
                    """
                    CREATE TABLE reminders (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      user_id BIGINT NOT NULL,
                      application_id BIGINT NULL,
                      reminder_type VARCHAR(32) NOT NULL DEFAULT 'CUSTOM',
                      due_at TIMESTAMP NOT NULL,
                      message VARCHAR(512) NOT NULL,
                      done BOOLEAN NOT NULL DEFAULT FALSE,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_reminders_user_id ON reminders(user_id);")
                st.execute(
                    """
                    CREATE TABLE ai_interview_plans (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      application_id BIGINT NOT NULL,
                      provider_mode VARCHAR(16) NOT NULL DEFAULT 'mock',
                      prompt_json TEXT NULL,
                      plan_json TEXT NOT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE prep_tasks (
                      id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      ai_interview_plan_id BIGINT NOT NULL,
                      label VARCHAR(255) NOT NULL,
                      description TEXT NULL,
                      due_date DATE NULL,
                      status VARCHAR(32) NOT NULL DEFAULT 'todo',
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX ix_prep_tasks_ai_interview_plan_id ON prep_tasks(ai_interview_plan_id);")
                st.execute("CREATE INDEX ix_prep_tasks_due_date ON prep_tasks(due_date);")
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
                ?: error("Missing token: $body")
        }

        val tokenA = registerAndLogin("dash@a.com")
        val tokenB = registerAndLogin("dash@b.com")

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/dashboard/stats").status)

        suspend fun assertDashboardEmpty(token: String) {
            val st = client.get("/api/dashboard/stats") { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, st.status)
            val sb = st.bodyAsText()
            assertTrue(sb.contains("\"total_applications\":0"))
            assertTrue(sb.contains("\"applications_this_week\":0"))
            assertTrue(sb.contains("\"interviews_count\":0"))
            assertTrue(sb.contains("\"offers_count\":0"))
            assertTrue(sb.contains("\"rejections_count\":0"))
            assertTrue(sb.contains("\"follow_ups_due\":0"))
            assertTrue(sb.contains("\"job_leads_discovered_this_week\":0"))
            assertTrue(sb.contains("\"prep_tasks_due_today\":0"))
            assertTrue(sb.contains("\"response_rate\":0") || sb.contains("\"response_rate\":0.0"))

            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/dashboard/follow-ups") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/dashboard/recent-job-leads") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/dashboard/upcoming-interviews") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/dashboard/prep-summary") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            )
        }
        assertDashboardEmpty(tokenA)

        java.sql.DriverManager.getConnection(e["DB_JDBC_URL"], "sa", "").use { c ->
            c.createStatement().use { st ->
                st.execute(
                    "INSERT INTO target_companies (user_id, name, careers_page_url, active) VALUES (1, 'Co', 'https://c.com', TRUE)",
                )
                st.execute(
                    """
                    INSERT INTO applications (user_id, company_id, role_title, job_url, status, next_follow_up_date)
                    VALUES
                    (1, 1, 'Saved role', 'https://dash/u1', 'SAVED', NULL),
                    (1, 1, 'Applied role', 'https://dash/u2', 'APPLIED', CURRENT_DATE),
                    (1, 1, 'Interview role', 'https://dash/u3', 'INTERVIEW', NULL);
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO interviews (application_id, round_name, scheduled_at, status)
                    VALUES
                    (3, 'EarlyIv', DATEADD('DAY', 1, CURRENT_TIMESTAMP), 'scheduled'),
                    (3, 'Onsite', DATEADD('DAY', 2, CURRENT_TIMESTAMP), 'scheduled'),
                    (3, 'LateIv', DATEADD('DAY', 3, CURRENT_TIMESTAMP), 'scheduled');
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO reminders (user_id, application_id, reminder_type, due_at, message, done)
                    VALUES (1, 2, 'CUSTOM', CURRENT_TIMESTAMP, 'Call HR', FALSE);
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO job_leads (company_id, title, url, discovered_at, saved_to_applications, status, source)
                    VALUES
                    (1, 'Lead Old', 'https://dash/lead-old', TIMESTAMP '2018-01-01 00:00:00', FALSE, 'new', 'career_page'),
                    (1, 'Lead A', 'https://dash/lead-a', DATEADD('HOUR', -2, CURRENT_TIMESTAMP), FALSE, 'new', 'career_page'),
                    (1, 'Lead New', 'https://dash/lead-new', CURRENT_TIMESTAMP, FALSE, 'new', 'career_page');
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO ai_interview_plans (application_id, provider_mode, plan_json)
                    VALUES (2, 'mock', '{}');
                    """.trimIndent(),
                )
                st.execute(
                    """
                    INSERT INTO prep_tasks (ai_interview_plan_id, label, due_date, status)
                    VALUES (1, 'Review JD', CURRENT_DATE, 'todo');
                    """.trimIndent(),
                )
            }
        }

        val stats = client.get("/api/dashboard/stats") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, stats.status)
        val stBody = stats.bodyAsText()
        assertTrue(stBody.contains("\"total_applications\":3"))
        assertTrue(stBody.contains("\"interviews_count\":3"))
        assertTrue(stBody.contains("\"prep_tasks_due_today\":1"))
        assertTrue(stBody.contains("\"job_leads_discovered_this_week\":2"))
        assertTrue(stBody.contains("\"response_rate\":"))

        val statsB = client.get("/api/dashboard/stats") {
            header(HttpHeaders.Authorization, "Bearer $tokenB")
        }
        assertEquals(HttpStatusCode.OK, statsB.status)
        assertTrue(statsB.bodyAsText().contains("\"total_applications\":0"))

        val fu = client.get("/api/dashboard/follow-ups") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, fu.status)
        assertTrue(fu.bodyAsText().contains("application_follow_up"))
        assertTrue(fu.bodyAsText().contains("reminder"))

        val leads = client.get("/api/dashboard/recent-job-leads") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, leads.status)
        val leadsBody = leads.bodyAsText()
        assertTrue(leadsBody.contains("Lead A"))
        val iNew = leadsBody.indexOf("Lead New")
        val iA = leadsBody.indexOf("Lead A")
        val iOld = leadsBody.indexOf("Lead Old")
        assertTrue(iNew >= 0 && iA >= 0 && iOld >= 0)
        assertTrue(iNew < iA && iA < iOld)

        val iv = client.get("/api/dashboard/upcoming-interviews") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, iv.status)
        val ivBody = iv.bodyAsText()
        assertTrue(ivBody.contains("Onsite"))
        val iEarly = ivBody.indexOf("EarlyIv")
        val iMid = ivBody.indexOf("Onsite")
        val iLate = ivBody.indexOf("LateIv")
        assertTrue(iEarly >= 0 && iMid >= 0 && iLate >= 0)
        assertTrue(iEarly < iMid && iMid < iLate)

        val prep = client.get("/api/dashboard/prep-summary") {
            header(HttpHeaders.Authorization, "Bearer $tokenA")
        }
        assertEquals(HttpStatusCode.OK, prep.status)
        assertTrue(prep.bodyAsText().contains("Review JD"))
    }
}

