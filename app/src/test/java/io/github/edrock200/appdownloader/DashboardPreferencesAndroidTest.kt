package io.github.edrock200.appdownloader

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.work.Configuration
import androidx.work.WorkManager
import io.github.edrock200.appdownloader.data.FirstRunStep
import io.github.edrock200.appdownloader.data.InstallMethod
import io.github.edrock200.appdownloader.data.Settings
import io.github.edrock200.appdownloader.ui.MainViewModel
import io.github.edrock200.appdownloader.ui.SiloraScreen
import io.github.edrock200.appdownloader.ui.theme.AppTheme
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the real screen, persistence and permission callbacks with a TV remote. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "w960dp-h540dp")
@OptIn(ExperimentalTestApi::class)
class DashboardPreferencesAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var app: App

    @Before fun createApp() {
        val context = RuntimeEnvironment.getApplication()
        try { WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
        app = App().also {
            ReflectionHelpers.callInstanceMethod<Void>(it, "attach", ClassParameter.from(Context::class.java, context))
            it.onCreate()
        }
        app.settings.saveFirstRunStep(FirstRunStep.COMPLETE)
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        shadowOf(app.packageManager).setCanRequestPackageInstalls(true)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    }

    @After fun closeApp() {
        activity.close()
        app.scope.cancel()
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
    }

    private fun showScreen() {
        val vm = MainViewModel(app)
        activity.get().setContent { AppTheme { SiloraScreen(vm) } }
        compose.waitForIdle()
    }

    private fun row(label: String) = compose.onNode(hasText(label) and hasClickAction() and
        if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) hasAnyAncestor(isDialog())
        else !hasAnyAncestor(isDialog()))

    private fun focus(label: String) {
        row(label).performSemanticsAction(SemanticsActions.RequestFocus)
        row(label).assertIsFocused()
    }

    private fun select(label: String) {
        focus(label)
        row(label).performKeyInput { pressKey(Key.DirectionCenter) }
    }

    @Test fun schedulePickerSavesCustomTimeAndReturnsFocusToItsRow() {
        showScreen()
        select("Background checks")
        row("Every 12 hours").assertIsFocused()
        compose.onNodeWithText("App installation method").assertDoesNotExist()
        select("Custom")
        compose.onNodeWithContentDescription("One hour later")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        assertTrue(Settings(app).current.hasDailyCheck)
        assertEquals(4, Settings(app).current.checkHour)
        compose.onNodeWithText("Done").performClick()
        row("Background checks").assertIsFocused()
        compose.onNodeWithText("Daily at ", substring = true).assertIsDisplayed()
        select("Background checks")
        row("Custom").assertIsFocused()
    }

    @Test fun remoteMovesBetweenRowsTogglesAutomaticInstallationAndOpensInstallerSetup() {
        showScreen()
        focus("Background checks")
        row("Background checks").performKeyInput { pressKey(Key.DirectionDown) }
        row("Automatic installation").assertIsFocused().assertIsOn()
        row("Automatic installation").performKeyInput { pressKey(Key.DirectionCenter) }
        row("Automatic installation").assertIsFocused().assertIsOff()
        assertFalse(Settings(app).current.autoInstallUpdates)
        row("Automatic installation").performKeyInput { pressKey(Key.DirectionDown) }
        row("Installation method").assertIsFocused()
        row("Installation method").performKeyInput { pressKey(Key.DirectionCenter) }
        row(InstallMethod.STANDARD.label).assertIsFocused()
        compose.onNodeWithText("Check for Silora and updater updates").assertDoesNotExist()
        select(InstallMethod.SHIZUKU.label)
        assertEquals(InstallMethod.SHIZUKU, Settings(app).current.installMethod)
        compose.onNodeWithText("Shizuku setup help").assertExists()
        compose.onNodeWithText("Done").performClick()
        row("Installation method").assertIsFocused()
    }

    @Test fun manualOnlyDisablesAutomaticInstallationAndScheduleSelectionDoesNotReenableIt() {
        showScreen()
        select("Background checks")
        select("When opened / manual only")
        compose.onNodeWithText("Done").performClick()
        assertFalse(Settings(app).current.autoInstallUpdates)
        compose.onNodeWithText("Choose a background schedule first.").assertExists()
        select("Automatic installation")
        row("When opened / manual only").assertIsFocused()
        select("Every 6 hours")
        compose.onNodeWithText("Done").performClick()
        row("Automatic installation").assertIsFocused().assertIsOff()
        assertEquals(6, Settings(app).current.checkIntervalHours)
        assertFalse(Settings(app).current.autoInstallUpdates)
        select("Automatic installation")
        row("Automatic installation").assertIsOn()
        assertTrue(Settings(app).current.autoInstallUpdates)
    }

    @Test fun enablingAutomaticInstallationUsesSetupOnlyPermissionFlow() {
        app.settings.update(app.settings.current.copy(autoInstallUpdates = false))
        shadowOf(app.packageManager).setCanRequestPackageInstalls(false)
        showScreen()
        select("Automatic installation")
        compose.onNodeWithText("Allow Silora installs").assertIsDisplayed()
        assertTrue(Settings(app).current.autoInstallUpdates)
        compose.onNodeWithText("Open settings").performClick()
        assertEquals(false, Settings(app).pendingInstallAfterPermission)
        assertTrue(app.controller.ops.value.isEmpty())
        assertTrue(app.packageManager.packageInstaller.mySessions.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        row("Automatic installation").assertIsFocused()
    }

    @Test fun fullSettingsAndDashboardShareChangesInBothDirections() {
        showScreen()
        select("Automatic installation")
        select("Settings")
        row("Install updates automatically").assertIsOff()
        select("Every 3 hours")
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Every 3 hours").assertIsDisplayed()
        select("Automatic installation")
        select("Settings")
        row("Install updates automatically").assertIsOn()
    }

    @Test @Config(qualifiers = "w400dp-h800dp")
    fun stackedLayoutKeepsRowsReachableAndRestoresFocus() {
        showScreen()
        select("Installation method")
        row(InstallMethod.STANDARD.label).assertIsFocused()
        compose.onNodeWithText("Done").performClick()
        row("Installation method").assertIsFocused().assertIsDisplayed()
        select("Background checks")
        select("Every 24 hours")
        compose.onNodeWithText("Done").performClick()
        row("Background checks").assertIsFocused().assertIsDisplayed()
    }
}
