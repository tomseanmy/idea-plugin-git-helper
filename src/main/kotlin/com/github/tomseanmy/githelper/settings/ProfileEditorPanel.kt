package com.github.tomseanmy.githelper.settings

import com.github.tomseanmy.githelper.ai.ProviderFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextArea

/**
 * Right-hand detail form for a single [ProviderProfile].
 *
 * Built with [GridBagLayout] rather than the platform FormBuilder: the latter
 * produces a panel that does not honour horizontal resizing, so on a wide
 * Settings window the fields would either stay narrow or stretch out of bounds.
 * Here the label column is fixed-width and the field column grows with the
 * window (weightx = 1.0, fill = HORIZONTAL), giving a proper elastic layout.
 */
class ProfileEditorPanel {

    private val nameField = textField()
    private val protocolCombo = ComboBox(DefaultComboBoxModel(arrayOf("OPENAI", "ANTHROPIC")))
    private val authSchemeCombo = ComboBox(DefaultComboBoxModel(arrayOf("AUTO", "BEARER", "X_API_KEY")))
    private val baseUrlField = textField("https://api.openai.com/v1")
    private val modelField = textField("gpt-4o-mini")
    private val apiKeyField = passwordField()
    private val temperatureField = textField("0.2")
    private val maxTokensField = textField("2048")
    private val timeoutField = textField("60")
    private val disableThinkingCheck = JCheckBox(
        "Disable thinking / reasoning (faster; recommended for commit messages)"
    ).apply {
        toolTipText = "<html>Sends params to turn off chain-of-thought reasoning " +
            "(<code>reasoning_effort=none</code>, <code>enable_thinking=false</code>). " +
            "On by default — commit messages don't need reasoning and it slows generation.</html>"
        isSelected = true
    }

    private var working: ProviderProfile = ProviderProfile()

    /**
     * A text field whose width does NOT grow with its content. By fixing the
     * preferred size, the GridBagLayout column width stays stable whether the
     * field is empty or holds a long API key — only the container resize moves it.
     */
    private fun textField(text: String? = null): JBTextField = JBTextField().apply {
        if (text != null) this.text = text
        columns = 28
        preferredSize = Dimension(280, preferredSize.height)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun passwordField(): JPasswordField = JPasswordField().apply {
        echoChar = '•'
        columns = 28
        preferredSize = Dimension(280, preferredSize.height)
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    val panel: JPanel = buildPanel()

    private fun buildPanel(): JPanel {
        val panel = JPanel(GridBagLayout())
        val c = GridBagConstraints()

        fun label(text: String, tooltip: String? = null) = JBLabel(text).apply {
            if (tooltip != null) toolTipText = tooltip
        }

        c.insets = Insets(4, 0, 4, 8)
        c.anchor = GridBagConstraints.WEST
        c.fill = GridBagConstraints.HORIZONTAL

        row(c, panel, label("Name"), nameField)
        row(c, panel, label("Protocol"), protocolCombo)
        row(
            c, panel,
            label(
                "Auth scheme",
                "<html>How the API key is sent.<br>" +
                    "<b>AUTO</b>: protocol default (OpenAI→Bearer, Anthropic→x-api-key+Bearer).<br>" +
                    "<b>BEARER</b>: <code>Authorization: Bearer</code> only.<br>" +
                    "<b>X_API_KEY</b>: <code>x-api-key</code> only.</html>"
            ),
            authSchemeCombo
        )
        row(c, panel, label("Base URL"), baseUrlField)
        row(c, panel, label("Model"), modelField)
        row(c, panel, label("API Key"), apiKeyField)
        row(c, panel, label("Temperature"), temperatureField)
        row(
            c, panel,
            label(
                "Max tokens",
                "<html>Maximum tokens in the response, entered in <b>tokens</b> (e.g. 1024, 4096; 1k ≈ 1000 tokens).</html>"
            ),
            maxTokensField
        )
        row(c, panel, label("Timeout (s)"), timeoutField)

        // Disable-thinking checkbox spans both columns.
        c.gridy++
        c.gridx = 0; c.gridwidth = 2; c.weightx = 1.0; c.weighty = 0.0
        c.fill = GridBagConstraints.NONE
        panel.add(disableThinkingCheck, c)

        // Test connection button + reset hint on a trailing row.
        c.gridx = 0; c.gridy++; c.gridwidth = 2; c.weightx = 1.0; c.weighty = 0.0
        c.fill = GridBagConstraints.NONE
        panel.add(testConnectionButton(), c)

        panel.border = JBUI.Borders.empty(10)
        return panel
    }

    /** Add a label + field row. Field column is elastic (weightx = 1.0). */
    private fun row(
        c: GridBagConstraints,
        panel: JPanel,
        label: JComponent,
        field: JComponent,
        weighty: Double = 0.0,
    ) {
        c.gridy++
        c.gridwidth = 1

        // label column — fixed width
        c.gridx = 0; c.weightx = 0.0; c.fill = GridBagConstraints.NONE
        c.ipadx = 0
        panel.add(label, c)

        // field column — elastic
        c.gridx = 1; c.weightx = 1.0; c.weighty = weighty
        c.fill = if (weighty > 0.0) GridBagConstraints.BOTH else GridBagConstraints.HORIZONTAL
        panel.add(field, c)
    }

    private fun testConnectionButton(): JButton = JButton("Test connection").apply {
        addActionListener {
            val profile = collect()
            val provider = ProviderFactory.fromProfile(profile)
            if (provider == null) {
                Messages.showWarningDialog(panel, "Unknown protocol: ${profile.protocol}", "Git Helper")
                return@addActionListener
            }
            ProgressManager.getInstance().run(object : Task.Backgroundable(null, "Testing connection…", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val resp = ProviderFactory.testConnection(profile)
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        if (resp.isSuccess) {
                            Messages.showInfoMessage(panel, "Connected. Model replied: ${resp.content}", "Git Helper")
                        } else {
                            Messages.showErrorDialog(panel, resp.error ?: "Unknown error", "Git Helper")
                        }
                    }
                }
            })
        }
    }

