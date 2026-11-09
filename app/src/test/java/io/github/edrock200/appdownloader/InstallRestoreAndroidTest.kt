package io.github.edrock200.appdownloader

import android.app.Application
import android.Manifest
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import io.github.edrock200.appdownloader.data.EntryRepository
import io.github.edrock200.appdownloader.data.HistoryKind
import io.github.edrock200.appdownloader.data.InstallRecord
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.InstallRestoreStore
import io.github.edrock200.appdownloader.install.InstalledApps
import io.github.edrock200.appdownloader.install.PendingInstallRestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [23])
class InstallRestoreAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var scope: CoroutineScope
    private lateinit var store: InstallRestoreStore
    private lateinit var repository: EntryRepository
    private lateinit var settings: Settings
    private val record = InstallRecord(at = 1, versionName = "old", versionCode = 100,
        tag = "old-tag", kind = HistoryKind.INSTALLED)

    @Before fun setup() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        store = InstallRestoreStore(context)
        store.clear()
        repository = EntryRepository(context)
        settings = Settings(context)
    }

    @After fun cleanup() { scope.cancel() }

    private fun controller() = AppController(context, repository, settings, scope)

    @Suppress("DEPRECATION")
    private fun packageInfo(code: Int) = PackageInfo().apply {
        packageName = Silora.PACKAGE; versionCode = code; versionName = "version-$code"
    }

    private fun stage(confirmed: Boolean = false, installing: Boolean = false): PendingInstallRestore {
        val source = File(context.cacheDir, "source.apk")
        ZipOutputStream(source.outputStream()).use {
            it.putNextEntry(ZipEntry("AndroidManifest.xml")); it.write("manifest".toByteArray()); it.closeEntry()
        }
        store.stage(source)
        shadowOf(context.packageManager).setPackageArchiveInfo(store.apk.absolutePath, packageInfo(100))
        return PendingInstallRestore(record, InstallRestoreStore.sha256(store.apk),
            previousVersionName = "new", previousVersionCode = 200,
            confirmed = confirmed, installing = installing).also { store.save(it) }
    }

    @Test fun restoreProgressAndStagedApkSurviveRestart() {
        val pending = stage(confirmed = true)
        assertEquals(pending, InstallRestoreStore(context).load())
        assertEquals(pending, controller().installRestore.value.pending)
        assertTrue(InstallRestoreStore(context).apk.isFile)
    }

    @Test @Config(sdk = [33]) fun restoreDeclaresThePermissionRequiredToOpenAndroidsUninstaller() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_DELETE_PACKAGES))
    }

    @Test fun retainedVersionsSurviveTheNextDownloadAndRejectUnrelatedPaths() {
        stage()
        val originalHash = InstallRestoreStore.sha256(store.apk)
        val path = store.retain(store.apk, originalHash)
        store.apk.writeText("next download")
        val saved = store.localApk(record.copy(apkPath = path))!!
        assertEquals(originalHash, InstallRestoreStore.sha256(saved))
        assertNull(store.localApk(record.copy(apkPath = File(context.filesDir, "entries.json").absolutePath)))
        saved.delete()
        assertNull(store.localApk(record.copy(apkPath = path)))
    }

    @Test fun installCannotStartBeforeTheDataLossConfirmation() {
        stage()
        val controller = controller()
        controller.installAfterRestoreUninstall()
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        assertNull(controller.ops.value[Silora.ID])
        assertFalse(controller.installRestore.value.pending!!.installing)
    }

    @Test fun cancelledOrBlockedUninstallNeverStartsAnInstall() {
        stage(confirmed = true)
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        val controller = controller()
        controller.installAfterRestoreUninstall()
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        assertTrue(controller.installRestore.value.error!!.contains("cancelled or blocked"))
        assertFalse(controller.installRestore.value.pending!!.installing)
    }

    @Test fun missingOrChangedApkStopsBeforeUninstall() = runBlocking {
        stage()
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        val controller = controller()
        store.apk.writeText("changed")
        assertNull(controller.confirmInstallRestore(200))
        assertFalse(controller.installRestore.value.pending!!.confirmed)
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
        store.apk.delete()
        assertNull(controller.confirmInstallRestore(200))
        assertFalse(controller.installRestore.value.pending!!.confirmed)
    }

    @Test fun confirmationPersistsConsentAndPreviousVersionOnlyAfterValidation() = runBlocking {
        stage()
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        val controller = controller()
        assertEquals(true, controller.confirmInstallRestore(200))
        assertTrue(store.load()!!.confirmed)
        assertEquals(200L, store.load()!!.previousVersionCode)
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun scheduledUpdatesDoNotTouchAnActiveRestore() = runBlocking {
        stage(confirmed = true)
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        settings.update(settings.current.copy(autoInstallUpdates = true))
        val controller = controller()
        assertTrue(controller.isBusy(Silora.ID))
        val result = controller.updateSelected(interactive = false)
        assertTrue(result.updated.isEmpty())
        assertTrue(result.failed.isEmpty())
        assertEquals(0, result.upToDate)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
    }

    @Test fun savedBuildPreparesOfflineWithoutUninstallingOrStartingAnInstall() = runBlocking {
        stage()
        val checksum = InstallRestoreStore.sha256(store.apk)
        val local = record.copy(tag = null, apkPath = store.retain(store.apk, checksum), apkSha256 = checksum)
        store.clear()
        repository.update(Silora.ID) { it.copy(history = listOf(local)) }
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        val controller = controller()
        controller.prepareInstallRestore(local)
        val ready = withTimeout(5000) { controller.installRestore.first { !it.preparing } }
        assertNull(ready.error)
        assertEquals(local, ready.pending!!.record)
        assertFalse(ready.pending.confirmed)
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun verifiedRestoreStartsTheInstallerOnlyAfterSiloraIsAbsent() = runBlocking {
        stage(confirmed = true)
        val controller = controller()
        controller.installAfterRestoreUninstall()
        withTimeout(5000) {
            while (context.packageManager.packageInstaller.mySessions.isEmpty()) delay(10)
        }
        val session = context.packageManager.packageInstaller.mySessions.single()
        assertEquals(Silora.PACKAGE, session.appPackageName)
        assertTrue(store.load()!!.installing)
        assertNotNull(store.load()!!.attemptId)
        assertEquals(OpState.Installing, controller.ops.value[Silora.ID])
    }

    @Test fun lateCallbacksFromAnEarlierRestoreCannotChangeTheCurrentAttempt() {
        val pending = stage(confirmed = true, installing = true).copy(attemptId = "current-attempt")
        store.save(pending)
        val controller = controller()
        assertTrue(controller.acceptsInstallResult(Silora.ID, "current-attempt"))
        assertFalse(controller.acceptsInstallResult(Silora.ID, "previous-attempt"))
        assertFalse(controller.acceptsInstallResult(Silora.ID, null))
        controller.onInstallFailed(Silora.ID, PackageInstaller.STATUS_FAILURE_ABORTED, null, restoreAttemptId = "previous-attempt")
        controller.onInstallSucceeded(Silora.ID, "old-tag", 100, store.apk.absolutePath, restoreAttemptId = "previous-attempt")
        assertEquals(pending, controller.installRestore.value.pending)
        assertTrue(store.apk.isFile)
    }

    @Test @Config(sdk = [33]) fun installationPermissionIsCheckedBeforeUninstalling() = runBlocking {
        stage()
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        val controller = controller()
        assertNull(controller.confirmInstallRestore(200))
        assertTrue(controller.installRestore.value.error!!.contains("Allow installs"))
        assertFalse(controller.installRestore.value.pending!!.confirmed)
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
    }

    @Test fun anInstallThatAppearedAfterTheWarningRequiresFreshConfirmation() = runBlocking {
        stage()
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        val controller = controller()
        assertNull(controller.confirmInstallRestore(null))
        assertTrue(controller.installRestore.value.error!!.contains("installed version changed"))
        assertFalse(controller.installRestore.value.pending!!.confirmed)
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
    }

    @Test fun failedInstallKeepsTheApkAndDurableRetryState() = runBlocking {
        stage(confirmed = true, installing = true)
        val controller = controller()
        controller.onInstallFailed(Silora.ID, PackageInstaller.STATUS_FAILURE_ABORTED, null)
        withTimeout(5000) { controller.installRestore.first { it.pending?.installing == false } }
        assertTrue(store.apk.isFile)
        assertTrue(store.load()!!.confirmed)
        assertFalse(store.load()!!.installing)
        assertNotNull(controller.installRestore.value.error)
    }

    @Test fun cancellingBeforeUninstallLeavesTheAppAndAutomaticUpdatesAlone() = runBlocking {
        stage()
        shadowOf(context.packageManager).installPackage(packageInfo(200))
        settings.update(settings.current.copy(autoInstallUpdates = true))
        val controller = controller()
        controller.cancelInstallRestore()
        withTimeout(5000) { controller.installRestore.first { !it.active } }
        assertNull(store.load())
        assertFalse(store.apk.exists())
        assertTrue(settings.current.autoInstallUpdates)
        assertEquals(200L, InstalledApps.get(context, Silora.PACKAGE)!!.versionCode)
    }

    @Test fun successfulRestoreRecordsTheDowngradeAndDisablesAutomaticInstallation() = runBlocking {
        stage(confirmed = true, installing = true)
        shadowOf(context.packageManager).installPackage(packageInfo(100))
        settings.update(settings.current.copy(autoInstallUpdates = true))
        val controller = controller()
        controller.onInstallSucceeded(Silora.ID, "old-tag", 100, store.apk.absolutePath)
        withTimeout(5000) { controller.installRestore.first { !it.active } }
        val installed = repository.get(Silora.ID)!!.history.first()
        assertEquals(HistoryKind.DOWNGRADED, installed.kind)
        assertEquals(200L, installed.previousVersionCode)
        assertEquals(100L, installed.versionCode)
        assertNotNull(installed.apkSha256)
        assertFalse(settings.current.autoInstallUpdates)
        assertNull(store.load())
        assertTrue(context.packageManager.packageInstaller.mySessions.isEmpty())
    }
}
