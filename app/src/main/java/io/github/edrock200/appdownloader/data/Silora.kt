package io.github.edrock200.appdownloader.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The only source this updater manages. Configuration is enforced on every repository write. */
object Silora {
    private val json = Json { ignoreUnknownKeys = true }

    const val ID = "silora"
    const val REPO = "SiloraOSS/silora-android"
    const val PACKAGE = "org.silora.app"

    fun assetName(isTv: Boolean) = if (isTv) "silora-tv.apk" else "silora-phone.apk"

    fun entries(entries: List<AppEntry>, isTv: Boolean, previous: AppEntry? = null): List<AppEntry> {
        val saved = entries.firstOrNull { it.id == ID } ?: previous
        return listOf((saved ?: AppEntry(id = ID, name = "Silora", type = SourceType.GITHUB, source = REPO)).copy(
            id = ID,
            name = "Silora",
            autoName = false,
            type = SourceType.GITHUB,
            source = REPO,
            packageName = PACKAGE,
            includePrereleases = false,
            assetFilter = "^" + Regex.escape(assetName(isTv)) + "$",
            autoUpdate = true,
        ))
    }

    fun parseBuildInfo(text: String, tag: String, downloadedAssetName: String): SiloraBuildInfo {
        val info = json.decodeFromString<SiloraBuildInfo>(text)
        require(info.tag == tag) { "Silora build information does not match this release" }
        require(info.versionName.isNotBlank() && info.versionCodeBase in 1..1_049_999_999) {
            "Invalid Silora build information"
        }
        require(downloadedAssetName == assetName(false) || downloadedAssetName == assetName(true)) { "Unexpected Silora APK" }
        return info
    }
}

@Serializable
data class SiloraBuildInfo(
    val tag: String,
    @SerialName("version_name") val versionName: String,
    @SerialName("version_code_base") val versionCodeBase: Long,
) {
    fun versionCode(isTv: Boolean) = versionCodeBase * 2 + if (isTv) 1 else 0
}
