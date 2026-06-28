package com.github.tomseanmy.githelper.settings

import java.util.UUID

/**
 * A single provider configuration. [apiKey] is transient — the real key is
 * stored in the OS keychain under [keyKeychainId]; this field is only used
 * as an in-memory transport when editing the settings UI.
 */
data class ProviderProfile(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New Profile",
    /** OPENAI / ANTHROPIC */
    var protocol: String = "OPENAI",
    var baseUrl: String = "https://api.openai.com/v1",
    var model: String = "gpt-4o-mini",
    var apiKey: String = "",
    var temperature: Double = 0.2,
    var maxTokens: Int = 2048,
    var systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    var requestTimeoutSeconds: Int = 60,
    var extraHeaders: String = "",
    /**
     * How the API key is sent. AUTO = let the protocol decide (OpenAI→Bearer,
     * Anthropic→x-api-key + Bearer); BEARER = Authorization: Bearer only;
     * X_API_KEY = x-api-key only. Gives explicit control for picky gateways.
     */
    var authScheme: String = "AUTO",
    /**
     * Disable reasoning/thinking for this profile. Commit messages don't need
     * chain-of-thought, so this is on by default for speed. Sends vendor params
     * to turn thinking off (reasoning_effort=none, enable_thinking=false, …).
     */
    var disableThinking: Boolean = true,
) {
    /**
     * Stable id used as the keychain record name. We avoid putting the key
     * itself into the persisted XML state.
     */
    val keyKeychainId: String get() = "git-helper.profile.${id}"

    /** Parse the free-form "Key: Value" per-line headers into a map. */
    fun parsedExtraHeaders(): Map<String, String> {
        if (extraHeaders.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (rawLine in extraHeaders.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim()
            val v = line.substring(idx + 1).trim()
            if (k.isNotEmpty()) result[k] = v
        }
        return result
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are a Git commit message assistant. Group the changed files by module and write ONE concise commit message per module — not one giant commit listing every file.

Output rules:
- Produce ONE or MORE Conventional Commits messages, one per logical module/area.
- Separate multiple messages with a blank line.
- Each message: `<type>(<scope>): <subject>` on a single line.
  - type: feat | fix | docs | style | refactor | perf | test | build | ci | chore | revert
  - scope: the module/component inferred from the files (e.g. auth, ui, api). Use the same language as the codebase for the scope word.
  - subject: <= 72 chars, imperative mood, lowercase, no trailing period.
- Do NOT add a body or bullet lists under each message. Keep each message to its single subject line — concise, like a real commit title.

When to split:
- If files touch clearly different modules (e.g. auth/ and ui/), emit one message per module.
- If all files are one cohesive change in one module, emit a single message.

Example output (three modules, three messages):

feat(auth): 添加 OAuth2 登录流程
fix(ui): 修复深色主题下按钮错位
chore(deps): 升级 OkHttp 到 4.12

Rules:
- Write in the same language as the code comments / surrounding changes (Chinese is fine if the project is Chinese).
- Cover the modules represented by the changed files; do not drop a whole module.
- Output ONLY the commit messages, nothing else — no explanation, no markdown fences."""
    }
}
