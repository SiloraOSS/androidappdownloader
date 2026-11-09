package io.github.edrock200.appdownloader

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInstaller
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.SelfUpdateStore
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.SiloraUpdater
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
@Config(application = Application::class, sdk = [23, 33])
class SelfUpdateAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val nextCode = BuildConfig.VERSION_CODE + 1L
    private fun controller() = AppController(context, EntryRepository(context), Settings(context), scope)
    @After fun cleanup() { scope.cancel() }

    private suspend fun stage(code: Long = nextCode): File {
        val apk = File(context.cacheDir, "self-update.apk").apply { writeText("staged") }
        SelfUpdateStore(context).update { it.copy(latestTag = "v99.0.0", latestVersionCode = code,
            latestAssetUrl = "https://example.invalid/SiloraUpdater-tv.apk", readyFile = apk.absolutePath,
            readyVersionCode = code, readyVersionName = "new", readyTag = "v99.0.0") }
        return apk
    }

    @Test fun readySelfUpdateSurvivesProcessRecreationAndAnOfflineLaunch() = runBlocking {
        val apk = stage()
        val fresh = controller()
        assertTrue(fresh.selfUpdate.value!!.downloaded)
        assertEquals(apk.absolutePath, fresh.waitingUpdates().single().readyFile)
        assertEquals(SiloraUpdater.ID, fresh.waitingUpdates().single().id)
        assertEquals(1, EntryRepository(context).entries.value.size) // Player history stays separate.
    }

    @Test fun successfulReplacementWithoutAnyOldProcessCallbackDiscardsTheStagedUpdate() = runBlocking {
        val apk = stage(BuildConfig.VERSION_CODE.toLong())
        val restarted = SelfUpdateStore(context)
        assertNull(restarted.current.readyFile)
        assertFalse(apk.exists())
        assertNull(controller().selfUpdate.value)
        assertTrue(controller().waitingUpdates().isEmpty())
    }

    @Test fun pendingUserActionKeepsTheSelfUpdateAcrossRestart() = runBlocking {
        stage()
        controller().onBackgroundInstallNeedsUser(SiloraUpdater.ID)
        val restarted = controller()
        assertTrue(restarted.selfUpdate.value!!.downloaded)
        assertEquals(1, restarted.waitingUpdates().size)
    }

    @Test fun cancellingThePromptKeepsItsOfflineDownloadButDismissesOnlyThisLaunch() = runBlocking {
        stage()
        val fresh = controller()
        fresh.dismissSelfUpdate()
        assertNull(fresh.selfUpdate.value)
        assertEquals(1, fresh.waitingUpdates().size)
        assertNotNull(controller().selfUpdate.value)
        fresh.onInstallFailed(SiloraUpdater.ID, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertTrue(controller().selfUpdate.value!!.downloaded)
    }

    @Test fun replacementReceiverIsDeclaredAndNotExported() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getReceiverInfo(android.content.ComponentName(context,
            io.github.edrock200.appdownloader.work.UpdateScheduleReceiver::class.java), 0)
        assertFalse(info.exported)
        val resolved = context.packageManager.queryBroadcastReceivers(Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
            .setPackage(context.packageName), 0)
        assertTrue(resolved.any { it.activityInfo.name.endsWith("UpdateScheduleReceiver") })
    }
}
