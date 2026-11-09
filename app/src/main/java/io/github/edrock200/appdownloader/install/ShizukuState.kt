package io.github.edrock200.appdownloader.install

enum class ShizukuState(val label: String, val help: String) {
    UNSUPPORTED("Unavailable", "Shizuku requires Android 6 or newer. Choose the Standard Android installer."),
    NOT_INSTALLED("Not installed", "Install and start Shizuku, then authorize Silora Updater."),
    NOT_RUNNING("Not running", "Start Shizuku, then return to Silora Updater."),
    OUTDATED("Update required", "Update Shizuku to version 12 or newer, then restart it."),
    PERMISSION_REQUIRED("Authorization required", "Authorize Silora Updater to use Shizuku."),
    DENIED("Authorization denied", "Open Shizuku and allow Silora Updater under Authorized applications."),
    READY("Ready", "Silora can be installed without confirmation while Shizuku is running."),
    ;

    val ready get() = this == READY
}
