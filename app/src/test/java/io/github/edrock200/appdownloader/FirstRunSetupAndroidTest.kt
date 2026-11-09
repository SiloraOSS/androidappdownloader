package io.github.edrock200.appdownloader

import android.Manifest
import android.app.Application
import android.content.Context
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.edrock200.appdownloader.data.FirstRunStep
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.ui.MainViewModel
import io.github.edrock200.appdownloader.ui.SiloraScreen
import io.github.edrock200.appdownloader.ui.theme.AppTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class FirstRunSetupAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activityController: ActivityController<ComponentActivity>
    private val activity get() = activityController.get()
    private val app by lazy {
        val context = RuntimeEnvironment.getApplication()
        // Robolectric does not run AndroidX Startup's provider before Application.onCreate.
        try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
        App().also {
            ReflectionHelpers.callInstanceMethod<Void>(it, "attach", ClassParameter.from(Context::class.java, context))
            it.onCreate()
        }
    }

    @Before fun createActivity() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager).setCanRequestPackageInstalls(false)
        activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After fun cleanup() {
        activityController.close()
        app.scope.cancel()
        // This Robolectric permission override otherwise leaks into other test classes.
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
    }

    private fun showScreen() {
        val vm = MainViewModel(app)
        activity.setContent { AppTheme { SiloraScreen(vm) } }
    }

    @Test fun welcomeLeadsToInstallSetupThenOptionalNotifications() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showScreen()

        compose.onNodeWithText("Welcome to Silora Updater").assertIsDisplayed()
        compose.onNodeWithText("Allow Silora installs").assertDoesNotExist()
        compose.onNodeWithText("Get Silora update alerts").assertDoesNotExist()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Allow Silora installs").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()
        compose.onNodeWithText("Get Silora update alerts").assertIsDisplayed()
        compose.onNodeWithText("Not now").performClick()

        assertEquals(FirstRunStep.COMPLETE, Settings(app).firstRunStep)
        assertTrue(app.settings.notificationPromptSeen)
        assertFalse(app.settings.notificationPermissionAsked)
    }

    @Test fun completedSetupIsNotShownOnLaterOpens() {
        app.settings.saveFirstRunStep(FirstRunStep.COMPLETE)
        showScreen()
        compose.onNodeWithText("Welcome to Silora Updater").assertDoesNotExist()
        compose.onNodeWithText("Allow Silora installs").assertDoesNotExist()
        compose.onNodeWithText("Get Silora update alerts").assertDoesNotExist()
    }

    @Test fun installSettingsKeepsDurableSetupProgressWithoutQueuingAnInstall() {
        showScreen()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Open settings").performClick()

        assertEquals(FirstRunStep.INSTALL_PERMISSION, Settings(app).firstRunStep)
        assertNull(app.settings.pendingInstallAfterPermission)
        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${app.packageName}", intent.dataString)
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun restartingAfterGrantingInstallAccessResumesAtContinueWithoutInstalling() {
        app.settings.saveFirstRunStep(FirstRunStep.INSTALL_PERMISSION)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showScreen()

        compose.onNodeWithText("Welcome to Silora Updater").assertDoesNotExist()
        compose.onNodeWithText("Allow Silora installs").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithText("Get Silora update alerts").assertIsDisplayed()
        assertTrue(app.controller.ops.value.isEmpty())
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
    }

    @Test fun grantedPermissionsAreSkipped() {
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showScreen()

        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Allow Silora installs").assertDoesNotExist()
        compose.onNodeWithText("Get Silora update alerts").assertDoesNotExist()
        assertEquals(FirstRunStep.COMPLETE, Settings(app).firstRunStep)
    }

    @Test fun notificationOptInUsesTheRuntimeRequestAndCompletesSetup() {
        app.settings.saveFirstRunStep(FirstRunStep.NOTIFICATIONS)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        showScreen()

        compose.onNodeWithText("Get Silora update alerts").assertIsDisplayed()
        compose.onNode(hasText("Enable notifications") and hasAnyAncestor(isDialog())).performClick()
        assertTrue(app.settings.notificationPermissionAsked)
        assertArrayEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            shadowOf(activity).lastRequestedPermission.requestedPermissions)
        assertEquals(FirstRunStep.COMPLETE, Settings(app).firstRunStep)
    }

    @Test fun skippingWelcomeDoesNotAskForEitherPermission() {
        showScreen()
        compose.onNodeWithText("Not now").performClick()
        assertEquals(FirstRunStep.COMPLETE, Settings(app).firstRunStep)
        assertFalse(app.settings.notificationPermissionAsked)
        assertNull(app.settings.pendingInstallAfterPermission)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
