package com.claudetabs

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Writes the plugin's bash integration into the user's `~/.claude/` directory.
 *
 * Idempotent — safe to call on every startup:
 *  - Resource files (rename-tab.sh, session-start-hook.sh, slash-command `.md`s) are
 *    overwritten from JAR resources so script updates ship with plugin updates.
 *  - The CLAUDE.md section is replaced between its [claudeMdMarker]s so instruction
 *    text stays current.
 *  - Permission and `SessionStart` hook entries in `settings.json` are only added if
 *    missing.
 *  - Pre-rename command filenames are cleaned up.
 *
 * The complementary uninstall path lives in [ClaudeTabWatcherStartup.Companion.uninstall].
 *
 * Extracted from `ClaudeTabWatcherStartup` to give the CRLF normalization fix and the
 * §8.x cleanup edits a smaller surface to work in.
 */
object ClaudeIntegrationDeployer {
    private val LOG = Logger.getInstance(ClaudeIntegrationDeployer::class.java)

    private const val HOOK_MARKER = "session-start-hook.sh"
    private const val HOOK_MARKER_LEGACY = "active-sessions"

    fun deploy(claudeHome: File, claudeMdMarker: String, permissionEntry: String) {
        try {
            deployResource("claude-integration/rename-tab.sh", File(claudeHome, "rider-plugin/rename-tab.sh"))
            deployResource("claude-integration/session-start-hook.sh", File(claudeHome, "rider-plugin/session-start-hook.sh"))
            File(claudeHome, "commands").mkdirs()
            deployResource("claude-integration/tab.md", File(claudeHome, "commands/tab.md"))
            deployResource("claude-integration/tabs-clear.md", File(claudeHome, "commands/tabs-clear.md"))
            deployResource("claude-integration/tabs-restore.md", File(claudeHome, "commands/tabs-restore.md"))
            deployResource("claude-integration/tabs-history.md", File(claudeHome, "commands/tabs-history.md"))
            deployResource("claude-integration/tabs-backup.md", File(claudeHome, "commands/tabs-backup.md"))
            deployResource("claude-integration/tabs-status.md", File(claudeHome, "commands/tabs-status.md"))
            // Cleanup old command filenames (pre-rename)
            File(claudeHome, "commands/clear-tabs.md").delete()
            File(claudeHome, "commands/restore-tabs.md").delete()
            File(claudeHome, "commands/tab-history.md").delete()
            File(claudeHome, "commands/backup-tabs.md").delete()

            updateClaudeMd(claudeHome, claudeMdMarker)
            addPermission(claudeHome, permissionEntry)
            addSessionStartHook(claudeHome)
        } catch (e: Exception) {
            LOG.warn("[ClaudeTabs] Deploy failed: ${e.message}")
        }
    }

    private fun updateClaudeMd(claudeHome: File, marker: String) {
        val claudeMd = File(claudeHome, "CLAUDE.md")
        val existing = if (claudeMd.exists()) claudeMd.readText() else ""
        val block = """
$marker
## Terminal Tab Naming (Rider Plugin)
At the **start of every conversation**, rename your Rider terminal tab by running:
```bash
bash ~/.claude/rider-plugin/rename-tab.sh "Short Topic Name"
```

**Naming priority:**
1. If the user provides a name — in their first message, via `/tab`, or by saying "name it X", "call this X", "name this X tab" — use their **exact words** as the tab name. "name this left tab" means the name IS "left tab". Never reinterpret the user's words as a description; take them literally as the desired name.
2. Otherwise, pick a concise name (3-5 words) that describes the conversation's purpose.
3. Update it if the topic shifts significantly.

This applies to **new chats, resumed chats** (`--resume`), **and `/resume`**. On resume, re-use the previous tab name if the topic hasn't changed.
$marker
""".trimStart()
        if (existing.contains(marker)) {
            // Replace existing section with latest version
            val pattern = Regex("$marker.*?$marker", RegexOption.DOT_MATCHES_ALL)
            val updated = existing.replace(pattern, block.trim())
            if (updated != existing) {
                claudeMd.writeText(updated)
                LOG.info("[ClaudeTabs] Updated CLAUDE.md section")
            }
        } else {
            // First install — append
            claudeMd.appendText("\n$block")
            LOG.info("[ClaudeTabs] Added CLAUDE.md section")
        }
    }

