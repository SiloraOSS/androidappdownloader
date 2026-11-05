package io.github.edrock200.appdownloader.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.edrock200.appdownloader.R
import io.github.edrock200.appdownloader.data.AppEntry
import io.github.edrock200.appdownloader.ui.MainActivity

object Notifications {
    private const val CHANNEL_UPDATES = "updates"
    private const val ID_UPDATES = 1
    private const val ID_WAITING = 2
    private const val ID_FAILED = 3
    private const val ID_UPDATED_BASE = 1000

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.channel_updates_desc) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun showUpdates(context: Context, updates: List<AppEntry>) {
        if (updates.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (updates.size == 1) "Update available: ${updates[0].name}" else "${updates.size} app updates available"
        val lines = updates.map { "${it.name} → ${it.latestTag}" }
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_UPDATES, notification)
    }

    /** "Updates downloaded, open the app to finish installing" (or clears it when [entries] is empty). */
    fun showWaitingUpdates(context: Context, entries: List<AppEntry>) {
        if (entries.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(ID_WAITING)
            return
        }
        val title = if (entries.size == 1) "Update ready: ${entries[0].name}" else "${entries.size} updates ready to install"
        val text = "Open App Downloader to finish installing" +
            if (entries.size > 1) ": " + entries.joinToString(", ") { it.name } else ""
        post(context, ID_WAITING, title, text)
    }

    fun showAutoUpdated(context: Context, entry: AppEntry, versionName: String?) {
        val version = versionName?.let { " to $it" } ?: ""
        post(context, ID_UPDATED_BASE + (entry.id.hashCode() and 0xFFFF), "Updated ${entry.name}$version", "Installed automatically")
    }

    fun showUpdateFailures(context: Context, names: List<String>) {
        if (names.isEmpty()) return
        post(
            context,
            ID_FAILED,
            "Scheduled update: ${names.size} app${if (names.size > 1) "s" else ""} could not be updated",
            names.joinToString(", ") + ". Open App Downloader for details.",
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    fun cancelUpdates(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_UPDATES)
    }
}
