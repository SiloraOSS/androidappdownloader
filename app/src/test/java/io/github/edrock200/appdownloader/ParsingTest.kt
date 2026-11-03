package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.SourceParser
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.install.ApkDownloader
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.net.AftvResolver
import io.github.edrock200.appdownloader.net.GitHubClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsingTest {

    @Test
    fun detectsAftvCodes() {
        assertEquals(SourceType.AFTV_CODE, SourceParser.parse("12345")?.type)
        assertEquals("12345", SourceParser.parse(" 12345 ")?.source)
        assertEquals("98765", SourceParser.parse("aftv.news/98765")?.source)
        assertEquals("98765", SourceParser.parse("https://aftv.news/98765")?.source)
        assertEquals("4321", SourceParser.parse("https://go.aftvnews.com/4321")?.source)
    }

    @Test
    fun detectsGitHubRepos() {
        val p = SourceParser.parse("https://github.com/owner/my-app")!!
        assertEquals(SourceType.GITHUB, p.type)
        assertEquals("owner/my-app", p.source)
        assertEquals("owner/my-app", SourceParser.parse("github.com/owner/my-app/releases")?.source)
        assertEquals("owner/my.app", SourceParser.parse("https://github.com/owner/my.app.git")?.source)
        assertEquals("owner/repo", SourceParser.parse("github:owner/repo")?.source)
        assertEquals("o/r", GitHubClient.parseRepo("https://www.github.com/o/r?tab=readme")?.slug)
    }

    @Test
    fun githubFileLinksAreUrls() {
        val p = SourceParser.parse("https://github.com/o/r/releases/download/v1/app.apk")!!
        assertEquals(SourceType.URL, p.type)
        assertEquals("app.apk", p.suggestedName)
    }

    @Test
    fun detectsUrls() {
        val p = SourceParser.parse("example.com/files/app.apk")!!
        assertEquals(SourceType.URL, p.type)
        assertEquals("https://example.com/files/app.apk", p.source)
        assertEquals("app.apk", p.suggestedName)
        assertEquals("http://example.com/x", SourceParser.parse("http://example.com/x")?.source)
        assertNull(SourceParser.parse("not a url"))
        assertNull(SourceParser.parse("ftp://example.com/a.apk"))
        assertNull(SourceParser.parse(""))
    }

    @Test
    fun parsesAftvRedirectPage() {
        val html = """
            <p>Redirecting <span id="countdown">in 6 seconds</span> to: <a href="https://example.com/app.apk">https://example.com/app.apk</a></p>
            <script>setTimeout(function(){ window.location = "https://example.com/app.apk"; }, 6000);</script>
        """.trimIndent()
        assertEquals("https://example.com/app.apk", AftvResolver.parseTarget(html))
        val linkOnly = """Redirecting <span>in 6 seconds</span> to: <a href="https://x.org/a?b=1&amp;c=2">x</a>"""
        assertEquals("https://x.org/a?b=1&c=2", AftvResolver.parseTarget(linkOnly))
        assertNull(AftvResolver.parseTarget("<p>The Short URL (or Code) that you entered does not exist.</p>"))
    }

    @Test
    fun findsApkLinksInPages() {
        val html = """<a href="/about">About</a> <a href='downloads/MyApp-1.2.apk?x=1&amp;y=2'>Get it</a>"""
        assertEquals(
            "https://site.com/apps/downloads/MyApp-1.2.apk?x=1&y=2",
            ApkDownloader.findApkLink(html, "https://site.com/apps/index.html"),
        )
        assertNull(ApkDownloader.findApkLink("<a href='x.zip'>zip</a>", "https://site.com/"))
    }

    @Test
    fun fileNames() {
        assertEquals("a b.apk", ApkDownloader.fileNameFrom("attachment; filename=\"a b.apk\"", "https://x/y"))
        assertEquals("app.apk", ApkDownloader.fileNameFrom(null, "https://x.com/dl/app.apk?token=1"))
    }

    @Test
    fun selectsSplitsForDevice() {
        val xapk = listOf(
            "manifest.json", "icon.png", "com.app.apk", "config.arm64_v8a.apk",
            "config.armeabi_v7a.apk", "config.x86_64.apk", "config.en.apk", "config.xxhdpi.apk",
        )
        assertEquals(
            listOf("com.app.apk", "config.armeabi_v7a.apk", "config.en.apk", "config.xxhdpi.apk"),
            ApkInspector.selectSplits(xapk, listOf("armeabi-v7a", "armeabi")),
        )
        val apks = listOf("toc.pb", "splits/base-master.apk", "splits/base-arm64_v8a.apk", "standalones/standalone-arm64_v8a.apk")
        assertEquals(
            listOf("splits/base-master.apk", "splits/base-arm64_v8a.apk"),
            ApkInspector.selectSplits(apks, listOf("arm64-v8a", "armeabi-v7a")),
        )
    }
}
