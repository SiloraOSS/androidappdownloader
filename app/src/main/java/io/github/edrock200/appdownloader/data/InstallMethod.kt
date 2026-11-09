package io.github.edrock200.appdownloader.data

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
}
