package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.net.AssetSelector
import io.github.edrock200.appdownloader.net.GhAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionAndAssetTest {

    @Test
    fun comparesVersions() {
        assertEquals(1, VersionUtils.compare("v1.10.0", "1.9.9"))
        assertEquals(0, VersionUtils.compare("v2.0", "2.0.0"))
        assertEquals(-1, VersionUtils.compare("release-1.2.3", "1.2.4-beta"))
        assertNull(VersionUtils.compare("nightly", "1.0"))
    }

    private fun entry(latest: String?, installedTag: String? = null, installedCode: Long? = null) = AppEntry(
        name = "x", type = SourceType.GITHUB, source = "o/r", packageName = "com.x",
        latestTag = latest, latestAssetUrl = latest?.let { "https://x/$it.apk" },
        installedTag = installedTag, installedVersionCode = installedCode,
    )

    @Test
    fun detectsUpdates() {
        // Installed by us: tag comparison is authoritative.
        assertTrue(VersionUtils.hasUpdate(entry("v1.1", "v1.0", 10), "1.0", 10))
        assertFalse(VersionUtils.hasUpdate(entry("v1.1", "v1.1", 11), "1.1-custom", 11))
        // Installed elsewhere: compare version names.
        assertTrue(VersionUtils.hasUpdate(entry("v2.0.0"), "1.9.0", 5))
        assertFalse(VersionUtils.hasUpdate(entry("v2.0.0"), "2.0.0", 5))
        // Not installed: no "update", just install.
        assertFalse(VersionUtils.hasUpdate(entry("v2.0.0"), null, null))
        assertFalse(VersionUtils.hasUpdate(entry(null), "1.0", 1))
    }

    private fun assets(vararg names: String) = names.map { GhAsset(it, "https://dl/$it") }

    @Test
    fun picksAssetForAbi() {
        val list = assets(
            "app-arm64-v8a-release.apk", "app-armeabi-v7a-release.apk",
            "app-x86_64-release.apk", "app-universal-release.apk", "source.zip",
        )
        assertEquals("app-arm64-v8a-release.apk", AssetSelector.select(list, null, listOf("arm64-v8a", "armeabi-v7a"))?.name)
        assertEquals("app-armeabi-v7a-release.apk", AssetSelector.select(list, null, listOf("armeabi-v7a", "armeabi"))?.name)
        assertEquals("app-universal-release.apk", AssetSelector.select(list, null, listOf("mips"))?.name)
        assertEquals("app-universal-release.apk", AssetSelector.select(list, "universal", listOf("arm64-v8a"))?.name)
        assertEquals("app-x86_64-release.apk", AssetSelector.select(list, "x86_64", listOf("x86_64"))?.name)
    }

    @Test
    fun skipsIncompatibleOrMissingAssets() {
        assertNull(AssetSelector.select(assets("app-arm64.apk"), null, listOf("armeabi-v7a")))
        assertNull(AssetSelector.select(assets("notes.txt", "src.tar.gz"), null, listOf("arm64-v8a")))
        assertEquals("App.apk", AssetSelector.select(assets("App.apk"), null, listOf("x86"))?.name)
        assertEquals("app.apk", AssetSelector.select(assets("app.xapk", "app.apk"), null, listOf("x86"))?.name)
    }

    @Test
    fun detectsAbiNames() {
        assertEquals("arm64-v8a", AssetSelector.detectAbi("foo-aarch64.apk"))
        assertEquals("armeabi-v7a", AssetSelector.detectAbi("foo-arm.apk"))
        assertEquals("armeabi-v7a", AssetSelector.detectAbi("foo_armv7.apk"))
        assertEquals("x86_64", AssetSelector.detectAbi("foo-x86_64.apk"))
        assertEquals("x86", AssetSelector.detectAbi("foo-x86.apk"))
        assertNull(AssetSelector.detectAbi("foo-release.apk"))
        assertNull(AssetSelector.detectAbi("smartapp.apk"))
    }
}
