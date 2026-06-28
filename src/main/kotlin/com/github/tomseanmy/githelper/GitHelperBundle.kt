package com.github.tomseanmy.githelper

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.logger
import javax.swing.Icon

object GitHelper {
    const val PLUGIN_ID = "com.github.tomseanmy.git-helper"

    val LOG = logger<GitHelper>()

    /** Reuse the platform's built-in intention-bulb icon — theme-aware, on-brand, zero maintenance. */
    val ICON: Icon = AllIcons.Actions.RealIntentionBulb

    /** Built-in stop icon shown while a generation is running. */
    val ICON_STOP: Icon = AllIcons.Actions.Suspend
}
