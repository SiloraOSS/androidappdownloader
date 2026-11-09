package io.github.edrock200.appdownloader

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.ApkInstaller
import io.github.edrock200.appdownloader.install.InstallResultReceiver
import io.github.edrock200.appdownloader.install.ShizukuManager
import io.github.edrock200.appdownloader.install.ShizukuState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShizukuAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun selectedInstallerSurvivesReopeningAndUnknownValuesFallBackToStandard() {
        val settings = Settings(context)
        assertEquals(InstallMethod.STANDARD, settings.current.installMethod)
        settings.update(settings.current.copy(installMethod = InstallMethod.SHIZUKU))
        assertEquals(InstallMethod.SHIZUKU, Settings(context).current.installMethod)
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("install_method", "future-value").commit()
        assertEquals(InstallMethod.STANDARD, Settings(context).current.installMethod)
    }

    @Test @Config(sdk = [23]) fun unsupportedSdkRetainsStandardInstallationWithoutInitializingShizuku() {
        // Robolectric 4.16 starts at API 23; override only the reported SDK to exercise the guard.
        val original = Build.VERSION.SDK_INT
        try {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 22)
            val manager = ShizukuManager(context)
            assertEquals(ShizukuState.UNSUPPORTED, manager.refresh())
            assertNotNull(manager.requestPermission())
            assertEquals(InstallMethod.STANDARD, Settings(context).current.installMethod)
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", original)
        }
    }

    @Test @Config(sdk = [23]) fun missingAndStoppedServiceHaveDistinctSetupStates() {
        val manager = ShizukuManager(context)
        assertEquals(ShizukuState.NOT_INSTALLED, manager.refresh())
        shadowOf(context.packageManager).installPackage(PackageInfo().apply { packageName = ShizukuManager.MANAGER_PACKAGE })
        assertEquals(ShizukuState.NOT_RUNNING, manager.refresh())
        assertNotNull(manager.requestPermission())
        assertTrue(context.resources.getBoolean(R.bool.shizuku_supported))
    }

    @Test fun resultCallbackKeepsHistoryMetadataAndCannotCollideWithAnotherInstall() {
        val file = File(context.cacheDir, "silora.apk")
        val info = ApkInfo("org.silora.app", "2", 20, "Silora", emptyList())
        fun callback() = ApkInstaller.resultCallback(context, 0, file, info, "silora", "new", true, "1", 10, viaShizuku = true)
        val first = callback()
        val second = callback()
        val intent = shadowOf(first).savedIntent
        assertNotEquals(first, second)
        assertEquals(ComponentName(context, InstallResultReceiver::class.java), intent.component)
        assertEquals(file.absolutePath, intent.getStringExtra(ApkInstaller.EXTRA_FILE))
        assertEquals(20, intent.getLongExtra(ApkInstaller.EXTRA_VERSION_CODE, -1))
        assertEquals(10, intent.getLongExtra(ApkInstaller.EXTRA_PREV_VERSION_CODE, -1))
        assertTrue(intent.getBooleanExtra(ApkInstaller.EXTRA_BACKGROUND, false))
        assertTrue(intent.getBooleanExtra(ApkInstaller.EXTRA_SHIZUKU, false))
        assertTrue(intent.getBooleanExtra(ApkInstaller.EXTRA_KEEP_DOWNLOAD, false))
    }

    @Test fun immutableCallbackDeliversResultCodeAndMetadataWithoutAFillInIntent() {
        var received = -1
        var metadata: String? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received = resultCode
                metadata = intent.getStringExtra("entry")
            }
        }
        val action = "${context.packageName}.TEST_SHIZUKU_RESULT"
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val callback = PendingIntent.getBroadcast(context, 25,
                Intent(action).setPackage(context.packageName).putExtra("entry", "silora"), PendingIntent.FLAG_IMMUTABLE)
            // Robolectric's fake sender requires Context; the device check covers the context-free call.
            callback.intentSender.sendIntent(context, android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, null, null, null)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, received)
            assertEquals("silora", metadata)
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
