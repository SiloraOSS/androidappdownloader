package io.github.edrock200.appdownloader

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.await
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.PlaybackDeferral
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.ApkInspector
import io.github.edrock200.appdownloader.install.InstallAttempt
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.SiloraPlaybackStatus
import io.github.edrock200.appdownloader.work.DeferredInstallReason
import io.github.edrock200.appdownloader.work.DeferredInstallWorker
import io.github.edrock200.appdownloader.work.UpdateScheduler
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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DeferredInstallAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var repository: EntryRepository
    private lateinit var settings: Settings
    private lateinit var apk: File
    private var playback = SiloraPlaybackStatus.IDLE
    private val manager get() = WorkManager.getInstance(context)

    @Before fun stageVerifiedUpdate() = runBlocking {
        try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
        val packages = shadowOf(context.packageManager)
        packages.setCanRequestPackageInstalls(true)
        packages.installPackage(packageInfo(1))
        packages.setInstallSourceInfo(Silora.PACKAGE, context.packageName, context.packageName)
        repository = EntryRepository(context)
        settings = Settings(context)
        apk = File(context.cacheDir, "deferred-test.apk")
        ZipOutputStream(apk.outputStream()).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml"))
            it.write("APK fixture".toByteArray())
            it.closeEntry()
        }
        packages.setPackageArchiveInfo(apk.absolutePath, packageInfo(2))
        val sha256 = InstallRestoreStore.sha256(apk)
        repository.update(Silora.ID) {
            it.copy(readyFile = apk.absolutePath, readyTag = TAG, readyVersionCode = 2, readySha256 = sha256,
                latestTag = TAG, latestVersionCode = 2, latestAssetDigest = "sha256:$sha256", latestAssetSize = apk.length())
        }
    }

    @After fun cleanup() { scope.cancel() }

    @Test fun playbackQueuesOneBoundedRetryWithoutChangingTheDailyCheck() = runBlocking {
        val daily = settings.current.copy(checkDaily = true, checkHour = 3, checkIntervalHours = 24)
        settings.update(daily)
        UpdateScheduler.schedule(context, daily).await()
        val before = periodicWork()
        val controller = controller()
        val download = AppController.DownloadedApk(apk, ApkInspector.inspect(context, apk))
        playback = SiloraPlaybackStatus.PLAYING
        val enqueuedAfter = System.currentTimeMillis()
        repeat(2) { assertFalse(controller.install(Silora.ID, download, TAG, background = true)) }
        val work = deferredWork(DeferredInstallReason.PLAYBACK).single()
        assertTrue(work.nextScheduleTimeMillis >= enqueuedAfter + TimeUnit.MINUTES.toMillis(15))
        assertEquals(before.id, periodicWork().id)
        assertEquals(before.nextScheduleTimeMillis, periodicWork().nextScheduleTimeMillis)

        playback = SiloraPlaybackStatus.UNKNOWN
        assertTrue(controller.retryStagedInstall(Silora.ID, apk.absolutePath, DeferredInstallReason.PLAYBACK))
        assertEquals(work.id, deferredWork(DeferredInstallReason.PLAYBACK).single().id)
        assertEquals(PlaybackDeferral.UNAVAILABLE, repository.get(Silora.ID)?.playbackDeferral)
        assertTrue(DeferredInstallWorker.canRetry(0))
        assertTrue(DeferredInstallWorker.canRetry(1))
        assertFalse(DeferredInstallWorker.canRetry(2))

        playback = SiloraPlaybackStatus.IDLE
        assertFalse(controller.retryStagedInstall(Silora.ID, apk.absolutePath, DeferredInstallReason.PLAYBACK))
        assertEquals(apk.absolutePath, repository.get(Silora.ID)?.installAttempt?.filePath)
        assertNull(repository.get(Silora.ID)?.playbackDeferral)
    }

    @Test fun unexpectedConfirmationGetsOneRetryEvenWhenAndroidAsksAgain() = runBlocking {
        val controller = controller()
        val original = InstallAttempt("original", InstallMethod.STANDARD, Silora.PACKAGE, apk.absolutePath,
            "new", 2, TAG, true, "old", 1, null, System.currentTimeMillis())
        repository.update(Silora.ID) { it.copy(installAttempt = original) }
        val enqueuedAfter = System.currentTimeMillis()
        controller.onInstallNeedsConfirmation(Silora.ID, original.id, -1, Intent(), true, null).join()
        assertEquals(true, repository.get(Silora.ID)?.backgroundInstallBlock?.confirmationRetryPending)
        val work = deferredWork(DeferredInstallReason.CONFIRMATION).single()
        assertTrue(work.nextScheduleTimeMillis >= enqueuedAfter + TimeUnit.MINUTES.toMillis(2))

        playback = SiloraPlaybackStatus.PLAYING
        assertTrue(controller.retryStagedInstall(Silora.ID, apk.absolutePath, DeferredInstallReason.CONFIRMATION))
        assertEquals(true, repository.get(Silora.ID)?.backgroundInstallBlock?.confirmationRetryPending)
        playback = SiloraPlaybackStatus.IDLE
        assertFalse(controller.retryStagedInstall(Silora.ID, apk.absolutePath, DeferredInstallReason.CONFIRMATION))
        val retry = repository.get(Silora.ID)?.installAttempt ?: error("Expected one Android install session")
        assertEquals(false, repository.get(Silora.ID)?.backgroundInstallBlock?.confirmationRetryPending)

        manager.cancelWorkById(work.id).await()
        controller.onInstallNeedsConfirmation(Silora.ID, retry.id, retry.sessionId!!, Intent(), true, null).join()
        val waiting = repository.get(Silora.ID)!!
        assertEquals(true, waiting.backgroundInstallBlock?.needsConfirmation)
        assertEquals(false, waiting.backgroundInstallBlock?.confirmationRetryPending)
        assertEquals(apk.absolutePath, waiting.readyFile)
        assertNull(waiting.installAttempt)
        assertEquals(work.id, deferredWork(DeferredInstallReason.CONFIRMATION).single().id)
        assertFalse(controller.retryStagedInstall(Silora.ID, apk.absolutePath, DeferredInstallReason.CONFIRMATION))
    }

    @Test fun retryRechecksPreferencesPermissionOwnershipAndTheExactStagedFile() = runBlocking {
        val controller = controller()
        repository.update(Silora.ID) { it.copy(playbackDeferral = PlaybackDeferral.ACTIVE) }
        val installedPath = apk.absolutePath
        settings.update(settings.current.copy(autoInstallUpdates = false))
        assertFalse(controller.retryStagedInstall(Silora.ID, installedPath, DeferredInstallReason.PLAYBACK))
        settings.update(settings.current.copy(autoInstallUpdates = true))
        val packages = shadowOf(context.packageManager)
        packages.setCanRequestPackageInstalls(false)
        assertFalse(controller.retryStagedInstall(Silora.ID, installedPath, DeferredInstallReason.PLAYBACK))
        packages.setCanRequestPackageInstalls(true)
        packages.setInstallSourceInfo(Silora.PACKAGE, "some.other.installer", "some.other.installer")
        assertFalse(controller.retryStagedInstall(Silora.ID, installedPath, DeferredInstallReason.PLAYBACK))
        packages.setInstallSourceInfo(Silora.PACKAGE, context.packageName, context.packageName)
        assertFalse(controller.retryStagedInstall(Silora.ID, "$installedPath.old", DeferredInstallReason.PLAYBACK))
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        assertTrue(apk.isFile)
    }

    private fun controller() = AppController(context, repository, settings, scope, SiloraPlaybackReader { playback })

    private fun deferredWork(reason: DeferredInstallReason) =
        manager.getWorkInfosForUniqueWork(DeferredInstallWorker.workName(Silora.ID, apk.absolutePath, reason))
            .get(10, TimeUnit.SECONDS)

    private fun periodicWork() = manager.getWorkInfosForUniqueWork("github-update-check")
        .get(10, TimeUnit.SECONDS).single { !it.state.isFinished }

    @Suppress("DEPRECATION")
    private fun packageInfo(code: Int) = PackageInfo().apply {
        packageName = Silora.PACKAGE
        versionCode = code
        applicationInfo = ApplicationInfo().apply { targetSdkVersion = 35 }
    }

    companion object {
        private const val TAG = "test-release"
    }
}
