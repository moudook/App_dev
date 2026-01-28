package com.example.smarty.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.HighlightOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.theme.*

/**
 * Utility object providing icons and colors for different note types.
 *
 * Uses O(1) array lookup indexed by NoteType enum ordinal for efficient access.
 * Icons are chosen to be instantly reJarviszable and meaningful.
 * Colors are semantic and consistent with the app's design language.
 *
 * Usage:
 * ```kotlin
 * val icon = NoteCardIcons.getIcon(NoteType.YOUTUBE)
 * val color = NoteCardIcons.getColor(NoteType.YOUTUBE)
 *
 * // Or use the convenience functions
 * Icon(
 *     imageVector = getNoteTypeIcon(note.type),
 *     tint = getNoteTypeColor(note.type)
 * )
 * ```
 *
 * @see NoteType for the list of supported note types
 */
object NoteCardIcons {

    // ==================== Icon Mappings ====================

    /**
     * O(1) icon lookup array indexed by NoteType enum ordinal.
     * Icons are Material Design icons chosen for instant reJarvistion.
     *
     * - BRAIN_DUMP: Create (pen) - writing thoughts
     * - YOUTUBE: PlayCircle - play button for video
     * - WEBSITE: Link - link chain
     * - IMAGE: Photo - photograph
     * - TWITTER: Tag - hashtag style
     * - INSTAGRAM: CameraAlt - camera
     * - DOCUMENT: Article - document/article
     * - SPREADSHEET: TableChart - table grid
     * - PRESENTATION: Slideshow - presentation slides
     * - VIDEO: Videocam - video camera
     * - AUDIO: MusicNote - music note
     * - CODE: Code - code brackets
     * - ARCHIVE: FolderZip - compressed folder
     * - APK: Android - Android robot
     * - FILE: AttachFile - attachment
     */
    private val NOTE_TYPE_ICONS: Array<ImageVector> by lazy {
        arrayOf(
            Icons.Outlined.AutoAwesome,      // BRAIN_DUMP - "Idea/Spark" (Creative Burst)
            Icons.Outlined.SmartDisplay,     // YOUTUBE - "Screen" (Modern Display)
            Icons.Outlined.Explore,          // WEBSITE - "Explore" (Compass/Navigation)
            Icons.Outlined.BurstMode,        // IMAGE - "Burst" (Capture moments)
            Icons.Outlined.Tag,              // TWITTER - "Hashtag" (Platform Icon)
            Icons.Outlined.FilterVintage,    // INSTAGRAM - "Vintage" (Artistic Filters)
            Icons.Outlined.Source,           // DOCUMENT - "Source" (Origin/Knowledge)
            Icons.Outlined.ViewKanban,       // SPREADSHEET - "Board" (Structured Data)
            Icons.Outlined.CoPresent,        // PRESENTATION - "Present" (Sharing ideas)
            Icons.Outlined.SlowMotionVideo,  // VIDEO - "Cinema" (Motion)
            Icons.Outlined.SpatialAudio,     // AUDIO - "Spatial" (Immersive Sound)
            Icons.Outlined.DataObject,       // CODE - "Object" (Abstract Structure)
            Icons.Outlined.AllInbox,         // ARCHIVE - "Vault" (Secure Storage)
            Icons.Outlined.Android,          // APK - "Droid" (Standard but iconic)
            Icons.Outlined.Attachment        // FILE - "Link" (Connection)
        )
    }

    // ==================== Color Mappings ====================

    /**
     * O(1) color lookup array indexed by NoteType enum ordinal.
     * Colors are semantic and consistent with content type.
     *
     * - BRAIN_DUMP: Purple - creative thoughts
     * - YOUTUBE: Red - YouTube brand color
     * - WEBSITE: Gray - neutral web content
     * - IMAGE: Teal - visual content
     * - TWITTER: Blue - Twitter brand color
     * - INSTAGRAM: Purple - Instagram brand gradient
     * - DOCUMENT: Blue - professional documents
     * - SPREADSHEET: Green - data/numbers
     * - PRESENTATION: Orange - presentation energy
     * - VIDEO: Red - video content
     * - AUDIO: Pink - audio/music
     * - CODE: Cyan - technical/code
     * - ARCHIVE: Yellow - compressed files
     * - APK: Green - Android brand color
     * - FILE: Gray - generic file
     */
    private val NOTE_TYPE_COLORS: Array<Color> by lazy {
        arrayOf(
            BrainDumpPurple,    // BRAIN_DUMP
            YoutubeRed,         // YOUTUBE
            WebGray,            // WEBSITE
            ImageTeal,          // IMAGE
            TwitterBlue,        // TWITTER
            NeonPurple,         // INSTAGRAM
            DocumentBlue,       // DOCUMENT
            SpreadsheetGreen,   // SPREADSHEET
            PresentationOrange, // PRESENTATION
            VideoRed,           // VIDEO
            AudioPink,          // AUDIO
            CodeCyan,           // CODE
            ArchiveYellow,      // ARCHIVE
            ApkGreen,           // APK
            FileGray            // FILE
        )
    }

