package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.net.AssetSelector
import io.github.edrock200.appdownloader.net.GhAsset
import io.github.edrock200.appdownloader.ui.plainText
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfUpdateTest {

    @Test
    fun selfUpdateFilterPicksReleaseApk() {
        val assets = listOf("AppDownloader-debug.apk", "AppDownloader.apk", "notes.txt").map { GhAsset(it, "https://x/$it") }
        // Same filter as AppController.checkSelfUpdate; the release only ships AppDownloader.apk.
        val picked = AssetSelector.select(assets.filter { it.name != "AppDownloader-debug.apk" }, "^AppDownloader.*\\.apk$", listOf("arm64-v8a"))
        assertEquals("AppDownloader.apk", picked?.name)
    }

    @Test
    fun versionCodeOrderingMatchesTags() {
        // Mirrors the versionCode formula in app/build.gradle.kts.
        fun code(v: String) = v.split('.').map { it.toInt() }.let { (a, b, c) -> a * 10_000 + b * 100 + c }
        assertEquals(10100, code("1.1.0"))
        assert(code("1.10.0") > code("1.9.99"))
        assert(code("2.0.0") > code("1.99.99"))
    }

    @Test
    fun autoCheckRunsOnEveryLaunchButNotTooOften() {
        val min = SelfUpdatePolicy.MIN_INTERVAL_MS
        fun check(enabled: Boolean = true, offered: Boolean = false, running: Boolean = false, last: Long = 0, now: Long = 10 * min) =
            SelfUpdatePolicy.shouldAutoCheck(enabled, offered, running, last, now)
        assertEquals(true, check()) // First launch.
        assertEquals(true, check(last = 8 * min)) // Reopened later: checks again.
        assertEquals(false, check(last = 10 * min - 1000)) // Reopened a moment after a successful check.
        assertEquals(false, check(enabled = false)) // Turned off in Settings.
        assertEquals(false, check(offered = true)) // Update dialog already showing / installing.
        assertEquals(false, check(running = true)) // A check (or its retries) is in progress.
    }

    @Test
    fun releaseNotesBecomePlainText() {
        val md = "**New**\n- Checks for its own [updates](https://x)\n\n## Heading\n* Item"
        assertEquals("New\n• Checks for its own updates\n\nHeading\n• Item", plainText(md))
    }

}
