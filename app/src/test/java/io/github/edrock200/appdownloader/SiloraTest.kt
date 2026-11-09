package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.SourceType
import io.github.edrock200.appdownloader.data.VersionUtils
import io.github.edrock200.appdownloader.net.GhAsset
import io.github.edrock200.appdownloader.net.GhRelease
import io.github.edrock200.appdownloader.net.GitHubClient
import org.junit.Assert.*
import org.junit.Test

class SiloraTest {
    private val tag = "build-2026-11-06.212804.42342838531"
    private val metadata = """{"tag":"$tag","version_name":"1.0.0 (29) (silora:11062026)","version_code_base":426803714,"source_sha":"ignored"}"""

    @Test fun siloraExistsOnFirstLaunchAndAfterRemovalOrReplacement() {
        val first = Silora.entries(emptyList(), false).single()
        assertEquals(Silora.ID, first.id)
        assertEquals(Silora.PACKAGE, first.packageName)
        val installed = first.copy(installedTag = tag, installedVersionCode = 853607428)
        assertEquals(installed, Silora.entries(emptyList(), false, installed).single())
        val other = AppEntry(name = "Other", type = SourceType.URL, source = "https://example.com/other.apk")
        assertEquals(listOf(installed), Silora.entries(listOf(other), false, installed))
    }

    @Test fun configurationCannotBeEditedButHistoryAndTrackingSurvive() {
        val edited = Silora.entries(emptyList(), false).single().copy(
            name = "Other", source = "other/repo", packageName = "other.app", type = SourceType.URL,
            assetFilter = ".*", includePrereleases = true, autoUpdate = false,
            installedTag = tag, latestTag = tag,
        )
        val fixed = Silora.entries(listOf(edited, edited.copy(id = "duplicate")), true).single()
        assertEquals("Silora", fixed.name)
        assertEquals(Silora.REPO, fixed.source)
        assertEquals(Silora.PACKAGE, fixed.packageName)
        assertEquals(SourceType.GITHUB, fixed.type)
        assertTrue(fixed.autoUpdate)
        assertFalse(fixed.includePrereleases)
        assertEquals(tag, fixed.installedTag)
        assertEquals(tag, fixed.latestTag)
    }

    @Test fun selectsOnlyTheCorrectFormFactorAndSkipsPrereleases() {
        val assets = listOf("silora-phone.apk", "silora-tv.apk", "silune-tv.apk", "other.apk").map { GhAsset(it, "https://example.com/$it") }
        val release = GhRelease(tagName = tag, assets = assets)
        for (tv in listOf(false, true)) {
            val entry = Silora.entries(emptyList(), tv).single()
            val picked = GitHubClient.pickRelease(listOf(release.copy(tagName = "preview", prerelease = true), release), entry, listOf("arm64-v8a"))
            assertEquals(Silora.assetName(tv), picked.asset.name)
            assertEquals(tag, picked.release.tagName)
        }
    }

    @Test fun metadataMatchesActualAndroidVersionCodes() {
        val info = Silora.parseBuildInfo(metadata, tag, "silora-phone.apk")
        assertEquals(853607428L, info.versionCode(false))
        assertEquals(853607429L, info.versionCode(true))
        val entry = Silora.entries(emptyList(), false).single().copy(
            latestTag = tag, latestAssetUrl = "https://example.com/silora-phone.apk", latestVersionCode = info.versionCode(false),
        )
        // Already-installed copies from Downloader must not compare the year 2026 with version 1.0.
        assertFalse(VersionUtils.hasUpdate(entry, info.versionName, 853607428))
        assertTrue(VersionUtils.hasUpdate(entry, info.versionName, 853607426))
        assertFalse(VersionUtils.hasUpdate(entry, info.versionName, 853607430))
        assertFalse(VersionUtils.hasUpdate(entry, null, null))
        assertFalse(VersionUtils.hasUpdate(entry.copy(latestVersionCode = null), info.versionName, 853607428))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMetadataForAnotherRelease() {
        Silora.parseBuildInfo(metadata, "other-tag", "silora-phone.apk")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidVersionCodes() {
        Silora.parseBuildInfo(metadata.replace("426803714", "1049999999" + "0"), tag, "silora-phone.apk")
    }
}
