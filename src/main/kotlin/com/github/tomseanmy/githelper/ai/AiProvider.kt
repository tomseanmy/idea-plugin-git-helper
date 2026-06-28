package com.github.tomseanmy.githelper.ai

/**
 * Provider-agnostic chat request. The generator only ever sends one user turn
 * (the diff), so we keep the surface minimal.
 */
data class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val userPrompt: String,
    val temperature: Double,
    val maxTokens: Int,
    val stream: Boolean,
    /** When true, the provider appends vendor params to disable thinking/reasoning. */
    val disableThinking: Boolean = false,
)

/** A single streamed chunk. [delta] is the incremental text (may be empty on the final event). */
data class ChatDelta(val delta: String, val done: Boolean)

data class ChatResponse(val content: String, val error: String? = null) {
    val isSuccess: Boolean get() = error == null
}

/**
 * Abstraction over OpenAI / Anthropic compatible endpoints.
 * Implementations own the HTTP + protocol mapping; callers see a uniform API.
 */
interface AiProvider {
    /** Blocking, single-shot call. */
    fun chat(request: ChatRequest): ChatResponse

    /**
     * Streaming call. [onDelta] is invoked on the calling thread for each
     * incremental chunk. Returns the fully assembled text on success or an
     * error response. [isCanceled] is polled between chunks so the caller
     * can stop the stream early; when it returns true, the partial text
     * accumulated so far is returned.
     */
    fun streamChat(
        request: ChatRequest,
        onDelta: (ChatDelta) -> Unit,
        isCanceled: () -> Boolean = { false },
    ): ChatResponse
}
