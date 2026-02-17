# Smarty Tools Improvement Plan

## Current Tools Analysis

### Overview of Existing Tools (25 tools)

| Category | Tools | Count |
|----------|-------|-------|
| Notes | create_note, search_notes, update_note, delete_note, archive_note | 5 |
| Calendar | schedule_event, list_events, delete_event | 3 |
| Timers | set_timer, set_alarm | 2 |
| Apps/Media | launch_app, take_screenshot, control_media, seek_media | 4 |
| Settings | toggle_setting | 1 |
| Web/Knowledge | web_search, query_knowledge | 2 |
| Context/Memory | store_context, update_context, delete_context | 3 |
| Navigation | navigate, share | 2 |
| Session | summarize_session | 1 |
| Future | generate_image | 1 |

---

## Issues Found

### ISSUE-001: Inconsistent Parameter Types
**Problem**: Tools use mixed types - some use UTC milliseconds (startTime), some use human strings (duration, time)
**Impact**: AI confused about what format to use, causes errors

### ISSUE-002: Unclear Tool Descriptions
**Problem**: Descriptions too brief, no examples
**Example**: "Save a new note/info." doesn't explain when to use vs other tools

### ISSUE-003: Missing Natural Language Time Parsing
**Problem**: schedule_event requires UTC ms, but AI often gets time calculations wrong
**Impact**: Events scheduled at wrong times, user frustration

### ISSUE-004: No Tool Discovery/Help
**Problem**: AI doesn't know all available tools, may miss useful ones
**Impact**: Suboptimal responses

### ISSUE-005: Redundant Tools
**Problem**: query_knowledge overlaps with search_notes
**Impact**: AI confused which to use

### ISSUE-006: Missing Common Tools
**Problem**: No tools for:
- Reading/playing music
- Getting weather
- Getting device info (battery, storage)
- Reading contacts
**Impact**: Limited functionality

---

## Best Practices (from Anthropic Research)

### 1. Simplicity Over Complexity
> "The most successful implementations use simple, composable patterns rather than complex frameworks."

**Apply**: Each tool should do ONE thing well.

### 2. Clear Tool Interface (ACI)
> "Think about how much effort goes into HCI, and plan to invest just as much effort in creating good agent-computer interfaces (ACI)."

**Apply**: Tool descriptions should be like great docstrings for junior developers.

### 3. Poka-yoke (Mistake-Proofing)
> "Change the arguments so that it is harder to make mistakes."

**Apply**: Use enums, natural language inputs, avoid calculations.

### 4. Format Close to Internet Text
> "Keep the format close to what the model has seen naturally occurring in text on the internet."

**Apply**: Accept natural language like "tomorrow at 3pm" instead of UTC ms.

### 5. Think Before Writing
> "Give the model enough tokens to 'think' before it writes itself into a corner."

**Apply**: Clear descriptions help model plan properly.

---

## Improvement Plans

### PLAN-001: Natural Language Time Parsing

**Priority**: HIGH
**Files**: ServerAgent.kt, CalendarRepository.kt

**Current**:
```kotlin
"startTime" to ToolProperty("number", "Start UTC ms")
```

**Proposed**:
```kotlin
ToolDefinition(
    name = "schedule_event",
    description = """Schedule a calendar event.
    
Examples:
- "schedule_event(title='Team meeting', when='tomorrow at 2pm', duration='1 hour')"
- "schedule_event(title='Doctor appointment', when='Friday 3pm', duration='30 minutes')"

Use natural language for times. The system handles timezone conversion automatically.
DO NOT calculate UTC timestamps yourself.
""",
    parameters = ToolParameters(
        properties = mapOf(
            "title" to ToolProperty("string", "Event title (e.g., 'Team meeting')"),
            "when" to ToolProperty("string", "When to schedule (e.g., 'tomorrow at 2pm', 'Friday 3pm', '2024-01-15 14:00')"),
            "duration" to ToolProperty("string", "How long (e.g., '1 hour', '30 minutes', '2h'). Default: 1 hour."),
            "description" to ToolProperty("string", "Optional extra details")
        ),
        required = listOf("title", "when")
    )
)
```

