package com.careerpilot.settings

import com.careerpilot.AppConfig
import kotlinx.serialization.Serializable

/**
 * Deployment status for the Settings UI: safe metadata only—never secret values.
 * Populated from the same env map passed to [com.careerpilot.Application.moduleWithEnv].
 */
@Serializable
data class SettingsStatusDto(
    val app_name: String,
    val app_version: String,
    /** `"connected"` or `"down"` */
    val db_status: String,
    val db_error: String? = null,
    /**
     * Effective AI mode for Python/scripts (`AI_PROVIDER` / `AI_MODE`), lowercased.
     * The JVM backend does not call OpenAI directly; this mirrors server `.env` for operators.
     */
    val ai_provider: String,
    val openai_api_key_configured: Boolean,
    val gemini_api_key_configured: Boolean,
    /** Model id for the active provider family when set (e.g. `AI_MODEL` or `GEMINI_MODEL`). */
    val ai_model: String? = null,
)

fun buildSettingsStatusDto(
    cfg: AppConfig,
    env: Map<String, String>,
    dbConnected: Boolean,
    dbError: String?,
): SettingsStatusDto {
    val providerRaw = (env["AI_PROVIDER"] ?: env["AI_MODE"] ?: "mock").trim()
    val provider = providerRaw.lowercase().ifBlank { "mock" }

    val openaiKey =
        env["AI_API_KEY"]?.trim()?.isNotEmpty() == true
    val geminiKey =
        env["GEMINI_API_KEY"]?.trim()?.isNotEmpty() == true ||
            env["GOOGLE_API_KEY"]?.trim()?.isNotEmpty() == true

    val model =
        when {
            provider == "gemini" ->
                env["GEMINI_MODEL"]?.trim()?.takeIf { it.isNotEmpty() }
            else ->
                env["AI_MODEL"]?.trim()?.takeIf { it.isNotEmpty() }
        }

    return SettingsStatusDto(
        app_name = cfg.appName,
        app_version = cfg.version,
        db_status = if (dbConnected) "connected" else "down",
        db_error = if (dbConnected) null else dbError,
        ai_provider = provider,
        openai_api_key_configured = openaiKey,
        gemini_api_key_configured = geminiKey,
        ai_model = model,
    )
}
