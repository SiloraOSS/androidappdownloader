package io.github.edrock200.appdownloader.data

import java.util.Calendar
import java.util.TimeZone

/** Date/time arithmetic for scheduled updates (kept free of Android APIs for unit tests). */
object Schedule {

    /**
     * Returns the first run time that is after [now]: [at] itself if it is still in the
     * future, otherwise [at] moved forward by whole days/weeks. Returns null for a
     * one-time schedule that has already passed. Wall-clock time is kept across DST changes.
     */
    fun nextRun(at: Long, repeat: Repeat, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        if (at > now) return at
        if (repeat.isHourly) {
            // Fixed intervals of real time, so a DST change doesn't skip or double a run.
            val interval = repeat.hours * 3_600_000L
            return at + ((now - at) / interval + 1) * interval
        }
        val field = when (repeat) {
            Repeat.DAILY -> Calendar.DAY_OF_YEAR
            Repeat.WEEKLY -> Calendar.WEEK_OF_YEAR
            else -> return null
        }
        val cal = Calendar.getInstance(zone).apply { timeInMillis = at }
        while (cal.timeInMillis <= now) cal.add(field, 1)
        return cal.timeInMillis
    }

    /** Midnight-relative helper: the given date (any time on that day) at [hour]:[minute]. */
    fun atTime(day: Long, hour: Int, minute: Int, zone: TimeZone = TimeZone.getDefault()): Long =
        Calendar.getInstance(zone).apply {
            timeInMillis = day
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** Default suggestion: the next 3:00 AM. */
    fun defaultTime(now: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val today = atTime(now, 3, 0, zone)
        return nextRun(today, Repeat.DAILY, now, zone)!!
    }
}
