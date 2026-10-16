package com.futsch1.medtimer.feature.mcp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.futsch1.medtimer.core.ui.R

internal object McpNotifications {
    // Arbitrary constant ("MCP" in ASCII) that is unlikely to collide with the ids of reminder notifications.
    const val NOTIFICATION_ID = 5_063_504
    private const val CHANNEL_ID = "mcp_server"

    /**
     * Builds the ongoing notification of the foreground service. It never contains the connector URL or secret.
     */
    fun build(context: Context, port: Int): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.capsule)
            .setContentTitle(context.getString(R.string.mcp_notification_title))
            .setContentText(context.getString(R.string.mcp_notification_text, port))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                context.getString(R.string.mcp_notification_stop),
                PendingIntent.getService(context, 0, McpServerService.stopIntent(context), PendingIntent.FLAG_IMMUTABLE)
            )
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launchIntent ->
            builder.setContentIntent(PendingIntent.getActivity(context, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE))
        }
        return builder.build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.mcp_notification_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
