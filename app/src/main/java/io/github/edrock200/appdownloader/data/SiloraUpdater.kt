package io.github.edrock200.appdownloader.data

import io.github.edrock200.appdownloader.BuildConfig

/** The updater's own fixed source, separate from the Silora player entry and its history. */
object SiloraUpdater {
    const val ID = "self-update"
    const val ASSET = "SiloraUpdater-tv.apk"

    fun entry(saved: AppEntry? = null): AppEntry = (saved ?: AppEntry(
        id = ID, name = "Silora Updater", type = SourceType.GITHUB, source = BuildConfig.UPDATE_REPO,
    )).copy(
        id = ID, name = "Silora Updater", autoName = false, type = SourceType.GITHUB,
        source = BuildConfig.UPDATE_REPO, packageName = BuildConfig.APPLICATION_ID,
        includePrereleases = false, assetFilter = "^${Regex.escape(ASSET)}$", autoUpdate = true,
    )

    /** Matches the release workflow and Gradle versionCode formula; rejects ambiguous tags. */
    fun versionCode(tag: String): Long {
        require(Regex("v?\\d+\\.\\d{1,2}\\.\\d{1,2}").matches(tag)) { "Invalid Silora Updater release version" }
        val (major, minor, patch) = tag.removePrefix("v").split('.').map { it.toLong() }
        require(major <= Int.MAX_VALUE / 10_000L) { "Invalid Silora Updater release version" }
        val code = major * 10_000 + minor * 100 + patch
        require(code in 1..Int.MAX_VALUE.toLong()) { "Invalid Silora Updater release version" }
        return code
    }
}
