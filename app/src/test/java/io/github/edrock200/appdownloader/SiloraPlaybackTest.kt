package io.github.edrock200.appdownloader

import androidx.media3.common.Player
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.install.PlaybackInstallPolicy
import io.github.edrock200.appdownloader.install.SiloraMediaSession
import io.github.edrock200.appdownloader.install.SiloraPlaybackReader
import io.github.edrock200.appdownloader.install.SiloraPlaybackStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SiloraPlaybackTest {
    @Test fun bufferingAndReadyWithPlaybackRequestedBothProtectThePlayer() {
        // isPlaying alone would incorrectly allow an install during buffering or audio-focus loss.
        for (state in listOf(Player.STATE_READY, Player.STATE_BUFFERING)) {
            assertEquals(SiloraPlaybackStatus.PLAYING, SiloraMediaSession.status(state, true))
            assertEquals(SiloraPlaybackStatus.IDLE, SiloraMediaSession.status(state, false))
        }
        for (state in listOf(Player.STATE_IDLE, Player.STATE_ENDED)) {
            for (playWhenReady in listOf(true, false)) {
                assertEquals(SiloraPlaybackStatus.IDLE, SiloraMediaSession.status(state, playWhenReady))
            }
        }
    }

    @Test fun automaticInstallDefersUntilAConfirmedIdleReadingWithoutCachingTheOldState() = runBlocking {
        var state = SiloraPlaybackStatus.PLAYING
        val reader = SiloraPlaybackReader { state }
        assertFalse(PlaybackInstallPolicy.allowsInstall(Silora.PACKAGE, true, reader))
        state = SiloraPlaybackStatus.UNKNOWN
        assertFalse(PlaybackInstallPolicy.allowsInstall(Silora.PACKAGE, true, reader))
        state = SiloraPlaybackStatus.IDLE
        assertTrue(PlaybackInstallPolicy.allowsInstall(Silora.PACKAGE, true, reader))
        state = SiloraPlaybackStatus.PLAYING
        assertFalse(PlaybackInstallPolicy.allowsInstall(Silora.PACKAGE, true, reader))
    }

    @Test fun explicitInstallsAndUpdaterReplacementDoNotQueryOrWaitForThePlayer() = runBlocking {
        val reader = SiloraPlaybackReader { error("This install must not query Silora's session") }
        assertTrue(PlaybackInstallPolicy.allowsInstall(Silora.PACKAGE, false, reader))
        assertTrue(PlaybackInstallPolicy.allowsInstall("org.silora.updater", true, reader))
    }

    @Test(expected = CancellationException::class)
    fun cancellingTheWorkerDoesNotTurnIntoPermissionToInstall() = runBlocking {
        PlaybackInstallPolicy.allowsInstall(Silora.PACKAGE, true,
            SiloraPlaybackReader { throw CancellationException("Worker stopped") })
        Unit
    }
}
