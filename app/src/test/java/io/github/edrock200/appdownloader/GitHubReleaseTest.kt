package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.net.GitHubClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class GitHubReleaseTest {

    // Trimmed copy of a GET /repos/{owner}/{repo}/releases response.
    private val body = """
        [
          {"url":"https://api.github.com/repos/o/r/releases/3","html_url":"https://github.com/o/r/releases/tag/v3.0.0-rc1",
           "id":3,"tag_name":"v3.0.0-rc1","name":"RC","draft":false,"prerelease":true,
           "published_at":"2026-10-30T10:00:00Z","author":{"login":"o"},
           "assets":[{"id":31,"name":"app-v3.0.0-rc1.apk","size":100,"content_type":"application/vnd.android.package-archive",
                      "browser_download_url":"https://github.com/o/r/releases/download/v3.0.0-rc1/app-v3.0.0-rc1.apk"}]},
          {"html_url":"https://github.com/o/r/releases/tag/v2.1.0","id":2,"tag_name":"v2.1.0","name":"2.1.0",
           "draft":false,"prerelease":false,"published_at":"2026-10-01T10:00:00Z",
           "assets":[
             {"name":"checksums.txt","size":1,"browser_download_url":"https://github.com/o/r/releases/download/v2.1.0/checksums.txt"},
             {"name":"app-arm64-v8a.apk","size":10,"browser_download_url":"https://github.com/o/r/releases/download/v2.1.0/app-arm64-v8a.apk"},
             {"name":"app-armeabi-v7a.apk","size":10,"browser_download_url":"https://github.com/o/r/releases/download/v2.1.0/app-armeabi-v7a.apk"}
           ]},
          {"html_url":"https://github.com/o/r/releases/tag/v2.0.0","id":1,"tag_name":"v2.0.0","draft":false,"prerelease":false,
           "assets":[{"name":"app-universal.apk","size":10,"browser_download_url":"https://github.com/o/r/releases/download/v2.0.0/app-universal.apk"}]}
        ]
    """.trimIndent()

    private fun entry(pre: Boolean = false, filter: String? = null) =
        AppEntry(name = "r", type = SourceType.GITHUB, source = "o/r", includePrereleases = pre, assetFilter = filter)

    @Test
    fun picksLatestStableForAbi() {
        val m = GitHubClient.pickRelease(GitHubClient.parseReleases(body), entry(), listOf("armeabi-v7a", "armeabi"))
        assertEquals("v2.1.0", m.release.tagName)
        assertEquals("app-armeabi-v7a.apk", m.asset.name)
        assertEquals("2026-10-01T10:00:00Z", m.release.publishedAt)
        assertEquals("https://github.com/o/r/releases/tag/v2.1.0", m.release.htmlUrl)
    }

    @Test
    fun includesPrereleasesWhenAsked() {
        val m = GitHubClient.pickRelease(GitHubClient.parseReleases(body), entry(pre = true), listOf("arm64-v8a"))
        assertEquals("v3.0.0-rc1", m.release.tagName)
    }

    @Test
    fun fallsBackToOlderReleaseWhenNoAssetMatches() {
        val m = GitHubClient.pickRelease(GitHubClient.parseReleases(body), entry(), listOf("x86_64"))
        assertEquals("v2.0.0", m.release.tagName)
        assertEquals("app-universal.apk", m.asset.name)
    }

    @Test
    fun explainsWhenNothingFits() {
        val e = runCatching {
            GitHubClient.pickRelease(GitHubClient.parseReleases(body), entry(filter = "nothing-like-this"), listOf("arm64-v8a"))
        }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("nothing-like-this"))
        val empty = runCatching { GitHubClient.pickRelease(emptyList(), entry(), listOf("arm64-v8a")) }.exceptionOrNull()
        assertEquals("o/r has no releases", empty?.message)
    }
}
