package io.github.edrock200.appdownloader.data

import kotlinx.serialization.Serializable

@Serializable
enum class HistoryKind { INSTALLED, UPDATED, REINSTALLED, DOWNGRADED, FOUND }

/** One install or update of an app. */
@Serializable
data class InstallRecord(
    val at: Long,
    val versionName: String? = null,
    val versionCode: Long,
    val previousVersionName: String? = null,
    val previousVersionCode: Long? = null,
    /** GitHub release tag, when installed from a release. */
    val tag: String? = null,
    val kind: HistoryKind,
    /** False when the change was made outside App Downloader (or before history was kept). */
    val byApp: Boolean = true,
    /** True when done by a scheduled update rather than by the user. */
    val automatic: Boolean = false,
)

/** Rules for an app's update history (pure, for tests). */
object History {
    const val MAX_RECORDS = 50

    /** How long a "seen on the device" record may be replaced by App Downloader's own record of the same install. */
    private const val MERGE_WINDOW_MS = 10 * 60_000L

    fun kindOf(previousCode: Long?, newCode: Long): HistoryKind = when {
        previousCode == null -> HistoryKind.INSTALLED
        newCode > previousCode -> HistoryKind.UPDATED
        newCode == previousCode -> HistoryKind.REINSTALLED
        else -> HistoryKind.DOWNGRADED
    }

    /** Adds an install App Downloader just did. Newest first; at most [MAX_RECORDS]. */
    fun recordInstall(history: List<InstallRecord>, record: InstallRecord): List<InstallRecord> {
        val last = history.firstOrNull()
        // The same install may already have been noticed on the device a moment earlier.
        val rest = if (last != null && !last.byApp && last.versionCode == record.versionCode &&
            record.at - last.at in -MERGE_WINDOW_MS..MERGE_WINDOW_MS
        ) {
            history.drop(1)
        } else {
            history
        }
        return (listOf(record) + rest).take(MAX_RECORDS)
    }

    /**
     * Notices a version change made outside App Downloader. [installedAt] is when Android says the
     * installed version was installed. Returns the history unchanged when nothing is new.
     */
    fun syncWithDevice(
        history: List<InstallRecord>,
        installedName: String?,
        installedCode: Long,
        installedAt: Long,
    ): List<InstallRecord> {
        val last = history.firstOrNull()
        if (last != null && last.versionCode == installedCode) return history
        val record = InstallRecord(
            at = installedAt,
            versionName = installedName,
            versionCode = installedCode,
            previousVersionName = last?.versionName,
            previousVersionCode = last?.versionCode,
            kind = if (last == null) HistoryKind.FOUND else kindOf(last.versionCode, installedCode),
            byApp = false,
        )
        return (listOf(record) + history).take(MAX_RECORDS)
    }

    /** Plain-language description, e.g. "Updated 1.2.0 → 1.3.0 (automatic)". */
    fun describe(r: InstallRecord): String {
        fun v(name: String?, code: Long?) = name?.takeIf { it.isNotBlank() } ?: code?.let { "build $it" } ?: "?"
        val to = v(r.versionName, r.versionCode)
        val from = v(r.previousVersionName, r.previousVersionCode)
        val text = when (r.kind) {
            HistoryKind.INSTALLED -> "Installed $to"
            HistoryKind.UPDATED -> "Updated $from → $to"
            HistoryKind.REINSTALLED -> "Reinstalled $to"
            HistoryKind.DOWNGRADED -> "Changed $from → $to (older version)"
            HistoryKind.FOUND -> "Version $to was on this device"
        }
        val notes = listOfNotNull(
            r.tag?.takeIf { it != r.versionName && it.removePrefix("v") != r.versionName }?.let { "release $it" },
            "automatic".takeIf { r.automatic },
            "outside App Downloader".takeIf { !r.byApp && r.kind != HistoryKind.FOUND },
        )
        return if (notes.isEmpty()) text else "$text (${notes.joinToString(", ")})"
    }
}
