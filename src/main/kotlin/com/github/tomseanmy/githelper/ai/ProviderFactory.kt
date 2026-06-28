package com.github.tomseanmy.githelper.ai

import com.github.tomseanmy.githelper.settings.GitHelperSettings
import com.github.tomseanmy.githelper.settings.Keychain
import com.github.tomseanmy.githelper.settings.ProviderProfile

/**
 * Builds an [AiProvider] from a [ProviderProfile], resolving the API key
 * from the OS keychain.
 */
object ProviderFactory {

    fun fromProfile(profile: ProviderProfile): AiProvider? {
        // Prefer an in-memory key (e.g. just typed in the Settings form and not
        // yet applied); fall back to the keychain. This lets "Test connection"
        // work before the key is persisted.
        val key = profile.apiKey.ifBlank { Keychain.load(profile.id) ?: "" }
        if (key.isBlank()) {
            // No key anywhere — surface this clearly instead of sending empty auth.
        }
        val headers = LinkedHashMap<String, String>()
        // Auth headers resolved centrally from authScheme + protocol.
        headers.putAll(authHeaders(profile, key))
        // User extra headers always win (applied last).
        headers.putAll(profile.parsedExtraHeaders())
        val protocol = profile.protocol.uppercase()
        return when (protocol) {
            "OPENAI" -> OpenAiProvider(profile.baseUrl, key, headers, profile.requestTimeoutSeconds)
            "ANTHROPIC" -> AnthropicProvider(profile.baseUrl, key, headers, profile.requestTimeoutSeconds)
            else -> null
        }
    }

    /**
     * Build the authentication headers according to [ProviderProfile.authScheme].
     * - AUTO: OpenAI → Authorization: Bearer; Anthropic → x-api-key + Authorization: Bearer
     * - BEARER: Authorization: Bearer only (some gateways reject x-api-key)
     * - X_API_KEY: x-api-key only (official Anthropic, some gateways)
     */
    private fun authHeaders(profile: ProviderProfile, key: String): Map<String, String> {
        val protocol = profile.protocol.uppercase()
        val scheme = profile.authScheme.uppercase()
        val map = LinkedHashMap<String, String>()
        when (scheme) {
            "BEARER" -> map["Authorization"] = "Bearer $key"
            "X_API_KEY", "X-API-KEY" -> {
                // HTTP headers are case-insensitive by spec; 'x-api-key' is the
                // conventional form and is what the official Anthropic API uses.
                map["x-api-key"] = key
                if (protocol == "ANTHROPIC") map["anthropic-version"] = "2023-06-01"
            }
            else -> { // AUTO
                if (protocol == "ANTHROPIC") {
                    map["x-api-key"] = key
                    map["anthropic-version"] = "2023-06-01"
                }
                map["Authorization"] = "Bearer $key"
            }
        }
        return map
    }

    /** Resolve the active profile and build a provider, or null if none configured. */
    fun active(): Pair<ProviderProfile, AiProvider>? {
        val profile = GitHelperSettings.getInstance().activeProfile() ?: return null
        return fromProfile(profile)?.let { profile to it }
    }

    /**
     * Lightweight connectivity check: send a tiny prompt and report success
     * or the error string. Used by the Settings "Test Connection" button.
     */
    fun testConnection(profile: ProviderProfile): ChatResponse {
        val key = profile.apiKey.ifBlank { Keychain.load(profile.id) ?: "" }
        if (key.isBlank()) {
            return ChatResponse("", "No API key: enter one in the form (it is not yet saved).")
        }
        val provider = fromProfile(profile)
            ?: return ChatResponse("", "Unknown protocol: ${profile.protocol}")
        val req = ChatRequest(
            model = profile.model,
            systemPrompt = "You are a connectivity test. Reply with exactly: OK",
            userPrompt = "ping",
            temperature = 0.0,
            maxTokens = 8,
            stream = false,
        )
        return provider.chat(req)
    }
}
