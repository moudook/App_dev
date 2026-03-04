package com.example.smarty.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.*
import com.example.smarty.ui.theme.SmartyIcons

/**
 * Utility object providing icons and colors for different note types.
 *
 * Uses O(1) array lookup indexed by NoteType enum ordinal for efficient access.
 * Icons are chosen to be instantly recognizable and meaningful.
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
     * Icons are Material Design icons chosen for instant recognition.
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
            Icons.Outlined.Description,      // BRAIN_DUMP - Standard note
            Icons.Outlined.PlayCircleOutline, // YOUTUBE
            Icons.Outlined.Language,         // WEBSITE - Standard web
            Icons.Outlined.Image,            // IMAGE
            Icons.Outlined.Tag,              // TWITTER
            Icons.Outlined.PhotoCamera,      // INSTAGRAM
            Icons.AutoMirrored.Outlined.Article,          // DOCUMENT
            Icons.Outlined.TableChart,       // SPREADSHEET
            Icons.Outlined.Slideshow,        // PRESENTATION
            Icons.Outlined.Videocam,         // VIDEO
            Icons.Outlined.Audiotrack,       // AUDIO
            Icons.Outlined.Code,             // CODE
            Icons.Outlined.Archive,          // ARCHIVE
            Icons.Outlined.Android,          // APK
            Icons.Outlined.AttachFile        // FILE
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
    /**
     * O(1) color lookup array indexed by NoteType enum ordinal.
     * Colors use RGB palette (Red/Blue/Green) for semantic meaning.
     * Mapping: Red=Video/Important, Blue=Social/Links, Green=Documents, Gray=Neutral
     */
    private val NOTE_TYPE_COLORS: Array<Color> by lazy {
        arrayOf(
            Color(0xFF007AFF), // BRAIN_DUMP - Blue (ideas/thoughts)
            Color(0xFFFF3B30), // YOUTUBE - Red (video content)
            Color(0xFF007AFF), // WEBSITE - Blue (external links)
            Color(0xFF8E8E93), // IMAGE - Gray (neutral media)
            Color(0xFF007AFF), // TWITTER - Blue (social/links)
            Color(0xFF8E8E93), // INSTAGRAM - Gray (neutral social)
            Color(0xFF34C759), // DOCUMENT - Green (text files)
            Color(0xFF34C759), // SPREADSHEET - Green (data files)
            Color(0xFF34C759), // PRESENTATION - Green (presentation files)
            Color(0xFFFF3B30), // VIDEO - Red (video content)
            Color(0xFF8E8E93), // AUDIO - Gray (neutral media)
            Color(0xFF007AFF), // CODE - Blue (technical content)
            Color(0xFF8E8E93), // ARCHIVE - Gray (neutral storage)
            Color(0xFF8E8E93), // APK - Gray (neutral app)
            Color(0xFF8E8E93)  // FILE - Gray (general file)
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
            "learn" to Icons.Default.School,
            "read" to Icons.Default.Description,
            "watch" to Icons.Default.PlayCircle,
            "idea" to Icons.Default.Lightbulb,
            "todo" to Icons.Default.CheckCircle,
            "buy" to Icons.Default.ShoppingCart,
            "meet" to Icons.Default.Event,
            "code" to Icons.Default.Code,
            "quote" to Icons.Default.FormatQuote,
            "inspo" to Icons.Default.AutoAwesome,
            "recipe" to Icons.Default.Description,
            "health" to Icons.Default.Favorite,
            "finance" to Icons.Default.Payments,
            "work" to Icons.Default.Work,
            "play" to Icons.Default.VideogameAsset,
            "note" to Icons.Default.Description,
            "legal" to Icons.Default.Gavel,
            "private_notes" to Icons.Default.Lock
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
        val Archive = SmartyIcons.Archive
        val Unarchive = SmartyIcons.Unarchive
        val Delete = SmartyIcons.Delete
        val Edit = SmartyIcons.Edit
        val Share = SmartyIcons.Share
        val Copy = SmartyIcons.Copy
        val Todo = SmartyIcons.CheckCircle
        val Search = SmartyIcons.Search
        val Add = SmartyIcons.Add
        val Settings = SmartyIcons.Settings
        val Back = SmartyIcons.Back
        val Close = SmartyIcons.Close
        val Done = SmartyIcons.Check
        val ExpandMore = Icons.Default.ExpandMore
        val ExpandLess = Icons.Default.ExpandLess
        val MoreVert = SmartyIcons.More
        val Lock = SmartyIcons.Lock
        val LockOpen = SmartyIcons.LockOpen
        val Visibility = SmartyIcons.Visibility
        val VisibilityOff = SmartyIcons.VisibilityOff
    }


    // ==================== Status Colors ====================

    /**
     * Colors for different processing statuses.
     * Updated to use semantic theme colors for a calmer aesthetic.
     */
    object StatusColors {
        @Composable
        fun getProcessingColor() = LocalAccentColor.current
        @Composable
        fun getErrorColor() = MaterialTheme.colorScheme.error
        @Composable
        fun getSuccessColor() = MaterialTheme.colorScheme.tertiary
        @Composable
        fun getPendingColor() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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

