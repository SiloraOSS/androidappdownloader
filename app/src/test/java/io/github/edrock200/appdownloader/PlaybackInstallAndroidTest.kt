package io.github.edrock200.appdownloader

import android.app.Application
import android.content.pm.PackageInfo
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.PlaybackDeferral
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
                val expectedReason = if (state == SiloraPlaybackStatus.PLAYING)
                    PlaybackDeferral.ACTIVE else PlaybackDeferral.UNAVAILABLE
                assertEquals(expectedReason, EntryRepository(context).get(Silora.ID)!!.playbackDeferral)
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
        assertEquals(PlaybackDeferral.ACTIVE, repository.get(Silora.ID)!!.playbackDeferral)
        state = SiloraPlaybackStatus.IDLE
        assertTrue(controller.install(Silora.ID, apk, "new", background = true))
        assertNull(repository.get(Silora.ID)!!.playbackDeferral)
        assertEquals(2, reads)
        assertTrue(context.packageManager.packageInstaller.mySessions.any { it.appPackageName == Silora.PACKAGE })
    }

    @Test fun explicitManualInstallationBypassesThePlaybackQuery() = runBlocking {
        repository.update(Silora.ID) { it.copy(playbackDeferral = PlaybackDeferral.ACTIVE) }
        val controller = AppController(context, repository, settings, scope,
            SiloraPlaybackReader { error("Manual install must not query playback") })
        assertTrue(controller.install(Silora.ID, apk, "new", background = false))
        assertNull(repository.get(Silora.ID)!!.playbackDeferral)
        assertTrue(context.packageManager.packageInstaller.mySessions.any { it.appPackageName == Silora.PACKAGE })
    }

    @Test fun disabledProtectionClearsThePreviousReasonAndStartsInstallationWithoutQuerying() = runBlocking {
        repository.update(Silora.ID) { it.copy(playbackDeferral = PlaybackDeferral.ACTIVE) }
        settings.update(settings.current.copy(waitUntilPlaybackStops = false))
        val controller = AppController(context, repository, settings, scope,
            SiloraPlaybackReader { error("Disabled protection must not query playback") })
        assertTrue(controller.install(Silora.ID, apk, "new", background = true))
        assertNull(repository.get(Silora.ID)!!.playbackDeferral)
        assertTrue(context.packageManager.packageInstaller.mySessions.any { it.appPackageName == Silora.PACKAGE })
    }

    @Test fun staleDownloadClearsItsPlaybackReasonWhenTheVersionIsAlreadyInstalled() = runBlocking {
        repository.update(Silora.ID) { it.copy(playbackDeferral = PlaybackDeferral.ACTIVE) }
        val controller = AppController(context, repository, settings, scope,
            SiloraPlaybackReader { error("An already installed version must not query playback") })
        val stale = AppController.DownloadedApk(apk.file, apk.info.copy(versionCode = 1))
        assertTrue(controller.install(Silora.ID, stale, "old", background = true))
        assertNull(repository.get(Silora.ID)!!.playbackDeferral)
        assertNull(repository.get(Silora.ID)!!.readyFile)
    }
}
