package com.github.tomseanmy.githelper.action

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CommitMessageI
import com.intellij.openapi.vcs.VcsDataKeys

/**
 * Writes generated text into the Commit panel's message field.
 *
 * The commit message input is NOT a regular editor — it is a dedicated control
 * exposed via two data keys:
 *   - [VcsDataKeys.COMMIT_MESSAGE_DOCUMENT]: the underlying [Document], used for
 *     streaming-friendly incremental edits (insertString);
 *   - [VcsDataKeys.COMMIT_MESSAGE_CONTROL]: a [CommitMessageI], used to set the
 *     whole message at once.
 *
 * Both are read from the [DataContext] of the triggering action (which carries
 * the commit panel context), and all writes are dispatched on the EDT under a
 * write command.
 */
class CommitMessageWriter(
    private val project: Project,
    private val dataContext: DataContext,
) {

    private val log = logger<CommitMessageWriter>()

    /** Appends [chunk] to the current commit message. */
    fun append(chunk: String) {
        val doc = document()
        if (doc != null) {
            runWrite {
                val sep = if (doc.textLength > 0 && doc.charsSequence.last() != '\n') "\n" else ""
                doc.insertString(doc.textLength, sep + chunk)
            }
            return
        }
        // Fallback: whole-message control (rebuilds the full text each chunk).
        appendViaControl(chunk)
    }

    /** Overwrites the commit message entirely. */
    fun replace(text: String) {
        val doc = document()
        if (doc != null) {
            runWrite { doc.setText(text) }
            return
        }
        control()?.let { runWrite { it.setCommitMessage(text) } }
    }

    fun currentText(): String = document()?.text ?: control()?.let { getControlText(it) } ?: ""

    private fun document(): Document? =
        VcsDataKeys.COMMIT_MESSAGE_DOCUMENT.getData(dataContext)

    private fun control(): CommitMessageI? =
        VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(dataContext)

    private fun appendViaControl(chunk: String) {
        val ctrl = control() ?: run {
            log.warn("No commit message control found in data context")
            return
        }
        runWrite {
            val current = getControlText(ctrl)
            val sep = if (current.isNotEmpty() && current.last() != '\n') "\n" else ""
            ctrl.setCommitMessage(current + sep + chunk)
        }
    }

    /** [CommitMessageI] only exposes a setter; the current text must be read via the document. */
    private fun getControlText(ctrl: CommitMessageI): String {
        document()?.let { return it.text }
        // Last resort: no readable text available.
        return ""
    }

    private fun runWrite(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project, action)
        }
    }
}
