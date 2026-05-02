package com.careerpilot

import com.careerpilot.db.DatabaseModule
import com.careerpilot.db.HealthRepository
import com.careerpilot.api.ApiResponse
import com.careerpilot.auth.AuthConfig
import com.careerpilot.auth.JwtService
import com.careerpilot.repo.UserRepository
import com.careerpilot.targetcompanies.TargetCompanyRepository
import com.careerpilot.targetcompanies.TargetCompanyValidation
import com.careerpilot.targetcompanies.CreateTargetCompanyRequest
import com.careerpilot.targetcompanies.PatchTargetCompanyRequest
import com.careerpilot.jobleads.JobLeadRepository
import com.careerpilot.jobleads.JobLeadValidation
import com.careerpilot.jobleads.CreateJobLeadRequest
import com.careerpilot.jobleads.PatchJobLeadRequest
import com.careerpilot.jobleads.InsertResult
import com.careerpilot.jobleads.UpdateResult
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.patch
import io.ktor.server.routing.delete
import io.ktor.server.routing.route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.sql.SQLException
import org.slf4j.event.Level
import java.util.UUID

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    moduleWithEnv(System.getenv())
}

fun Application.moduleWithEnv(env: Map<String, String>) {
    val cfg = AppConfig.fromEnv(env)
    environment.log.info("Starting {} v{} on port {}", cfg.appName, cfg.version, cfg.port)
    environment.log.info(
        "DB config loaded (no connection yet): host={}, port={}, name={}, user={}",
        cfg.db.host,
        cfg.db.port,
        cfg.db.name,
        cfg.db.user,
    )

    val db = DatabaseModule(cfg.db)
    val healthRepo = HealthRepository(db)
    val userRepo = UserRepository(db)
    val targetCompanies = TargetCompanyRepository(db)
    val jobLeads = JobLeadRepository(db)

    val authCfg = AuthConfig.fromEnv(env)
    val jwt = JwtService(authCfg)
    environment.monitor.subscribe(ApplicationStopped) {
        db.close()
    }

    install(CallId) {
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }

    install(CallLogging) {
        level = Level.INFO
    }

    install(CORS) {
        // Local dev only for now.
        anyHost()
        allowNonSimpleContentTypes = true
    }

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = false
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }

    install(StatusPages) {
        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message = ApiResponse.fail("bad_request", cause.message ?: "Bad request"),
            )
        }
    }

    install(Authentication) {
        jwt("auth-jwt") {
            realm = authCfg.realm
            verifier(jwt.verifier)
            validate { cred ->
                val sub = cred.payload.subject ?: return@validate null
                sub.toLongOrNull()?.let { JWTPrincipal(cred.payload) }
            }
            challenge { _, _ ->
                call.respond(
                    status = HttpStatusCode.Unauthorized,
                    message = ApiResponse.fail("unauthorized", "Missing or invalid token"),
                )
            }
        }
    }

    routing {
        get("/health") { call.respond(HealthResponse(status = "ok")) }
        get("/api/version") { call.respond(ApiResponse.ok(VersionResponse(name = cfg.appName, version = cfg.version))) }

        get("/health/db") {
            val failure = try {
                healthRepo.selectOne()
                null
            } catch (t: Throwable) {
                t
            }
            if (failure == null) {
                call.respond(DbHealthResponse(status = "connected"))
            } else {
                logDbFailure(failure, cfg.db)
                call.respond(
                    status = HttpStatusCode.ServiceUnavailable,
                    message = DbHealthResponse(
                        status = "down",
                        error = classifyDbError(failure),
                    ),
                )
            }
        }

        post("/api/auth/register") {
            val req = call.receive<com.careerpilot.auth.RegisterRequest>()
            val res = com.careerpilot.auth.AuthHandlers.register(req, userRepo, jwt)
            when (res) {
                is com.careerpilot.auth.AuthResult.Ok -> call.respond(HttpStatusCode.Created, ApiResponse.ok(res.payload))
                is com.careerpilot.auth.AuthResult.Err ->
                    call.respond(res.status, ApiResponse.fail(res.code, res.message))
            }
        }

        post("/api/auth/login") {
            val req = call.receive<com.careerpilot.auth.LoginRequest>()
            val res = com.careerpilot.auth.AuthHandlers.login(req, userRepo, jwt)
            when (res) {
                is com.careerpilot.auth.AuthResult.Ok -> call.respond(ApiResponse.ok(res.payload))
                is com.careerpilot.auth.AuthResult.Err ->
                    call.respond(res.status, ApiResponse.fail(res.code, res.message))
            }
        }

        authenticate("auth-jwt") {
            get("/api/me") {
                val principal = call.principal<JWTPrincipal>()!!
                val userId = principal.payload.subject!!.toLong()
                val user = userRepo.findById(userId)
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ApiResponse.fail("unauthorized", "User not found"),
                    )
                call.respond(ApiResponse.ok(com.careerpilot.auth.MeResponse(user = user.toPublic())))
            }

            route("/api/target-companies") {
                get {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val items = targetCompanies.listByUser(userId).map { it.toDto() }
                    call.respond(ApiResponse.ok(items))
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val req = call.receive<CreateTargetCompanyRequest>()
                    val failure = TargetCompanyValidation.validateCreate(req)
                    if (failure != null) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.fail(failure.code, failure.message),
                        )
                    }
                    val norm = TargetCompanyValidation.normalizeCreate(req)
                    val created =
                        targetCompanies.insert(
                            userId = userId,
                            name = norm.name,
                            careersUrl = norm.careers_url,
                            keywords = norm.keywords,
                            locations = norm.locations,
                            active = norm.active,
                            notes = norm.notes,
                        )
                    call.respond(HttpStatusCode.Created, ApiResponse.ok(created.toDto()))
                }

                get("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.fail("bad_request", "Invalid id"))
                    val item = targetCompanies.findById(userId, id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ApiResponse.fail("not_found", "Not found"))
                    call.respond(ApiResponse.ok(item.toDto()))
                }

                patch("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiResponse.fail("bad_request", "Invalid id"))
                    val req = call.receive<PatchTargetCompanyRequest>()
                    val failure = TargetCompanyValidation.validatePatch(req)
                    if (failure != null) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.fail(failure.code, failure.message),
                        )
                    }
                    val patch = TargetCompanyValidation.normalizePatch(req)
                    val updated = targetCompanies.update(userId, id, patch)
                        ?: return@patch call.respond(HttpStatusCode.NotFound, ApiResponse.fail("not_found", "Not found"))
                    call.respond(ApiResponse.ok(updated.toDto()))
                }

                delete("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiResponse.fail("bad_request", "Invalid id"))
                    val ok = targetCompanies.softDelete(userId, id)
                    if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ApiResponse.fail("not_found", "Not found"))
                    call.respond(ApiResponse.ok(Unit))
                }
            }

            route("/api/job-leads") {
                get {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()

                    val companyId = call.request.queryParameters["company_id"]?.toLongOrNull()
                    val keyword = call.request.queryParameters["keyword"]?.trim()?.takeIf { it.isNotBlank() }
                    val minMatchScore = call.request.queryParameters["min_match_score"]?.toDoubleOrNull()
                    val savedToApplications =
                        call.request.queryParameters["saved_to_applications"]?.lowercase()?.let { v ->
                            when (v) {
                                "true", "1", "yes" -> true
                                "false", "0", "no" -> false
                                else -> null
                            }
                        }

                    val items =
                        jobLeads
                            .listByUser(
                                userId = userId,
                                companyId = companyId,
                                keyword = keyword,
                                minMatchScore = minMatchScore,
                                savedToApplications = savedToApplications,
                            ).map { it.toDto() }
                    call.respond(ApiResponse.ok(items))
                }

                post {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val req = call.receive<CreateJobLeadRequest>()
                    val failure = JobLeadValidation.validateCreate(req)
                    if (failure != null) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.fail(failure.code, failure.message),
                        )
                    }
                    val norm = JobLeadValidation.normalizeCreate(req)
                    val discoveredAt = JobLeadValidation.defaultDiscoveredAtIso(norm)
                    val res =
                        jobLeads.insert(
                            userId = userId,
                            companyId = norm.company_id,
                            roleTitle = norm.role_title,
                            jobUrl = norm.job_url,
                            location = norm.location,
                            rawDescription = norm.raw_description,
                            matchedKeywords = norm.matched_keywords,
                            matchScore = norm.match_score,
                            discoveredAtIso = discoveredAt,
                            savedToApplications = norm.saved_to_applications,
                        )
                    when (res) {
                        is InsertResult.Created -> call.respond(HttpStatusCode.Created, ApiResponse.ok(res.record.toDto()))
                        InsertResult.CompanyNotFound ->
                            call.respond(HttpStatusCode.NotFound, ApiResponse.fail("company_not_found", "Target company not found"))
                        InsertResult.DuplicateJobUrl ->
                            call.respond(HttpStatusCode.Conflict, ApiResponse.fail("duplicate_job_url", "job_url already exists"))
                    }
                }

                get("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.fail("bad_request", "Invalid id"))
                    val item = jobLeads.findById(userId, id)
                        ?: return@get call.respond(HttpStatusCode.NotFound, ApiResponse.fail("not_found", "Not found"))
                    call.respond(ApiResponse.ok(item.toDto()))
                }

                patch("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@patch call.respond(HttpStatusCode.BadRequest, ApiResponse.fail("bad_request", "Invalid id"))
                    val req = call.receive<PatchJobLeadRequest>()
                    val failure = JobLeadValidation.validatePatch(req)
                    if (failure != null) {
                        return@patch call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.fail(failure.code, failure.message),
                        )
                    }
                    val patch = JobLeadValidation.normalizePatch(req)
                    when (val res = jobLeads.update(userId, id, patch)) {
                        is UpdateResult.Updated -> call.respond(ApiResponse.ok(res.record.toDto()))
                        UpdateResult.NotFound -> call.respond(HttpStatusCode.NotFound, ApiResponse.fail("not_found", "Not found"))
                        UpdateResult.DuplicateJobUrl ->
                            call.respond(HttpStatusCode.Conflict, ApiResponse.fail("duplicate_job_url", "job_url already exists"))
                    }
                }

                delete("/{id}") {
                    val principal = call.principal<JWTPrincipal>()!!
                    val userId = principal.payload.subject!!.toLong()
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ApiResponse.fail("bad_request", "Invalid id"))
                    val ok = jobLeads.delete(userId, id)
                    if (!ok) return@delete call.respond(HttpStatusCode.NotFound, ApiResponse.fail("not_found", "Not found"))
                    call.respond(ApiResponse.ok(Unit))
                }
            }
        }
    }
}