    // ==================== Public API ====================

    /**
     * Get the icon for a note type.
     *
     * @param type The note type
     * @return Material Design icon representing the note type
     */
    fun getIcon(type: NoteType): ImageVector = NOTE_TYPE_ICONS[type.ordinal]

    /**
     * Get the color for a note type.
     *
     * @param type The note type
     * @return Semantic color for the note type
     */
    fun getColor(type: NoteType): Color = NOTE_TYPE_COLORS[type.ordinal]

    // ==================== Category Icons ====================

    /**
     * Icon mappings for note categories.
     * Used in StacksScreen and category selection UI.
     */
    private val CATEGORY_ICONS: Map<String, ImageVector> by lazy {
        mapOf(
            "Learn" to Icons.Default.School,             // School - classical learning
            "Read" to Icons.Default.AutoStories,            // AutoStories - magic book
            "Watch" to Icons.Default.TheaterComedy,      // Theater - entertainment/drama
            "Idea" to Icons.Default.EmojiObjects,        // EmojiObjects - bright ideas
            "Todo" to Icons.Default.Verified,             // Verified - completed task
            "Buy" to Icons.Default.Loyalty,              // Loyalty - brand/shopping
            "Meet" to Icons.Default.Handshake,           // Handshake - collaboration
            "Code" to Icons.Default.DataObject,          // DataObject - structure/code
            "Quote" to Icons.Default.FormatQuote,        // Quote - citation
            "Inspo" to Icons.Default.WbSunny,            // Sunny - illumination/inspiration
            "Recipe" to Icons.Default.RestaurantMenu,    // Menu - culinary arts
            "Health" to Icons.Default.Spa,               // Spa - wellness/balance
            "Finance" to Icons.Default.AccountBalanceWallet, // Wallet - wealth
            "Work" to Icons.Default.BusinessCenter,      // Briefcase - professional
            "Play" to Icons.Default.SportsEsports,       // Gamepad - gaming/fun
            "Note" to Icons.Default.DesignServices,            // DesignServices - creative writing
            "Legal" to Icons.Default.Gavel,              // Gavel - justice
            "Private Notes" to Icons.Default.PrivacyTip  // PrivacyTip - secret
        )
    }

    /**
     * Get the icon for a category.
     *
     * @param category The category name
     * @return Material Design icon for the category, or folder icon if unknown
     */
    fun getCategoryIcon(category: String): ImageVector =
        CATEGORY_ICONS[category] ?: Icons.Default.FolderSpecial // Special folder for unknown

    // ==================== Action Icons ====================

    /**
     * Common action icons used throughout the app.
     * Centralized here for consistency.
     */
    object Actions {
        val Archive = Icons.Default.AllInbox             // Vault/Inbox
        val Unarchive = Icons.Default.Unarchive          // Unarchive
        val Delete = Icons.Default.Whatshot             // Fire - creative destruction
        val Edit = Icons.Default.DesignServices         // Design - creative refinement
        val Share = Icons.Default.RocketLaunch          // Rocket - launching to others
        val Copy = Icons.Default.FileCopy            // Copy
        val Todo = Icons.Default.Verified                // Task check
        val Search = Icons.Default.Explore              // Compass - exploration
        val Add = Icons.Default.AutoAwesome             // Spark - creative addition
        val Settings = Icons.Default.SettingsSuggest    // Suggested settings
        val Back = Icons.AutoMirrored.Filled.ArrowBack
        val Close = Icons.Default.HighlightOff
        val Done = Icons.Default.Verified
        val ExpandMore = Icons.Default.ExpandMore
        val ExpandLess = Icons.Default.ExpandLess
        val MoreVert = Icons.Default.MoreVert           // Vertical dots
        val Lock = Icons.Default.Lock                   // Lock
        val LockOpen = Icons.Default.LockOpen
        val Visibility = Icons.Default.Visibility
        val VisibilityOff = Icons.Default.VisibilityOff
    }

    // ==================== Status Colors ====================

    /**
     * Colors for different processing statuses.
     */
    object StatusColors {
        fun getProcessingColor() = Color(0xFFCCFF00) // Acid green
        fun getErrorColor() = Color(0xFFFF4D00) // Safety orange
        fun getSuccessColor() = Color(0xFF4CAF50) // Green
        fun getPendingColor() = Color(0xFF8E8E93) // Gray
    }
}

// ==================== Convenience Functions ====================

/**
 * Get the icon for a note type.
 * Convenience function that delegates to NoteCardIcons.
 *
 * @param type The note type
 * @return Material Design icon representing the note type
 */
fun getNoteTypeIcon(type: NoteType): ImageVector = NoteCardIcons.getIcon(type)

/**
 * Get the color for a note type.
 * Convenience function that delegates to NoteCardIcons.
 *
 * @param type The note type
 * @return Semantic color for the note type
 */
fun getNoteTypeColor(type: NoteType): Color = NoteCardIcons.getColor(type)
