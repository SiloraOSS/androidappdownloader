package io.github.edrock200.appdownloader

import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import io.github.edrock200.appdownloader.data.HistoryKind
import io.github.edrock200.appdownloader.data.InstallRecord
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.ui.HistoryDialog
import io.github.edrock200.appdownloader.ui.theme.AppTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33], qualifiers = "w960dp-h540dp")
class HistoryDialogAndroidTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    private fun showHistory(fontScale: Float = 1f) {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        shadowOf(activity.get().packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val entry = Silora.entries(emptyList(), true).single().copy(
            history = List(8) { index ->
                InstallRecord(
                    at = 1_794_614_400_000L - index * 86_400_000L,
                    versionName = "1.2.0 (31) (silora:11132026)", versionCode = 8L - index,
                    previousVersionName = "1.2.0 (31) (silora:11122026)",
                    tag = "build-2026-11-13.131559.43509062773", kind = HistoryKind.UPDATED,
                    automatic = true,
                )
            },
        )
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                AppTheme { HistoryDialog(entry, onDismiss = {}, onRestore = {}) }
            }
        }
        compose.waitForIdle()
    }

    @After fun closeActivity() { activity.close() }

    private fun focusRestore(index: Int) {
        val button = compose.onAllNodesWithText("Restore this build")[index]
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        button.assertIsFocused()
    }

    private fun assertEntryVisible(index: Int) {
        val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val date = compose.onAllNodesWithText(" at ", substring = true)[index].getUnclippedBoundsInRoot()
        assertTrue("Date $index ($date) must be fully inside $viewport", date.top >= viewport.top && date.bottom <= viewport.bottom)
        assertRestoreVisible(index)
    }

    private fun assertRestoreVisible(index: Int) {
        val viewport = compose.onNode(hasScrollAction()).getUnclippedBoundsInRoot()
        val button = compose.onAllNodesWithText("Restore this build")[index].getUnclippedBoundsInRoot()
        assertTrue("Restore $index ($button) must be fully inside $viewport", button.top >= viewport.top && button.bottom <= viewport.bottom)
    }

    @Test fun openingOverflowingHistoryKeepsTheFirstDateVisible() {
        showHistory()
        focusRestore(0)
        assertEntryVisible(0)
    }

    @Test fun returningToTheFirstRestoreKeepsItsDateVisible() {
        showHistory()
        focusRestore(1)
        assertEntryVisible(1)
        focusRestore(0)
        assertEntryVisible(0)
    }

    @Test fun navigatingOlderEntriesKeepsTheirDatesAndRestoreActionsVisible() {
        showHistory()
        for (index in 0..7) {
            focusRestore(index)
            assertEntryVisible(index)
        }
    }

    @Test fun largerTextKeepsTheFirstDateVisibleAfterScrollingBack() {
        showHistory(fontScale = 1.3f)
        focusRestore(0)
        assertEntryVisible(0)
        focusRestore(1)
        assertEntryVisible(1)
        focusRestore(0)
        assertEntryVisible(0)
    }

    @Test fun entryTallerThanTheViewportKeepsItsRestoreActionVisible() {
        showHistory(fontScale = 2f)
        focusRestore(0)
        assertRestoreVisible(0)
        focusRestore(1)
        assertRestoreVisible(1)
        focusRestore(0)
        assertRestoreVisible(0)
    }
}
