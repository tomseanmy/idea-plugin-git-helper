package com.github.tomseanmy.githelper.settings

import com.github.tomseanmy.githelper.settings.GitHelperSettings
import java.awt.Component
import javax.swing.DefaultListCellRenderer
import javax.swing.JList

/** Renders a profile as "name (protocol) [+ active]". */
class ProfileListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean
    ): Component {
        val profile = value as? ProviderProfile
        val activeId = GitHelperSettings.getInstance().activeProfileId
        val label = if (profile == null) "" else buildString {
            append(profile.name)
            append(" (").append(profile.protocol.lowercase()).append(")")
            if (profile.id == activeId) append("  ✓")
        }
        return super.getListCellRendererComponent(list, label, index, selected, hasFocus)
    }
}