**Implementation**:
1. Add natural language time parser on server
2. Convert "tomorrow at 2pm" to UTC ms server-side
3. Remove startTime/endTime parameters, add "when" and "duration"

---

### PLAN-002: Consolidate Knowledge Tools

**Priority**: MEDIUM
**Files**: ServerAgent.kt

**Current**: `search_notes` and `query_knowledge` are confusing

**Proposed**: Merge into single smart search tool

```kotlin
ToolDefinition(
    name = "search_memory",
    description = """Search user's notes, facts, and memories.
    
Use this when:
- Looking up information the user previously saved
- Checking if something was already noted
- Finding past conversations or events

Returns relevant matches from notes, calendar events, and stored facts.
""",
    parameters = ToolParameters(
        properties = mapOf(
            "query" to ToolProperty("string", "What to search for"),
            "type" to ToolProperty(
                "string", 
                "Optional filter: 'notes', 'events', 'facts', or 'all' (default)",
                enum = listOf("notes", "events", "facts", "all")
            )
        ),
        required = listOf("query")
    )
)
```

---

### PLAN-003: Improve Tool Descriptions with Examples

**Priority**: HIGH
**Files**: ServerAgent.kt

**Current**: Descriptions too brief

**Proposed Pattern**:
```kotlin
ToolDefinition(
    name = "create_note",
    description = """Save information to the user's note library.

WHEN TO USE:
- User asks to save, remember, or note something
- User shares information they want to keep
- User says "note this" or "remember this"

WHEN NOT TO USE:
- User just wants a quick answer (just respond directly)
- Information is temporary or ephemeral

EXAMPLES:
✓ "Create a note about my WiFi password: hungry-cat-42"
✓ "Save this recipe for later"
✗ User asks "what's 2+2" → Just answer "4"

The note will be searchable later via search_memory.
""",
    parameters = ToolParameters(...)
)
```

---

### PLAN-004: Add Missing Common Tools

**Priority**: MEDIUM
**Files**: ServerAgent.kt, new tool implementations

**New Tools to Add**:

```kotlin
// Weather
ToolDefinition(
    name = "get_weather",
    description = """Get current weather for a location.
    
Returns temperature, conditions, and forecast.
If no location specified, uses user's current location.
""",
    parameters = ToolParameters(
        properties = mapOf(
            "location" to ToolProperty("string", "City name or 'current' for user's location")
        ),
        required = emptyList()
    )
)

// Device Info
ToolDefinition(
    name = "get_device_status",
    description = """Get device information like battery level, storage, etc.
    
Useful when user asks about their device status.
""",
    parameters = ToolParameters(
        properties = mapOf(
            "info" to ToolProperty(
                "string", 
                "What to check: 'battery', 'storage', 'network', or 'all'",
                enum = listOf("battery", "storage", "network", "all")
            )
        ),
        required = listOf("info")
    )
)

// Play Music (improved)
ToolDefinition(
    name = "play_music",
    description = """Play music on the device.

EXAMPLES:
- "play_music(query='Bohemian Rhapsody')"
- "play_music(query='jazz playlist')"
- "play_music(query='relaxing music', service='spotify')"

This launches the music app and starts playback.
""",
    parameters = ToolParameters(
        properties = mapOf(
            "query" to ToolProperty("string", "Song, artist, album, or playlist name"),
            "service" to ToolProperty("string", "Optional: 'spotify', 'youtube', 'apple' (default: user's preferred)")
        ),
        required = listOf("query")
    )
)
```

---

### PLAN-005: Simplify Timer/Alarm Tools

**Priority**: MEDIUM
**Files**: ServerAgent.kt

**Current**: set_timer and set_alarm are separate but similar

**Proposed**: Single unified tool

