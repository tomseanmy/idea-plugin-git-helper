package com.github.tomseanmy.githelper.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

/**
 * Thin wrapper around IntelliJ [PasswordSafe], which on macOS is backed by
 * the system Keychain. API keys are stored under a per-profile key and never
 * written to the persisted XML settings.
 */
object Keychain {
    private val LOG = logger<Keychain>()

    private const val SERVICE = "git-helper"

    fun store(profileId: String, key: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            PasswordSafe.instance.set(attributes(profileId), Credentials(profileId, key))
        }.onFailure { LOG.warn("Failed to store API key for profile $profileId", it) }
    }

    fun load(profileId: String): String? = runCatching {
        PasswordSafe.instance[attributes(profileId)]?.getPasswordAsString()
    }.onFailure {
        LOG.warn("Failed to load API key for profile $profileId", it)
    }.getOrNull()

    fun remove(profileId: String) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        runCatching {
            // Passing null credentials removes the record.
            PasswordSafe.instance.set(attributes(profileId), null)
        }.onFailure { LOG.warn("Failed to remove API key for profile $profileId", it) }
    }

    private fun attributes(profileId: String): CredentialAttributes =
        CredentialAttributes("$SERVICE.profile.$profileId")
}
