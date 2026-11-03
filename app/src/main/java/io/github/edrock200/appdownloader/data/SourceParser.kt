package io.github.edrock200.appdownloader.data

import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.GitHubClient

/** Figures out what kind of source the user typed and normalises it. */
object SourceParser {

    data class Parsed(val type: SourceType, val source: String, val suggestedName: String)

    fun parse(raw: String): Parsed? {
        val input = raw.trim()
        if (input.isEmpty()) return null

        AftvResolver.normalizeCode(input)?.let { code ->
            return Parsed(SourceType.AFTV_CODE, code, "AFTV code $code")
        }
        // Direct file links on github.com are plain downloads, not repositories to monitor.
        val isGitHubFileLink = Regex("/(releases/download|raw|blob)/").containsMatchIn(input)
        if (!isGitHubFileLink) {
            GitHubClient.parseRepo(input)?.let { ref ->
                return Parsed(SourceType.GITHUB, ref.slug, ref.repo)
            }
        }
        if (input.any { it.isWhitespace() }) return null

        val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(input)) input else "https://$input"
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val afterScheme = url.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (host.isBlank() || !host.contains('.') && !host.startsWith("localhost")) return null
        val fileName = afterScheme.substringBefore('?').substringBefore('#')
            .substringAfterLast('/', "").takeIf { it.isNotBlank() }
        return Parsed(SourceType.URL, url, fileName ?: host)
    }
}
