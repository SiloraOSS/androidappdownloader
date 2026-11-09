package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.HistoryKind
import io.github.edrock200.appdownloader.data.InstallRecord
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.net.GhAsset
import io.github.edrock200.appdownloader.net.GhRelease
import io.github.edrock200.appdownloader.net.GitHubClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class InstallRestoreTest {
    private val record = InstallRecord(at = 1, versionCode = 100, tag = "old-tag", kind = HistoryKind.INSTALLED)
    private val info = ApkInfo(Silora.PACKAGE, "old", 100, "Silora", emptyList())

    @Test fun restoreRequiresTheExactPackageAndBuild() {
        InstallRestoreStore.validate(record, info, "hash")
        assertThrows(IOException::class.java) { InstallRestoreStore.validate(record, info.copy(packageName = "other.app"), "hash") }
        assertThrows(IOException::class.java) { InstallRestoreStore.validate(record, info.copy(versionCode = 101), "hash") }
        assertThrows(IOException::class.java) { InstallRestoreStore.validate(record, info.copy(splitEntries = listOf("base.apk")), "hash") }
    }

    @Test fun rejectsAReplacedReleaseEvenWhenItsVersionStillMatches() {
        val pinned = record.copy(apkSha256 = "original")
        InstallRestoreStore.validate(pinned, info, "original")
        assertThrows(IOException::class.java) { InstallRestoreStore.validate(pinned, info, "replacement") }
    }

    @Test fun olderHistoryWithoutArtifactFieldsStillLoads() {
        val restored = EntryRepository.JSON.decodeFromString<InstallRecord>(
            """{"at":1,"versionCode":100,"kind":"INSTALLED","tag":"old-tag"}""")
        assertEquals(record, restored)
        assertNull(restored.apkPath)
        assertNull(restored.apkSha256)
    }

    @Test fun exactReleaseUsesTheRightDeviceApkWithoutPickingANewerRelease() {
        val release = GhRelease(tagName = "old-tag", assets = listOf(
            GhAsset("silora-phone.apk", "https://example.com/phone.apk"),
            GhAsset("silora-tv.apk", "https://example.com/tv.apk")))
        assertEquals("silora-phone.apk", GitHubClient.pastSiloraRelease(release, "old-tag", false).asset.name)
        assertEquals("silora-tv.apk", GitHubClient.pastSiloraRelease(release, "old-tag", true).asset.name)
        assertThrows(IOException::class.java) { GitHubClient.pastSiloraRelease(release, "different-tag", true) }
        assertThrows(IOException::class.java) { GitHubClient.pastSiloraRelease(release.copy(draft = true), "old-tag", true) }
        assertThrows(IOException::class.java) { GitHubClient.pastSiloraRelease(release.copy(assets = release.assets.take(1)), "old-tag", true) }
    }
}
