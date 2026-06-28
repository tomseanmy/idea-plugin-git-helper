package com.github.tomseanmy.githelper.git

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import java.io.File

/**
 * Exposes the diff text to feed to the commit-message model.
 *
 * Integrates with the IDE so the generated message matches what the Commit
 * panel shows. We read the changed files from [ChangeListManager], then build
 * a diff per change type:
 *   - MODIFICATION / MOVED / DELETED: `git diff HEAD -- <path>` (the file
 *     exists in HEAD so the diff is meaningful);
 *   - NEW: the file is untracked, so `git diff HEAD` would skip it. We use
 *     `git diff --no-index /dev/null <path>` to obtain its full content as an
 *     added file.
 *
 * Without the NEW-file handling, newly added files ticked in the Commit panel
 * would be silently missing from the generated message.
 */
class GitDiffProvider(private val project: Project) {

    private val log = Logger.getInstance(GitDiffProvider::class.java)

    data class DiffResult(
        val root: String,
        val branch: String,
        val changedFiles: List<String>,
        val diff: String,
        val truncated: Boolean,
        val source: String,
    ) {
        val isEmpty: Boolean get() = diff.isBlank()
    }

    fun collectDiff(maxChars: Int = 200_000): DiffResult? {
        val root = findGitRoot() ?: run {
            log.warn("No git root found for project base path: ${project.basePath}")
            return null
        }
        val branch = runGit(root, "rev-parse", "--abbrev-ref", "HEAD").trim()

        val changes = collectIdeChanges(root)

        val (diffText, fileList, source) = if (changes.isNotEmpty()) {
            // Split into tracked changes (modified/moved/deleted) and new (untracked) files.
            // Tracked changes are fetched in ONE git call with all paths at once — this is
            // both faster and avoids per-file path resolution errors that could drop files.
            val tracked = changes.filter { it.second != Change.Type.NEW }
            val newFiles = changes.filter { it.second == Change.Type.NEW }

            val parts = ArrayList<String>()
            val files = ArrayList<String>()

            if (tracked.isNotEmpty()) {
                val paths = tracked.map { it.first }
                val d = runGit(root, *arrayOf("diff", "HEAD", "--no-color", "--") + paths)
                if (d.isNotBlank()) {
                    parts += d
                    files += paths
                    paths.forEach { log.info("  tracked diff included: $it") }
                } else {
                    // No HEAD yet (fresh repo) — diff the worktree directly.
                    val d2 = runGit(root, *arrayOf("diff", "--no-color", "--") + paths)
                    if (d2.isNotBlank()) { parts += d2; files += paths }
                }
            }
            // New (untracked) files aren't in HEAD, so diff each against /dev/null.
            for ((relPath, _) in newFiles) {
                val d = runGit(root, "diff", "--no-color", "--no-index", "/dev/null", relPath)
                if (d.isNotBlank()) {
                    parts += d
                    files += relPath
                    log.info("  new-file diff: $relPath (${d.length} chars)")
                } else {
                    log.warn("  new-file diff EMPTY for '$relPath'")
                }
            }
            Triple(parts.joinToString("\n\n"), files, "IDE changelist")
        } else {
            // Fallback: all changes vs HEAD (still misses new files, but only used when
            // the changelist is unreadable for some reason).
            val d = runGit(root, "diff", "HEAD", "--no-color").ifBlank { runGit(root, "diff", "--no-color") }
            val f = runGit(root, "diff", "--name-only", "HEAD").ifBlank { runGit(root, "diff", "--name-only") }
                .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            Triple(d, f, "git diff HEAD")
        }

        if (diffText.isBlank()) {
            log.info("diff was empty (source=$source). files=${fileList.size}. status:\n${runGit(root, "status", "--short")}")
        } else {
            log.info("collected diff: ${fileList.size} files, ${diffText.length} chars (source=$source)")
        }

        val truncated = diffText.length > maxChars
        val finalDiff = if (truncated) {
            diffText.take(maxChars) + "\n\n# ... (diff truncated, ${diffText.length - maxChars} more chars omitted)"
        } else diffText

        return DiffResult(root, branch, fileList, finalDiff.trim(), truncated, source)
    }

    /**
     * Returns the IDE-known changes as (repo-root-relative-path, change-type) pairs.
     */
    private fun collectIdeChanges(gitRoot: String): List<Pair<String, Change.Type>> = try {
        val mgr = ChangeListManager.getInstance(project)
        val rootPath = File(gitRoot).canonicalFile.toPath()
        val result = mgr.allChanges.mapNotNull { change ->
            val rev = change.afterRevision ?: change.beforeRevision ?: return@mapNotNull null
            val nioPath = rev.file?.virtualFile?.canonicalFile?.toNioPath() ?: return@mapNotNull null
            val rel = rootPath.relativize(nioPath.normalize()).toString().replace(File.separatorChar, '/')
            rel to (change.type ?: Change.Type.MODIFICATION)
        }.distinctBy { it.first }
        log.info("IDE changelist: ${result.size} changes")
        result
    } catch (e: Exception) {
        log.warn("Failed to read IDE changelist", e)
        emptyList()
    }

    /** Walk up from the project base directory until a `.git` entry is found. */
    private fun findGitRoot(): String? {
        var dir: File? = File(project.basePath ?: return null)
        while (dir != null) {
            if (File(dir, ".git").exists()) return dir.canonicalPath
            dir = dir.parentFile
        }
        return null
    }

    private fun runGit(root: String, vararg args: String): String = try {
        val command = ArrayList<String>(args.size + 1)
        command.add("git")
        command.addAll(args.toList())
        val proc = ProcessBuilder(command)
            .directory(File(root))
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        // --no-index returns exit code 1 when files differ, which is expected here.
        if (code != 0 && code != 1) {
            log.warn("git ${args.joinToString(" ")} failed ($code): ${err.take(300)}")
        }
        out.trim()
    } catch (e: Exception) {
        log.warn("Failed to run git ${args.joinToString(" ")}", e)
        ""
    }
}
