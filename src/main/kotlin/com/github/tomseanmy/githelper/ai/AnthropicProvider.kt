package com.github.tomseanmy.githelper.ai

import com.google.gson.JsonObject

/**
 * Anthropic Messages compatible provider.
 *
 * Key differences vs OpenAI:
 *  - system prompt is a top-level `system` field, not a message;
 *  - auth header is `x-api-key` plus an `anthropic-version` header;
 *  - `max_tokens` is required;
 *  - SSE content arrives as `content_block_delta` events with type `text_delta`.
 */
class AnthropicProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val extraHeaders: Map<String, String>,
    private val timeoutSeconds: Int,
) : AiProvider {

    private val endpoint: String = buildEndpoint()

    override fun chat(request: ChatRequest): ChatResponse {
        val body = buildBody(request, stream = false)
        val resp = try {
            HttpExecutor.postJson(endpoint, body, headers(), timeoutSeconds)
        } catch (e: Exception) {
            return ChatResponse("", e.message ?: e.javaClass.simpleName)
        }
        if (resp.statusCode() !in 200..299) {
            return ChatResponse("", friendlyError(resp.statusCode(), resp.body()))
        }
        val root = parseJson(resp.body()) ?: return ChatResponse("", "Empty response")
        // content is an array of blocks; concatenate text blocks.
        val content = root.getAsJsonArray("content")?.joinToString("") { block ->
            block.asJsonObject.str("type")?.let { if (it == "text") block.asJsonObject.str("text") ?: "" else "" } ?: ""
        }
        return ChatResponse(content?.trim() ?: "")
    }

    override fun streamChat(
        request: ChatRequest,
        onDelta: (ChatDelta) -> Unit,
        isCanceled: () -> Boolean,
    ): ChatResponse {
        val body = buildBody(request, stream = true)
        val sb = StringBuilder()
        val code = try {
            HttpExecutor.postStream(
                endpoint, body, headers(), timeoutSeconds,
                onLine = { line ->
                    val deltaText = parseAnthropicEvent(line)
                    if (!deltaText.isNullOrEmpty()) {
                        sb.append(deltaText)
                        onDelta(ChatDelta(deltaText, done = false))
                    }
                },
                isCanceled = isCanceled,
            )
        } catch (e: Exception) {
            return ChatResponse("", e.message ?: e.javaClass.simpleName)
        }
        if (isCanceled()) {
            return ChatResponse(sb.toString().trim()) // partial result on cancel
        }
        if (code !in 200..299) {
            return ChatResponse("", friendlyError(code, "<stream>"))
        }
        onDelta(ChatDelta("", done = true))
        return ChatResponse(sb.toString().trim())
    }

    /**
     * Friendlier auth error. Many Anthropic-compatible gateways (and the official
     * API) reject requests with a terse JSON body; we surface a hint about where
     * the key is read from and how to override auth via extra headers.
     */
    private fun friendlyError(status: Int, body: String): String {
        if (status == 401 || status == 403) {
            val detail = HttpExecutor.errorBody(status, body)
            return "$detail\n\nTip: the key is read from the OS keychain for this profile. " +
                "If your gateway expects a different auth header, override it in " +
                "'Extra headers' (e.g. Authorization: Bearer sk-...)."
        }
        return HttpExecutor.errorBody(status, body)
    }

    private fun buildBody(request: ChatRequest, stream: Boolean): String {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            addProperty("system", request.systemPrompt)
            // Anthropic requires at least one user message.
            val messages = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", request.userPrompt)
                })
            }
            add("messages", messages)
            addProperty("max_tokens", request.maxTokens)
            addProperty("temperature", request.temperature)
            addProperty("stream", stream)
        }
        return GSON.toJson(root)
    }

    private fun headers(): Map<String, String> {
        // Auth headers (x-api-key / Authorization / anthropic-version) are injected
        // centrally by ProviderFactory based on the profile's authScheme.
        return extraHeaders
    }

    private fun buildEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1/messages")) base
        else if (base.endsWith("/v1")) "$base/messages"
        else "$base/v1/messages"
    }

    /**
     * Anthropic SSE: `event:` line then `data:` line. We only need the data
     * payloads of `content_block_delta` events, whose `delta.type == "text_delta"`.
     * Returns the text fragment or null/empty when not applicable.
     */
    private fun parseAnthropicEvent(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty()) return null
        val root = parseJson(payload) ?: return null
        if (root.str("type") != "content_block_delta") return null
        val delta = root.obj("delta") ?: return null
        if (delta.str("type") != "text_delta") return null
        return delta.str("text")
    }
}
