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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

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

    // ====================================================================================
    // CUSTOM NAVIGATION ICONS (from _private/icons) - Smooth/Rounded versions, no sharp edges
    // Active state uses Pink tint (Color(0xFFF49BE0)) applied in HorizontalActionBar
    // ====================================================================================

    /**
     * AI Mode / Chat Icon - Custom from ai_mode.svg
     * Active: Pink tint, Inactive: Neutral
     */
    val NavChatCustom: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavChatCustom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF1f1f1f)),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f
            ) {
                // Lightbulb/AI icon with rounded strokes
                moveTo(491f, -339f)
                quadTo(561f, -339f, 610f, -384.5f)
                quadTo(659f, -430f, 659f, -494f)
                quadTo(659f, -551f, 622.5f, -590.5f)
                quadTo(586f, -630f, 534f, -630f)
                quadTo(487f, -630f, 454.5f, -600f)
                quadTo(422f, -570f, 422f, -525f)
                quadTo(422f, -506f, 429.5f, -488f)
                quadTo(437f, -470f, 451f, -457f)
                lineTo(508f, -514f)
                quadTo(505f, -516f, 503.5f, -519f)
                quadTo(502f, -522f, 502f, -525f)
                quadTo(502f, -536f, 511f, -542.5f)
                quadTo(520f, -549f, 534f, -549f)
                quadTo(554f, -549f, 567f, -532.5f)
                quadTo(580f, -516f, 580f, -493f)
                quadTo(580f, -462f, 554.5f, -440.5f)
                quadTo(529f, -419f, 492f, -419f)
                quadTo(445f, -419f, 412.5f, -457f)
                quadTo(380f, -495f, 380f, -549f)
                quadTo(380f, -578f, 391f, -604.5f)
                quadTo(402f, -631f, 422f, -651f)
                lineTo(365f, -708f)
                quadTo(333f, -677f, 316f, -636f)
                quadTo(299f, -595f, 299f, -549f)
                quadTo(299f, -461f, 355f, -399.5f)
                quadTo(411f, -338f, 491f, -339f)
                close()
                moveTo(240f, -80f)
                verticalLineTo(-252f)
                quadTo(183f, -304f, 151.5f, -373.5f)
                quadTo(120f, -443f, 120f, -520f)
                quadTo(120f, -670f, 225f, -775f)
                quadTo(330f, -880f, 480f, -880f)
                quadTo(605f, -880f, 701.5f, -806.5f)
                quadTo(798f, -733f, 827f, -615f)
                lineTo(879f, -410f)
                quadTo(884f, -391f, 872f, -375.5f)
                quadTo(860f, -360f, 840f, -360f)
                horizontalLineTo(760f)
                verticalLineTo(-240f)
                quadTo(760f, -207f, 736.5f, -183.5f)
                quadTo(713f, -160f, 680f, -160f)
                horizontalLineTo(600f)
                verticalLineTo(-80f)
                horizontalLineTo(520f)
                verticalLineTo(-240f)
                horizontalLineTo(680f)
                verticalLineTo(-440f)
                horizontalLineTo(788f)
                lineTo(750f, -595f)
                quadTo(727f, -686f, 652f, -743f)
                quadTo(577f, -800f, 480f, -800f)
                quadTo(364f, -800f, 282f, -719f)
                quadTo(200f, -638f, 200f, -522f)
                quadTo(200f, -462f, 224.5f, -408f)
                quadTo(249f, -354f, 293f, -312f)
                lineTo(319f, -288f)
                verticalLineTo(-80f)
                horizontalLineTo(240f)
                close()
                moveTo(494f, -440f)
                close()
            }
        }.build()
    }

    /**
     * Notes Icon - Custom from notes.svg
     * Active: Pink tint, Inactive: Neutral
     */
    val NavNotesCustom: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavNotesCustom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF1f1f1f)),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f
            ) {
                // Notes/document icon
                moveTo(160f, -160f)
                quadTo(127f, -160f, 103.5f, -183.5f)
                quadTo(80f, -207f, 80f, -240f)
                verticalLineTo(-720f)
                quadTo(80f, -753f, 103.5f, -776.5f)
                quadTo(127f, -800f, 160f, -800f)
                horizontalLineTo(800f)
                quadTo(833f, -800f, 856.5f, -776.5f)
                quadTo(880f, -753f, 880f, -720f)
                verticalLineTo(-240f)
                quadTo(880f, -207f, 856.5f, -183.5f)
                quadTo(833f, -160f, 800f, -160f)
                horizontalLineTo(160f)
                close()
                moveTo(423f, -240f)
                horizontalLineTo(800f)
                verticalLineTo(-549f)
                lineTo(773f, -586f)
                lineTo(680f, -556f)
                lineTo(588f, -586f)
                lineTo(530f, -507f)
                lineTo(437f, -477f)
                verticalLineTo(-379f)
                lineTo(380f, -300f)
                lineTo(423f, -240f)
                close()
                moveTo(324f, -240f)
                lineTo(281f, -300f)
                lineTo(357f, -405f)
                verticalLineTo(-535f)
                lineTo(480f, -575f)
                lineTo(557f, -680f)
                lineTo(680f, -640f)
                lineTo(800f, -679f)
                verticalLineTo(-720f)
                horizontalLineTo(160f)
                verticalLineTo(-240f)
                horizontalLineTo(324f)
                close()
                moveTo(437f, -477f)
                close()
            }
        }.build()
    }

    /**
     * Calendar Icon - Custom from calander.svg
     * Active: Pink tint, Inactive: Neutral
     */
    val NavCalendarCustom: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavCalendarCustom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF1f1f1f)),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f
            ) {
                // Calendar/settings gear icon
                moveTo(480f, -675f)
                quadTo(462f, -688f, 441.5f, -694f)
                quadTo(421f, -700f, 400f, -700f)
                quadTo(373f, -700f, 347f, -689.5f)
                quadTo(321f, -679f, 301f, -659f)
                quadTo(281f, -639f, 270.5f, -613f)
                quadTo(260f, -587f, 260f, -560f)
                quadTo(260f, -539f, 266f, -518.5f)
                quadTo(272f, -498f, 285f, -480f)
                quadTo(272f, -462f, 266f, -441.5f)
                quadTo(260f, -421f, 260f, -400f)
                quadTo(260f, -373f, 270.5f, -347f)
                quadTo(281f, -321f, 301f, -301f)
                quadTo(321f, -281f, 347f, -270.5f)
                quadTo(373f, -260f, 400f, -260f)
                quadTo(421f, -260f, 441.5f, -266f)
                quadTo(462f, -272f, 480f, -285f)
                quadTo(498f, -272f, 518.5f, -266f)
                quadTo(539f, -260f, 560f, -260f)
                quadTo(587f, -260f, 613f, -270.5f)
                quadTo(639f, -281f, 659f, -301f)
                quadTo(679f, -321f, 689.5f, -347f)
                quadTo(700f, -373f, 700f, -400f)
                quadTo(700f, -421f, 694f, -441.5f)
                quadTo(688f, -462f, 675f, -480f)
                quadTo(688f, -498f, 694f, -518.5f)
                quadTo(700f, -539f, 700f, -560f)
                quadTo(700f, -587f, 689.5f, -613f)
                quadTo(679f, -639f, 659f, -659f)
                quadTo(639f, -679f, 613f, -689.5f)
                quadTo(587f, -700f, 560f, -700f)
                quadTo(539f, -700f, 518.5f, -694f)
                quadTo(498f, -688f, 480f, -675f)
                close()
                moveTo(480f, -383f)
                lineTo(434f, -351f)
                quadTo(426f, -346f, 417.5f, -343f)
                quadTo(409f, -340f, 400f, -340f)
                quadTo(389f, -340f, 378f, -344.5f)
                quadTo(367f, -349f, 358f, -358f)
                quadTo(349f, -367f, 344.5f, -378f)
                quadTo(340f, -389f, 340f, -400f)
                quadTo(340f, -409f, 343f, -417.5f)
                quadTo(346f, -426f, 351f, -434f)
                lineTo(383f, -480f)
                lineTo(351f, -526f)
                quadTo(346f, -534f, 343f, -542.5f)
                quadTo(340f, -551f, 340f, -560f)
                quadTo(340f, -571f, 344.5f, -582f)
                quadTo(349f, -593f, 358f, -602f)
                quadTo(367f, -611f, 378f, -615.5f)
                quadTo(389f, -620f, 400f, -620f)
                quadTo(409f, -620f, 417.5f, -617f)
                quadTo(426f, -614f, 434f, -609f)
                lineTo(480f, -577f)
                lineTo(526f, -609f)
                quadTo(534f, -614f, 542.5f, -617f)
                quadTo(551f, -620f, 560f, -620f)
                quadTo(571f, -620f, 582f, -615.5f)
                quadTo(593f, -611f, 602f, -602f)
                quadTo(611f, -593f, 615.5f, -582f)
                quadTo(620f, -571f, 620f, -560f)
                quadTo(620f, -551f, 617f, -542.5f)
                quadTo(614f, -534f, 609f, -526f)
                lineTo(577f, -480f)
                lineTo(609f, -434f)
                quadTo(614f, -426f, 617f, -417.5f)
                quadTo(620f, -409f, 620f, -400f)
                quadTo(620f, -389f, 615.5f, -378f)
                quadTo(611f, -367f, 602f, -358f)
                quadTo(593f, -349f, 582f, -344.5f)
                quadTo(571f, -340f, 560f, -340f)
                quadTo(551f, -340f, 542.5f, -343f)
                quadTo(534f, -346f, 526f, -351f)
                lineTo(480f, -383f)
                close()
                moveTo(515.5f, -444.5f)
                quadTo(530f, -459f, 530f, -480f)
                quadTo(530f, -501f, 515.5f, -515.5f)
                quadTo(501f, -530f, 480f, -530f)
                quadTo(459f, -530f, 444.5f, -515.5f)
                quadTo(430f, -501f, 430f, -480f)
                quadTo(430f, -459f, 444.5f, -444.5f)
                quadTo(459f, -430f, 480f, -430f)
                quadTo(501f, -430f, 515.5f, -444.5f)
                close()
                moveTo(480f, -80f)
                quadTo(397f, -80f, 324f, -111.5f)
                quadTo(251f, -143f, 197f, -197f)
                quadTo(143f, -251f, 111.5f, -324f)
                quadTo(80f, -397f, 80f, -480f)
                quadTo(80f, -563f, 111.5f, -636f)
                quadTo(143f, -709f, 197f, -763f)
                quadTo(251f, -817f, 324f, -848.5f)
                quadTo(397f, -880f, 480f, -880f)
                quadTo(563f, -880f, 636f, -848.5f)
                quadTo(709f, -817f, 763f, -763f)
                quadTo(817f, -709f, 848.5f, -636f)
                quadTo(880f, -563f, 880f, -480f)
                quadTo(880f, -397f, 848.5f, -324f)
                quadTo(817f, -251f, 763f, -197f)
                quadTo(709f, -143f, 636f, -111.5f)
                quadTo(563f, -80f, 480f, -80f)
                close()
                moveTo(480f, -160f)
                quadTo(614f, -160f, 707f, -253f)
                quadTo(800f, -346f, 800f, -480f)
                quadTo(800f, -614f, 707f, -707f)
                quadTo(614f, -800f, 480f, -800f)
                quadTo(346f, -800f, 253f, -707f)
                quadTo(160f, -614f, 160f, -480f)
                quadTo(160f, -346f, 253f, -253f)
                quadTo(346f, -160f, 480f, -160f)
                close()
                moveTo(480f, -480f)
                close()
            }
        }.build()
    }

    /**
     * Archive Icon - Custom from archive.svg
     * Active: Pink tint, Inactive: Neutral
     */
    val NavArchiveCustom: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavArchiveCustom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF1f1f1f)),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f
            ) {
                // Archive/circle target icon
                moveTo(480f, -300f)
                quadTo(555f, -300f, 607.5f, -352.5f)
                quadTo(660f, -405f, 660f, -480f)
                quadTo(660f, -555f, 607.5f, -607.5f)
                quadTo(555f, -660f, 480f, -660f)
                quadTo(405f, -660f, 352.5f, -607.5f)
                quadTo(300f, -555f, 300f, -480f)
                quadTo(300f, -405f, 352.5f, -352.5f)
                quadTo(405f, -300f, 480f, -300f)
                close()
                moveTo(451.5f, -451.5f)
                quadTo(440f, -463f, 440f, -480f)
                quadTo(440f, -497f, 451.5f, -508.5f)
                quadTo(463f, -520f, 480f, -520f)
                quadTo(497f, -520f, 508.5f, -508.5f)
                quadTo(520f, -497f, 520f, -480f)
                quadTo(520f, -463f, 508.5f, -451.5f)
                quadTo(497f, -440f, 480f, -440f)
                quadTo(463f, -440f, 451.5f, -451.5f)
                close()
                moveTo(480f, -80f)
                quadTo(397f, -80f, 324f, -111.5f)
                quadTo(251f, -143f, 197f, -197f)
                quadTo(143f, -251f, 111.5f, -324f)
                quadTo(80f, -397f, 80f, -480f)
                quadTo(80f, -563f, 111.5f, -636f)
                quadTo(143f, -709f, 197f, -763f)
                quadTo(251f, -817f, 324f, -848.5f)
                quadTo(397f, -880f, 480f, -880f)
                quadTo(563f, -880f, 636f, -848.5f)
                quadTo(709f, -817f, 763f, -763f)
                quadTo(817f, -709f, 848.5f, -636f)
                quadTo(880f, -563f, 880f, -480f)
                quadTo(880f, -397f, 848.5f, -324f)
                quadTo(817f, -251f, 763f, -197f)
                quadTo(709f, -143f, 636f, -111.5f)
                quadTo(563f, -80f, 480f, -80f)
                close()
                moveTo(480f, -160f)
                quadTo(614f, -160f, 707f, -253f)
                quadTo(800f, -346f, 800f, -480f)
                quadTo(800f, -614f, 707f, -707f)
                quadTo(614f, -800f, 480f, -800f)
                quadTo(346f, -800f, 253f, -707f)
                quadTo(160f, -614f, 160f, -480f)
                quadTo(160f, -346f, 253f, -253f)
                quadTo(346f, -160f, 480f, -160f)
                close()
                moveTo(480f, -480f)
                close()
            }
        }.build()
    }

    /**
     * Stacks Icon - Custom from stacks.svg
     * Active: Pink tint, Inactive: Neutral
     */
    val NavStacksCustom: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavStacksCustom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF1f1f1f)),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f
            ) {
                // Stacks/hub/network icon
                moveTo(127f, -167f)
                quadTo(80f, -214f, 80f, -280f)
                quadTo(80f, -346f, 127f, -393f)
                quadTo(174f, -440f, 240f, -440f)
                quadTo(306f, -440f, 353f, -393f)
                quadTo(400f, -346f, 400f, -280f)
                quadTo(400f, -214f, 353f, -167f)
                quadTo(306f, -120f, 240f, -120f)
                quadTo(174f, -120f, 127f, -167f)
                close()
                moveTo(607f, -167f)
                quadTo(560f, -214f, 560f, -280f)
                quadTo(560f, -346f, 607f, -393f)
                quadTo(654f, -440f, 720f, -440f)
                quadTo(786f, -440f, 833f, -393f)
                quadTo(880f, -346f, 880f, -280f)
                quadTo(880f, -214f, 833f, -167f)
                quadTo(786f, -120f, 720f, -120f)
                quadTo(654f, -120f, 607f, -167f)
                close()
                moveTo(296.5f, -223.5f)
                quadTo(320f, -247f, 320f, -280f)
                quadTo(320f, -313f, 296.5f, -336.5f)
                quadTo(273f, -360f, 240f, -360f)
                quadTo(207f, -360f, 183.5f, -336.5f)
                quadTo(160f, -313f, 160f, -280f)
                quadTo(160f, -247f, 183.5f, -223.5f)
                quadTo(207f, -200f, 240f, -200f)
                quadTo(273f, -200f, 296.5f, -223.5f)
                close()
                moveTo(776.5f, -223.5f)
                quadTo(800f, -247f, 800f, -280f)
                quadTo(800f, -313f, 776.5f, -336.5f)
                quadTo(753f, -360f, 720f, -360f)
                quadTo(687f, -360f, 663.5f, -336.5f)
                quadTo(640f, -313f, 640f, -280f)
                quadTo(640f, -247f, 663.5f, -223.5f)
                quadTo(687f, -200f, 720f, -200f)
                quadTo(753f, -200f, 776.5f, -223.5f)
                close()
                moveTo(367f, -567f)
                quadTo(320f, -614f, 320f, -680f)
                quadTo(320f, -746f, 367f, -793f)
                quadTo(414f, -840f, 480f, -840f)
                quadTo(546f, -840f, 593f, -793f)
                quadTo(640f, -746f, 640f, -680f)
                quadTo(640f, -614f, 593f, -567f)
                quadTo(546f, -520f, 480f, -520f)
                quadTo(414f, -520f, 367f, -567f)
                close()
                moveTo(536.5f, -623.5f)
                quadTo(560f, -647f, 560f, -680f)
                quadTo(560f, -713f, 536.5f, -736.5f)
                quadTo(513f, -760f, 480f, -760f)
                quadTo(447f, -760f, 423.5f, -736.5f)
                quadTo(400f, -713f, 400f, -680f)
                quadTo(400f, -647f, 423.5f, -623.5f)
                quadTo(447f, -600f, 480f, -600f)
                quadTo(513f, -600f, 536.5f, -623.5f)
                close()
                moveTo(480f, -680f)
                close()
                moveTo(720f, -280f)
                close()
                moveTo(240f, -280f)
                close()
            }
        }.build()
    }

    /**
     * Settings Icon - Custom from settings.svg
     * Active: Pink tint, Inactive: Neutral
     */
    val NavSettingsCustom: ImageVector by lazy {
        ImageVector.Builder(
            name = "NavSettingsCustom",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color(0xFF1f1f1f)),
                stroke = null,
                strokeLineWidth = 0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                strokeLineMiter = 4f
            ) {
                // Settings/gear icon
                moveTo(565f, -395f)
                quadTo(600f, -430f, 600f, -480f)
                quadTo(600f, -530f, 565f, -565f)
                quadTo(530f, -600f, 480f, -600f)
                quadTo(430f, -600f, 395f, -565f)
                quadTo(360f, -530f, 360f, -480f)
                quadTo(360f, -430f, 395f, -395f)
                quadTo(430f, -360f, 480f, -360f)
                quadTo(530f, -360f, 565f, -395f)
                close()
                moveTo(480f, -80f)
                quadTo(397f, -80f, 324f, -111.5f)
                quadTo(251f, -143f, 197f, -197f)
                quadTo(143f, -251f, 111.5f, -324f)
                quadTo(80f, -397f, 80f, -480f)
                quadTo(80f, -563f, 111.5f, -636f)
                quadTo(143f, -709f, 197f, -763f)
                quadTo(251f, -817f, 324f, -848.5f)
                quadTo(397f, -880f, 480f, -880f)
                quadTo(563f, -880f, 636f, -848.5f)
                quadTo(709f, -817f, 763f, -763f)
                quadTo(817f, -709f, 848.5f, -636f)
                quadTo(880f, -563f, 880f, -480f)
                quadTo(880f, -397f, 848.5f, -324f)
                quadTo(817f, -251f, 763f, -197f)
                quadTo(709f, -143f, 636f, -111.5f)
                quadTo(563f, -80f, 480f, -80f)
                close()
                moveTo(480f, -160f)
                quadTo(614f, -160f, 707f, -253f)
                quadTo(800f, -346f, 800f, -480f)
                quadTo(800f, -614f, 707f, -707f)
                quadTo(614f, -800f, 480f, -800f)
                quadTo(346f, -800f, 253f, -707f)
                quadTo(160f, -614f, 160f, -480f)
                quadTo(160f, -346f, 253f, -253f)
                quadTo(346f, -160f, 480f, -160f)
                close()
                moveTo(480f, -480f)
                close()
            }
        }.build()
    }

    // ====================================================================================
    // LEGACY NAVIGATION ICONS (kept for backward compatibility)
    // These will be replaced by the custom icons above in HorizontalActionBar
    // ====================================================================================

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