@Serializable
data class HealthResponse(
    val status: String,
)

@Serializable
data class DbHealthResponse(
    val status: String,
    val error: String? = null,
)

private fun classifyDbError(t: Throwable): String {
    val msg = (t.message ?: "").lowercase()
    val sqlState = (t as? SQLException)?.sqlState

    return when {
        sqlState == "28000" || "access denied" in msg -> "access_denied"
        "public key retrieval is not allowed" in msg -> "public_key_retrieval"
        "communications link failure" in msg -> "communications_link_failure"
        "connection refused" in msg -> "connection_refused"
        else -> "db_unreachable"
    }
}

private fun Application.logDbFailure(t: Throwable, cfg: DbConfig) {
    val sqlState = (t as? SQLException)?.sqlState
    val code = (t as? SQLException)?.errorCode
    environment.log.error(
        "DB health check failed: host={}, port={}, name={}, user={}, sqlState={}, errorCode={}, message={}",
        cfg.host,
        cfg.port,
        cfg.name,
        cfg.user,
        sqlState,
        code,
        t.message,
        t,
    )
}

@Serializable
data class VersionResponse(
    val name: String,
    val version: String,
)

data class AppConfig(
    val appName: String,
    val version: String,
    val port: Int,
    val db: DbConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): AppConfig {
            val appName = env["APP_NAME"]?.takeIf { it.isNotBlank() } ?: "careerpilot-backend"
            val version = env["APP_VERSION"]?.takeIf { it.isNotBlank() } ?: "0.1.0"
            val port = env["BACKEND_PORT"]?.toIntOrNull() ?: 8080
            val db = DbConfig.fromEnv(env)
            return AppConfig(appName = appName, version = version, port = port, db = db)
        }
    }
}

data class DbConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val jdbcUrlOverride: String? = null,
) {
    companion object {
        fun fromEnv(env: Map<String, String>): DbConfig {
            return DbConfig(
                host = env["DB_HOST"] ?: "localhost",
                port = env["DB_PORT"]?.toIntOrNull() ?: 3306,
                name = env["DB_NAME"] ?: "careerpilot",
                user = env["DB_USER"] ?: "careerpilot",
                password = env["DB_PASSWORD"] ?: "",
                jdbcUrlOverride = env["DB_JDBC_URL"]?.trim()?.takeIf { it.isNotBlank() },
            )
        }
    }
}
