package com.example.smarty.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.smarty.data.model.NoteType
import com.example.smarty.ui.theme.*

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
            Icons.AutoMirrored.Filled.StickyNote2, // BRAIN_DUMP - Sticky note style
            Icons.Default.PlayArrow,       // YOUTUBE - Play arrow (Clean)
            Icons.Default.Public,          // WEBSITE - Globe (More Apple/Safari)
            Icons.Default.Image,           // IMAGE - Single image frame
            Icons.AutoMirrored.Filled.TextSnippet, // TWITTER - Text snippet style
            Icons.Default.CameraAlt,       // INSTAGRAM - camera
            Icons.AutoMirrored.Filled.Article, // DOCUMENT - article/document
            Icons.Default.TableChart,      // SPREADSHEET - table grid
            Icons.Default.Slideshow,       // PRESENTATION - slides
            Icons.Default.Videocam,        // VIDEO - video camera
            Icons.Default.MusicNote,       // AUDIO - music note
            Icons.Default.Code,            // CODE - code brackets
            Icons.Default.FolderZip,       // ARCHIVE - compressed folder
            Icons.Default.Android,         // APK - Android robot
            Icons.Default.AttachFile       // FILE - attachment
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
            "Learn" to Icons.Default.School,
            "Read" to Icons.Default.Book,
            "Watch" to Icons.Default.PlayCircle,
            "Idea" to Icons.Default.Lightbulb,
            "Todo" to Icons.Default.Checklist,
            "Buy" to Icons.Default.ShoppingCart,
            "Meet" to Icons.Default.People,
            "Code" to Icons.Default.Code,
            "Quote" to Icons.Default.FormatQuote,
            "Inspo" to Icons.Default.AutoAwesome,
            "Recipe" to Icons.Default.Restaurant,
            "Health" to Icons.Default.FitnessCenter,
            "Finance" to Icons.Default.AccountBalance,
            "Work" to Icons.Default.Work,
            "Play" to Icons.Default.SportsEsports,
            "Note" to Icons.AutoMirrored.Filled.StickyNote2,
            "Legal" to Icons.Default.Gavel,
            "Private Notes" to Icons.Default.Lock
        )
    }

    /**
     * Get the icon for a category.
     *
     * @param category The category name
     * @return Material Design icon for the category, or folder icon if unknown
     */
    fun getCategoryIcon(category: String): ImageVector =
        CATEGORY_ICONS[category] ?: Icons.Default.Folder

    // ==================== Action Icons ====================

    /**
     * Common action icons used throughout the app.
     * Centralized here for consistency.
     */
    object Actions {
        val Archive = Icons.Default.Archive
        val Unarchive = Icons.Default.Unarchive
        val Delete = Icons.Default.Delete
        val Edit = Icons.Default.Edit
        val Share = Icons.Default.Share
        val Copy = Icons.Default.ContentCopy
        val Todo = Icons.Default.Checklist
        val Search = Icons.Default.Search
        val Add = Icons.Default.Add
        val Settings = Icons.Default.Settings
        val Back = Icons.AutoMirrored.Filled.ArrowBack
        val Close = Icons.Default.Close
        val Done = Icons.Default.Done
        val ExpandMore = Icons.Default.ExpandMore
        val ExpandLess = Icons.Default.ExpandLess
        val MoreVert = Icons.Default.MoreVert
        val Lock = Icons.Default.Lock
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
