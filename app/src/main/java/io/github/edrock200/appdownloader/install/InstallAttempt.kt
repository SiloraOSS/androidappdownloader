package io.github.edrock200.appdownloader.install

import io.github.edrock200.appdownloader.data.InstallMethod
import kotlinx.serialization.Serializable

/** Saved before handing off to Android or Shizuku, both of which can outlive this process. */
@Serializable
data class InstallAttempt(
    val id: String,
    val method: InstallMethod,
    val packageName: String,
    val filePath: String,
    val versionName: String?,
    val versionCode: Long,
    val tag: String?,
    val background: Boolean,
    val previousVersionName: String?,
    val previousVersionCode: Long?,
    val restoreId: String?,
    val startedAt: Long,
    val sessionId: Int? = null,
    val needsConfirmation: Boolean = false,
)
