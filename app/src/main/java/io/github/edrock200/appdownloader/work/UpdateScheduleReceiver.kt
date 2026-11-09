package io.github.edrock200.appdownloader.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.edrock200.appdownloader.app
import androidx.work.await
import kotlinx.coroutines.launch

/** Keep the custom hour local when the system clock or time zone changes. */
class UpdateScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val state = context.app().settings.current
        if (!state.hasDailyCheck) return
        val pending = goAsync()
        context.app().scope.launch {
            try {
                UpdateScheduler.schedule(context, context.app().settings.current).await()
            } finally {
                pending.finish()
            }
        }
    }
}
