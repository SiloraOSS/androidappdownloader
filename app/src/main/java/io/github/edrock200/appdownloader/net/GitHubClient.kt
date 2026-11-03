package io.github.edrock200.appdownloader.net

import io.github.edrock200.appdownloader.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.io.IOException

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

data class RepoRef(val owner: String, val repo: String) {
    val slug get() = "$owner/$repo"
    val url get() = "https://github.com/$slug"
}

data class ReleaseMatch(val release: GhRelease, val asset: GhAsset)

object GitHubClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val REPO_URL = Regex(
        "^(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:[/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val SLUG = Regex("^(?:gh:|github:)([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$", RegexOption.IGNORE_CASE)

    /** Accepts `https://github.com/owner/repo[/...]`, `github.com/owner/repo` or `github:owner/repo`. */
    fun parseRepo(input: String): RepoRef? {
        val s = input.trim()
        val m = REPO_URL.find(s) ?: SLUG.find(s) ?: return null
        val (owner, repo) = m.destructured
        if (owner.isBlank() || repo.isBlank()) return null
        return RepoRef(owner, repo)
    }

    /**
     * Returns the newest (non-draft) release that has an installable asset
     * matching the entry's filter and this device's ABIs.
     */
    suspend fun findLatest(
        entry: AppEntry,
        token: String?,
        supportedAbis: List<String>,
    ): ReleaseMatch = withContext(Dispatchers.IO) {
        val ref = parseRepo("github.com/" + entry.source)
            ?: throw IOException("Invalid repository: ${entry.source}")
        pickRelease(fetchReleases(ref, token), entry, supportedAbis)
    }

    /** Chooses the newest eligible release with a suitable asset, or throws explaining why none fits. */
    fun pickRelease(releases: List<GhRelease>, entry: AppEntry, supportedAbis: List<String>): ReleaseMatch {
        val slug = entry.source
        val candidates = releases.filter { !it.draft && (entry.includePrereleases || !it.prerelease) }
        if (candidates.isEmpty()) {
            throw IOException(
                if (releases.isEmpty()) "$slug has no releases"
                else "$slug has no stable releases (enable pre-releases to include them)"
            )
        }
        for (release in candidates) {
            val asset = AssetSelector.select(release.assets, entry.assetFilter, supportedAbis)
            if (asset != null) return ReleaseMatch(release, asset)
        }
        throw IOException(
            if (entry.assetFilter.isNullOrBlank()) "No APK for this device in the latest releases of $slug"
            else "No APK matching \"${entry.assetFilter}\" in the latest releases of $slug"
        )
    }

    fun parseReleases(body: String): List<GhRelease> =
        json.decodeFromString(ListSerializer(GhRelease.serializer()), body)

    private suspend fun fetchReleases(ref: RepoRef, token: String?): List<GhRelease> {
        val builder = Request.Builder()
            .url("https://api.github.com/repos/${ref.owner}/${ref.repo}/releases?per_page=15")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
        if (!token.isNullOrBlank()) builder.header("Authorization", "Bearer $token")
        Http.execute(builder.build()).use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> return parseReleases(body)
                response.code == 404 ->
                    throw IOException("Repository ${ref.slug} not found (private repos need a token)")
                response.code == 401 ->
                    throw IOException("GitHub token is invalid or expired")
                (response.code == 403 || response.code == 429) &&
                    (response.header("X-RateLimit-Remaining") == "0" || body.contains("rate limit", true)) ->
                    throw IOException("GitHub rate limit reached. Add a personal access token in Settings.")
                response.code == 403 -> throw IOException("GitHub denied access to ${ref.slug} (HTTP 403)")
                else -> throw IOException("GitHub returned HTTP ${response.code}")
            }
        }
    }
}
