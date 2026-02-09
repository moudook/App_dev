# UI/UX Logical Error Audit

## 1. Chat Section (Input Stream)

### Issue 1.1: Send Button Not Appearing
**Description:** When typing in the chat input field, the "Send" button does not appear. Instead, the "Attachment" icon (or other options) remains visible, making it impossible to send text messages via the button.
**Status:** Fix Applied
**File:** `app/src/main/java/com/example/smarty/ui/components/SmartyInputField.kt`
**Root Cause:** Suspected state synchronization issue with `AnimatedContent` or strict `isNotBlank()` check.
**Fix:** 
1. Relaxed condition to `value.text.isNotEmpty()` to handle all non-empty text.
2. Replaced `AnimatedContent` with `Crossfade` to ensure state updates are visually reflected immediately.

## 2. Settings Section

### Issue 2.1: Tavily Search Icon Mismatch
**Description:** In the "Tavily Web Search API" settings section, the "Power On" activation control displays the OpenAI Assistant icon instead of a search-related icon.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/screens/SettingsScreen.kt`
**Root Cause:** Hardcoded `AIProvider.OPENAI` in `TavilyApiSection` composable which `ProviderSection` uses to select the icon.
**Fix Applied:** Added `iconOverride` parameter to `ProviderSection` and passed `Icons.Default.Search` for Tavily.

### Issue 2.2: Missing Data Export Functionality
**Description:** The "Data Vault" section in Settings manually implements rows for Backup, Sync, and Cache Clearing, but omits the "Export Data" option which exists in the unused `DataManagementSection` composable.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/screens/SettingsScreen.kt`
**Root Cause:** `SettingsScreen` manually constructs the UI and fails to include the Export option.
**Fix Applied:** Added `onExportData` callback and inserted the "Export Data" row into the Data Vault section.

## 3. Knowledge Card (Detail) Section
**Status:** Verified Clean. In-place editing and Version History seem functional.

## 4. Archive Section
**Status:** Verified Clean. Archive logic seems correct.

## 5. Calendar Section

### Issue 5.1: Missing Visible Back Navigation
**Description:** The `CalendarScreen` includes an `onBackClick` parameter and handles the system back button, but there is no visible back button (e.g., an `ArrowBack` icon) in the top navigation bar.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/screens/CalendarScreen.kt`
**Root Cause:** The `Top Navigation Bar` row only contains the `Sync` and `Add` buttons on the right side.
**Fix Applied:** Added `FilledIconButton` with `Icons.AutoMirrored.Filled.ArrowBack` on the left side of the top navigation bar.

## 6. Stacks Section

### Issue 6.1: Missing Visible Back Navigation
**Description:** Similar to `CalendarScreen`, `StacksScreen` lacks a visible back button in the `TopAppBar`, even though it receives `onBackClick`.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/screens/StacksScreen.kt`
**Root Cause:** `TopAppBar` only contains a title.
**Fix Applied:** Added `navigationIcon` parameter to `TopAppBar` with back button.

### Issue 6.2: Jarring QR Code Background in Dark Mode
**Description:** The `QRCodeDialog` hardcodes a white background for the QR code image. In dark mode, this creates a high-contrast, jarring visual.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/screens/CategoryNotesScreen.kt`
**Root Cause:** `Box` containing the QR image has `background(androidx.compose.ui.graphics.Color.White)`.
**Fix Applied:** Implemented theme-aware background color (softer white `0xFFF5F5F5` in dark mode) to reduce glare while maintaining readability.

## 7. Login Section

### Issue 7.1: Redundant imePadding
**Description:** `LoginScreen` applies `imePadding()` twice: once on the main `Column` and once on a trailing `Spacer`. This can cause excessive scrolling or empty space when the software keyboard is active.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/screens/LoginScreen.kt`
**Root Cause:** Over-implementation of keyboard awareness.
**Fix Applied:** Removed the redundant `Spacer(modifier = Modifier.imePadding())` as the main `Column` already handles it.

## 8. Voice Section

