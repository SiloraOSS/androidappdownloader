package io.github.edrock200.appdownloader

import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.Repeat
import io.github.edrock200.appdownloader.data.Schedule
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.TimeZone

class ScheduleTest {

    private val ny = TimeZone.getTimeZone("America/New_York")
    private fun t(s: String) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = ny }.parse(s)!!.time
    private fun f(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm").apply { timeZone = ny }.format(ms)

    @Test
    fun futureTimeIsKept() {
        assertEquals(t("2026-11-06 03:00"), Schedule.nextRun(t("2026-11-06 03:00"), Repeat.ONCE, t("2026-11-05 12:00"), ny))
    }

    @Test
    fun pastOneTimeScheduleEnds() {
        assertNull(Schedule.nextRun(t("2026-11-05 03:00"), Repeat.ONCE, t("2026-11-05 03:00"), ny))
    }

    @Test
    fun repeatsMoveForward() {
        val now = t("2026-11-05 12:00")
        assertEquals("2026-11-06 03:00", f(Schedule.nextRun(t("2026-11-05 03:00"), Repeat.DAILY, now, ny)!!))
        assertEquals("2026-11-12 03:00", f(Schedule.nextRun(t("2026-11-05 03:00"), Repeat.WEEKLY, now, ny)!!))
        // Several missed runs (device was off) skip straight to the next one.
        assertEquals("2026-11-06 03:00", f(Schedule.nextRun(t("2026-10-20 03:00"), Repeat.DAILY, now, ny)!!))
    }

    @Test
    fun keepsWallClockTimeAcrossDaylightSavingChange() {
        // US clocks go back on 2026-11-01; a daily 3:00 run must stay at 3:00 local time.
        assertEquals("2026-11-01 03:00", f(Schedule.nextRun(t("2026-10-31 03:00"), Repeat.DAILY, t("2026-10-31 04:00"), ny)!!))
    }

    @Test
    fun atTimeAndDefault() {
        assertEquals("2026-11-07 22:45", f(Schedule.atTime(t("2026-11-07 09:13"), 22, 45, ny)))
        assertEquals("2026-11-06 03:00", f(Schedule.defaultTime(t("2026-11-05 12:00"), ny)))
        assertEquals("2026-11-05 03:00", f(Schedule.defaultTime(t("2026-11-05 01:00"), ny)))
    }

    @Test
    fun summariesReadNaturally() {
        assertEquals(
            "Updated 2, 1 waiting to install, 3 up to date, 1 failed",
            UpdateRunSummary(listOf("a", "b"), listOf("c"), emptyList(), 3, listOf("d")).describe(),
        )
        assertEquals("No apps are selected for auto-update", UpdateRunSummary().describe())
    }

    @Test
    fun listsSavedByOlderVersionsStillLoad() {
        val old = """[{"id":"1","name":"Old","type":"URL","source":"https://x/a.apk"}]"""
        val entry = EntryRepository.JSON.decodeFromString(ListSerializer(AppEntry.serializer()), old).single()
        assertTrue(entry.autoUpdate)
        assertNull(entry.readyFile)
    }
}
