# Cogni UI Architecture Documentation

> A comprehensive guide to the Cogni app's user interface design, animations, components, and interaction patterns.

---

## Table of Contents

1. [Design Philosophy](#design-philosophy)
2. [Screen Architecture](#screen-architecture)
3. [Navigation Flow](#navigation-flow)
4. [Components](#components)
5. [Theme System](#theme-system)
6. [Animation System](#animation-system)
7. [Interaction Patterns](#interaction-patterns)
8. [User Flows](#user-flows)
9. [Accessibility & Feedback](#accessibility--feedback)
10. [File Organization](#file-organization)

---

## Design Philosophy

Cogni's UI is built on three core principles:

### 1. Mathematical Harmony
- **Golden Ratio (φ = 1.618)** spacing system
- **Fibonacci sequence** for component gaps: 2, 4, 6, 8, 13, 21, 34, 55, 89dp
- Creates natural visual rhythm that feels "right" without users knowing why

### 2. Apple-Inspired Motion
- **Spring physics** for all animations (no linear tweens)
- **Easing curves** matching iOS system animations
- **Micro-interactions** that provide immediate tactile feedback

### 3. Functional Brutalism
- **Monospace typography** for headers (technical aesthetic)
- **High contrast** color scheme (OLED-optimized blacks)
- **Content-first** design with minimal chrome

---

## Screen Architecture

### Screen Hierarchy

```
┌─────────────────────────────────────────────────────────────┐
│                      SPLASH SCREEN                          │
│              (Animated logo + gradient waves)               │
└──────────────────────────┬──────────────────────────────────┘
                           │
              ┌────────────▼────────────┐
              │    PIN CONFIGURED?      │
              └────────────┬────────────┘
                    YES    │    NO
              ┌────────────┴────────────┐
              ▼                         ▼
┌─────────────────────┐    ┌─────────────────────────────────┐
│     PIN SCREEN      │    │       INPUT STREAM SCREEN       │
│  (6-digit verify)   │───▶│         (Main Hub)              │
└─────────────────────┘    └───────────────┬─────────────────┘
                                           │
         ┌─────────────┬───────────────────┼───────────────┬─────────────┐
         ▼             ▼                   ▼               ▼             ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────────┐ ┌───────────┐ ┌───────────┐
│   STACKS    │ │  SETTINGS   │ │    CALENDAR     │ │  ARCHIVE  │ │ KNOWLEDGE │
│ (Categories)│ │ (Config)    │ │ (Tasks/Events)  │ │  (Hidden) │ │   CARD    │
└──────┬──────┘ └──────┬──────┘ └─────────────────┘ └───────────┘ │  (Detail) │
       │               │                                          └───────────┘
       ▼               ▼
┌─────────────┐ ┌─────────────┐
│  CATEGORY   │ │   BACKUP    │
│   NOTES     │ │  SETTINGS   │
└─────────────┘ └─────────────┘
```

### Screen Details

| Screen | File | Purpose |
|--------|------|---------|
| **Splash** | `SplashScreen.kt` | Animated entry with logo breathing effect |
| **PIN** | `PinScreen.kt` | Security verification (setup, verify, change) |
| **InputStream** | `InputStreamScreen.kt` | Main note creation and viewing hub |
| **Stacks** | `StacksScreen.kt` | Category/folder grid view |
| **CategoryNotes** | `CategoryNotesScreen.kt` | Notes filtered by category |
| **KnowledgeCard** | `KnowledgeCardScreen.kt` | Individual note detail view |
| **Settings** | `SettingsScreen.kt` | API keys, security, theme, cache |
| **Archive** | `ArchiveScreen.kt` | Archived notes management |
| **BackupSettings** | `BackupSettingsScreen.kt` | Google Drive backup config |
| **Calendar** | `CalendarScreen.kt` | Task and event management |

---

## Navigation Flow

### Route Definitions

```kotlin
sealed class Screen(val route: String) {
    data object Splash : Screen("splash")
    data object Pin : Screen("pin")
    data object PinSetup : Screen("pin_setup")
    data object PinChange : Screen("pin_change")
    data object InputStream : Screen("input_stream")
    data object Stacks : Screen("stacks")
    data object CategoryNotes : Screen("category_notes")
    data object KnowledgeCard : Screen("knowledge_card")
    data object Settings : Screen("settings")
    data object Archive : Screen("archive")
    data object BackupSettings : Screen("backup_settings")
    data object Calendar : Screen("calendar")
}
```

### Navigation Actions

| From | To | Trigger |
|------|-----|---------|
| Splash | PIN or InputStream | Auto after 1.5s |
| InputStream | Stacks | Grid icon tap |
| InputStream | Settings | Gear icon tap |
| InputStream | Calendar | Calendar icon tap |
| InputStream | KnowledgeCard | Note card tap |
| Stacks | CategoryNotes | Category tap |
| Settings | Archive | "Archived Notes" tap |
| Settings | BackupSettings | "Cloud Backup" tap |
| Settings | PinSetup/PinChange | Security settings |

---

## Components

### Core Components

#### 1. NoteCard (`NoteCard.kt`)

The primary content display component with advanced interactions.

**Features:**
- Spring-based press animation (0.97x scale)
- Bidirectional swipe gestures
- 3D interactive tilt on press
- Multi-select mode support
- Type-specific styling (YouTube, Audio, Image, etc.)

**Visual States:**

```
┌────────────────────────────────────────────┐
│ ┌──────┐                                   │
│ │ TYPE │  Note Title                  🔒   │
│ │ ICON │  ─────────────────────────────    │
│ └──────┘  Preview text content...          │
│                                            │
│           ▶ Play Audio (if audio type)     │
│                                            │
│  💡 AI insight text                        │
│                                            │
│  ┌─────┐ ┌─────┐ ┌─────┐                  │
│  │Todo1│ │Todo2│ │ +2  │  (Todo chips)    │
│  └─────┘ └─────┘ └─────┘                  │
└────────────────────────────────────────────┘
```

**Swipe Actions:**

| Direction | Main View | Archive View |
|-----------|-----------|--------------|
| ← Left | Open Todos | Unarchive |
| → Right | Archive | Delete |

**Animation Specs:**
```kotlin
// Press feedback
scale: spring(dampingRatio = 0.8f, stiffness = 600f)
targetValue: 0.97f

// Swipe snap-back
spring(dampingRatio = 0.8f, stiffness = 800f)

// Swipe threshold
45.dp (quick, effortless swipes)
```

---

#### 2. CogniInputField (`CogniInputField.kt`)

Intelligent input field with attachment support.

**Layout:**

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ╭───────────────────────────────────────────────────────╮  │
│  │ What's on your mind?                          📎  (>) │  │
│  ╰───────────────────────────────────────────────────────╯  │
│                                                             │
│   ( 🖼️ ) ( 🎬 ) ( 📄 ) ( 🎵 ) ( 📁 )   (Floating Bubbles)  │
│                                                             │
│  ┌────────┐ ┌────────┐ ┌────────┐                           │
│  │ img.jpg│ │doc.pdf │ │ +more  │  (Attachment previews)   │
│  │   ✕   │ │   ✕   │ │        │                           │
│  └────────┘ └────────┘ └────────┘                           │
└─────────────────────────────────────────────────────────────┘
```

**States:**
- Default: Translucent pill, no border
- Focused: Accent glow, magic prompt breathing
- With text: Send button ("swoosh" rotation) visible
- Chat mode: Attachment tool hidden
- AI Excluded: Eye-off indicator visible

---

#### 3. AlphabetFastScroller (`AlphabetFastScroller.kt`)

Advanced letter navigation with fisheye lens effect.

**Fisheye Wave Effect:**

```
Normal:     A B C D E F G H I J K
                    ↓ Touch at 'F'
Fisheye:    A B C  D   E    F    G   H  I J K
                        ↑
                   Enlarged + Offset
```

**Mathematical Model:**
```kotlin
// Gaussian distribution for wave effect
scale = 1 + (maxScale - 1) × e^(-(distance²)/(2σ²))

// Parameters
maxScale = 2.8   // Maximum letter enlargement
sigma = 2.0      // Wave spread
maxOffset = 28dp // Horizontal bulge
```

**Behavior:**
- Auto-hides after 2 seconds of inactivity
- Haptic feedback on letter changes
- Smooth scroll to alphabetical position in list

---

#### 4. ShareBottomSheet (`ShareBottomSheet.kt`)

Modal for configuring shared content before saving.

**Layout:**

```
┌─────────────────────────────────────────────┐
│ ═══════════ (drag handle)                   │
│                                             │
│ 🛡️ Full Privacy Mode - No AI Processing    │  ← (if shake activated)
│                                             │
│ save_to_cogni                          ✕    │
│                                             │
│ ┌─────────────────────────────────────────┐ │
│ │ 🎬 │ Video Title                        │ │
│ │    │ youtube.com/watch?v=...            │ │
│ │    │ [youtube] [1.2 MB]                 │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ Category                                    │
│ ┌─────────────────────────────────────────┐ │
│ │ ✨ Let AI decide                    [ON]│ │
│ └─────────────────────────────────────────┘ │
│                                             │
│ Related Notes                               │
│ ┌────────┐ ┌────────┐ ┌────────┐          │
│ │ Note 1 │ │ Note 2 │ │ Note 3 │          │
│ └────────┘ └────────┘ └────────┘          │
│                                             │
│ Instructions for AI (optional)              │
│ ┌─────────────────────────────────────────┐ │
│ │ e.g., "This is for my project X"        │ │
│ └─────────────────────────────────────────┘ │
│                                             │
│     📳 Shake phone for Full Privacy Mode   │
│                                             │
│  ┌──────────┐  ┌──────────────────────┐   │
│  │  Cancel  │  │    💾 Save           │   │
│  └──────────┘  └──────────────────────┘   │
└─────────────────────────────────────────────┘
```

---

#### 5. Audio Components

**AudioWaveform (`audio/AudioWaveform.kt`):**

```
Played              Unplayed
  ↓                    ↓
 ███                  ░░░
 ███ ██              ░░░ ░░
 ███ ██ █           ░░░ ░░ ░
 ███ ██ █ ██       ░░░ ░░ ░ ░░
 ███ ██ █ ██ █ █  ░░░ ░░ ░ ░░ ░ ░
─────────────────────────────────────
        ↑ Playhead (tap to seek)
```

**MiniAudioPlayer (`audio/MiniAudioPlayer.kt`):**

```
┌─────────────────────────────────────────────────────────────┐
│ 64dp │  🎵 Track Name           ▶/⏸  ⏩  ↗️               │
│      │  Artist • 2:34 / 5:12    [═══════░░░░░░░]           │
└─────────────────────────────────────────────────────────────┘
```

---

### Component Spacing Constants

```kotlin
object ComponentSpacing {
    // Fibonacci-based gaps
    val listItemGap = 13.dp      // Between list items
    val sectionGap = 21.dp       // Between sections
    val cardContentGap = 8.dp    // Inside card elements
    val cardHeaderGap = 6.dp     // Title to content

    // Fixed sizes
    val screenPadding = 16.dp    // Screen edge padding
    val cardPadding = 16.dp      // Card internal padding
    val cardCornerRadius = 18.dp // Rounded corners
    val buttonCornerRadius = 13.dp

    // Icons
    val iconSizeSmall = 14.dp
    val iconSizeDefault = 18.dp
    val iconSizeLarge = 24.dp

    // PIN specific
    val pinDotSize = 20.dp
    val pinDotGap = 16.dp
}
```

---

## Theme System

### Color Palette

#### Dark Theme (Primary)

| Token | Hex | Usage |
|-------|-----|-------|
| `AcidGreen` | `#CCFF00` | Primary accent, buttons |
| `DeepBlack` | `#000000` | Background (OLED optimized) |
| `DarkSurface` | `#1C1C1E` | Cards, elevated surfaces |
| `DarkSurfaceHigh` | `#2C2C2E` | Higher elevation |
| `PureWhite` | `#FFFFFF` | Primary text |
| `SecondaryGray` | `#8E8E93` | Secondary text |
| `BorderDark` | `#38383A` | Subtle borders |

#### Light Theme

| Token | Hex | Usage |
|-------|-----|-------|
| `BrightOrange` | `#FF6B00` | Primary accent |
| `AppleGray` | `#F2F2F7` | Background |
| `PureWhite` | `#FFFFFF` | Cards, surfaces |
| `PureBlack` | `#000000` | Primary text |
| `SecondaryLight` | `#3C3C43` | Secondary text |
| `BorderLight` | `#D1D1D6` | Borders |

#### Semantic Colors

| Token | Hex | Usage |
|-------|-----|-------|
| `SafetyOrange` | `#FF4D00` | Warnings, delete actions |
| `YouTubeRed` | `#FF0000` | YouTube content |
| `AudioPink` | `#FF2D55` | Audio content |
| `DocumentBlue` | `#007AFF` | Documents |
| `ImageTeal` | `#5AC8FA` | Images |
| `CodeCyan` | `#64D2FF` | Code snippets |

### Typography Scale

```kotlin
// Headers - Monospace (JetBrains Mono style)
displayLarge:  32sp, Bold,     LineHeight: 40sp
headlineLarge: 24sp, Bold,     LineHeight: 32sp
headlineMedium: 20sp, Bold,    LineHeight: 28sp
titleLarge:    18sp, SemiBold, LineHeight: 24sp
titleMedium:   16sp, SemiBold, LineHeight: 22sp

// Body - Sans-serif (Inter-like)
bodyLarge:  16sp, Normal, LineHeight: 24sp
bodyMedium: 14sp, Normal, LineHeight: 20sp
bodySmall:  12sp, Normal, LineHeight: 16sp

// Labels
labelLarge:  14sp, Medium, LineHeight: 20sp
labelMedium: 12sp, Medium, LineHeight: 16sp
labelSmall:  10sp, Medium, LineHeight: 14sp
```

### Theme Transitions

```kotlin
// All color properties animate on theme change
animationSpec = tween(400ms)

// Applied to:
- Background colors
- Surface colors
- Text colors
- Border colors
- Icon tints
```

---

## Animation System

### Spring Physics Configurations

| Name | Damping | Stiffness | Use Case |
|------|---------|-----------|----------|
| `snappy` | MediumBouncy | High | Critical actions |
| `interactive` | 0.8 | 400 | User interactions |
| `gentle` | 0.9 | 200 | Large movements |
| `bouncy` | 0.6 | 300 | Playful feedback |
| `veryBouncy` | 0.5 | 350 | Emphasis |
| `smooth` | 1.0 | 150 | Critically damped |
| `quick` | 0.85 | 600 | Micro-interactions |

### Custom Easing Curves

```kotlin
// Apple standard ease-out
appleEaseOut = CubicBezier(0.25, 0.1, 0.25, 1.0)

// Smooth acceleration/deceleration
appleEaseInOut = CubicBezier(0.42, 0.0, 0.58, 1.0)

// Dramatic deceleration
emphasizedDecelerate = CubicBezier(0.05, 0.7, 0.1, 1.0)

// Very smooth deceleration
exponentialOut = { t -> 1 - 2^(-10 * t) }

// Overshoot then settle
backOut = { t -> 1 + c₃(t-1)³ + c₁(t-1)² }

// Oscillating decay
elasticOut = { t -> sin(-13π/2 * (t+1)) * 2^(-10t) + 1 }
```

### Stagger Patterns

For list item animations:

```kotlin
// Linear: Constant delay between items
delay(i) = baseDelay × i

// Fibonacci: Golden ratio cascade (most natural)
delay(i) = baseDelay × φ^i

// Logarithmic: Fast start, slow tail
delay(i) = baseDelay × ln(i+1) / ln(2)

// Quadratic: Accelerating delays
delay(i) = baseDelay × i²

// Wave: Sinusoidal pattern
delay(i) = baseDelay × |sin(i × π/4)| × i
```

### Animation Examples

#### Splash Screen

```kotlin
// Logo breathing effect
scale: spring(dampingRatio = 0.6f)
targetValue: oscillates 0.95 ↔ 1.05

// Logo fade-in
alpha: tween(600ms, LinearEasing)
targetValue: 0 → 1

// Logo rise
offsetY: spring animation
targetValue: 30dp → 0dp

// Gradient waves (background)
wave1: tween(3000ms, reverseRepeat)
wave2: tween(2000ms, reverseRepeat) // Creates depth
```

#### Card Press

```kotlin
// Scale down
animateFloatAsState(
    targetValue = if (pressed) 0.97f else 1f,
    animationSpec = spring(
        dampingRatio = 0.8f,
        stiffness = 600f
    )
)

// Subtle rotation (based on index)
rotation = if (pressed) (index % 2 * 2 - 1) * 0.5f else 0f
```

#### List Entry (Staggered)

```kotlin
// For first 5 items, stagger; rest appear instantly
val staggerDelay = if (index < 5)
    StaggerCalculator.logarithmic(index, 40)
else 0

LaunchedEffect(Unit) {
    delay(staggerDelay.toLong())
    appeared = true
}

// Scale animation
scale: spring(dampingRatio = 0.7f, stiffness = 300f)
targetValue: 0.85f → 1f

// Alpha animation
alpha: tween(200ms, appleEaseOut)
targetValue: 0f → 1f

// Slide up
offsetY: spring(dampingRatio = 0.8f, stiffness = 400f)
targetValue: 20f → 0f
```

---

## Interaction Patterns

### Gesture Controls

#### Swipe Gestures

```
                    ← LEFT SWIPE →                → RIGHT SWIPE ←

Main View:          Open Todo Sheet               Archive Note
                    (Green glow)                  (Orange glow)

Archive View:       Unarchive                     Delete
                    (Green glow)                  (Red glow)

Threshold: 45dp (designed for quick, effortless swipes)
```

#### Press Interactions

| Action | Visual Response |
|--------|-----------------|
| Touch down | Scale to 0.97x, slight rotation |
| Touch up | Spring back to 1.0x |
| Long press | Activate multi-select mode |

#### Special Gestures

| Gesture | Location | Action |
|---------|----------|--------|
| Tap | Waveform | Seek to position |
| Tap | Letter scroller | Jump to letter |
| Tap | Attachment ✕ | Remove attachment |
| Shake | Share sheet | Toggle privacy mode |
| Shake | Input field | Toggle AI exclusion |
| Shake | Empty input | Toggle chat mode |

### Haptic Feedback

| Event | Feedback Type |
|-------|---------------|
| Button press | `TextHandleMove` |
| Error state | Long press haptic |
| Swipe threshold | Haptic pulse |
| Letter change | Light haptic |
| Audio seek | Medium haptic |

---

## User Flows

### Main App Flow

```
┌─────────────────────────────────────────────────────────────┐
│ 1. LAUNCH                                                   │
│    └─→ Splash screen (1.5s)                                │
│        └─→ Animated logo + gradient waves                  │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. SECURITY CHECK                                           │
│    ├─→ No PIN: Skip to main                                │
│    └─→ Has PIN: Show PIN screen                            │
│        └─→ 6-digit entry with dot animation               │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. MAIN HUB (InputStream)                                   │
│    ├─→ View notes (scrollable list + fast scroller)        │
│    ├─→ Create note (input field + attachments)             │
│    ├─→ Navigate (Stacks / Settings / Calendar)             │
│    └─→ Interact with notes (tap / swipe / long-press)      │
└─────────────────────────────────────────────────────────────┘
```

### Note Creation Flow

```
1. Tap input field
   └─→ Keyboard appears, border glows accent color

2. Type content OR add attachments
   └─→ Attachment panel slides up
   └─→ Select type (image/video/doc/audio/file)
   └─→ File picker opens
   └─→ Attachment preview appears

3. (Optional) Shake for AI exclusion
   └─→ Eye-off icon appears
   └─→ Note will skip AI processing

4. Tap send button
   └─→ Button scales down with spring
   └─→ Note card animates into list
   └─→ Processing indicator if AI enabled
   └─→ Card updates when processing complete
```

### Share Flow

```
1. Share content TO Cogni from another app
   └─→ App opens with ShareBottomSheet

2. Review shared content
   └─→ File preview displayed
   └─→ Type auto-detected (YouTube, Web, Image, etc.)

3. Configure (optional)
   └─→ Select category OR let AI decide
   └─→ Add AI instructions
   └─→ Shake for Full Privacy Mode

4. Save
   └─→ Note created
   └─→ AI processing (unless privacy mode)
   └─→ Categorized and summarized
```

### Chat Mode Flow

```
1. Shake with empty input
   └─→ Chat mode activates
   └─→ Input placeholder changes
   └─→ Note list replaced with chat bubbles

2. Type message and send
   └─→ User bubble appears
   └─→ Processing indicator shows
   └─→ AI response bubble appears

3. Continue conversation
   └─→ Messages accumulate
   └─→ Scroll to latest

4. Shake again to exit
   └─→ Returns to note list
```

---

## Accessibility & Feedback

### Visual Feedback

| State | Visual Indicator |
|-------|------------------|
| Loading | Pulsing scale (0.95 ↔ 1.05) |
| Processing | Three-dot animation |
| Shimmer | Gradient sweep effect |
| Error | Shake animation + SafetyOrange |
| Success | Scale bounce + checkmark |
| Selected | Accent border + checkmark |

### Animation Timing Guidelines

| Category | Duration |
|----------|----------|
| Micro-interactions | 150-300ms |
| Feedback animations | 200-400ms |
| Page transitions | Spring-based (variable) |
| Loading states | Continuous loop |
| Auto-hide elements | 2 second delay |
| Theme transition | 400ms |

### Content Descriptions

All interactive elements include `contentDescription` for screen readers:
- Icons have descriptive labels
- Buttons state their action
- Toggle states are announced
- Images have alt text

---

## File Organization

```
app/src/main/java/com/example/smarty/
├── ui/
│   ├── screens/
│   │   ├── SplashScreen.kt          # Animated entry
│   │   ├── PinScreen.kt             # PIN verification (3 modes)
│   │   ├── InputStreamScreen.kt     # Main note hub
│   │   ├── StacksScreen.kt          # Category grid
│   │   ├── CategoryNotesScreen.kt   # Filtered note list
│   │   ├── KnowledgeCardScreen.kt   # Note detail view
│   │   ├── SettingsScreen.kt        # App configuration
│   │   ├── ArchiveScreen.kt         # Archived notes
│   │   ├── BackupSettingsScreen.kt  # Cloud backup
│   │   └── CalendarScreen.kt        # Task management
│   │
│   ├── components/
│   │   ├── NoteCard.kt              # Primary content card
│   │   ├── CogniInputField.kt       # Smart input field
│   │   ├── AlphabetFastScroller.kt  # Letter navigation
│   │   ├── AttachmentPreview.kt     # Media previews
│   │   ├── AttachmentTypeSelector.kt# Attachment picker
│   │   ├── ShareBottomSheet.kt      # Share configuration
│   │   ├── ChatMessageItem.kt       # Chat bubbles
│   │   ├── NoteTodoSheet.kt         # Todo management
│   │   ├── PrivacyModeIndicator.kt  # Privacy badges
│   │   ├── YouTubePlayButton.kt     # YouTube player trigger
│   │   ├── ProcessingDotsIndicator.kt# Loading indicator
│   │   └── audio/
│   │       ├── AudioWaveform.kt     # Waveform visualization
│   │       ├── MiniAudioPlayer.kt   # Compact player bar
│   │       └── FullAudioPlayer.kt   # Full-screen player
│   │
│   ├── theme/
│   │   ├── Color.kt                 # 42 color definitions
│   │   ├── Theme.kt                 # Dark/light schemes
│   │   ├── Type.kt                  # Typography scale
│   │   └── Spacing.kt               # Golden ratio system
│   │
│   ├── animation/
│   │   ├── CogniAnimations.kt       # Springs, easing, helpers
│   │   └── FisheyeAnimations.kt     # Letter scroller math
│   │
│   └── CompositionLocal.kt          # LocalAccentColor
│
└── navigation/
    └── CogniNavigation.kt           # 10-screen nav graph
```

---

## Best Practices

### Do's

- Use spring animations for all interactive elements
- Apply staggered animations to list items
- Provide haptic feedback for significant actions
- Use semantic colors consistently
- Follow the Fibonacci spacing scale
- Include content descriptions for accessibility

### Don'ts

- Avoid linear animations (use springs instead)
- Don't skip loading states
- Avoid harsh, instant transitions
- Don't use arbitrary spacing values
- Avoid blocking the main thread with animations

---

## Version History

| Version | Date | Changes |
|---------|------|---------|
| 1.0 | Dec 2024 | Initial UI architecture |
| 1.1 | Dec 2024 | Added calendar, YouTube playback |
| 1.2 | Dec 2024 | Full privacy mode, cache management |

---

*Documentation generated for Cogni v1.2*
*Last updated: December 2024*
