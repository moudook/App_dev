package com.example.smarty.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.smarty.MainActivity
import com.example.smarty.R

/**
 * Home screen widget for quick note capture.
 * Tapping the widget opens the app ready to capture a new note.
 */
class QuickNoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Update each widget instance
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Called when the first widget is added
    }

    override fun onDisabled(context: Context) {
        // Called when the last widget is removed
    }

    companion object {
        const val ACTION_QUICK_NOTE = "com.example.smarty.action.QUICK_NOTE"
        const val ACTION_VOICE_NOTE = "com.example.smarty.action.VOICE_NOTE"
        const val ACTION_CAMERA_NOTE = "com.example.smarty.action.CAMERA_NOTE"

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            // Create the RemoteViews
            val views = RemoteViews(context.packageName, R.layout.widget_quick_note)

            // Create intent to open the app
            // Main Add Button (Text Note)
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_QUICK_NOTE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context, appWidgetId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_add_button, pendingIntent)
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent) // Tap background opens app too

            // Voice Note Button
            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_VOICE_NOTE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val voicePendingIntent = PendingIntent.getActivity(
                context, appWidgetId + 1000, voiceIntent, // Unique RequestCode
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_voice_button, voicePendingIntent)

            // Camera Note Button
            val cameraIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_CAMERA_NOTE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val cameraPendingIntent = PendingIntent.getActivity(
                context, appWidgetId + 2000, cameraIntent, // Unique RequestCode
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_camera_button, cameraPendingIntent)

            // Update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Force update all widget instances.
         * Call this when widget content might need refreshing.
         */
        fun updateAllWidgets(context: Context) {
            val intent = Intent(context, QuickNoteWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            context.sendBroadcast(intent)
        }
    }
}
