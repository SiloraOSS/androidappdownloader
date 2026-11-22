package io.github.edrock200.appdownloader.data

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import io.github.edrock200.appdownloader.install.ApkInfo

enum class InstallMethod(val label: String) {
    STANDARD("Standard Android installer"),
    SHIZUKU("Shizuku"),
}

/** Permission checks belong to the selected installer; Shizuku does not need unknown-sources access. */
object InstallPolicy {
    fun canInstallInBackground(method: InstallMethod, sdk: Int, standardAllowed: Boolean, shizukuReady: Boolean): Boolean =
        when (method) {
            InstallMethod.STANDARD -> sdk >= 31 && standardAllowed
            InstallMethod.SHIZUKU -> sdk >= 23 && shizukuReady
        }

    fun standardCanUpdateSilently(context: Context, apk: ApkInfo): Boolean {
        if (Build.VERSION.SDK_INT < 31 || apk.targetSdk < minimumSilentTarget(Build.VERSION.SDK_INT)) return false
        if (apk.packageName == context.packageName) return true
        return runCatching { ownsUpdate(context, apk.packageName) }.getOrDefault(false)
    }

    internal fun minimumSilentTarget(sdk: Int): Int = when (sdk) {
        31, 32 -> 29
        33 -> 30
        34 -> 31
        35 -> 33
        36 -> 34
        else -> 35
    }

    @RequiresApi(31)
    private fun ownsUpdate(context: Context, packageName: String): Boolean {
        val source = context.packageManager.getInstallSourceInfo(packageName)
        val owner = if (Build.VERSION.SDK_INT >= 34) source.updateOwnerPackageName else null
        return (owner ?: source.installingPackageName) == context.packageName
    }
}