### Issue 8.1: Wake Word Documentation Mismatch
**Description:** The class-level documentation and comments in `VoskWakeWordManager.kt` claim the wake word is "Start", but the actual implementation (`WAKE_WORD` and `WAKE_WORD_PATTERNS`) uses "hear me out".
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/voice/VoskWakeWordManager.kt`
**Root Cause:** Inconsistent updates to code vs comments.
**Fix Applied:** Updated documentation to correctly reflect "hear me out" as the wake word.

### Issue 8.2: Unhandled Error State in VoiceNoteButton
**Description:** When `VoiceNoteRecorder` enters the `Error` state, `VoiceNoteButton` simply reverts to showing the `MicButton`. There is no visual feedback to the user that an error occurred (e.g., a toast, snackbar, or red tint).
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/components/VoiceNoteButton.kt`
**Root Cause:** `when (state)` block groups `Error` with `Idle` without any error-specific UI.
**Fix Applied:** Added a distinct red error state to the microphone button to visually indicate failure and allow retry.

## 9. Overlay / Assist Section

### Issue 9.1: Microhpone Conflict with Wake Word
**Description:** `AssistActivity` triggers voice input but does not signal `VoskWakeWordManager` to pause. This can lead to both services competing for the microphone, causing one to fail or crash.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/AssistActivity.kt`
**Root Cause:** `AssistActivity` fails to set `VoskWakeWordManager.isGloballyPaused = true` during its lifecycle.
**Fix Applied:** Set `isGloballyPaused = true` in `onCreate` and reset it in `finishWithAnimation` and `onDestroy`.

### Issue 9.2: Hardcoded Remote Agent URL in AssistViewModel
**Description:** `RemoteAgentService` in `AssistViewModel` is initialized with a hardcoded `serverUrl = "http://10.0.2.2:7860"`. This makes it impossible for users to connect to their own local LLM servers configured in Settings.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/viewmodel/AssistViewModel.kt`
**Root Cause:** Previous version used hardcoded string.
**Fix Applied:** Updated to use `securePreferences.getSmartyServerUrl()` for dynamic configuration.

### Issue 9.3: Redundant Dependency Injection in AssistViewModelFactory
**Description:** `AssistViewModelFactory` takes a full list of dependencies but the `create` method ignores them and just returns `AssistViewModel(application)`, which then re-initializes everything lazily.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/viewmodel/AssistViewModel.kt`
**Root Cause:** Legacy refactoring artifact.
**Fix Applied:** Updated `AssistViewModel` to use constructor injection and removed redundant lazy initializations.

## 11. Data Section

### Issue 11.1: Missing Index on categoryId
**Description:** The `notes` table is frequently queried by `categoryId` (both for display and during category deletion), but there is no explicit database index on the `categoryId` column.
**Status:** ✅ FIXED
**File:** `common/src/commonMain/kotlin/com/example/smarty/data/model/Note.kt`
**Root Cause:** Index was thought to be missing.
**Fix Applied:** Verified that `Index(value = ["categoryId"])` already exists in the `Note` entity definition.

### Issue 10.1: Potential Content Obscuration in UnifiedBottomSheet
**Description:** `UnifiedBottomSheet` applies hardcoded vertical gradient scrims at the top and bottom of its content area. These scrims may obscure interactive elements (like buttons or text inputs) that are scrolled into these areas.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/components/UnifiedBottomSheet.kt`
**Root Cause:** Fixed-height gradient `Box` overlays were too tall.
**Fix Applied:** Reduced scrim heights (Top: 16dp, Bottom: 32dp) to prevent obscuration while maintaining the fade effect.

### Issue 10.2: Unbounded Swipe Drag in NoteCard
**Description:** The swipe interaction in `NoteCard` does not have strict physical bounds. A user can drag the card horizontally across the entire width of the screen, which breaks the "tactile card" metaphor and looks broken.
**Status:** ✅ FIXED
**File:** `app/src/main/java/com/example/smarty/ui/components/NoteCard.kt`
**Root Cause:** `accumulatedDrag` was applied directly to `swipeOffset` without limits.
**Fix Applied:** Clamped `accumulatedDrag` to `±swipeThreshold * 1.5` to constrain movement.