package com.github.tomseanmy.githelper.ai

import com.google.gson.JsonObject

/**
 * OpenAI Chat Completions compatible provider.
 *
 * Works with any endpoint implementing /v1/chat/completions: OpenAI itself,
 * DeepSeek,智谱, Kimi, OpenRouter, local vLLM / Ollama (with OpenAI mode), etc.
 */
class OpenAiProvider(
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
            return ChatResponse("", HttpExecutor.errorBody(resp.statusCode(), resp.body()))
        }
        val root = parseJson(resp.body())
        val content = root
            ?.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?.obj("message")?.str("content")
            ?: return ChatResponse("", "Empty response")
        return ChatResponse(content.trim())
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
                    parseOpenAiEvent(line)?.let { deltaText ->
                        if (deltaText.isNotEmpty()) {
                            sb.append(deltaText)
                            onDelta(ChatDelta(deltaText, done = false))
                        }
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
            return ChatResponse("", HttpExecutor.errorBody(code, "<stream>"))
        }
        onDelta(ChatDelta("", done = true))
        return ChatResponse(sb.toString().trim())
    }

    private fun buildBody(request: ChatRequest, stream: Boolean): String {
        val root = JsonObject().apply {
            addProperty("model", request.model)
            // OpenAI: system goes as a message with role=system.
            val messages = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", request.systemPrompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", request.userPrompt)
                })
            }
            add("messages", messages)
            addProperty("temperature", request.temperature)
            addProperty("max_tokens", request.maxTokens)
            addProperty("stream", stream)
        }
        if (request.disableThinking) {
            // Vendor params to disable reasoning/thinking. Sending several is safe:
            // each API only reads the one it understands and ignores the rest.
            // - reasoning_effort: OpenAI o-series, some gateways
            // - enable_thinking: DeepSeek, Qwen
            // - thinking: {type:"disabled"}: some Anthropic-compatible gateways
            root.addProperty("reasoning_effort", "none")
            root.addProperty("enable_thinking", false)
            val thinking = JsonObject().apply { addProperty("type", "disabled") }
            root.add("thinking", thinking)
        }
        return GSON.toJson(root)
    }

    private fun headers(): Map<String, String> {
        // Auth headers are injected centrally by ProviderFactory (authScheme).
        return extraHeaders
    }

    private fun buildEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        // Allow user to supply a fully-qualified endpoint; otherwise append the standard path.
        return if (base.endsWith("/chat/completions")) base
        else "$base/chat/completions"
    }

    /** Lines look like: data: {...}  or  data: [DONE]. Returns null for non-data lines. */
    private fun parseOpenAiEvent(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("data:")) return null
        val payload = trimmed.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return ""
        val root = parseJson(payload) ?: return null
        val delta = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject?.obj("delta")
        return delta?.str("content")
    }
}
