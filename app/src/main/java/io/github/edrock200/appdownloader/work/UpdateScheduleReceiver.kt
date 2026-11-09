package io.github.edrock200.appdownloader.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.edrock200.appdownloader.app
import androidx.work.await
import kotlinx.coroutines.launch

/** Restore monitoring after our process is replaced, and keep custom hours local. */
class UpdateScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val replaced = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!replaced && intent.action != Intent.ACTION_TIME_CHANGED && intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val state = context.app().settings.current
        if (!replaced && !state.hasDailyCheck) return
        val pending = goAsync()
        context.app().scope.launch {
            try {
                // Receipt starts Application/WorkManager in the NEW package without opening an
                // activity. WorkManager recovers interrupted work from its persistent database.
                // UPDATE also repairs missing OS jobs while preserving this unique work's identity.
                UpdateScheduler.schedule(context, context.app().settings.current).await()
                if (replaced) android.util.Log.i("SiloraUpdater", "Restored update checks after package replacement")
            } finally {
                pending.finish()
            }
        }
    }
}
