# Smarty Security Documentation

## Overview

This document outlines all security measures implemented in the Smarty notes application to protect user privacy, especially regarding AI access to private notes.

---

## Table of Contents

1. [Privacy Guard System](#privacy-guard-system)
2. [AI Access Control](#ai-access-control)
3. [Defense in Depth Architecture](#defense-in-depth-architecture)
4. [Chat History Security](#chat-history-security)
5. [Content Security Filter](#content-security-filter)
6. [Action-Level Security](#action-level-security)
7. [Known Limitations](#known-limitations)
8. [Security Best Practices for Users](#security-best-practices-for-users)

---

## Privacy Guard System

### Location
`app/src/main/java/com/example/smarty/util/PrivacyGuard.kt`

### Purpose
PrivacyGuard is the **ABSOLUTE SECURITY BARRIER** between AI and private notes. It is the ONLY gateway for AI to access notes.

### Privacy Flags

Notes can be marked private using two flags:

| Flag | Description |
|------|-------------|
| `isFullPrivacy` | Full privacy mode - no AI processing whatsoever |
| `excludeFromAiChat` | Excluded from AI chat context but may still be categorized |

A note is considered **private** if EITHER flag is `true`.

### Core Rules (NEVER MODIFY)

1. AI can NEVER read private notes
2. AI can NEVER write to private notes
3. AI can NEVER search private notes
4. AI can NEVER reference private notes
5. AI can NEVER know private notes exist
6. AI can NEVER modify private notes
7. AI can NEVER delete private notes
8. AI can NEVER archive private notes
9. AI can NEVER get count of private notes
10. AI can NEVER get any metadata about private notes

### Key Functions

```kotlin
// Check if a note is private
fun isPrivate(note: Note): Boolean

// Check if AI can access a note
fun isAiAccessible(note: Note): Boolean

// THE ONLY WAY for AI to get notes - filters all private notes
fun getAiVisibleNotes(notes: List<Note>): List<Note>

// Filter for AI search operations
fun filterForAiSearch(notes: List<Note>): List<Note>

// Filter for AI modification operations
fun filterForAiModification(notes: List<Note>): List<Note>

// Find note by ID - returns null if private
fun findByIdForAi(notes: List<Note>, noteId: String): Note?

// Validate AI can process a note
fun canAiProcess(note: Note): Boolean

// Throws SecurityException if note is private
fun requireAiAccess(note: Note, operation: String)

// Filter note IDs to only AI-accessible ones
fun filterNoteIds(noteIds: List<String>, allNotes: List<Note>): List<String>
```

---

## AI Access Control

### Entry Points

All AI access to notes flows through these controlled entry points:

#### 1. Chat Message Processing
**Location:** `CogniViewModel.sendChatMessage()`

```kotlin
// ABSOLUTE SECURITY BARRIER
val aiAccessibleNotes = PrivacyGuard.getAiVisibleNotes(notes.value)

val response = agentService.processUserMessage(
    userMessage = content,
    attachments = attachments,
    chatHistory = _chatMessages.value,
    allNotes = aiAccessibleNotes,  // Only non-private notes
    allCategories = categories.value
)
```

#### 2. Agent Service Note Selection
**Location:** `AgentService.selectRelevantNotes()`

```kotlin
// SECURITY BARRIER: Only AI-visible notes
val eligibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
```

#### 3. Description-Based Search
**Location:** `AgentService.findNoteByDescription()`

```kotlin
// Double protection: caller passes filtered list + function filters again
val eligible = PrivacyGuard.filterForAiSearch(notes)
```

---

## Defense in Depth Architecture

The system implements **3 layers of protection**:

### Layer 1: Entry Point Filtering
Before AI sees ANY notes, they are filtered:
```kotlin
PrivacyGuard.getAiVisibleNotes(notes.value)
```

### Layer 2: Action Validation
When AI requests an action on a specific note:
```kotlin
PrivacyGuard.findByIdForAi(notes.value, action.noteId)
```

### Layer 3: Execution Guard
Final check before any modification:
```kotlin
if (!PrivacyGuard.canAiProcess(note)) {
    Log.w(TAG, "SECURITY: Blocked AI operation on private note")
    return@let
}
```

### Protected Actions

All AI actions have double validation:

| Action | Layer 2 | Layer 3 |
|--------|---------|---------|
| DELETE_NOTE | `findByIdForAi` | `canAiProcess` |
| ARCHIVE_NOTE | `findByIdForAi` | `canAiProcess` |
| UNARCHIVE_NOTE | `findByIdForAi` | `canAiProcess` |
| UPDATE_NOTE | `findByIdForAi` | `canAiProcess` |
| SUMMARIZE_NOTE | `findByIdForAi` | `canAiProcess` |
| ADD_TODOS | `findByIdForAi` | `canAiProcess` |
| TOGGLE_TODO | `findByIdForAi` | `canAiProcess` |
| DELETE_TODO | `findByIdForAi` | `canAiProcess` |

---

## Chat History Security

### Location
`CogniViewModel.sendChatMessage()`

### Sanitization

Before saving AI responses to chat history, private note references are filtered:

```kotlin
val sanitizedResponse = response.copy(
    referencedNoteIds = response.referencedNoteIds.filter { noteId ->
        val note = notes.value.find { it.id == noteId }
        note != null && PrivacyGuard.isAiAccessible(note)
    }
)
```

### Chat Persistence Conditions

Chats are only saved when:
1. Valid session exists
2. API responded successfully (not demo mode)
3. API keys are configured
4. Both user message and assistant response have content

```kotlin
private fun shouldSaveChat(): Boolean {
    if (_currentSessionId.value == null) return false
    if (!lastApiCallSuccessful) return false
    if (!securePreferences.hasAnyApiKeys()) return false
    return true
}
```

---

## Content Security Filter

### Location
`app/src/main/java/com/example/smarty/util/ContentSecurityFilter.kt`

### Purpose
Prevents prompt injection attacks by sanitizing content before AI processing.

### Risk Levels

| Level | Action |
|-------|--------|
| `SAFE` | Content passes through |
| `MODIFIED` | Content sanitized, continues |
| `BLOCKED` | Content rejected entirely |

### Usage

```kotlin
val securityCheck = ContentSecurityFilter.sanitizeForChat(userMessage)
if (securityCheck.riskLevel == ContentSecurityFilter.RiskLevel.BLOCKED) {
    return ChatMessage(
        role = ChatRole.ASSISTANT,
        content = "Request blocked for security reasons."
    )
}
```

---

## Action-Level Security

### Batch Actions
`BATCH_ACTIONS` recursively calls `executeAgentAction()`, ensuring all sub-actions go through the same security validation.

### Category Notes
`GET_CATEGORY_NOTES` does not fetch notes at runtime - the AI only sees notes already filtered by `PrivacyGuard.getAiVisibleNotes()`.

### Share Privacy Mode
When sharing content in full privacy mode:
- "Let AI decide" category option is hidden
- Category chips are always visible for manual selection
- `letAIDecide` is forced to `false`

---

## Known Limitations

### Historical Chat Data Edge Case

**Scenario:** A note was public, discussed with AI, then later marked private.

**Impact:** Chat history from BEFORE the privacy change may contain information about that note.

**Mitigation:**
- Privacy flags are set at note creation, not typically changed after
- Users should delete chat history if they later decide to make notes private
- Future enhancement: Implement chat history scrubbing when privacy status changes

### Log Output

Note IDs appear in logs for debugging. In production builds, consider:
- Reducing log verbosity
- Hashing note IDs in logs
- Disabling debug logs entirely

---

## Security Best Practices for Users

1. **Mark sensitive notes as private at creation time** - Don't discuss them with AI first

2. **Use Full Privacy mode for highly sensitive content** - This ensures zero AI processing

3. **Delete chat history if you make notes private** - Prevents historical leakage

4. **Review API key security** - Keys are stored in encrypted SharedPreferences

5. **Keep the app updated** - Security patches are included in updates

---

## Security Logging

All security events are logged with the tag `PrivacyGuard`:

```
D/PrivacyGuard: BLOCKED: AI access denied for private note abc123...
W/PrivacyGuard: SECURITY: AI attempted to access private note by ID: abc123...
E/PrivacyGuard: SECURITY VIOLATION: AI attempted DELETE on private note abc123
```

---

## File References

| File | Purpose |
|------|---------|
| `util/PrivacyGuard.kt` | Core privacy barrier |
| `util/ContentSecurityFilter.kt` | Prompt injection protection |
| `viewmodel/CogniViewModel.kt` | Action execution with security checks |
| `data/remote/AgentService.kt` | AI service with filtered note access |
| `data/repository/ChatRepository.kt` | Chat persistence with smart saving |
| `ui/components/ShareBottomSheet.kt` | Privacy-aware sharing UI |

---

## Audit Checklist

- [ ] PrivacyGuard filters applied at all AI entry points
- [ ] Double validation (findByIdForAi + canAiProcess) on all actions
- [ ] Chat history sanitized before persistence
- [ ] Content security filter active on user input
- [ ] Share sheet respects privacy mode
- [ ] No private note IDs leak to chat history
- [ ] Batch actions recursively validated

---

## Version History

| Date | Version | Changes |
|------|---------|---------|
| 2024-12-18 | 1.0 | Initial security documentation |
| 2024-12-18 | 1.1 | Added double validation to all AI actions |
| 2024-12-18 | 1.2 | Chat history sanitization implemented |

---

## Contact

For security concerns or vulnerability reports, please contact the development team.

**This document should be updated whenever security-related changes are made to the codebase.**
