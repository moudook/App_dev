# Cogni Codebase Reference

Updated: 2025-12-17

## Do and Dont

### NEVER DO
- Never remove a function without checking all usages first via Grep
- Never change function signature without updating all call sites
- Never extract private composable without making it internal/public
- Never forget to add import when moving code to new file
- Never use `values()` on enum, use `entries` instead (Kotlin 1.9+)
- Never create file without reading existing similar files first
- Never assume package name, always check existing files in same folder
- Never edit multiple functions in one Edit call, do one at a time
- Never guess parameter order, read the function signature first
- Never remove import without verifying its unused via grep

### ALWAYS DO
- Always read file before editing
- Always grep for function/class name before removing it
- Always add `modifier: Modifier = Modifier` as last param in composables
- Always use `.asStateFlow()` when exposing MutableStateFlow
- Always use unique keys in LazyColumn items
- Always prefix duplicate keys like `"upcoming_${it.id}"`
- Always check companion object for constants before adding new ones
- Always run incremental edits, verify each before next
- Always keep original function signature when delegating to manager

### EXTRACTION PATTERN
When extracting code to new file:
1. Read source file fully
2. Create new file with correct package
3. Copy code to new file
4. Add all needed imports to new file
5. Add import in source file for new location
6. Remove old code from source
7. Grep to verify no broken references

## File Map

### Entry Points
```
MainActivity.kt -> NavHost -> Screens
```

### ViewModel Layer
```
CogniViewModel.kt (1350 lines) - main orchestrator
  uses-> CalendarManager.kt (340) - calendar ops
  uses-> CogniRepository.kt - data access
  uses-> AIService.kt - ai calls
  uses-> ContentTypeDetector.kt - type detection
```

### AI Layer
```
AIService.kt (650) - orchestrator
  uses-> AIProviderContract.kt - interface
  uses-> GeminiProvider.kt (245)
  uses-> OpenAICompatibleProvider.kt (265) - deepseek/groq/openai
  uses-> OpenRouterProvider.kt (225)
  uses-> HuggingFaceProvider.kt (250)
  uses-> AIResponseParser.kt (200) - json parsing
```

### UI Screens
```
InputStreamScreen.kt (830) - main notes/chat
  uses-> NoteCard.kt (670)
  uses-> InputBar.kt

CalendarScreen.kt (190) - calendar view
  uses-> calendar/AddEventDialog.kt (280)
  uses-> calendar/EventCard.kt (270)
  uses-> calendar/CalendarComponents.kt (240)

SettingsScreen.kt (580) - settings
  uses-> settings/AIProviderSection.kt (500)

StacksScreen.kt (450) - categorized notes
  uses-> NoteCard.kt
```

### Data Models Location
```
data/model/Note.kt - Note, NoteType, TodoItem
data/model/CalendarEvent.kt - CalendarEvent, EventPriority, EventCategory
data/model/ChatMessage.kt - ChatMessage, MessageRole
data/model/AIAnalysisResult.kt - AIAnalysisResult
data/local/AIProvider.kt - AIProvider enum
data/local/AIProviderConfig.kt - config data class
```

### Utility Location
```
util/ContentTypeDetector.kt - detectContentType(), extractTitle(), extractUrl()
data/remote/AIResponseParser.kt - extractJsonFromResponse(), parseAnalysisResponse()
ui/components/NoteCardIcons.kt - getTypeIcon(), getTypeColor()
```

## Common Fixes

### Error: Unresolved reference after move
```
Fix: Add import for new location
Check: grep -r "functionName" to find all usages
```

### Error: Type mismatch StateFlow
```
Wrong: val x: StateFlow<T> = _x
Right: val x: StateFlow<T> = _x.asStateFlow()
```

### Error: Composable cant be called
```
Cause: Parameter mismatch
Fix: Read target function signature, match exactly
```

### Error: Suspend function not in coroutine
```
In ViewModel: viewModelScope.launch { }
In Manager: scope.launch { } (inject scope in constructor)
```

### Error: Duplicate keys in LazyColumn
```
Fix: items(list, key = { "prefix_${it.id}" })
```

## Key Patterns Used

### Provider Pattern (AI)
```kotlin
interface AIProviderContract {
    suspend fun analyzeContent(...): AIAnalysisResult
    suspend fun chat(...): String
    suspend fun testConnection(...): Boolean
}
// Each provider implements this
```

### Manager Pattern (ViewModel decomp)
```kotlin
class SomeManager(
    private val repository: CogniRepository,
    private val scope: CoroutineScope
) {
    // StateFlows here
    // Methods launch coroutines via scope
}
// ViewModel delegates to manager, exposes manager's flows
```

### Extracted Composable Pattern
```kotlin
@Composable
fun ExtractedComponent(
    requiredParam: Type,
    optionalParam: Type = default,
    modifier: Modifier = Modifier  // always last
) {
    // Root composable gets modifier
}
```

## File Size Limits
- ViewModel: max 600 lines
- Screen: max 500 lines
- Component: max 350 lines
- Manager: max 400 lines
- Provider: max 300 lines

## Quick Checks Before Edit

### Before extracting function
```
1. grep function name - find all usages
2. check if private - will need visibility change
3. check all params - will need imports in new file
4. check return type - will need import in new file
```

### Before removing code
```
1. grep class/function name
2. verify zero usages outside current file
3. check for indirect usage via delegation
```

### Before changing signature
```
1. grep function name
2. count call sites
3. update ALL call sites in same edit session
```

## Package Structure
```
com.example.smarty/
  data/
    local/        - Room DB, DAOs, enums
    model/        - Data classes
    remote/       - API services
      providers/  - AI provider implementations
    repository/   - CogniRepository
  ui/
    screens/      - Full screen composables
      calendar/   - Calendar sub-components
      settings/   - Settings sub-components
    components/   - Reusable composables
    theme/        - Colors, Typography, Spacing
  viewmodel/
    managers/     - ViewModel helper classes
  util/           - Pure utility functions
```

## Delegation Pattern in ViewModel
```kotlin
// In CogniViewModel
private val calendarManager = CalendarManager(repository, viewModelScope)

// Expose manager's flows directly
val nextEvent: StateFlow<CalendarEvent?> = calendarManager.nextEvent

// Delegate methods
fun addCalendarEvent(...) = calendarManager.addEvent(...)
```

## Recent Session Summary

Created 10 files, modified 4 files.
Total lines moved: ~1900
Net reduction in large files: ~2000 lines

Key extractions:
- AIService providers -> 5 separate provider files
- CalendarManager <- CogniViewModel calendar logic
- Calendar UI <- CalendarScreen (3 files)
- AI Settings UI <- SettingsScreen (1 file)
