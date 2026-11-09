package io.github.edrock200.appdownloader.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/** Process-wide listeners also receive the Binder when a background worker wakes the app. */
class ShizukuManager(private val context: Context) {
    private val _state = MutableStateFlow(ShizukuState.NOT_RUNNING)
    val state = _state.asStateFlow()
    private val received = Shizuku.OnBinderReceivedListener { refresh() }
    private val died = Shizuku.OnBinderDeadListener { refresh() }
    private var permissionDenied = false
    private val permission = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE) permissionDenied = grantResult != PackageManager.PERMISSION_GRANTED
        refresh()
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Shizuku.addBinderReceivedListenerSticky(received)
            Shizuku.addBinderDeadListener(died)
            Shizuku.addRequestPermissionResultListener(permission)
        }
        refresh()
    }

    fun refresh(): ShizukuState {
        val current = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) ShizukuState.UNSUPPORTED else runCatching {
            when {
                !Shizuku.pingBinder() -> {
                    val installed = runCatching { context.packageManager.getPackageInfo(MANAGER_PACKAGE, 0) }.isSuccess
                    if (installed) ShizukuState.NOT_RUNNING else ShizukuState.NOT_INSTALLED
                }
                Shizuku.isPreV11() || Shizuku.getVersion() < 12 -> ShizukuState.OUTDATED
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
                permissionDenied || Shizuku.shouldShowRequestPermissionRationale() -> ShizukuState.DENIED
                else -> ShizukuState.PERMISSION_REQUIRED
            }
        }.getOrDefault(ShizukuState.NOT_RUNNING)
        _state.value = current
        return current
    }

    /** Called only by a foreground user action, never by an update worker. */
    fun requestPermission(): String? {
        val current = refresh()
        if (current == ShizukuState.READY) return null
        if (current != ShizukuState.PERMISSION_REQUIRED) return current.help
        return runCatching { Shizuku.requestPermission(REQUEST_CODE); null }
            .getOrElse { refresh().help }
    }

    suspend fun awaitReady(): ShizukuState {
        var current = refresh()
        repeat(3) {
            if (current == ShizukuState.NOT_RUNNING) {
                kotlinx.coroutines.delay(500)
                current = refresh()
            }
        }
        return current
    }

    companion object {
        const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4812
    }
}