    private fun addSessionStartHook(claudeHome: File) {
        val sf = File(claudeHome, "settings.json")
        if (!sf.exists()) return
        try {
            val text = sf.readText()
            if (text.contains(HOOK_MARKER) || text.contains(HOOK_MARKER_LEGACY)) return

            val hookEntry = """
                      {
                        "hooks": [
                          {
                            "type": "command",
                            "command": "bash ~/.claude/rider-plugin/session-start-hook.sh",
                            "timeout": 5
                          }
                        ]
                      }
            """.trimIndent()

            if (!text.contains("\"hooks\"")) {
                // No hooks section at all — add the entire block
                val hookJson = "\"hooks\": {\n    \"SessionStart\": [\n      $hookEntry\n    ]\n  }"
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  $hookJson\n}")
                LOG.info("[ClaudeTabs] Added hooks section with SessionStart hook")
            } else if (!text.contains("\"SessionStart\"")) {
                // Has hooks but no SessionStart — add SessionStart array
                sf.writeText(text.replace(Regex(""""hooks"\s*:\s*\{"""), "\"hooks\": {\n    \"SessionStart\": [\n      $hookEntry\n    ],"))
                LOG.info("[ClaudeTabs] Added SessionStart hook to existing hooks")
            } else {
                // Has SessionStart but our hook isn't in it — append to the array
                sf.writeText(text.replace(Regex(""""SessionStart"\s*:\s*\["""), "\"SessionStart\": [\n      $hookEntry,"))
                LOG.info("[ClaudeTabs] Appended hook to existing SessionStart array")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Hook install failed: ${e.message}")
        }
    }

    private fun addPermission(claudeHome: File, permissionEntry: String) {
        val sf = File(claudeHome, "settings.json")
        if (!sf.exists()) return
        try {
            val text = sf.readText()
            if (text.contains(permissionEntry)) return
            if (text.contains("\"allow\"")) {
                sf.writeText(text.replace(Regex(""""allow"\s*:\s*\["""), "\"allow\": [\"$permissionEntry\", "))
            } else if (text.contains("\"permissions\"")) {
                sf.writeText(text.replace(Regex(""""permissions"\s*:\s*\{"""), "\"permissions\": {\n    \"allow\": [\"$permissionEntry\"],"))
            } else {
                sf.writeText(text.trimEnd().removeSuffix("}") + ",\n  \"permissions\": {\n    \"allow\": [\"$permissionEntry\"]\n  }\n}")
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Permission install failed: ${e.message}")
        }
    }

    private fun deployResource(path: String, target: File) {
        try {
            javaClass.classLoader.getResourceAsStream(path)?.use { stream ->
                target.parentFile?.mkdirs()
                target.writeBytes(normalizeIfText(path, stream.readBytes()))
            }
        } catch (e: Exception) {
            LOG.debug("[ClaudeTabs] Deploy resource failed: $path — ${e.message}")
        }
    }

    // Bash chokes on CRLF in #!/bin/bash scripts: every \r becomes a syntax error
    // before the script runs. Source files are already LF (pinned by .gitattributes),
    // but a future processResources filter or repackaging step could reintroduce CRLF —
    // normalize at extraction time as defense in depth.
    internal fun normalizeIfText(path: String, content: ByteArray): ByteArray {
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext !in TEXT_EXTENSIONS) return content
        return String(content, Charsets.UTF_8).replace("\r\n", "\n").toByteArray(Charsets.UTF_8)
    }

    private val TEXT_EXTENSIONS = setOf("sh", "md", "json", "txt")
}
