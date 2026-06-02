package com.example.smarty.core.common.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.smarty.MainActivity
import com.example.smarty.R

object NotificationHelper {
    const val CHANNEL_ID_GENERAL = "general_notifications"
    const val CHANNEL_ID_UPDATES = "app_updates"

    // Notification IDs
    private const val NOTIFICATION_ID_FCM = 1001

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // General Channel
            val generalChannel =
                NotificationChannel(
                    CHANNEL_ID_GENERAL,
                    "General Notifications",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "General notifications from Smarty"
                }

            // Updates Channel
            val updatesChannel =
                NotificationChannel(
                    CHANNEL_ID_UPDATES,
                    "App Updates",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Notifications about app updates and maintenance"
                }

            notificationManager.createNotificationChannels(listOf(generalChannel, updatesChannel))
        }
    }

    fun showNotification(
        context: Context,
        title: String,
        message: String,
        data: Map<String, String> = emptyMap(),
    ) {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                // Pass any data from the notification to the activity
                data.forEach { (key, value) ->
                    putExtra(key, value)
                }
            }

        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID_GENERAL)
                .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure this resource exists, fallback to standard if needed
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

        try {
            // Check for permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FCM, builder.build())
                }
            } else {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FCM, builder.build())
            }
        } catch (e: SecurityException) {
            // Permission not granted
            CrashLogger.log(context, "Failed to post notification: Permission denied")
        }
    }

    fun showDailyBriefing(
        context: Context,
        title: String,
        body: String,
        fullContent: String,
    ) {
        val intent =
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("route", "briefing")
                putExtra("briefing_content", fullContent)
            }

        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_FCM + 1, // Use a different ID
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID_GENERAL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullContent))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FCM + 1, builder.build())
                }
            } else {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FCM + 1, builder.build())
            }
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }
}
