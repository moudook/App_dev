# COGNI AI AGENT - Complete Technical Documentation

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [File Structure](#file-structure)
3. [Agent Workflow](#agent-workflow)
4. [Available Tools](#available-tools)
5. [API Key Management](#api-key-management)
6. [System Prompt](#system-prompt)
7. [Response Parsing](#response-parsing)
8. [Tool Execution](#tool-execution)
9. [Current Issues & Fixes](#current-issues--fixes)
10. [Data Flow Diagram](#data-flow-diagram)

---

## Architecture Overview

The Cogni AI Agent is an **agentic AI system** that operates in a loop, capable of:
- Calling tools (actions) based on user requests
- Waiting for tool results
- Processing results and deciding next steps
- Providing final responses to users

### Core Components

| Component | File | Purpose |
|-----------|------|---------|
| Agent Service | `AgentService.kt` | Processes user messages, selects context, calls AI |
| AI Service | `AIService.kt` | Manages API calls to LLM providers (Gemini, OpenAI, etc.) |
| ViewModel | `CogniViewModel.kt` | Orchestrates agent loop, executes tools, manages UI state |
| Action Models | `AgentAction.kt` | Defines all possible agent actions/tools |
| Search Provider | `TavilySearchProvider.kt` | Handles web search via Tavily API |
| Audio Service | `AudioPlayerService.kt` | Handles audio playback |

### Agent Loop Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        USER INPUT                                │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CogniViewModel.sendChatMessage()              │
│  - Sanitizes input via ContentSecurityFilter                    │
│  - Selects relevant notes for context                           │
│  - Enters AGENT LOOP                                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                         AGENT LOOP                               │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 1. Call AgentService.processUserMessage()                   ││
│  │    - Uses REASONING API (Key 1)                             ││
│  │    - Returns JSON with action + response                    ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 2. Parse Response                                           ││
│  │    - Extract "action", "params", "response"                 ││
│  │    - Validate action type and parameters                    ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 3. Execute Tool (if action present)                         ││
│  │    - Call executeActionWithResult()                         ││
│  │    - Wait for tool completion                               ││
│  │    - Collect result data                                    ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 4. Feed Result Back to LLM                                  ││
│  │    - Format: "TOOL EXECUTION COMPLETED..."                  ││
│  │    - LLM processes result                                   ││
│  │    - Decides: another tool OR final response                ││
│  └─────────────────────────────────────────────────────────────┘│
│                              │                                   │
│                              ▼                                   │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ 5. Loop or Exit                                             ││
│  │    - If another action needed: continue loop                ││
│  │    - If no action: exit with final response                 ││
│  │    - Max iterations: 5 (prevents infinite loops)            ││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      FINAL RESPONSE TO USER                      │
└─────────────────────────────────────────────────────────────────┘
```

---

## File Structure

```
app/src/main/java/com/example/smarty/
├── data/
│   ├── model/
│   │   └── AgentAction.kt          # All action types (sealed class)
│   ├── remote/
│   │   ├── AgentService.kt         # Agent logic, system prompt, parsing
│   │   ├── AIService.kt            # LLM API calls, key management
│   │   ├── AIResponseParser.kt     # JSON response parsing
│   │   └── providers/
│   │       ├── TavilySearchProvider.kt  # Web search
│   │       ├── GeminiProvider.kt        # Google Gemini API
│   │       ├── OpenAICompatibleProvider.kt  # OpenAI/DeepSeek/Groq
│   │       └── ...other providers
│   └── local/
│       └── SecurePreferences.kt    # API key storage
├── viewmodel/
│   └── CogniViewModel.kt           # Agent loop execution
├── service/
│   └── AudioPlayerService.kt       # Audio playback
└── util/
    ├── ContentSecurityFilter.kt    # Input sanitization
    └── PrivacyGuard.kt             # Note privacy filtering
```

---

## Agent Workflow

### Step-by-Step Execution

#### 1. User Sends Message
```kotlin
// CogniViewModel.kt
fun sendChatMessage(content: String, attachments: List<Attachment>) {
    viewModelScope.launch {
        // Enter agent loop
        var currentMessage = content
        var iterationCount = 0
        val maxIterations = 5
```

#### 2. Agent Processes Message
```kotlin
// AgentService.kt
suspend fun processUserMessage(
    userMessage: String,
    attachments: List<Attachment>,
    chatHistory: List<ChatMessage>,
    allNotes: List<Note>,
    allCategories: List<Category>,
    mode: AgentMode = AgentMode.REASONING
): AgentChatResponse
```

#### 3. LLM Returns JSON Response
```json
{
  "action": "WEB_SEARCH",
  "params": {
    "query": "latest AI news",
    "reason": "User wants current information"
  },
  "response": "Let me search for that..."
}
```

#### 4. Tool Executed
```kotlin
// CogniViewModel.kt
suspend fun executeActionWithResult(action: AgentAction): ActionExecutionResult {
    val result = executeAction(action)
    return ActionExecutionResult(
        success = true,
        summary = "Action completed",
        resultData = result.joinToString("\n")
    )
}
```

#### 5. Result Fed Back to LLM
```kotlin
currentMessage = buildString {
    append("TOOL EXECUTION COMPLETED.\n\n")
    append("Original user request: $content\n\n")
    append("Tool executed: ${parsedAction.javaClass.simpleName}\n")
    append("Tool result:\n${actionResult.resultData}\n\n")
    append("Based on this result, either:\n")
    append("1. Execute another tool if needed\n")
    append("2. Provide a final response to the user")
}
```

---

## Available Tools

### Complete Tool Reference

| # | Tool Name | Purpose | Required Params | Optional Params |
|---|-----------|---------|-----------------|-----------------|
| 1 | `CREATE_NOTE` | Create new note | `content` | `title`, `category` |
| 2 | `SEARCH_NOTES` | Search notes | `query` | `category`, `limit` |
| 3 | `DELETE_NOTE` | Delete note permanently | `noteId` OR `description` | - |
| 4 | `ARCHIVE_NOTE` | Archive note | `noteId` OR `description` | - |
| 5 | `UNARCHIVE_NOTE` | Restore archived note | `noteId` OR `description` | - |
| 6 | `UPDATE_NOTE` | Modify note | `noteId` | `newContent`, `newTitle`, `newCategory` |
| 7 | `SUMMARIZE_NOTE` | Generate summary | `noteId` | - |
| 8 | `ADD_TODOS` | Add todo items | `noteId`, `todos` | - |
| 9 | `TOGGLE_TODO` | Toggle todo status | `noteId`, `todoId` | - |
| 10 | `DELETE_TODO` | Remove todo item | `noteId`, `todoId` | - |
| 11 | `LIST_CATEGORIES` | List all categories | - | - |
| 12 | `GET_CATEGORY_NOTES` | Get notes by category | `categoryName` | - |
| 13 | `ANSWER_QUESTION` | Direct response | - | `question` |
| 14 | `SUGGEST_ACTIONS` | Suggest next actions | `context` | - |
| 15 | `BATCH_ACTIONS` | Execute multiple actions | `actions` (array) | - |
| 16 | `WEB_SEARCH` | Search the web | `query`, `reason` | `topic`, `maxResults` |
| 17 | `PLAY_AUDIO` | Play audio file | `query` | `noteId`, `attachmentIndex`, `source` |

### Tool Definitions (AgentAction.kt)

```kotlin
sealed class AgentAction {
    data class CreateNote(
        val content: String,
        val title: String? = null,
        val category: String? = null
    ) : AgentAction()

    data class SearchNotes(
        val query: String,
        val category: String? = null,
        val limit: Int = 10
    ) : AgentAction()

    data class DeleteNote(
        val noteId: String? = null,
        val description: String? = null
    ) : AgentAction()

    data class WebSearch(
        val query: String,
        val reason: String,
        val topic: String = "general",  // general, news, finance
        val maxResults: Int = 5
    ) : AgentAction()

    data class PlayAudio(
        val query: String,
        val noteId: String? = null,
        val attachmentIndex: Int = 0,
        val source: String? = null
    ) : AgentAction()

    // ... other actions
}
```

### Tool JSON Formats

#### WEB_SEARCH
```json
{
  "action": "WEB_SEARCH",
  "params": {
    "query": "search terms",
    "reason": "why this search is needed",
    "topic": "general|news|finance",
    "maxResults": 5
  },
  "response": "User-facing message"
}
```

#### PLAY_AUDIO
```json
{
  "action": "PLAY_AUDIO",
  "params": {
    "query": "what to play",
    "noteId": "optional-direct-id",
    "attachmentIndex": 0
  },
  "response": "Playing..."
}
```

#### CREATE_NOTE
```json
{
  "action": "CREATE_NOTE",
  "params": {
    "content": "Note content here",
    "title": "Optional title",
    "category": "Idea"
  },
  "response": "Created your note."
}
```

---

## API Key Management

### Dual-API Architecture

The agent uses a **dedicated API key** separate from normal operations:

```
API Keys: [Key1, Key2, Key3, Key4]
           │      └─────────────────── Normal operations (note processing)
           └──────────────────────────── Agent ONLY (reasoning + tool execution)
```

### Key Rotation Logic

```kotlin
// AIService.kt

// AGENT CALLS - Use Key 1 ONLY
suspend fun agentReasoning(systemPrompt: String, userPrompt: String): String {
    val config = configs[provider] ?: continue
    val agentKey = config.apiKeys.firstOrNull() ?: continue  // KEY 1 ONLY
    // ...
}

// NORMAL CALLS - Use Keys 2, 3, 4... (cycle through)
suspend fun analyzeContent(content: String): AIResponse {
    val keysToTry = if (config.apiKeys.size > 1) {
        config.apiKeys.drop(1) + listOf(config.apiKeys.first())  // Try 2,3,4...then 1 as fallback
    } else {
        config.apiKeys
    }
    // ...
}
```

### Why Separate Keys?
1. **Rate Limiting**: Agent may make multiple rapid calls
2. **Isolation**: Agent failures don't affect note processing
3. **Cost Tracking**: Easier to track agent usage vs. note processing

---

## System Prompt

### Location
`AgentService.kt` - `AGENT_SYSTEM_PROMPT`

### Current System Prompt Structure

```
# COGNI AI AGENT - SYSTEM INSTRUCTIONS

## IDENTITY
- ACTION-ORIENTED agent
- EXECUTE operations directly
- NEVER hallucinate

## RESPONSE FORMAT - CRITICAL
{"action": "ACTION_NAME", "params": {...}, "response": "Human message"}

## AGENTIC WORKFLOW - HOW YOU OPERATE
1. RECEIVE REQUEST
2. DECIDE - TOOL OR DIRECT RESPONSE?
3. CALL TOOL (return JSON)
4. RECEIVE TOOL RESULT ("TOOL EXECUTION COMPLETED...")
5. RESPOND WITH RESULTS

## WEB_SEARCH TOOL - DETAILED INSTRUCTIONS
- Format, parameters, when to use, when not to use
- How to process JSON results
- Example flow

## PLAY_AUDIO TOOL - DETAILED INSTRUCTIONS
- Format, parameters, rules
- Prefer noteId when available

## ALL TOOLS REFERENCE
- Tables with format and required params

## ERROR HANDLING
- How to handle errors, empty results

## EXECUTION RULES
- Use exact note IDs
- Verify before delete
- No fabrication

## BEHAVIORAL RULES
- Be concise, direct, honest
- No emojis

## EXAMPLES
- Creating notes
- Web search with results
- Playing audio
- Handling empty results
```

---

## Response Parsing

### Parse Flow (AgentService.kt)

```kotlin
private fun parseResponse(response: String): Pair<String, AgentAction?> {
    var clean = response.trim()

    // Remove markdown code blocks
    if (clean.startsWith("```json")) {
        clean = clean.substringAfter("```json").substringBefore("```")
    }

    val jsonStart = clean.indexOf("{")
    val jsonEnd = clean.lastIndexOf("}")
    val jsonStr = clean.substring(jsonStart, jsonEnd + 1)

    val json = JsonParser.parseString(jsonStr).asJsonObject
    val responseText = json.get("response")?.asString ?: "Done."
    val actionType = json.get("action")?.asString
    val params = json.getAsJsonObject("params")

    val action = parseAction(actionType, params)
    return responseText to action
}
```

### Action Parsing with Validation

```kotlin
private fun parseAction(actionType: String?, params: JsonObject?): AgentAction? {
    return when (actionType) {
        "CREATE_NOTE" -> {
            val content = params?.get("content")?.asString
            if (content.isNullOrBlank()) return null  // Validation
            AgentAction.CreateNote(
                content = content.take(MAX_NOTE_CONTENT_LENGTH),
                title = params.get("title")?.asString?.take(MAX_TITLE_LENGTH),
                category = params.get("category")?.asString
            )
        }
        "WEB_SEARCH" -> {
            val query = params?.get("query")?.asString
            if (query.isNullOrBlank()) return null  // Validation
            AgentAction.WebSearch(
                query = query.take(MAX_SEARCH_QUERY_LENGTH),
                reason = params.get("reason")?.asString ?: "User requested",
                topic = params.get("topic")?.asString ?: "general",
                maxResults = (params.get("maxResults")?.asInt ?: 5).coerceIn(1, 10)
            )
        }
        // ... other actions
    }
}
```

---

## Tool Execution

### Execution Function (CogniViewModel.kt)

```kotlin
private suspend fun executeAction(action: AgentAction): List<String> {
    return when (action) {
        is AgentAction.CreateNote -> {
            val note = Note(
                title = action.title ?: extractTitle(action.content),
                content = action.content,
                type = detectContentType(action.content)
            )
            repository.insertNote(note)
            listOf(note.id)
        }

        is AgentAction.WebSearch -> {
            val apiKey = securePreferences.getTavilyApiKey()
            if (apiKey.isNullOrBlank()) {
                listOf("{\"status\":\"error\",\"error\":\"No API key\"}")
            } else {
                val searchResult = tavilySearchProvider.search(
                    apiKey = apiKey,
                    query = action.query,
                    maxResults = action.maxResults,
                    topic = action.topic
                )
                // Return formatted JSON results
                val resultsJson = buildString {
                    append("{\"status\":\"success\",")
                    append("\"query\":\"${action.query}\",")
                    searchResult.answer?.let {
                        append("\"ai_summary\":\"$it\",")
                    }
                    append("\"results\":[...]}")
                }
                listOf(resultsJson)
            }
        }

        is AgentAction.PlayAudio -> {
            // Find note, get audio attachment, start playback
            val targetNote = findAudioNote(action)
            targetNote?.let { note ->
                val track = createAudioTrack(note, action.attachmentIndex)
                AudioPlayerService.play(context, track)
                listOf(note.id)
            } ?: emptyList()
        }

        // ... other actions
    }
}
```

---

## Current Issues & Fixes

### FIXED Issues

| Issue | Root Cause | Fix Applied |
|-------|------------|-------------|
| Audio player random sound on init | Visualizer enabled before playback started | Moved `setupVisualizer()` to `onIsPlayingChanged()` with 150ms delay |
| Web search not returning results | `emptyList()` always returned, discarding results | Now returns formatted JSON with actual results |
| Hindi/multilingual text blocked | Unicode override pattern and broad emoji regex | Removed Unicode override filter, simplified emoji pattern |
| API keys rotating with agent key | Agent key included in normal rotation | Agent uses Key 1 only; normal ops use Keys 2,3,4... |

### Current Known Issues

| Issue | Status | Description |
|-------|--------|-------------|
| Agent may not always process tool results | Needs Testing | LLM should incorporate tool results in final response |
| Audio search fallback | Needs Testing | When noteId not available, search may fail |
| Batch actions | Needs Testing | Complex batch operations may timeout |

---

## Data Flow Diagram

```
┌──────────────┐     ┌──────────────────┐     ┌─────────────────┐
│   User       │────▶│  CogniViewModel  │────▶│  AgentService   │
│   Input      │     │  (Agent Loop)    │     │  (LLM Call)     │
└──────────────┘     └──────────────────┘     └─────────────────┘
                              │                        │
                              │                        ▼
                              │               ┌─────────────────┐
                              │               │   AIService     │
                              │               │  (API Keys)     │
                              │               └─────────────────┘
                              │                        │
                              │                        ▼
                              │               ┌─────────────────┐
                              │               │  LLM Provider   │
                              │               │ (Gemini/OpenAI) │
                              │               └─────────────────┘
                              │                        │
                              ▼                        ▼
                     ┌──────────────────┐     ┌─────────────────┐
                     │  Tool Execution  │◀────│  JSON Response  │
                     │                  │     │  {action, ...}  │
                     └──────────────────┘     └─────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│  Note Repository │ │ TavilySearch     │ │ AudioPlayer      │
│  (CRUD ops)      │ │ (Web Search)     │ │ Service          │
└──────────────────┘ └──────────────────┘ └──────────────────┘
          │                   │                   │
          └───────────────────┼───────────────────┘
                              ▼
                     ┌──────────────────┐
                     │  Result Data     │
                     │  (fed back to    │
                     │   LLM for next   │
                     │   iteration)     │
                     └──────────────────┘
```

---

## UI State Management

### DynamicIsland States (for Agent)

```kotlin
// DynamicIslandState.kt
sealed class DynamicIslandState {
    object Contracted : DynamicIslandState()

    // Agent states
    data class AgentThinking(val message: String) : DynamicIslandState()
    data class AgentExecutingTool(
        val toolName: String,
        val toolDisplayName: String,
        val elapsedSeconds: Int
    ) : DynamicIslandState()
    data class AgentWaitingForResult(val toolName: String) : DynamicIslandState()
    data class AgentCompleted(val toolsUsed: Int) : DynamicIslandState()
    data class AgentError(val message: String) : DynamicIslandState()
}
```

### State Updates During Agent Loop

```kotlin
// In agent loop
setAgentState(DynamicIslandState.AgentThinking("Analyzing..."))

// When executing tool
setAgentState(DynamicIslandState.AgentExecutingTool(
    toolName = action.javaClass.simpleName,
    toolDisplayName = getToolDisplayName(action),
    elapsedSeconds = 0
))

// When complete
showAgentCompleted(toolsUsedInRun.size)
```

---

## Security & Privacy

### Content Security Filter

```kotlin
// ContentSecurityFilter.kt
object ContentSecurityFilter {
    // Blocks dangerous content (weapons, hacking, etc.)
    private val DANGEROUS_CONTENT_PATTERNS = listOf(...)

    // Neutralizes prompt injection attempts
    private val INJECTION_PATTERNS = listOf(...)

    // Only removes actual control characters, NOT language text
    private val CONTROL_CHARS_PATTERN = Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]""")

    // Simplified emoji pattern - safe for all languages
    private val EMOJI_PATTERN = Regex(
        "[\uD83D\uDE00-\uD83D\uDE4F...]+"  // Only actual emojis
    )
}
```

### Privacy Guard

```kotlin
// PrivacyGuard.kt
object PrivacyGuard {
    // Filters out private notes from AI access
    fun getAiVisibleNotes(allNotes: List<Note>): List<Note> {
        return allNotes.filter { note ->
            !note.isFullPrivacy && !note.excludeFromAiChat
        }
    }
}
```

---

## Categories

Available note categories:
- `Learn` - tutorials, courses, educational
- `Read` - articles, blog posts, news
- `Watch` - videos, movies, streams
- `Idea` - thoughts, brainstorms, concepts
- `Todo` - tasks, reminders, action items
- `Buy` - shopping, products, wishlists
- `Meet` - contacts, appointments, events
- `Code` - programming, technical snippets
- `Quote` - memorable phrases, wisdom
- `Inspo` - creative inspiration, designs
- `Recipe` - food, cooking, ingredients
- `Health` - fitness, medical, wellness
- `Finance` - money, budgets, investments
- `Work` - professional, projects, career
- `Play` - entertainment, hobbies, fun
- `Note` - general (default)
- `Legal` - contracts, agreements

---

## Configuration

### Validation Constants (AgentService.kt)

```kotlin
private const val MAX_NOTE_CONTENT_LENGTH = 50000
private const val MAX_TITLE_LENGTH = 500
private const val MAX_CATEGORY_LENGTH = 100
private const val MAX_SEARCH_QUERY_LENGTH = 500
private const val MAX_DESCRIPTION_LENGTH = 1000
private const val MAX_TODO_LENGTH = 500
private const val MAX_TODOS_PER_ACTION = 50
private const val MAX_QUESTION_LENGTH = 2000
private const val MAX_CONTEXT_LENGTH = 5000
private const val MAX_BATCH_ACTIONS = 10
```

### Context Limits

```kotlin
private const val MAX_CONTEXT_CHARS = 12000
private const val MAX_NOTES_IN_CONTEXT = 20
private const val MAX_CHAT_HISTORY = 8
```

---

## Testing Checklist

### Agent Core
- [ ] Agent responds to simple questions
- [ ] Agent creates notes correctly
- [ ] Agent searches notes correctly
- [ ] Agent deletes/archives notes with confirmation

### Web Search
- [ ] Web search triggers on appropriate queries
- [ ] Results are returned as JSON
- [ ] LLM incorporates results in response
- [ ] Sources are cited with URLs
- [ ] Error handling works (no API key, network error)

### Audio Playback
- [ ] Audio plays without random sounds on init
- [ ] Agent can play audio by noteId
- [ ] Agent can search and play audio by query
- [ ] Visualizer works after playback starts

### Multi-language
- [ ] Hindi text passes through filter
- [ ] Mixed Hindi-English works
- [ ] Other languages (Arabic, Chinese) work

### API Keys
- [ ] Agent uses Key 1 only
- [ ] Note processing uses Keys 2, 3, 4...
- [ ] Key cycling works on failure

---

## Future Improvements

1. **Streaming Responses**: Stream agent responses for better UX
2. **Tool Chaining**: Better support for multi-step tool execution
3. **Memory System**: Long-term memory for user preferences
4. **Voice Input**: Speech-to-text for agent queries
5. **Proactive Suggestions**: Agent suggests actions based on context
6. **Calendar Integration**: Schedule events from notes
7. **Image Understanding**: Analyze images attached to notes

---

## Quick Reference

### Agent Call Flow
```
User Input → ContentSecurityFilter → AgentService → AIService → LLM
                                                                  ↓
User ← ChatMessage ← CogniViewModel ← Tool Execution ← JSON Response
```

### Key Files to Modify
- **Change tools**: `AgentAction.kt`, `CogniViewModel.kt` (execution)
- **Change prompts**: `AgentService.kt` (AGENT_SYSTEM_PROMPT)
- **Change API logic**: `AIService.kt`
- **Change UI states**: `DynamicIsland.kt`, `DynamicIslandState.kt`
