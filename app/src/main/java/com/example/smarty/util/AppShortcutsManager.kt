package com.example.smarty.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.example.smarty.MainActivity
import com.example.smarty.R

/**
 * Manages Android App Shortcuts (long-press launcher icon menu).
 * Provides quick access to common actions like adding notes or opening chat.
 */
object AppShortcutsManager {

    // Shortcut IDs
    private const val SHORTCUT_NEW_NOTE = "shortcut_new_note"
    private const val SHORTCUT_CHAT = "shortcut_chat"
    private const val SHORTCUT_CALENDAR = "shortcut_calendar"
    private const val SHORTCUT_STACKS = "shortcut_stacks"

    // Action constants (used in MainActivity to detect shortcut source)
    const val ACTION_NEW_NOTE = "com.example.smarty.action.NEW_NOTE"
    const val ACTION_OPEN_CHAT = "com.example.smarty.action.OPEN_CHAT"
    const val ACTION_OPEN_CALENDAR = "com.example.smarty.action.OPEN_CALENDAR"
    const val ACTION_OPEN_STACKS = "com.example.smarty.action.OPEN_STACKS"

    /**
     * Initialize app shortcuts on startup.
     * Call this in SmartyApplication.onCreate()
     */
    fun setupShortcuts(context: Context) {
        val shortcuts = listOf(
            createNewNoteShortcut(context),
            createChatShortcut(context),
            createCalendarShortcut(context),
            createStacksShortcut(context)
        )

        try {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        } catch (e: Exception) {
            android.util.Log.e("AppShortcuts", "Failed to set shortcuts: ${e.message}")
        }
    }

    private fun createNewNoteShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_NEW_NOTE
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return ShortcutInfoCompat.Builder(context, SHORTCUT_NEW_NOTE)
            .setShortLabel(context.getString(R.string.shortcut_new_note_short))
            .setLongLabel(context.getString(R.string.shortcut_new_note_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_note))
            .setIntent(intent)
            .setRank(0) // First position
            .build()
    }

    private fun createChatShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CHAT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return ShortcutInfoCompat.Builder(context, SHORTCUT_CHAT)
            .setShortLabel(context.getString(R.string.shortcut_chat_short))
            .setLongLabel(context.getString(R.string.shortcut_chat_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_chat))
            .setIntent(intent)
            .setRank(1)
            .build()
    }

    private fun createCalendarShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_CALENDAR
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return ShortcutInfoCompat.Builder(context, SHORTCUT_CALENDAR)
            .setShortLabel(context.getString(R.string.shortcut_calendar_short))
            .setLongLabel(context.getString(R.string.shortcut_calendar_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_calendar))
            .setIntent(intent)
            .setRank(2)
            .build()
    }

    private fun createStacksShortcut(context: Context): ShortcutInfoCompat {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_STACKS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        return ShortcutInfoCompat.Builder(context, SHORTCUT_STACKS)
            .setShortLabel(context.getString(R.string.shortcut_stacks_short))
            .setLongLabel(context.getString(R.string.shortcut_stacks_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_stacks))
            .setIntent(intent)
            .setRank(3)
            .build()
    }

    /**
     * Report that a shortcut was used.
     * Helps Android learn which shortcuts are most important.
     */
    fun reportShortcutUsed(context: Context, shortcutId: String) {
        ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
    }
}
