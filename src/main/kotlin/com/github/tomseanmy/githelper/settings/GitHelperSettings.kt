package com.github.tomseanmy.githelper.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Plugin-wide settings persisted to IDE config dir.
 *
 * NOTE: API keys are NOT stored here. They live in the OS keychain via
 * [com.intellij.ide.passwordSafe.PasswordSafe]; this state only keeps
 * profile metadata plus a stable id used to look up the key.
 */
@Service
@State(
    name = "com.github.tomseanmy.githelper.settings.GitHelperSettings",
    storages = [Storage("git-helper.xml")],
    category = SettingsCategory.TOOLS
)
class GitHelperSettings : PersistentStateComponent<GitHelperSettings> {

    var profiles: MutableList<ProviderProfile> = mutableListOf()
    var activeProfileId: String = ""

    /** Commit message style. One of CONVENTIONAL / FREESTYLE / CUSTOM */
    var commitStyle: String = "CONVENTIONAL"

    /** When true, also include unstaged changes in the diff context. */
    var includeUnstagedInContext: Boolean = false

    /** Append policy: APPEND (default) / REPLACE / PREVIEW */
    var refillPolicy: String = "APPEND"

    /** Streaming output to the editor. */
    var streaming: Boolean = true

    override fun getState(): GitHelperSettings = this

    override fun loadState(state: GitHelperSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    fun activeProfile(): ProviderProfile? = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()

    companion object {
        @JvmStatic
        fun getInstance(): GitHelperSettings =
            ApplicationManager.getApplication().getService(GitHelperSettings::class.java)
    }
}
