# ♿ ACCESSIBILITY IMPLEMENTATION GUIDE

**Version:** 3.2.2  
**Date:** March 14, 2026  
**Status:** 🟢 ACTIVE  
**WCAG Target:** 2.1 AA Compliance

---

## 📋 TABLE OF CONTENTS

1. [Accessibility Standards](#accessibility-standards)
2. [Implementation Guidelines](#implementation-guidelines)
3. [Touch Target Requirements](#touch-target-requirements)
4. [Content Description Guidelines](#content-description-guidelines)
5. [Semantics and Roles](#semantics-and-roles)
6. [Color Contrast](#color-contrast)
7. [Focus Management](#focus-management)
8. [Testing](#testing)
9. [Checklist](#accessibility-checklist)

---

## 🎯 ACCESSIBILITY STANDARDS

Smarty targets **WCAG 2.1 AA** compliance for mobile applications.

### WCAG 2.1 AA Requirements

| Guideline | Requirement | Smarty Standard |
|-----------|-------------|-----------------|
| **1.1.1 Non-text Content** | All non-text has text alternative | ✅ All icons have contentDescription |
| **1.3.1 Info and Relationships** | Information structure preserved | ✅ Semantics with roles |
| **1.4.3 Contrast (Minimum)** | 4.5:1 for normal text, 3:1 for large | ✅ Contrast validation |
| **2.1.1 Keyboard** | All functionality keyboard accessible | ✅ Focus management |
| **2.4.3 Focus Order** | Focus order preserves meaning | ✅ Logical focus order |
| **2.5.5 Target Size** | 44pt (48dp) minimum touch target | ✅ 48dp minimum enforced |
| **4.1.2 Name, Role, Value** | UI elements have accessible name | ✅ Semantics and testTags |

---

## 💻 IMPLEMENTATION GUIDELINES

### Using AccessibilityUtils

**Import:**
```kotlin
import com.example.smarty.core.common.util.*
```

### Accessible Clickable Modifier

**For general clickable elements:**
```kotlin
Box(
    modifier = Modifier
        .accessibleClickable(
            onClick = { handleNoteClick() },
            contentDescription = "Note: Meeting notes, March 14, 2026",
            enabled = true,
            role = Role.Button
        )
) {
    NoteCard(note = note)
}
```

**For icon buttons:**
```kotlin
Icon(
    imageVector = Icons.Default.Add,
    contentDescription = "Add new note",
    modifier = Modifier
        .iconButtonClickable(
            onClick = { addNote() },
            contentDescription = "Add new note"
        )
)
```

### Touch Target Enforcement

**All interactive elements MUST be at least 48dp:**

```kotlin
// ✅ GOOD: Accessible touch target
Box(
    modifier = Modifier
        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .size(32.dp)  // Visual size can be smaller
        .clickable { onClick() }
) {
    Icon(Icons.Default.Add, contentDescription = "Add")
}

// ❌ BAD: Too small
Box(
    modifier = Modifier
        .size(24.dp)  // Too small!
        .clickable { onClick() }
) {
    Icon(Icons.Default.Add, contentDescription = "Add")
}
```

---

## 📏 TOUCH TARGET REQUIREMENTS

### Minimum Sizes

| Element Type | Minimum Size | Notes |
|--------------|--------------|-------|
| **Buttons** | 48dp x 48dp | All clickable buttons |
| **Icon Buttons** | 48dp x 48dp | Including padding |
| **List Items** | 48dp height minimum | Swipeable items |
| **Form Inputs** | 48dp height | Text fields, dropdowns |
| **Navigation** | 48dp x 48dp | Tabs, navigation items |
| **Icons (visual)** | 20-24dp | Inside 48dp touch target |

### Spacing Between Targets

```kotlin
// ✅ GOOD: Adequate spacing
Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
    IconButton(onClick = { /* ... */ }) { /* ... */ }
    IconButton(onClick = { /* ... */ }) { /* ... */ }
}

// ❌ BAD: Too close together
Row(
    horizontalArrangement = Arrangement.spacedBy(2.dp)  // Too close!
) {
    // ...
}
```

---

## 📝 CONTENT DESCRIPTION GUIDELINES

### Writing Good Content Descriptions

**DO:**
- ✅ Be concise but descriptive
- ✅ Describe the action or purpose
- ✅ Use sentence case
- ✅ No punctuation at end
- ✅ Include context when needed

**DON'T:**
- ❌ Use "button", "link", etc. (screen reader adds this)
- ❌ Be too verbose
- ❌ Use ALL CAPS
- ❌ Include redundant information

### Examples

| Element | ❌ Bad | ✅ Good |
|---------|-------|---------|
| Add Button | "Add button" | "Add new note" |
| Delete Icon | "Delete" | "Delete note" |
| Back Arrow | "Back button" | "Go back" |
| Search Icon | "Search" | "Search notes" |
| Profile Image | "User" | "Profile picture, John Doe" |
| Attachment | null (decorative) | "Attach image" |

### Content Description Patterns

**For Actions:**
```kotlin
// Pattern: [Action] [Object]
contentDescription = "Add note"
contentDescription = "Delete message"
contentDescription = "Edit profile"
```

**For Navigation:**
```kotlin
// Pattern: [Direction/Action] [Destination]
contentDescription = "Go back"
contentDescription = "Open settings"
contentDescription = "View calendar"
```

**For Status:**
```kotlin
// Pattern: [Object] [Status]
contentDescription = "Note archived"
contentDescription = "Sync in progress"
contentDescription = "Private mode enabled"
```

---

## 🏷️ SEMANTICS AND ROLES

### Using Modifier.semantics {}

```kotlin
Box(
    modifier = Modifier
        .clickable(onClick = onClick)
        .semantics {
            role = Role.Button
            contentDescription = "Add new note"
            // Optional: disable if not interactive
            // disabled()
        }
)
```

### Common Roles

```kotlin
Role.Button      // Clickable buttons
Role.Checkbox    // Toggle switches
Role.RadioButton // Radio buttons
Role.Tab         // Tab navigation
Role.TabBar      // Tab container
Role.Header      // Section headers
Role.Image       // Images with meaning
Role.SearchBox   // Search inputs
Role.TextField   // Text input fields
Role.AlertDialog // Alert dialogs
```

### Complex Components

**For swipeable items:**
```kotlin
Surface(
    modifier = Modifier
        .fillMaxWidth()
        .semantics {
            role = Role.Button
            contentDescription = buildString {
                append(note.title)
                append(", ")
                append(note.dateString)
                if (note.isArchived) append(", Archived")
                if (note.isPinned) append(", Pinned")
            }
        }
)
```

**For tabs:**
```kotlin
Row(
    modifier = Modifier
        .semantics {
            role = Role.TabBar
        }
) {
    tabs.forEach { tab ->
        Box(
            modifier = Modifier
                .clickable { selectTab(tab) }
                .semantics {
                    role = Role.Tab
                    contentDescription = "${tab.title}, ${if (tab.isSelected) "selected" else "not selected"}"
                }
        )
    }
}
```

---

## 🎨 COLOR CONTRAST

### Contrast Requirements

| Text Type | Minimum Ratio | Example |
|-----------|---------------|---------|
| **Normal Text** (< 18pt) | 4.5:1 | Body text, captions |
| **Large Text** (≥ 18pt or ≥ 14pt bold) | 3:1 | Headings, titles |
| **UI Components** | 3:1 | Icons, borders |
| **Focus Indicators** | 3:1 | Focus rings |

### Using Contrast Utilities

```kotlin
// Check if color has sufficient contrast
val hasContrast = textColor.hasSufficientContrast(backgroundColor)

// Get a color with sufficient contrast
val accessibleColor = textColor.withContrastAgainst(backgroundColor)

// Usage in Compose
Text(
    text = "Hello",
    color = LocalContentColor.current.withContrastAgainst(
        MaterialTheme.colorScheme.background
    )
)
```

### Approved Color Combinations

**Light Theme:**
```kotlin
// ✅ GOOD: High contrast
Text(color = Color(0xFF1A1A1A)) on Background (Color.White)
Text(color = Color(0xFF424242)) on Background (Color.White)

// ⚠️ REVIEW: May need adjustment
Text(color = Color(0xFF8E8E93)) on Background (Color.White)  // 3.5:1
```

**Dark Theme:**
```kotlin
// ✅ GOOD: High contrast
Text(color = Color.White) on Background (Color(0xFF000000))
Text(color = Color(0xFFE0E0E0)) on Background (Color(0xFF121212))

// ⚠️ REVIEW: May need adjustment
Text(color = Color(0xFF8A8A8E)) on Background (Color(0xFF0A0A0A))  // 3.8:1
```

---

## ⌨️ FOCUS MANAGEMENT

### Focus Requester Pattern

```kotlin
@Composable
fun LoginForm() {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    
    Column {
        TextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier
                .focusRequester(focusRequester)
                .onKeyEvent { key ->
                    if (key.key == Key.DirectionDown) {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    } else {
                        false
                    }
                }
        )
        
        TextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.onKeyEvent { key ->
                if (key.key == Key.Enter) {
                    focusManager.clearFocus()
                    true
                } else {
                    false
                }
            }
        )
    }
    
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
```

### Focus Order

Ensure logical focus order (left-to-right, top-to-bottom):

```kotlin
// ✅ GOOD: Natural focus order
Column {
    TextField(modifier = Modifier.focusRequester(firstField))
    TextField(modifier = Modifier.focusRequester(secondField))
    Button(onClick = submit)
}

// ❌ BAD: Confusing focus order
Row {
    TextField(modifier = Modifier.focusRequester(thirdField))
    TextField(modifier = Modifier.focusRequester(firstField))
    Button(modifier = Modifier.focusRequester(secondField))
}
```

---

## 🧪 TESTING

### Manual Testing Checklist

- [ ] Enable TalkBack (Settings → Accessibility → TalkBack)
- [ ] Navigate through all screens
- [ ] Verify all elements are announced
- [ ] Verify announcements are meaningful
- [ ] Verify focus order is logical
- [ ] Verify all actions can be performed
- [ ] Test with eyes closed (simulate blindness)

### Automated Testing

```kotlin
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testAccessibility() {
        composeTestRule.setContent {
            SmartyApp()
        }

        // Check for missing content descriptions
        composeTestRule
            .onAllNodes(hasContentDescription())
            .assertExists()

        // Check touch target sizes
        composeTestRule
            .onAllNodes(hasMinTouchTarget(48.dp))
            .assertExists()
    }
}
```

### Accessibility Scanner

Use Android Accessibility Scanner:
1. Install Accessibility Scanner from Play Store
2. Open Smarty app
3. Take screenshots
4. Review suggestions
5. Fix identified issues

---

## ✅ ACCESSIBILITY CHECKLIST

### Before Merging Any UI Change

#### Content Descriptions
- [ ] All icons have contentDescription (or null if decorative)
- [ ] All images have contentDescription
- [ ] Content descriptions are meaningful
- [ ] No redundant "button", "link" in descriptions

#### Touch Targets
- [ ] All clickable elements are at least 48dp x 48dp
- [ ] Adequate spacing (8dp) between touch targets
- [ ] Touch target visible or has clear visual indicator

#### Semantics
- [ ] Interactive elements have role defined
- [ ] Complex components have proper semantics
- [ ] State changes are announced (e.g., "selected", "expanded")

#### Focus Management
- [ ] Forms have logical focus order
- [ ] Focus is visible and clear
- [ ] Keyboard navigation works
- [ ] Focus not trapped in any component

#### Color Contrast
- [ ] Text has 4.5:1 contrast ratio (normal text)
- [ ] Large text has 3:1 contrast ratio
- [ ] Icons have 3:1 contrast ratio
- [ ] Focus indicators have 3:1 contrast

#### Testing
- [ ] Tested with TalkBack enabled
- [ ] All functionality accessible via TalkBack
- [ ] No accessibility errors in Accessibility Scanner

---

## 📖 ADDITIONAL RESOURCES

### Internal Documentation
- [AccessibilityUtils.kt](app/src/main/java/com/example/smarty/core/common/util/AccessibilityUtils.kt)
- [Testing Guidelines](TESTING_GUIDELINES.md)

### External Resources
- [WCAG 2.1 Guidelines](https://www.w3.org/WAI/WCAG21/quickref/)
- [Android Accessibility](https://developer.android.com/guide/topics/ui/accessibility)
- [Compose Accessibility](https://developer.android.com/jetpack/compose/accessibility)
- [Accessibility Scanner](https://play.google.com/store/apps/details?id=com.google.android.apps.accessibility.auditor)

---

**Last Updated:** March 14, 2026  
**Version:** 3.2.2  
**Maintained By:** Development Team
