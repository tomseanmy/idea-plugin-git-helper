package com.github.tomseanmy.githelper.settings

import com.github.tomseanmy.githelper.GitHelper
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionListener

/**
 * Settings page: Tools > Git Helper.
 *
 * Layout: global options on top, then a master/detail of provider profiles
 * on the bottom (list on the left, editor on the right).
 */
class GitHelperConfigurable : SearchableConfigurable {

    private var root: JPanel? = null

    private companion object {
        const val EMPTY_CARD = "empty"
        const val EDITOR_CARD = "editor"
    }

    // Global options
    private val commitStyleCombo = ComboBox(DefaultComboBoxModel(arrayOf("CONVENTIONAL", "FREESTYLE", "CUSTOM")))
    private val refillCombo = ComboBox(DefaultComboBoxModel(arrayOf("APPEND", "REPLACE", "PREVIEW")))
    private val streamingCheck = JCheckBox("Stream output into the commit message field (recommended)")

    // Profiles master/detail
    private val profilesModel = CollectionListModel<ProviderProfile>()
    private val profilesList = JBList(profilesModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ProfileListCellRenderer()
    }
    private val editor = ProfileEditorPanel()
    private var detailCards: JPanel? = null
    private var modified = false

    override fun getId(): String = "com.github.tomseanmy.githelper.settings.GitHelperConfigurable"

    override fun getDisplayName(): String = "Git Helper"

    override fun createComponent(): JComponent {
        if (root != null) return root!!

        val globalPanel = FormBuilder.createFormBuilder()
            .addComponent(JLabel("Commit message style:").apply { border = JBUI.Borders.emptyTop(5) })
            .addComponent(commitStyleCombo)
            .addComponent(refillCombo.apply { /* labeled below */ })
            .addComponent(streamingCheck)
            .addTooltip(
                "<html><b>Refill policy</b> — APPEND: add after existing text; " +
                    "REPLACE: overwrite; PREVIEW: show in a dialog first.<br>" +
                    "<b>Diff source</b>: all uncommitted changes (staged + unstaged) are sent.</html>"
            )
            .panel

        val leftPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(profilesList), BorderLayout.CENTER)
            add(buildProfileButtons(), BorderLayout.SOUTH)
            // Fixed-width list column; the detail panel takes the rest.
            preferredSize = Dimension(220, 320)
            minimumSize = Dimension(180, 240)
            border = JBUI.Borders.empty(0, 0, 0, 8)
        }

        // Detail area uses a CardLayout: an empty-state hint when nothing is
        // selected, the editor form when a profile is. This prevents editing a
        // detached form whose contents would be lost (no profile to apply to).
        val emptyCard = com.intellij.ui.components.JBLabel(
            "Select a profile on the left, or click Add to create one."
        ).apply {
            border = JBUI.Borders.empty(40)
            font = font.deriveFont(font.size2D + 2f)
        }

        val detailScroll = JBScrollPane(editor.panel).apply {
            border = null
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            minimumSize = Dimension(360, 240)
        }

        val detailCards = JPanel(java.awt.CardLayout()).apply {
            add(emptyCard, EMPTY_CARD)
            add(detailScroll, EDITOR_CARD)
        }

        val profilesPanel = JPanel(BorderLayout()).apply {
            add(globalPanel, BorderLayout.NORTH)
            add(leftPanel, BorderLayout.WEST)
            add(detailCards, BorderLayout.CENTER)
            border = JBUI.Borders.empty(10)
            minimumSize = Dimension(620, 360)
        }

        this.detailCards = detailCards

        profilesList.addListSelectionListener(ListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = profilesList.selectedValue
                if (selected != null) {
                    editor.bind(selected)
                    showCard(EDITOR_CARD)
                } else {
                    showCard(EMPTY_CARD)
                }
            }
        })

        root = profilesPanel
        return root!!
    }

    private fun showCard(name: String) {
        (detailCards?.layout as? java.awt.CardLayout)?.show(detailCards, name)
    }

    private fun buildProfileButtons(): JPanel = JPanel(BorderLayout()).apply {
        val add = JButton("Add")
        val setActive = JButton("Set Active")
        val remove = JButton("Remove")
        add.addActionListener {
            val p = ProviderProfile(name = "New Profile")
            profilesModel.add(p)
            profilesList.selectedIndex = profilesModel.size - 1
            // First profile becomes active by default.
            if (GitHelperSettings.getInstance().activeProfileId.isBlank()) {
                GitHelperSettings.getInstance().activeProfileId = p.id
            }
            modified = true
        }
        setActive.addActionListener {
            val selected = profilesList.selectedValue ?: return@addActionListener
            GitHelperSettings.getInstance().activeProfileId = selected.id
            profilesList.repaint()
            modified = true
        }
        remove.addActionListener {
            val selected = profilesList.selectedValue ?: return@addActionListener
            val confirm = Messages.showYesNoDialog(
                "Remove profile \"${selected.name}\" and its stored API key?",
                "Git Helper", Messages.getQuestionIcon()
            )
            if (confirm == Messages.YES) {
                Keychain.remove(selected.id)
                if (GitHelperSettings.getInstance().activeProfileId == selected.id) {
                    GitHelperSettings.getInstance().activeProfileId = ""
                }
                profilesModel.remove(selected)
                modified = true
            }
        }
        add(add, BorderLayout.WEST)
        add(setActive, BorderLayout.CENTER)
        add(remove, BorderLayout.EAST)
    }

    override fun isModified(): Boolean {
        // Global flags
        val settings = GitHelperSettings.getInstance()
        if (commitStyleCombo.selectedItem != settings.commitStyle) return true
        if (refillCombo.selectedItem != settings.refillPolicy) return true
        if (streamingCheck.isSelected != settings.streaming) return true
        // Profile edits or structural changes
        if (modified) return true
        val current = profilesList.selectedValue
        if (current != null) {
            val collected = editor.collect()
            // Equality excluding the volatile apiKey field (handled via keychain).
            return collected.copy(apiKey = current.apiKey) != current
        }
        return false
    }

    override fun apply() {
        // Flush the currently edited profile back into the model first.
        profilesList.selectedValue?.let { current ->
            editor.validateFields()?.let { err ->
                throw java.lang.IllegalStateException(err)
            }
            val collected = editor.collect()
            val idx = profilesModel.items.indexOf(current)
            if (idx >= 0) profilesModel.setElementAt(collected, idx)
            // Persist the API key to the keychain (separate from XML state).
            if (collected.apiKey.isNotEmpty()) {
                Keychain.store(collected.id, collected.apiKey)
            } else {
                Keychain.remove(collected.id)
            }
        }

        val settings = GitHelperSettings.getInstance()
        settings.profiles = profilesModel.toList().map { it.copy(apiKey = "") }.toMutableList()
        settings.commitStyle = commitStyleCombo.selectedItem as String
        settings.refillPolicy = refillCombo.selectedItem as String
        settings.streaming = streamingCheck.isSelected
        modified = false
    }

    override fun reset() {
        val settings = GitHelperSettings.getInstance()
        profilesModel.replaceAll(settings.profiles)
        commitStyleCombo.selectedItem = settings.commitStyle
        refillCombo.selectedItem = settings.refillPolicy
        streamingCheck.isSelected = settings.streaming
        // Select the active profile if present.
        val active = settings.activeProfile()
        when {
            active != null -> profilesList.setSelectedValue(active, true)
            profilesModel.size > 0 -> profilesList.selectedIndex = 0
            else -> {
                // No profiles at all: show the empty-state card.
                profilesList.clearSelection()
                showCard(EMPTY_CARD)
            }
        }
        modified = false
    }
}
