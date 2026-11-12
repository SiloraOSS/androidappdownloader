package io.github.edrock200.appdownloader

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import io.github.edrock200.appdownloader.data.SettingsState
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.PlaybackDeferral
import io.github.edrock200.appdownloader.install.InstalledInfo
import io.github.edrock200.appdownloader.install.ShizukuState
import io.github.edrock200.appdownloader.ui.EntryUi
import io.github.edrock200.appdownloader.ui.SiloraDashboard
import io.github.edrock200.appdownloader.ui.theme.AppTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "w960dp-h540dp")
class SiloraFocusAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val release = Silora.entries(emptyList(), true).single().copy(
        latestVersionCode = 3, latestVersionName = "3.0",
    )
    private var item by mutableStateOf(EntryUi(release, null, null, false))
    private var checking by mutableStateOf(false)
    private var settings by mutableStateOf(SettingsState())

    @Before fun showDashboard() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().setContent {
            AppTheme {
                SiloraDashboard(
                    item = item, checking = checking, updatingAutomatically = false,
                    settings = settings, shizuku = ShizukuState.READY,
                    installAllowed = true, notificationsEnabled = true,
                    canOpen = item.installed != null, isTv = true,
                    onInstall = {}, onCheck = {}, onOpen = {}, onCancel = {},
                    onSettings = {}, onHistory = {}, restoring = false,
                    onInstallPermission = {}, onNotifications = {},
                )
            }
        }
        compose.waitForIdle()
        // Drive frames explicitly while progress indicators are animating.
        compose.mainClock.autoAdvance = false
    }

    @After fun closeActivity() { activity.close() }

    private fun focus(label: String) {
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText(label).assertIsFocused()
    }

    private fun updateState(block: () -> Unit) {
        compose.runOnIdle {
            block()
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeBy(100)
        compose.waitForIdle()
    }

    private fun finishInstall() {
        updateState { item = item.copy(op = OpState.Downloading(0.5f)) }
        compose.onNodeWithText("Please wait…").assertIsNotEnabled()
        updateState { item = item.copy(op = OpState.Installing) }
        compose.onNodeWithText("Please wait…").assertIsNotEnabled()
        updateState {
            item = item.copy(installed = InstalledInfo("3.0", 3), op = null, hasUpdate = false)
        }
        compose.onNodeWithText("Open Silora").assertIsFocused()
    }

    @Test fun firstInstallKeepsFocusWhenTheButtonBecomesOpen() {
        compose.onNodeWithText("Check for updates").assertIsFocused()
        focus("Install Silora")
        finishInstall()
    }

    @Test fun upgradeKeepsFocusWhenTheButtonBecomesOpen() {
        updateState { item = item.copy(installed = InstalledInfo("2.0", 2), hasUpdate = true) }
        focus("Update Silora")
        finishInstall()
    }

    @Test @Config(qualifiers = "w400dp-h800dp")
    fun stackedLayoutKeepsInstallFocusToo() {
        focus("Install Silora")
        finishInstall()
    }

    @Test fun failedInstallReturnsFocusToRetry() {
        focus("Install Silora")
        updateState { item = item.copy(op = OpState.Installing) }
        updateState { item = item.copy(op = OpState.Failed("Install was cancelled.")) }
        compose.onNodeWithText("Install Silora").assertIsFocused()
    }

    @Test fun checkKeepsFocusWhenItMovesFromPrimaryToSecondary() {
        updateState {
            item = item.copy(entry = release.copy(latestVersionCode = null), installed = InstalledInfo("2.0", 2))
        }
        focus("Check for updates")
        updateState { checking = true }
        updateState {
            item = item.copy(entry = release, hasUpdate = true)
            checking = false
        }
        compose.onNodeWithText("Check for updates").assertIsFocused()
    }

    @Test fun backgroundInstallDoesNotSelectOpenWhenCheckWasSelected() {
        focus("Check for updates")
        updateState { item = item.copy(op = OpState.Installing) }
        updateState { item = item.copy(installed = InstalledInfo("3.0", 3), op = null) }
        compose.onNodeWithText("Check for updates").assertIsFocused()
    }

    @Test fun waitingReasonDistinguishesPlaybackFromConfirmationAndRespectsTheSetting() {
        updateState {
            item = item.copy(installed = InstalledInfo("2.0", 2), hasUpdate = true,
                entry = release.copy(readyFile = "/downloaded.apk", playbackDeferral = PlaybackDeferral.ACTIVE))
        }
        compose.onNodeWithText("Waiting for playback").assertExists()
        compose.onNodeWithText("Update downloaded · Waiting for playback to stop.", substring = true).assertExists()
        updateState { settings = settings.copy(waitUntilPlaybackStops = false) }
        compose.onNodeWithText("Waiting for playback").assertDoesNotExist()
        compose.onNodeWithText("An update is downloaded. Choose Install update to finish.").assertExists()
        updateState {
            settings = settings.copy(waitUntilPlaybackStops = true)
            item = item.copy(entry = item.entry.copy(playbackDeferral = PlaybackDeferral.UNAVAILABLE))
        }
        compose.onNodeWithText("Playback check unavailable").assertExists()
        compose.onNodeWithText("Update downloaded · Could not check playback.", substring = true).assertExists()
        updateState { settings = settings.copy(autoInstallUpdates = false) }
        compose.onNodeWithText("Playback check unavailable").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w400dp-h800dp")
    fun stackedLayoutShowsPlaybackDeferralToo() {
        updateState { item = item.copy(entry = release.copy(readyFile = "/downloaded.apk", playbackDeferral = PlaybackDeferral.ACTIVE)) }
        compose.onNodeWithText("Waiting for playback").assertExists()
        compose.onNodeWithText("Update downloaded · Waiting for playback to stop.", substring = true).assertExists()
    }
}
