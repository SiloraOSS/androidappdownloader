package io.github.edrock200.appdownloader.install

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.edrock200.appdownloader.data.Silora
import io.github.edrock200.appdownloader.data.PlaybackDeferral
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SiloraPlaybackStatus { IDLE, PLAYING, UNKNOWN }

fun interface SiloraPlaybackReader {
    suspend fun read(): SiloraPlaybackStatus
}

/** The exported player service already exists in Silora; no notification access is needed. */
@androidx.annotation.OptIn(UnstableApi::class)
class SiloraMediaSession(context: Context) : SiloraPlaybackReader {
    private val context = context.applicationContext

    override suspend fun read(): SiloraPlaybackStatus = withContext(Dispatchers.Main.immediate) {
        try {
            withTimeoutOrNull(5_000) {
                val token = SessionToken(context, ComponentName(Silora.PACKAGE, SERVICE_CLASS))
                val future = MediaController.Builder(context, token).buildAsync()
                try {
                    val controller = suspendCancellableCoroutine<MediaController> { continuation ->
                        future.addListener({
                            if (continuation.isActive) {
                                try {
                                    continuation.resume(future.get())
                                } catch (error: Exception) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }, { it.run() })
                    }
                    if (!controller.isConnected) SiloraPlaybackStatus.UNKNOWN
                    else status(controller.playbackState, controller.playWhenReady)
                } finally {
                    // Also releases a controller that connects after a timeout/cancellation.
                    MediaController.releaseFuture(future)
                }
            } ?: SiloraPlaybackStatus.UNKNOWN
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Missing/old service, rejected connection or dead process is not proof of idle.
            SiloraPlaybackStatus.UNKNOWN
        }
    }

    companion object {
        const val SERVICE_CLASS = "org.siloserver.silo.common.player.SiloPlaybackService"

        fun status(playbackState: Int, playWhenReady: Boolean): SiloraPlaybackStatus =
            if (playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING))
                SiloraPlaybackStatus.PLAYING
            else SiloraPlaybackStatus.IDLE
    }
}

/** Applies to automatic player replacement; explicit installs and updater self-updates proceed. */
object PlaybackInstallPolicy {
    suspend fun allowsInstall(packageName: String, background: Boolean, reader: SiloraPlaybackReader,
        waitUntilPlaybackStops: Boolean = true): Boolean =
        deferralReason(packageName, background, reader, waitUntilPlaybackStops) == null

    suspend fun deferralReason(packageName: String, background: Boolean, reader: SiloraPlaybackReader,
        waitUntilPlaybackStops: Boolean): PlaybackDeferral? {
        if (!background || packageName != Silora.PACKAGE || !waitUntilPlaybackStops) return null
        return when (reader.read()) {
            SiloraPlaybackStatus.PLAYING -> PlaybackDeferral.ACTIVE
            SiloraPlaybackStatus.UNKNOWN -> PlaybackDeferral.UNAVAILABLE
            SiloraPlaybackStatus.IDLE -> null
        }
    }
}
