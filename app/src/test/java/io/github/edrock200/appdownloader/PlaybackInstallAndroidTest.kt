package io.github.edrock200.appdownloader

import android.app.Application
import android.content.pm.PackageInfo
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.ApkInfo
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.SiloraPlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class PlaybackInstallAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var repository: EntryRepository
    private lateinit var settings: Settings
    private lateinit var apk: AppController.DownloadedApk

    @Before fun stageUpdate() = runBlocking {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = Silora.PACKAGE
            @Suppress("DEPRECATION")
            versionCode = 1
        })
        repository = EntryRepository(context)
        settings = Settings(context)
        val file = File(context.cacheDir, "silora-playback-test.apk").apply { writeText("staged APK") }
        apk = AppController.DownloadedApk(file, ApkInfo(Silora.PACKAGE, "new", 2, "Silora", emptyList()))
        repository.update(Silora.ID) { it.copy(readyFile = file.absolutePath,
            readyVersionCode = 2, readyVersionName = "new", readyTag = "new") }
    }

    @After fun cleanup() { scope.cancel() }

    @Test fun activeOrUnreadablePlaybackLeavesTheApkReadyWithoutCallingEitherInstaller() = runBlocking {
        for (method in InstallMethod.entries) {
            settings.update(settings.current.copy(installMethod = method))
            for (state in listOf(SiloraPlaybackStatus.PLAYING, SiloraPlaybackStatus.UNKNOWN)) {
                val controller = AppController(context, repository, settings, scope, SiloraPlaybackReader { state })
                assertFalse(controller.install(Silora.ID, apk, "new", background = true))
                assertTrue(apk.file.exists())
                assertEquals(apk.file.absolutePath, repository.get(Silora.ID)!!.readyFile)
                assertFalse(controller.isBusy(Silora.ID))
                assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
            }
        }
    }

    @Test fun nextAttemptReadsPlaybackAgainAndStartsInstallationOnceIdle() = runBlocking {
        var state = SiloraPlaybackStatus.PLAYING
        var reads = 0
        val controller = AppController(context, repository, settings, scope,
            SiloraPlaybackReader { reads++; state })
        assertFalse(controller.install(Silora.ID, apk, "new", background = true))
        state = SiloraPlaybackStatus.IDLE
        assertTrue(controller.install(Silora.ID, apk, "new", background = true))
        assertEquals(2, reads)
        assertTrue(context.packageManager.packageInstaller.mySessions.any { it.appPackageName == Silora.PACKAGE })
    }

    @Test fun explicitManualInstallationBypassesThePlaybackQuery() = runBlocking {
        val controller = AppController(context, repository, settings, scope,
            SiloraPlaybackReader { error("Manual install must not query playback") })
        assertTrue(controller.install(Silora.ID, apk, "new", background = false))
        assertTrue(context.packageManager.packageInstaller.mySessions.any { it.appPackageName == Silora.PACKAGE })
    }
}
