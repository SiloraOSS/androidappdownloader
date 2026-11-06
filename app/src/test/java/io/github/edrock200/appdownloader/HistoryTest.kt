package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.History
import io.github.edrock200.appdownloader.data.HistoryKind
import io.github.edrock200.appdownloader.data.InstallRecord
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTest {

    private fun rec(code: Long, at: Long, byApp: Boolean = true, name: String = "1.$code", prev: Long? = null) =
        InstallRecord(at = at, versionName = name, versionCode = code, previousVersionCode = prev,
            previousVersionName = prev?.let { "1.$it" }, kind = History.kindOf(prev, code), byApp = byApp)

    @Test
    fun kinds() {
        assertEquals(HistoryKind.INSTALLED, History.kindOf(null, 5))
        assertEquals(HistoryKind.UPDATED, History.kindOf(4, 5))
        assertEquals(HistoryKind.REINSTALLED, History.kindOf(5, 5))
        assertEquals(HistoryKind.DOWNGRADED, History.kindOf(6, 5))
    }

    @Test
    fun newestFirstAndCapped() {
        var h = emptyList<InstallRecord>()
        for (i in 1..60L) h = History.recordInstall(h, rec(i, i * 1000, prev = if (i > 1) i - 1 else null))
        assertEquals(History.MAX_RECORDS, h.size)
        assertEquals(60L, h.first().versionCode)
    }

    @Test
    fun ownInstallReplacesTheSameInstallNoticedOnTheDevice() {
        val noticed = History.syncWithDevice(listOf(rec(1, 0)), "1.2", 2, 100_000)
        assertEquals(false, noticed.first().byApp)
        val merged = History.recordInstall(noticed, rec(2, 160_000, prev = 1))
        assertEquals(listOf(2L, 1L), merged.map { it.versionCode })
        assertTrue(merged.first().byApp)
    }

    @Test
    fun syncAddsOnlyRealChanges() {
        val start = listOf(rec(3, 0))
        assertSame(start, History.syncWithDevice(start, "1.3", 3, 50))
        val outside = History.syncWithDevice(start, "1.4", 4, 99)
        assertEquals(HistoryKind.UPDATED, outside.first().kind)
        assertEquals(false, outside.first().byApp)
        assertEquals(99L, outside.first().at)
        // An app with no history yet records the version that is on the device.
        assertEquals(HistoryKind.FOUND, History.syncWithDevice(emptyList(), "2.0", 20, 7).single().kind)
    }

    @Test
    fun descriptions() {
        assertEquals("Installed 1.5", History.describe(rec(5, 0)))
        assertEquals("Updated 1.4 → 1.5", History.describe(rec(5, 0, prev = 4)))
        assertEquals("Updated 1.4 → 1.5 (automatic)", History.describe(rec(5, 0, prev = 4).copy(automatic = true)))
        assertEquals("Updated 1.4 → 1.5 (outside App Downloader)", History.describe(rec(5, 0, byApp = false, prev = 4)))
        assertEquals("Version 1.5 was on this device", History.describe(rec(5, 0).copy(kind = HistoryKind.FOUND, byApp = false)))
        assertEquals("Installed 1.5 (release nightly-42)", History.describe(rec(5, 0).copy(tag = "nightly-42")))
        assertEquals("Installed 1.5", History.describe(rec(5, 0).copy(tag = "v1.5"))) // Tag same as version: not repeated.
        assertEquals("Installed build 7", History.describe(InstallRecord(at = 0, versionCode = 7, kind = HistoryKind.INSTALLED)))
    }

    @Test
    fun historySurvivesSaving() {
        val entry = AppEntry(name = "x", type = io.github.edrock200.appdownloader.data.SourceType.URL, source = "https://x/a.apk",
            history = listOf(rec(2, 5, prev = 1)))
        val serializer = ListSerializer(AppEntry.serializer())
        val back = EntryRepository.JSON.decodeFromString(serializer, EntryRepository.JSON.encodeToString(serializer, listOf(entry)))
        assertEquals(entry.history, back.single().history)
    }
}