    fun bind(profile: ProviderProfile) {
        this.working = profile
        nameField.text = profile.name
        protocolCombo.selectedItem = profile.protocol
        authSchemeCombo.selectedItem = profile.authScheme
        baseUrlField.text = profile.baseUrl
        modelField.text = profile.model
        apiKeyField.text = Keychain.load(profile.id) ?: ""
        temperatureField.text = profile.temperature.toString()
        maxTokensField.text = profile.maxTokens.toString()
        timeoutField.text = profile.requestTimeoutSeconds.toString()
        disableThinkingCheck.isSelected = profile.disableThinking
    }

    /** Validates inputs and returns an error message, or null if everything is fine. */
    fun validateFields(): String? {
        if (nameField.text.isBlank()) return "Name is required"
        if (baseUrlField.text.isBlank()) return "Base URL is required"
        if (modelField.text.isBlank()) return "Model is required"
        temperatureField.text.trim().toDoubleOrNull() ?: return "Temperature must be a number"
        maxTokensField.text.trim().toIntOrNull() ?: return "Max tokens must be an integer"
        timeoutField.text.trim().toIntOrNull() ?: return "Timeout must be an integer"
        return null
    }

    /** Collects the form into a fresh profile copy (does not touch the keychain). */
    fun collect(): ProviderProfile = working.copy(
        name = nameField.text.trim(),
        protocol = protocolCombo.selectedItem as String,
        authScheme = authSchemeCombo.selectedItem as String,
        baseUrl = baseUrlField.text.trim(),
        model = modelField.text.trim(),
        apiKey = String(apiKeyField.password),
        temperature = temperatureField.text.trim().toDouble(),
        maxTokens = maxTokensField.text.trim().toInt(),
        requestTimeoutSeconds = timeoutField.text.trim().toInt(),
        disableThinking = disableThinkingCheck.isSelected,
        // extraHeaders / systemPrompt are hidden from the UI; keep the existing
        // values so previously-configured profiles keep working unchanged.
    )
}
