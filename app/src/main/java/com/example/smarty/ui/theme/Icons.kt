package com.example.smarty.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Centralized Icon Library for the Smarty Design System.
 * 
 * Philosophy: Consistency across all screens.
 * If we change an icon here, it updates everywhere.
 * 
 * Design Aesthetic: "Creative Smooth" — uses Outlined variants
 * for a lighter, more refined feel instead of heavy Filled icons.
 */
object SmartyIcons {
    // Navigation
    val Back: ImageVector = Icons.AutoMirrored.Filled.ArrowBack
    val Forward: ImageVector = Icons.AutoMirrored.Filled.ArrowForward
    val Close: ImageVector = Icons.Outlined.Close
    val Menu: ImageVector = Icons.Outlined.Menu
    val More: ImageVector = Icons.Outlined.MoreVert
    val Logout: ImageVector = Icons.AutoMirrored.Filled.Logout
    val ChevronUp: ImageVector = Icons.Outlined.KeyboardArrowUp
    val ChevronDown: ImageVector = Icons.Outlined.KeyboardArrowDown
    val ChevronRight: ImageVector = Icons.Outlined.ChevronRight

    // Actions
    val Add: ImageVector = Icons.Outlined.Add
    val Edit: ImageVector = Icons.Outlined.Edit
    val Delete: ImageVector = Icons.Outlined.Delete
    val Save: ImageVector = Icons.Outlined.Save
    val Search: ImageVector = Icons.Outlined.Search
    val Share: ImageVector = Icons.Outlined.Share
    val Copy: ImageVector = Icons.Outlined.ContentCopy
    val Archive: ImageVector = Icons.Outlined.Archive
    val Unarchive: ImageVector = Icons.Outlined.Unarchive
    val Refresh: ImageVector = Icons.Outlined.Refresh
    val Filter: ImageVector = Icons.Outlined.FilterList
    val Sort: ImageVector = Icons.Outlined.SwapVert

    // Feature Specific
    val Settings: ImageVector = Icons.Outlined.Settings
    val Profile: ImageVector = Icons.Outlined.Person
    val Notifications: ImageVector = Icons.Outlined.Notifications
    val Calendar: ImageVector = Icons.Outlined.CalendarMonth
    val History: ImageVector = Icons.Outlined.History
    val Analytics: ImageVector = Icons.Outlined.Architecture // Metaphor: The blueprint of strategy
    val Psychology: ImageVector = Icons.Outlined.Psychology
    val AutoAwesome: ImageVector = Icons.Outlined.AutoFixHigh // Refined "magic" feel
    val Code: ImageVector = Icons.Outlined.Code
    val Folder: ImageVector = Icons.Outlined.Folder
    val FolderSpecial: ImageVector = Icons.Outlined.FolderSpecial

    // Symbols
    val Check: ImageVector = Icons.Outlined.Check
    val CheckCircle: ImageVector = Icons.Outlined.TaskAlt
    val Info: ImageVector = Icons.Outlined.Info
    val Warning: ImageVector = Icons.Outlined.WarningAmber
    val Error: ImageVector = Icons.Outlined.ErrorOutline
    val Favorite: ImageVector = Icons.Outlined.FavoriteBorder
    val FavoriteBorder: ImageVector = Icons.Outlined.FavoriteBorder
    val Star: ImageVector = Icons.Outlined.StarOutline
    val Visibility: ImageVector = Icons.Outlined.Visibility
    val VisibilityOff: ImageVector = Icons.Outlined.VisibilityOff
    val Lock: ImageVector = Icons.Outlined.Lock
    val LockOpen: ImageVector = Icons.Outlined.LockOpen
    
    // Features
    val Cloud: ImageVector = Icons.Outlined.Cloud
    val CloudSync: ImageVector = Icons.Outlined.CloudSync
    val CloudUpload: ImageVector = Icons.Outlined.CloudUpload
    val CloudOff: ImageVector = Icons.Outlined.CloudOff
    val Download: ImageVector = Icons.Outlined.Download
    val Vibration: ImageVector = Icons.Outlined.Waves // Metaphor: flow, movement, instead of a rattling phone
    val Casino: ImageVector = Icons.Outlined.Contrast // Metaphor: duality, heads vs tails 50/50 balance
    val Games: ImageVector = Icons.Outlined.Air // Metaphor: a breather, space, taking a breath (mental break)
    val Build: ImageVector = Icons.Outlined.Tune // Tuning/adjusting instead of wrench
    val DarkMode: ImageVector = Icons.Outlined.DarkMode
    val DeleteOutline: ImageVector = Icons.Outlined.BlurOff // Metaphor: clear the fog/cache

    // Auth Specific
    val Email: ImageVector = Icons.Outlined.Email
    val Password: ImageVector = Icons.Outlined.Key

    // AI / Assistant
    val Assistant: ImageVector = Icons.Outlined.LensBlur // Metaphor: abstract nucleus, deep focus
    val Sparkles: ImageVector = Icons.Outlined.AllInclusive // Metaphor: endless possibilities, abstraction
    val Mic: ImageVector = Icons.Outlined.MicNone
    val Send: ImageVector = Icons.AutoMirrored.Outlined.Send
    val Attachment: ImageVector = Icons.Outlined.AttachFile

    // --- Navigation Header Icons (Active / Inactive) ---
    // Chat (AI Core) - Metaphor: Connectivity / Abstraction
    val NavChatActive: ImageVector = Icons.Filled.AllInclusive
    val NavChatInactive: ImageVector = Icons.Outlined.AllInclusive

    // Notes - Metaphor: Written thoughts
    val NavNotesActive: ImageVector = Icons.AutoMirrored.Filled.Notes
    val NavNotesInactive: ImageVector = Icons.AutoMirrored.Outlined.Notes

    // Calendar - Metaphor: Planner / Time
    val NavCalendarActive: ImageVector = Icons.Filled.CalendarMonth
    val NavCalendarInactive: ImageVector = Icons.Outlined.CalendarMonth

    // Stacks - Metaphor: Grouped categories / Hub
    val NavStacksActive: ImageVector = Icons.Filled.Hub
    val NavStacksInactive: ImageVector = Icons.Outlined.Hub

    // Archive - Metaphor: Storage box
    val NavArchiveActive: ImageVector = Icons.Filled.Archive
    val NavArchiveInactive: ImageVector = Icons.Outlined.Archive

    // Settings - Metaphor: Tuning / Controls
    val NavSettingsActive: ImageVector = Icons.Filled.Tune
    val NavSettingsInactive: ImageVector = Icons.Outlined.Tune
}
