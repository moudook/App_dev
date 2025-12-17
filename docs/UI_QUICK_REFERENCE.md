# Cogni UI Quick Reference

> Fast lookup for common UI patterns, animations, and constants.

---

## Spacing (Fibonacci Scale)

```kotlin
2.dp   // Micro gap
4.dp   // Tiny gap
6.dp   // Small gap
8.dp   // Card content gap
13.dp  // List item gap
16.dp  // Screen padding, card padding
21.dp  // Section gap
34.dp  // Large section gap
55.dp  // Extra large gap
89.dp  // Huge gap
```

---

## Corner Radii

```kotlin
28.dp  // Pill shape (Input field)
18.dp  // Card corners
13.dp  // Button corners
12.dp  // Chip corners
8.dp   // Small elements
4.dp   // Micro elements
```

---

## Icon Sizes

```kotlin
14.dp  // Small (labels, hints)
18.dp  // Default
24.dp  // Large (headers, actions)
```

---

## Spring Presets

```kotlin
// Quick micro-interactions
spring(dampingRatio = 0.85f, stiffness = 600f)

// Interactive feedback
spring(dampingRatio = 0.8f, stiffness = 400f)

// Gentle movements
spring(dampingRatio = 0.9f, stiffness = 200f)

// Bouncy emphasis
spring(dampingRatio = 0.6f, stiffness = 300f)

// Smooth (no bounce)
spring(dampingRatio = 1.0f, stiffness = 150f)
```

---

## Common Animations

### Press Feedback
```kotlin
val scale by animateFloatAsState(
    targetValue = if (pressed) 0.97f else 1f,
    animationSpec = spring(
        dampingRatio = 0.8f,
        stiffness = 600f
    )
)
```

### List Item Entry
```kotlin
// Stagger delay
val delay = if (index < 5) index * 40 else 0

// Scale + fade + slide
scale: 0.85f → 1f (spring 0.7, 300)
alpha: 0f → 1f (tween 200ms)
offsetY: 20f → 0f (spring 0.8, 400)
```

### Breathing Pulse
```kotlin
val scale by rememberInfiniteTransition().animateFloat(
    initialValue = 0.95f,
    targetValue = 1.05f,
    animationSpec = infiniteRepeatable(
        animation = tween(1000),
        repeatMode = RepeatMode.Reverse
    )
)
```

### 3D Card Tilt
```kotlin
// Interactive tilt based on touch position
val tilt = animateCardTilt(
    pressed = isPressed,
    maxTilt = 5f,
    pressedElevation = 4f
)

Modifier.cardTilt3D(tilt)
```

### Magic Pop (Spring)
```kotlin
spring(
    dampingRatio = 0.5f, // Very bouncy
    stiffness = 350f     // Snappy but elastic
)
```

---

## Colors Quick Reference

### Dark Theme
```kotlin
Background:  Color(0xFF000000)  // Pure black
Surface:     Color(0xFF1C1C1E)  // Elevated
Accent:      Color(0xFFCCFF00)  // Acid green
Text:        Color(0xFFFFFFFF)  // White
Secondary:   Color(0xFF8E8E93)  // Gray
```

### Light Theme
```kotlin
Background:  Color(0xFFF2F2F7)  // Apple gray
Surface:     Color(0xFFFFFFFF)  // White
Accent:      Color(0xFFFF6B00)  // Orange
Text:        Color(0xFF000000)  // Black
Secondary:   Color(0xFF3C3C43)  // Dark gray
```

### Semantic
```kotlin
SafetyOrange: Color(0xFFFF4D00)  // Warnings, delete
YouTubeRed:   Color(0xFFFF0000)  // YouTube
AudioPink:    Color(0xFFFF2D55)  // Audio
DocumentBlue: Color(0xFF007AFF)  // Docs
ImageTeal:    Color(0xFF5AC8FA)  // Images
```

---

## Gesture Thresholds

```kotlin
Swipe activation:     45.dp
Long press delay:     500ms (default)
Double tap interval:  300ms
```

---

## Easing Curves

```kotlin
// Apple standard
CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

// Smooth in-out
CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

// Dramatic deceleration
CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
```

---

## Common Modifiers

### Card Style
```kotlin
Modifier
    .fillMaxWidth()
    .padding(horizontal = 16.dp)
    .clip(RoundedCornerShape(18.dp))
    .background(MaterialTheme.colorScheme.surface)
```

### Pressable (with Tilt)
```kotlin
Modifier
    .then(Modifier.cardTilt3D(tilt)) // Apply 3D transform first
    .graphicsLayer { ... }           // Then standard transforms
    .pointerInput(Unit) {
        detectTapGestures(
            onPress = { pressed = true; tryAwaitRelease(); pressed = false }
        )
    }
```

### Swipeable
```kotlin
Modifier
    .offset { IntOffset(offsetX.roundToInt(), 0) }
    .draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta -> offsetX += delta }
    )
```

---

## Haptics

```kotlin
// Light feedback
view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

// Medium feedback
view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)

// Heavy feedback (errors)
view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
```

---

## Typography

```kotlin
// Headers (monospace)
MaterialTheme.typography.headlineMedium  // 20sp bold

// Body
MaterialTheme.typography.bodyMedium      // 14sp normal

// Labels
MaterialTheme.typography.labelSmall      // 10sp medium
```

---

## Screen Names

```kotlin
"splash"           // Entry
"pin"              // Verify PIN
"pin_setup"        // Create PIN
"pin_change"       // Change PIN
"input_stream"     // Main hub
"stacks"           // Categories
"category_notes"   // Filtered list
"knowledge_card"   // Note detail
"settings"         // Configuration
"archive"          // Archived notes
"backup_settings"  // Cloud backup
"calendar"         // Tasks/events
```

---

## Component Checklist

When creating new components:

- [ ] Use spring animations (not tween for interactions)
- [ ] Follow Fibonacci spacing
- [ ] Add haptic feedback for actions
- [ ] Include content descriptions
- [ ] Use LocalAccentColor for accent color
- [ ] Handle dark/light themes
- [ ] Add press feedback animation (Scale + optional Tilt)
- [ ] Use Pill shapes (28.dp) for input fields
- [ ] Consider staggered entry for lists

---

*Quick reference for Cogni UI development*
