package io.github.edrock200.appdownloader

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.InstallAttempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class InstallAttemptAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository by lazy { EntryRepository(context) }
    private fun controller() = AppController(context, repository, Settings(context), scope)

    @After fun cleanup() { scope.cancel() }

    @Test fun vanishedSessionOnRestartLeavesTheApkReady() = runBlocking {
        val attempt = stage()
        val controller = controller()
        assertTrue(controller.isBusy(Silora.ID))
        controller.reconcileInstallAttempts().join()
        assertFalse(controller.isBusy(Silora.ID))
        assertEquals(attempt.filePath, controller.waitingUpdates().single().readyFile)
        assertNull(EntryRepository(context).get(Silora.ID)?.installAttempt)
    }

    @Test fun lateFailureIsIgnoredAndLateSuccessIsRecordedOnceWithoutReplacingTheNewAttempt() = runBlocking {
        val attempt = stage()
        val controller = controller()
        controller.onInstallFailed(Silora.ID, PackageInstaller.STATUS_FAILURE_ABORTED, null, attemptId = "older").join()
        repeat(2) {
            controller.onInstallSucceeded(Silora.ID, "old-tag", 100, null, attemptId = "older",
                versionName = "older build").join()
        }
        val entry = repository.get(Silora.ID)!!
        assertEquals(attempt, entry.installAttempt)
        assertEquals(attempt.filePath, entry.readyFile)
        assertEquals("older", entry.history.single().attemptId)
        assertEquals(OpState.Installing, controller.ops.value[Silora.ID])
    }

    @Test fun confirmationWithoutAForegroundActivityPreservesTheDownload() = runBlocking {
        val attempt = stage()
        val controller = controller()
        controller.onInstallNeedsConfirmation(Silora.ID, attempt.id, -1, Intent(), false, null).join()
        assertFalse(controller.isBusy(Silora.ID))
        assertEquals(attempt.filePath, controller.waitingUpdates().single().readyFile)
        assertTrue(File(attempt.filePath).isFile)
    }

    @Test fun backgroundConfirmationIsRememberedForThisBuild() = runBlocking {
        val attempt = stage(background = true)
        controller().onInstallNeedsConfirmation(Silora.ID, attempt.id, -1, Intent(), true, null).join()
        val saved = EntryRepository(context).get(Silora.ID)!!
        assertNull(saved.installAttempt)
        assertEquals(attempt.versionCode, saved.backgroundInstallBlock?.versionCode)
        assertEquals(true, saved.backgroundInstallBlock?.needsConfirmation)
    }

    @Test fun readyPromptIsOfferedOnceForEachNewVersionAcrossSettingsInstances() {
        assertTrue(Settings(context).markReadyPromptShown(101))
        assertFalse(Settings(context).markReadyPromptShown(101))
        assertTrue(Settings(context).markReadyPromptShown(102))
    }

    private suspend fun stage(background: Boolean = false): InstallAttempt {
        val apk = File(context.cacheDir, "attempt.apk").apply { writeText("staged bytes") }
        val attempt = InstallAttempt("current", InstallMethod.STANDARD, Silora.PACKAGE, apk.absolutePath,
            "new build", 200, "new-tag", background, null, null, null, System.currentTimeMillis(), sessionId = 999)
        repository.update(Silora.ID) {
            it.copy(readyFile = apk.absolutePath, readyVersionCode = 200, readyTag = "new-tag", installAttempt = attempt)
        }
        return attempt
    }
}
