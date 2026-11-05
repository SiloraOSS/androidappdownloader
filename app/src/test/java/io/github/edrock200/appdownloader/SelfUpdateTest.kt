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
    fun autoCheckRunsOnEveryLaunch() {
        fun check(enabled: Boolean = true, offered: Boolean = false, running: Boolean = false) =
            SelfUpdatePolicy.shouldAutoCheck(enabled, offered, running)
        assertEquals(true, check()) // Every launch while the tick box is on.
        assertEquals(false, check(enabled = false)) // Tick box off.
        assertEquals(false, check(offered = true)) // Prompt already showing / update installing.
        assertEquals(false, check(running = true)) // A check (or its retries) is in progress.
    }

    @Test
    fun appListIsCheckedOnLaunchButNotOverAndOver() {
        val min = UpdateCheckPolicy.MIN_INTERVAL_MS
        assertEquals(true, UpdateCheckPolicy.shouldCheckOnLaunch(false, 0, 10 * min)) // First launch.
        assertEquals(true, UpdateCheckPolicy.shouldCheckOnLaunch(false, 9 * min, 10 * min)) // Reopened later.
        assertEquals(false, UpdateCheckPolicy.shouldCheckOnLaunch(false, 10 * min - 1000, 10 * min)) // Just checked.
        assertEquals(false, UpdateCheckPolicy.shouldCheckOnLaunch(true, 0, 10 * min)) // Check in progress.
    }

    @Test
    fun releaseNotesBecomePlainText() {
        val md = "**New**\n- Checks for its own [updates](https://x)\n\n## Heading\n* Item"
        assertEquals("New\n• Checks for its own updates\n\nHeading\n• Item", plainText(md))
    }

}
