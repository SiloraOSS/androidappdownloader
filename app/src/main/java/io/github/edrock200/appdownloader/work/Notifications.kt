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
import io.github.edrock200.appdownloader.data.SourceType
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

    fun areEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_UPDATES)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun showUpdates(context: Context, updates: List<AppEntry>): Boolean {
        if (updates.isEmpty() || !areEnabled(context)) return false

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (updates.size == 1) "Update available: ${updates[0].name}" else "${updates.size} app updates available"
        val lines = updates.map { if (it.latestTag != null && it.type == SourceType.GITHUB) "${it.name} → ${it.latestTag}" else "${it.name}: new file available" }
        val style = NotificationCompat.InboxStyle().also { s -> lines.forEach { s.addLine(it) } }
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", "))
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(ID_UPDATES, notification)
            true
        } catch (_: SecurityException) {
            false // Permission can be revoked between the check and posting.
        }
    }

    /** "Updates downloaded, open the app to finish installing" (or clears it when [entries] is empty). */
    fun showWaitingUpdates(context: Context, entries: List<AppEntry>) {
        if (entries.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(ID_WAITING)
            return
        }
        val title = if (entries.size == 1) "Update ready: ${entries[0].name}" else "${entries.size} updates ready to install"
        val text = "Open Silora Updater to finish installing" +
            if (entries.size > 1) ": " + entries.joinToString(", ") { it.name } else ""
        post(context, ID_WAITING, title, text)
    }

    fun showAutoUpdated(context: Context, entry: AppEntry, versionName: String?) {
        val version = versionName?.let { " to $it" } ?: ""
        post(context, ID_UPDATED_BASE + (entry.id.hashCode() and 0xFFFF), "Updated ${entry.name}$version", "Installed automatically")
    }

    fun showUpdateFailures(context: Context, names: List<String>) {
        if (names.isEmpty()) {
            NotificationManagerCompat.from(context).cancel(ID_FAILED)
            return
        }
        post(
            context,
            ID_FAILED,
            "Scheduled update: ${names.size} app${if (names.size > 1) "s" else ""} could not be updated",
            names.joinToString(", ") + ". Open Silora Updater for details.",
        )
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!areEnabled(context)) return
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
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // A permission change must not crash an install-result callback.
        }
    }

    fun cancelUpdates(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_UPDATES)
    }
}
