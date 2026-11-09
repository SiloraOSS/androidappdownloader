package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.SiloraUpdater
import io.github.edrock200.appdownloader.net.GitHubClient
import io.github.edrock200.appdownloader.net.GhRelease
import io.github.edrock200.appdownloader.net.AssetSelector
import io.github.edrock200.appdownloader.net.GhAsset
import io.github.edrock200.appdownloader.ui.plainText
import org.junit.Assert.assertEquals
import org.junit.Test

class SelfUpdateTest {

    @Test
    fun selfUpdateFilterPicksReleaseApk() {
        val assets = listOf("AppDownloader.apk", "SiloraUpdater-device-check.apk", "SiloraUpdater-tv.apk", "SHA256SUMS")
            .map { GhAsset(it, "https://x/$it") }
        val picked = AssetSelector.select(assets, SiloraUpdater.entry().assetFilter, listOf("arm64-v8a"))
        assertEquals("SiloraUpdater-tv.apk", picked?.name)
        val stable = GhRelease("v1.2.0", assets = assets)
        val match = GitHubClient.pickRelease(listOf(stable.copy(tagName = "v1.3.0", prerelease = true),
            stable.copy(tagName = "v1.2.1", draft = true), stable), SiloraUpdater.entry(), listOf("arm64-v8a"))
        assertEquals("v1.2.0", match.release.tagName)
    }

    @Test fun updaterVersionsMatchGradleAndRejectAmbiguousTags() {
        assertEquals(10100L, SiloraUpdater.versionCode("v1.1.0"))
        org.junit.Assert.assertTrue(SiloraUpdater.versionCode("1.10.0") > SiloraUpdater.versionCode("1.9.99"))
        for (tag in listOf("latest", "v1.0", "v1.0.4-beta", "1.100.1", "999999999999.0.0", "0.0.0")) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SiloraUpdater.versionCode(tag) }
        }
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