```kotlin
ToolDefinition(
    name = "set_reminder",
    description = """Set a timer, alarm, or reminder.

Use this for ALL time-based reminders:
- Countdown timers: "remind me in 10 minutes"
- Alarms: "wake me up at 7am"  
- Reminders: "remind me to call mom at 3pm"

The system figures out the right type automatically.
""",
    parameters = ToolParameters(
        properties = mapOf(
            "message" to ToolProperty("string", "What to remind about"),
            "when" to ToolProperty("string", "When: 'in 10 minutes', 'at 3pm', 'tomorrow morning'"),
            "repeat" to ToolProperty("string", "Optional: 'daily', 'weekdays', 'weekly', or omit for one-time")
        ),
        required = listOf("message", "when")
    )
)
```

---

### PLAN-006: Add Tool Categories/Namespacing

**Priority**: LOW
**Files**: ServerAgent.kt

**Problem**: 25 tools is a lot for AI to search through

**Proposed**: Group tools by category in system prompt

```
## Available Tools (organized by category)

### NOTES & MEMORY
- create_note: Save information
- search_memory: Find saved info
- update_note: Modify a note
- delete_note: Remove a note

### TIME & SCHEDULE  
- schedule_event: Add calendar event
- set_reminder: Timer/alarm/reminder
- list_events: Show upcoming events

### DEVICE CONTROL
- launch_app: Open an app
- control_media: Play/pause music
- get_device_status: Battery, storage, etc
- toggle_setting: WiFi, Bluetooth, flashlight

### INFORMATION
- web_search: Search the internet
- get_weather: Current weather

### NAVIGATION
- navigate: Switch screens
- share: Share content
```

---

### PLAN-007: Improve Error Messages

**Priority**: MEDIUM
**Files**: All tool implementations

**Current**: Generic errors like "Error: ..."

**Proposed**: Helpful, actionable errors

```kotlin
// Instead of:
return "Error: Invalid time format"

// Use:
return """I couldn't understand that time. Try saying it like:
- "tomorrow at 2pm"
- "Friday at 3:30"
- "in 2 hours"

What time did you mean?"""
```

---

## Implementation Priority Order

| Priority | Plan | Effort | Impact |
|----------|------|--------|--------|
| 1 | PLAN-003: Improve descriptions with examples | Low | High |
| 2 | PLAN-001: Natural language time parsing | Medium | High |
| 3 | PLAN-007: Improve error messages | Low | Medium |
| 4 | PLAN-002: Consolidate knowledge tools | Medium | Medium |
| 5 | PLAN-005: Simplify timer/alarm | Medium | Medium |
| 6 | PLAN-004: Add missing tools | Medium | Medium |
| 7 | PLAN-006: Tool categories | Low | Low |

---

## Tool-by-Tool Improvements

### create_note
- Add examples of when to use
- Add note about searchability
- Consider auto-categorization

### search_notes → search_memory
- Merge with query_knowledge
- Add type filter
- Better ranking

### schedule_event
- Natural language time input
- Remove UTC ms requirement
- Add duration string

### set_timer + set_alarm → set_reminder
- Merge into single tool
- Natural language time
- Support recurring

### launch_app
- Add common app aliases (spotify, music, camera)
- Fuzzy matching for package names

### toggle_setting
- Add more settings (dnd, airplane, location)
- Add status check capability

### web_search
- Add source preference
- Add date filter
- Better result formatting

### control_media
- Add volume control
- Add playback speed
- Add current track info

---

## Metrics to Track

1. **Tool Success Rate**: % of tool calls that complete successfully
2. **Tool Retry Rate**: How often AI retries failed tools
3. **Tool Usage Distribution**: Which tools are used most/least
4. **User Satisfaction**: Post-interaction feedback
5. **Error Categories**: What types of errors occur most

---

## Next Steps

1. Implement PLAN-003 (improve descriptions) - quick win
2. Implement PLAN-001 (natural time parsing) - high impact
3. Add telemetry for tool performance
4. A/B test new tool formats
5. Gather user feedback on tool improvements
