package com.github.tomseanmy.githelper.action

import com.github.tomseanmy.githelper.GitHelper
import com.github.tomseanmy.githelper.ai.ChatResponse
import com.github.tomseanmy.githelper.ai.ProviderFactory
import com.github.tomseanmy.githelper.git.CommitMessageGenerator
import com.github.tomseanmy.githelper.git.GitDiffProvider
import com.github.tomseanmy.githelper.settings.GitHelperSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextArea

/**
 * Reads the changed files, calls the active AI provider, and writes the generated
 * commit message back according to the configured refill policy.
 *
 * While a generation is running, the action turns into a "Stop" button: clicking
 * it cancels the in-flight HTTP stream and keeps whatever was generated so far.
 */
class GenerateCommitMessageAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // If a generation is already running, this click means "stop".
        val running = currentIndicator
        if (running != null && !running.isCanceled) {
            running.cancel()
            refresh()
            return
        }

        val settings = GitHelperSettings.getInstance()
        val active = settings.activeProfile()
        if (active == null) {
            notifyError(project, "No provider configured", "Open Settings → Tools → Git Helper to add one.")
            return
        }
        val provider = ProviderFactory.fromProfile(active)
        if (provider == null) {
            notifyError(project, "Unsupported protocol", "Protocol '${active.protocol}' is not supported.")
            return
        }

        val writer = CommitMessageWriter(project, e.dataContext)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating commit message…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                currentIndicator = indicator
                edt { refresh() }

                indicator.text = "Reading changes…"
                val diff = GitDiffProvider(project).collectDiff()
                if (diff == null) {
                    edt { notifyError(project, "No git repository", "Open a project under git version control.") }
                    return
                }
                if (diff.isEmpty) {
                    edt { notifyError(project, "No changes detected", "There are no uncommitted changes to describe.") }
                    return
                }
                if (diff.truncated) {
                    edt { notifyInfo(project, "Diff truncated", "The diff was large; only part of it was sent to the model.") }
                }

                // For APPEND, keep whatever the user already typed as a prefix.
                val prefix = if (settings.refillPolicy == "APPEND") {
                    writer.currentText().trimEnd().let { if (it.isEmpty()) "" else "$it\n\n" }
                } else ""

                indicator.text = "Generating with ${active.name}…"
                val buffer = StringBuilder()
                val generator = CommitMessageGenerator()
                val canceled = java.util.concurrent.atomic.AtomicBoolean(false)
                val response: ChatResponse = try {
                    generator.generateStreaming(
                        active, provider, diff,
                        onDelta = { delta ->
                            buffer.append(delta)
                            edt { writer.replace(prefix + buffer.toString()) }
                            indicator.text2 = "${buffer.length} chars"
                        },
                        isCanceled = {
                            val ind = currentIndicator
                            val c = ind == null || ind.isCanceled
                            canceled.set(c)
                            c
                        },
                    )
                } catch (ex: Exception) {
                    ChatResponse("", ex.message ?: ex.javaClass.simpleName)
                }

                // Don't clobber partial text or show "failed" when the user stopped it.
                if (canceled.get()) {
                    val partial = buffer.toString().trim()
                    edt {
                        if (partial.isNotEmpty()) writer.replace(prefix + partial)
                        notifyInfo(project, "Stopped", "Kept ${partial.length} chars generated so far.")
                    }
                    return
                }

                if (!response.isSuccess) {
                    edt { notifyError(project, "Generation failed", response.error ?: "Unknown error") }
                    return
                }

                val text = response.content.ifBlank { buffer.toString() }.trim()
                if (text.isBlank()) {
                    edt { notifyError(project, "Empty response", "The model returned an empty commit message.") }
                    return
                }

                when (settings.refillPolicy) {
                    "APPEND", "REPLACE" -> edt { writer.replace(prefix + text) }
                    "PREVIEW" -> edt { showPreview(project, text) { writer.replace(it) } }
                }
                edt { notifyInfo(project, "Commit message generated", "${text.length} chars from ${active.name}.") }
            }

            override fun onFinished() {
                currentIndicator = null
                edt { refresh() }
            }
        })
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
        val running = currentIndicator != null
        if (running) {
            e.presentation.icon = GitHelper.ICON_STOP
            e.presentation.text = "Stop Generating"
            e.presentation.description = "Cancel the in-flight commit message generation"
        } else {
            e.presentation.icon = GitHelper.ICON
            e.presentation.text = "Generate Commit Message"
            e.presentation.description = "Generate an AI commit message from the staged diff"
        }
    }

    /** Run [block] on the EDT with default modality. */
    private fun edt(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block, ModalityState.defaultModalityState())
    }

    /**
     * Re-run [update] on all action toolbars so the icon/text switches between
     * "generate" and "stop" immediately. We call the platform's toolbar refresh
     * via reflection to avoid a hard compile dependency on the `impl` package
     * (which has historically stayed accessible to plugins).
     */
    private fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            runCatching {
                val cls = Class.forName("com.intellij.openapi.actionSystem.impl.ActionToolbarImpl")
                val m = cls.getMethod("updateAllToolbarsImmediately")
                m.invoke(null)
            }
            // Fallback: nudge the action manager so toolbars re-evaluate on idle.
            runCatching {
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
            }
        }
    }

    private fun showPreview(project: Project, text: String, onApply: (String) -> Unit) {
        val area = JBTextArea(text).apply { lineWrap = true; wrapStyleWord = true }
        val scroll = com.intellij.ui.components.JBScrollPane(area)
        val builder = com.intellij.openapi.ui.DialogBuilder(project)
        builder.setTitle("Preview commit message")
        builder.setCenterPanel(scroll)
        builder.setPreferredFocusComponent(area)
        builder.setDimensionServiceKey("GitHelper.PreviewCommitMessage")
        builder.setOkOperation {
            builder.dialogWrapper.close(com.intellij.openapi.ui.DialogWrapper.OK_EXIT_CODE)
            onApply(area.text)
        }
        builder.show()
    }

    private fun notifyInfo(project: Project, title: String, content: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Git Helper")
            .createNotification(title, content, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }

    private fun notifyError(project: Project, title: String, content: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Git Helper")
            .createNotification(title, content, com.intellij.notification.NotificationType.ERROR)
            .notify(project)
    }

    companion object {
        @Volatile
        private var currentIndicator: ProgressIndicator? = null
    }
}
