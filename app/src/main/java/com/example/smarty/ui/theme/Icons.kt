package com.example.smarty.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized Icon Library for the Smarty Design System.
 * 
 * Philosophy: Consistency across all screens.
 * If we change an icon here, it updates everywhere.
 */
object SmartyIcons {
    // Navigation
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Forward: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
    val Close: ImageVector = Icons.Default.Close
    val Menu: ImageVector = Icons.Default.Menu
    val More: ImageVector = Icons.Default.MoreVert
    val Logout: ImageVector = Icons.AutoMirrored.Filled.Logout

    // Actions
    val Add: ImageVector = Icons.Default.Add
    val Edit: ImageVector = Icons.Default.Edit
    val Delete: ImageVector = Icons.Default.DeleteOutline
    val Save: ImageVector = Icons.Default.Save
    val Search: ImageVector = Icons.Default.Search
    val Share: ImageVector = Icons.Default.Share
    val Copy: ImageVector = Icons.Default.ContentCopy
    val Archive: ImageVector = Icons.Default.Archive
    val Unarchive: ImageVector = Icons.Default.Unarchive
    val Refresh: ImageVector = Icons.Default.Refresh
    val Filter: ImageVector = Icons.Default.FilterList
    val Sort: ImageVector = Icons.Default.Sort

    // Feature Specific
    val Settings: ImageVector = Icons.Default.Settings
    val Profile: ImageVector = Icons.Default.Person
    val Notifications: ImageVector = Icons.Default.Notifications
    val Calendar: ImageVector = Icons.Default.CalendarMonth
    val History: ImageVector = Icons.Default.History
    val Analytics: ImageVector = Icons.Default.Analytics
    val Psychology: ImageVector = Icons.Default.Psychology
    val AutoAwesome: ImageVector = Icons.Default.AutoAwesome
    val Code: ImageVector = Icons.Default.Code
    val Folder: ImageVector = Icons.Default.Folder
    val FolderSpecial: ImageVector = Icons.Default.FolderSpecial

    // Symbols
    val Check: ImageVector = Icons.Default.Check
    val CheckCircle: ImageVector = Icons.Default.CheckCircle
    val Info: ImageVector = Icons.Default.Info
    val Warning: ImageVector = Icons.Default.Warning
    val Error: ImageVector = Icons.Default.Error
    val Favorite: ImageVector = Icons.Default.Favorite
    val FavoriteBorder: ImageVector = Icons.Default.FavoriteBorder
    val Star: ImageVector = Icons.Default.Star
    val Visibility: ImageVector = Icons.Default.Visibility
    val VisibilityOff: ImageVector = Icons.Default.VisibilityOff
    val Lock: ImageVector = Icons.Default.Lock
    val LockOpen: ImageVector = Icons.Default.LockOpen

    // Auth Specific
    val Email: ImageVector = Icons.Default.AlternateEmail
    val Password: ImageVector = Icons.Default.VpnKey

    // AI / Assistant
    val Assistant: ImageVector = Icons.Default.AutoAwesome
    val Sparkles: ImageVector = Icons.Default.AutoAwesome
    val Mic: ImageVector = Icons.Default.Mic
    val Send: ImageVector = Icons.Default.Send
    val Attachment: ImageVector = Icons.Default.AttachFile
}
