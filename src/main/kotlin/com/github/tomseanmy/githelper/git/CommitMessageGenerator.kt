package com.github.tomseanmy.githelper.git

import com.github.tomseanmy.githelper.GitHelper
import com.github.tomseanmy.githelper.ai.AiProvider
import com.github.tomseanmy.githelper.ai.ChatRequest
import com.github.tomseanmy.githelper.ai.ChatResponse
import com.github.tomseanmy.githelper.settings.GitHelperSettings
import com.github.tomseanmy.githelper.settings.ProviderProfile

/**
 * Glue between [GitDiffProvider] and an [AiProvider]: turns a diff into a
 * prompt, calls the model, and returns the generated commit message.
 */
class CommitMessageGenerator {

    /**
     * Streaming generation. [onDelta] is called from a background thread for
     * each incremental chunk; callers must marshal to EDT themselves before
     * touching UI.
     */
    fun generateStreaming(
        profile: ProviderProfile,
        provider: AiProvider,
        diff: GitDiffProvider.DiffResult,
        onDelta: (String) -> Unit,
        isCanceled: () -> Boolean = { false },
    ): ChatResponse {
        val settings = GitHelperSettings.getInstance()
        val systemPrompt = styleSystemPrompt(settings.commitStyle, profile.systemPrompt)
        val userPrompt = buildUserPrompt(diff)
        val request = ChatRequest(
            model = profile.model,
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            temperature = profile.temperature,
            maxTokens = profile.maxTokens,
            stream = settings.streaming,
            disableThinking = profile.disableThinking,
        )
        return if (settings.streaming) {
            provider.streamChat(request, { d -> if (d.delta.isNotEmpty()) onDelta(d.delta) }, isCanceled)
        } else {
            val resp = provider.chat(request)
            if (resp.isSuccess && resp.content.isNotEmpty()) onDelta(resp.content)
            resp
        }
    }

    private fun buildUserPrompt(diff: GitDiffProvider.DiffResult): String = buildString {
        if (diff.branch.isNotEmpty()) {
            appendLine("Branch: ${diff.branch}")
        }
        if (diff.changedFiles.isNotEmpty()) {
            appendLine("Changed files:")
            diff.changedFiles.take(50).forEach { appendLine("- $it") }
            if (diff.changedFiles.size > 50) appendLine("- ... and ${diff.changedFiles.size - 50} more")
            appendLine()
        }
        appendLine("Diff:")
        appendLine(diff.diff)
    }

    private fun styleSystemPrompt(style: String, base: String): String {
        // CONVENTIONAL / CUSTOM use the profile's prompt as-is.
        // FREESTYLE appends a relaxation note so it doesn't depend on exact wording.
        return when (style.uppercase()) {
            "FREESTYLE" -> base +
                "\n\n(Style override: you may relax the strict Conventional Commits format; " +
                "use a clear, concise natural-language summary, subject line first.)"
            else -> base
        }
    }
}
