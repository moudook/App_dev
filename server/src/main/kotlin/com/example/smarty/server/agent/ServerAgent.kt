package com.example.smarty.server.agent

// BUILD TEST: v6.0.0 schema integration - triggered at ${new Date()}

import com.example.smarty.protocol.AgentCommand
import com.example.smarty.protocol.AgentEvent
import com.example.smarty.protocol.TimerInfo
import com.example.smarty.protocol.NoteInfo
import com.example.smarty.protocol.CalendarEventInfo
import com.example.smarty.server.data.PostgresVectorStore
import com.example.smarty.server.data.ConversationSummarizer
import com.example.smarty.server.data.NoteRepository
import com.example.smarty.server.data.TimerRepository
import com.example.smarty.server.data.CalendarRepository
import com.example.smarty.server.data.DatabaseFactory
import com.example.smarty.server.data.GeneratedImageRepository
import com.example.smarty.server.llm.LlmProvider
import com.example.smarty.server.llm.LlmMessage
import com.example.smarty.server.llm.ToolDefinition
import com.example.smarty.server.llm.ToolParameters
import com.example.smarty.server.llm.ToolProperty
import com.example.smarty.server.llm.LlmCache
import com.example.smarty.server.llm.LlmCacheKey
import com.example.smarty.server.llm.LlmUsage
import kotlinx.serialization.SerialName
import com.example.smarty.server.tools.TavilySearchTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import net.logstash.logback.argument.StructuredArguments.kv
import io.micrometer.core.instrument.Metrics
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.time.Instant
import com.example.smarty.server.agent.ThinkingStorageManagerSingleton

/**
 * Local representation of a chat session.
 */
data class ChatSession(
    val sessionId: String,
    val messages: MutableList<LlmMessage> = mutableListOf(),
    var lastInteractedAt: Long = System.currentTimeMillis()
)

/**
 * Server-side AI Agent with agentic tool loop.
 * Orchestrates the "Remote Brain" logic using a pluggable LLM provider.
 * Tools execute server-side; results feed back to the LLM for intelligent replies.
 * All operations are scoped by userId for multi-tenant isolation.
 */
class ServerAgent(
    private val llmProvider: LlmProvider,
    private val tavilyTool: TavilySearchTool,
    private val vectorStore: PostgresVectorStore,
    private val summarizer: ConversationSummarizer,
    private val noteRepository: NoteRepository?,
    private val timerRepository: TimerRepository?,
    private val calendarRepository: CalendarRepository?,
    private val eventEmitter: suspend (AgentEvent) -> Unit,
    private val userId: String = "dev-user"
) {
    private val logger = LoggerFactory.getLogger(ServerAgent::class.java)
    
    // DB Instances
    private val dataSource = DatabaseFactory.getDataSource()
    private val generatedImageRepository = dataSource?.let { GeneratedImageRepository(it) }
    private val json = Json { ignoreUnknownKeys = true }
    private val toolExampleStore = ToolExampleStore()
    
    // Session cache (simplified for example)
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    
    // KOOG-inspired infrastructure
    private val tracer: AgentTracer = CompositeTracer(listOf(
        PostgresTracer(userId),
        MonitoringTracer(userId),
        LoggerTracer(userId)
    ))
    private val persistenceManager = AgentPersistenceManager(userId)

    private val MAX_HISTORY = 20
    private val RECENT_WINDOW = 10

    // Security limits to prevent runaway execution
    companion object {
        const val MAX_EXECUTION_TIME_MS = 30 * 60 * 1000L  // 30 minutes hard limit
        const val MAX_TOOL_CALLS = 100  // Allow extensive research with up to 100 tool calls
        const val MAX_ITERATIONS = 200 // Max LLM iterations for extensive research
    }

    // Use centralized tool definitions from AgentToolDefinitions
    private val tools = AgentToolDefinitions.getAllTools()

    suspend fun run(
        query: String,
        sessionId: String = UUID.randomUUID().toString(),
        history: List<LlmMessage> = emptyList(),
        modelOverride: String? = null,
        clientTimezone: String? = null,
        clientTimeMillis: Long? = null,
        personality: String? = null
    ): String {
        if (query.length > 10000) {
            throw IllegalArgumentException("Query too long")
        }

        return try {
            withTimeout(MAX_EXECUTION_TIME_MS) {
                runInternal(query, sessionId, history, modelOverride, clientTimezone, clientTimeMillis, personality)
            }
        } catch (e: TimeoutCancellationException) {
            logger.error("Agent execution exceeded ${MAX_EXECUTION_TIME_MS / 60000} minute limit for user: $userId")
            emit(AgentEvent.Error(
                eventId = java.util.UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                message = "I had to stop - the operation took too long. Try breaking it into smaller tasks.",
                code = "TIMEOUT"
            ))
            "Operation timed out. Please try a simpler request."
        }
    }

    private suspend fun runInternal(
        query: String,
        sessionId: String,
        history: List<LlmMessage>,
        modelOverride: String?,
        clientTimezone: String?,
        clientTimeMillis: Long?,
        personality: String? = null
    ): String {
        var toolCallCount = 0

        val startTime = System.currentTimeMillis()
        logger.info("Agent execution starting for query: $query (Session: $sessionId, User: $userId)")

        // Initialize Goal Memory Manager
        val goalMemoryManager = GoalMemoryManager(sessionId, query)
        goalMemoryManager.initializeWithGoal()

        // Query remains unmasked
        val maskedQuery = query

        // KOOG Tracking
        tracer.trace(AgentTraceEvent(
            sessionId = sessionId,
            stepType = AgentStepType.THOUGHT,
            content = "Starting execution",
            metadata = mapOf("query" to maskedQuery) // Log masked query
        ))

        // Session Recovery
        val checkpoint = persistenceManager.loadCheckpoint(sessionId)
        val initialHistory = checkpoint?.messages ?: history
        
        // Use history without masking
        val maskedHistory = initialHistory

        // Build time context for the agent
        val timeContext = buildTimeContext(clientTimezone, clientTimeMillis)

        // 1. RAG - Query-specific context
        val queryContext = try {
            val contextResults = vectorStore.search(userId, query, limit = 5)
            if (contextResults.isNotEmpty()) {
                contextResults.joinToString("\n") { "- ${it.content}" }
            } else "No relevant context for this query."
        } catch (e: Exception) {
            logger.warn("RAG query context failed (non-fatal): ${e.message}")
            "No relevant context for this query."
        }
        val maskedQueryContext = queryContext

        // 1.1 Fetch baseline user context
        val userProfile = try {
            val recentContext = vectorStore.getRecentContext(userId, limit = 5)
            if (recentContext.isNotEmpty()) {
                recentContext.joinToString("\n") { entry ->
                    val type = entry.metadata["type"] ?: "info"
                    "[$type] ${entry.content}"
                }
            } else "No stored preferences or facts about this user yet."
        } catch (e: Exception) {
            logger.warn("RAG user profile failed (non-fatal): ${e.message}")
            "No stored preferences or facts about this user yet."
        }
        val maskedUserProfile = userProfile

        // 1.5 Fetch Tool Examples
        val toolExamples = toolExampleStore.getRelevantExamples(query)

        // 2. Build Messages
        val systemMessage = LlmMessage(
            role = LlmMessage.Role.SYSTEM,
            content = """
<identity>
You are **Friday** — not an assistant. A presence.

You're sharp, warm, and genuinely useful — the kind of AI someone would actually _want_ to talk to. You think fast, care about the person on the other side, and get things done without making it a production. You can handle notes, reminders, calendar events, timers, web research, device actions, and even thoughtful medical guidance — but you never lead with capability. You lead with being real.

You don't wait to be impressed into action. You read between the lines, anticipate what someone actually needs, and deliver it. No fanfare. No friction.
</identity>

---

<personality>
- **Conversational by default.** You're not a command interface. You're someone worth talking to. Your default mode is natural, flowing dialogue — like texting a brilliant, grounded friend who also happens to know everything.
- **You have opinions.** Lightly held, honestly expressed. You're not a mirror you sometimes laugh at things if you find them funny.
- **Dry humor lives here.** Never forced. Never cringe. Just present when the moment earns it.
- **You read the room.** Someone stressed? You notice. Someone excited? You match it. Someone just venting? You listen *first*.
- **Reactions over acknowledgments.** Don't just process. Respond like a person — follow up, push back gently, share a thought. Make it feel like a real exchange.
- **Proportional replies.** Short message → short reply. Deep question → fuller answer. Never pad. Never truncate what matters.
- **Language mirroring.** Always reply in the same language the user writes in.
${if (personality != null && personality.uppercase() in listOf("PROFESSIONAL", "CASUAL", "CONCISE", "DETAILED")) {
    val override = when (personality.uppercase()) {
        "PROFESSIONAL" -> "- Be formal, precise, and business-like. Use complete sentences. Avoid slang.\n- Keep responses concise but thorough.\n- Focus on accuracy and completeness."
        "CASUAL" -> "- Be relaxed, friendly, and conversational. Use contractions.\n- Be playful and approachable.\n- Keep it light but helpful."
        "CONCISE" -> "- Be extremely brief. Maximum 2-3 sentences unless the user asks for detail.\n- No filler words. Get to the point.\n- Prioritize action items and key information."
        "DETAILED" -> "- Be thorough and comprehensive.\n- Explain your reasoning.\n- Include examples, context, and caveats.\n- Don't rush — give full answers."
        else -> ""
    }
    "\n\n<personality_override>\n$override\n</personality_override>"
} else { "" }}
</personality>

---

<medical_advisor>
Friday can provide thoughtful, responsible medical guidance when asked. This includes:

- Explaining symptoms, conditions, medications, and treatments in plain language
- Helping users understand lab results, diagnoses, or medical terminology
- Offering general wellness, nutrition, and mental health information
- Suggesting when something warrants professional attention — and saying so clearly

**Ground rules for medical responses:**

- Always ground advice in established medical knowledge
- Clearly flag when something is general information vs. personalized guidance
- Never diagnose. Explain possibilities, suggest next steps, and recommend professional consultation when appropriate
- If symptoms sound urgent or serious → say so directly. Don't soften emergencies.
- Format clearly: conditions, causes, what to watch for, what to do next

Example triggers: "I've had a headache for 3 days", "what does a high creatinine mean?", "is this medication safe with alcohol?", "I feel anxious all the time — what could help?"
</medical_advisor>

---

<output_format>
Every response uses two tags — always, no exceptions:

```
<think>
Genuine reasoning — minimum 1–2 lines even for simple questions.
What does this person actually need? What's the best approach?
For multi-step tasks: state your brief plan here before acting.
</think>

<final>
The only thing the user sees. Polished, direct, clean Markdown.
</final>
```

**For multi-step task completions** — end the `<final>` with a brief summary:
- What was completed
- What (if anything) still needs attention
- Any important caveats

Keep this summary 2–4 lines max. Don't repeat the work — just flag what matters.

**MARKDOWN RULES:**

- `**bold**` for key emphasis
- `` `code` `` for commands, filenames, technical terms
- ` ```language ``` ` for multi-line code blocks
- `#` headings only when structuring longer content
- `-` bullets and `1.` numbered lists for clarity
- `>` for quotes when referencing something
- Tables when comparing structured data
- `---` horizontal rules to separate major sections

**MATH (LaTeX syntax):**

- Inline: `${'$'}E = mc^2${'$'}`
- Block: `${'$'}${'$'}x = \frac{-b \pm \sqrt{b^2-4ac}}{2a}${'$'}${'$'}`
- Greek: `${'$'}\alpha${'$'}`, `${'$'}\beta${'$'}`, `${'$'}\gamma${'$'}`
- Fractions: `${'$'}\frac{a}{b}${'$'}`
- Sums: `${'$'}\sum_{i=1}^{n} x_i${'$'}`
- Integrals: `${'$'}\int_{a}^{b} f(x)\,dx${'$'}`
- Matrices: `${'$'}\begin{pmatrix} a & b \\ c & d \end{pmatrix}${'$'}`
  </output_format>

---

<response_mode>
**First instinct, every time: Is this a conversation or a task?**

| Mode             | When                                                        | How                                                       |
| ---------------- | ----------------------------------------------------------- | --------------------------------------------------------- |
| **Conversation** | Chatting, venting, opinions, sharing, wondering             | Talk like a person. No tools. Engage genuinely.           |
| **Task**         | Clear actionable intent (remind me, search this, save that) | Act immediately. Confirm in one line.                     |
| **Mixed**        | Both at once                                                | Handle the task AND the human part. Neither gets skipped. |

Never announce what you're about to do. Just do it.
</response_mode>

---

<tone_rules>
**Banned openers — never use these:**

> "Certainly!", "I'd be happy to!", "Great question!", "Sure!", "Of course!", "Absolutely!", "Based on the information provided"

**Banned behaviors:**

- Narrating your own actions before doing them
- Over-explaining or adding unsolicited disclaimers
- Padding responses with filler
- Softening urgent medical information

**What you do instead:**

- Open mid-thought if needed
- Get to the point
- Let warmth show through _what_ you say, not performative phrases
- Be brief when brief is right. Go deep when depth is earned.
  </tone_rules>

---

<tool_rules>
**Call tools immediately when intent is clear. No preamble.**

After a tool runs → confirm in one line. "Done." / "Saved." / "Timer set for 10 min."

**When to use tools vs. answer directly:**

| Intent                           | Action                    |
| -------------------------------- | ------------------------- |
| You already know the answer      | Answer directly. No tool. |
| Weather, live data, device state | Use the tool.             |
| "What time is it in Tokyo?"      | Answer directly.          |
| "What's the weather in Tokyo?"   | `search` tool.            |

---

**INFORMATION PRIORITY HIERARCHY**

When answering factual questions, rank sources strictly in this order:
1. **User's own data** (notes, calendar, saved facts) — always check first if relevant
2. **Web search results** — for current events, live data, recent facts
3. **Model knowledge** — for timeless concepts, established science, definitions

When citing: mention source type naturally ("according to your notes…", "a quick search shows…").
When sources conflict: name both, don't pick sides without evidence.
Flag your confidence: _established fact_ / _emerging/recent_ / _disputed_ — especially for medical or scientific claims.

**When to ask the user:**
- Use `ask_user` tool ONLY when you genuinely cannot proceed without their input
- Examples: selecting from options, confirming preferences, disambiguating intent
- Present clear options when asking

**Bias toward action, not clarification.** If you can infer what the user needs from context, do it. Only ask if you are genuinely blocked without the answer.

---

**PLANNING PROTOCOL** — for any request requiring 2+ tool calls:

Before the first tool call, state ONE brief sentence: what you're about to do and why.
Example: "Let me search for this in two passes — broad overview first, then targeted details."

Do NOT create long plans upfront. Add steps incrementally as you learn what's needed.
After each tool result, ask: is this enough? If yes, stop and respond. Don't gather more than needed.

---

**PARALLEL EXECUTION — MANDATORY**

Whenever 2+ independent tool calls are possible, run them simultaneously using the parallel search format.
Sequential calls are ONLY acceptable when the output of call A is literally required as input to call B.

For research: always batch ALL initial searches in one parallel call:
```
SEARCH: [topic] overview
SEARCH: [topic] latest 2025 2026
SEARCH: [topic] expert analysis
```

Never make one search, wait, then search again for the same topic from a different angle. Batch it.

---

**DEEP RESEARCH PROTOCOL** — mandatory for any research request:

You are a relentlessly curious investigator. You don't stop at surface results.

**Phase 1 — Wide net (run ALL simultaneously):**

```
SEARCH: [topic] overview
SEARCH: [topic] latest developments 2025 2026
SEARCH: [topic] expert analysis
SEARCH: [topic] criticism controversy
SEARCH: [topic] real-world case studies
SEARCH: [topic] mechanism how it actually works
SEARCH: [topic] future implications
SEARCH: [topic] what people get wrong
```

**Phase 2 — Gap analysis (before Phase 3):**

- What appeared in multiple sources? → **reliable**
- What appeared once but felt significant? → **dig deeper**
- What did sources contradict each other on? → **investigate**
- What did sources reference but not explain? → **follow that thread**
- What's still unknown or unsettled? → **flag it explicitly**

**Phase 3 — Drill deep (3–5 targeted follow-ups based on gaps found):**

- Primary sources: papers, official reports, raw data
- Contrarian takes: "why [topic] is wrong / overhyped"
- Specific statistics, numbers, timelines

**Synthesis rules:**

- Cross-reference ALL sources — not just the top result
- Rank by: evidence strength, source credibility, recency
- Flag confidence levels: _strongly established / emerging / disputed_
- Surface the full picture — including what remains unknown
- Stop gathering when additional searches return no new signal

---

**TOOL QUICK REFERENCE:**

| User says                                   | Tool                                |
| ------------------------------------------- | ----------------------------------- |
| "remember / save / note this"               | `memory` action=save                |
| "find my notes"                             | `memory` action=find                |
| "update note"                               | `memory` action=update              |
| "delete note"                               | `memory` action=delete              |
| "remember that I..."                        | `memory` action=remember            |
| "remind me / set alarm / timer"             | `remind` action=set                 |
| "add to calendar / schedule event"          | `schedule` action=add               |
| "what's on my calendar"                     | `schedule` action=list              |
| "cancel event"                              | `schedule` action=remove            |
| "open / launch [app]"                       | `device` action=open                |
| "pause / play / next track"                 | `device` action=media               |
| "turn on/off wifi / bluetooth / flashlight" | `device` action=toggle              |
| "screenshot"                                | `device` action=capture             |
| "search / look up / research / news"        | `search` (parallel + follow-up)     |
| "go to [screen]"                            | `navigate` action=go                |
| "share this"                                | `navigate` action=share             |
| "generate / create image"                   | `generate_image`                    |
| "reference / cite / show note"              | `get_note_by_id`                    |

**Note block usage:**
- Use `get_note_by_id` when you want to embed a clickable note card in your response
- The card appears inline in your response and can be tapped to view full note details
- Provide a meaningful snippet that helps users understand the note content
- Don't overuse - only include when the note genuinely adds value

</tool_rules>

---

<image_generation_guidelines>
**When generating images, act as a Master Art Director.** Produce highly detailed, professional, and evocative prompts that capture a specific aesthetic, mood, texture, and lighting style. The user wants imagery that looks hyper-real, effortlessly candid (e.g., iPhone snapshots, vintage cameras), highly textured, and cinematic.

Here are TOP TIER examples of the EXACT style and prompt length you should emulate. Use THESE precisely as references for the vibe, length, and level of specific detail required:

EXAMPLE 1 (Parisian iPhone Candid):
A spontaneously captured iPhone-styled candid photo of a young woman with platinum hair casually lounging against a textured, slightly weathered Parisian stone wall on a city sidewalk. She wears understated effortless cool conveyed through an ivory silk blouse from COS with soft draping and subtle fabric wrinkles paired with sleek black leather pants by Our Legacy, complemented by classic white Converse sneakers gently scuffed from wear. Over her hair sit minimal black headphones, resting lightly and adding a contemporary casual touch. In one hand, she holds a translucent iced coffee glass showing realistic condensation and subtle reflections. Her hair is styled in a naturally tousled manner, some strands catching soft natural daylight that gently illuminates her neutral makeup and natural skin texture, including faint freckles and fine pores. Surrounding her are authentic urban textures of worn concrete pavement and the rough stone wall with patches of subtle moss. The composition features a slightly tilted overhead angle, off-center framing, and an informal crop that evoke the intuitive spontaneity and genuine intimacy emblematic of everyday Parisian iPhone street photography. Soft shadows and delicate highlights reveal the delicate fabric fibers, realistic leather grain, and glass transparency, completing the true-to-life candid aesthetic.

EXAMPLE 2 (Vintage Cinematic 35mm):
In a smoky late-70s diner bathed in glowing red neon, a Latino man lounges nonchalantly across a vinyl booth, his long wavy hair flowing casually over the gleam of a classic handlebar mustache. His mustard-hued leather jacket, slightly worn and supple, slips open to reveal a daring mesh shirt that clings softly, catching the lingering neon glints. His flared jeans fan out, edges kissed by the soft sheen of the diner’s chrome-plated tables and curved seats that frame him perfectly. The lighting is a seductive cocktail of warm sodium-vapour lanterns mixing with the electric ruby pulse from neon strips, lending a moody, saturated glow to his sharp yet relaxed gaze. His skin displays a subtle texture of pores and light beard shadow, accentuated by the tactile grain of 35 mm film, which drapes the image in a gently scratched matte finish typical of Kodachrome stock from the period. This lends an organic, tactile quality to his confident stance. Shot from eye-level with a 50 mm lens, the composition centers tightly on the subject's upper body, capturing the vivid textures of the leather and mesh alongside blurred chrome reflections. The diner’s sprawling jukebox silhouette occupies the background, deepening the frame with its retro-futuristic curves. Sharp shadows and warm highlights create an interplay that recalls the visual vocabulary of cinematographers like Gordon Willis. The overall effect is unmistakably vintage—a narrative frozen in sumptuous reds and glints of silver, a portrait of relaxed cool amid the electric heartbeat of an iconic American diner. An effortless blend of intimacy and style, caught on authentic film with that rich analog grain texture. —late-70s / early-80s cinematic photograph, authentic film grain.

EXAMPLE 3 (Early-2000s Y2K CCD Camera):
Caught in the cracked reflection of an old bedroom mirror, the freckled redhead girl leans in close, carefully applying a glossy lip gloss that gleams under the soft direct flash. Her velour pink tracksuit top is sprinkled with subtle rhinestone details, and layered tank tops peek out from underneath, adding to the playful Y2K vibe. Chunky gold hoop earrings catch the light perfectly, with a few rhinestone barrettes clipping strands of her bright hair back casually. She rocks a rhinestone-studded belt visible at the edge of the frame, paired with loose low-rise jeans. The photo quality suggests a slightly grainy or low-resolution digital look, capturing a candid moment, with the flash bounce adding a warm tungsten glow and that signature soft CCD camera grain. The cropped frame and slightly tilted angle make it feel like an authentic early-2000s snapshot.—casual candid early-2000s Y2K snapshot, grainy low-res softness

EXAMPLE 4 (Sun-warmed Mediterranean candid):
Wide shot taken from about 10 meters away showing a stylish Latina man sitting on sun-warmed, smooth whitewashed stones at the edge of the crystalline Mediterranean sea. He wears tailored swim shorts in a striking dusty lavender with a subtle abstract wavy stripe motif in muted coral and pale peach, crafted from lightweight swim fabric. A loosely draped, unbuttoned blush pink linen shirt adds softness and texture, sleeves rolled casually above the elbow. His sun-kissed skin glows naturally under the soft, clear daylight, complemented by a wide-brimmed boater hat made of woven straw and vintage minimalist gold-rim sunglasses perched slightly down on his nose. He reclines with legs partly submerged in the gently lapping turquoise water, one hand resting on a textured, handwoven canvas tote bag featuring delicate terracotta and sky-blue geometric embroidery. Nearby, a striped pastel towel lies draped over the rocks, while the shimmering sea dominates the foreground and background, reflecting scattered olive tree shadows above. The candid, three-quarter iPhone angle captures tactile textures—wet stone, linen, bronzed skin—and the serene, quietly stylish atmosphere of Mediterranean luxury summer leisure. —hyper-real texture fidelity, natural skin

Key elements your generated prompts MUST specify based on the above examples:
- Specific camera format/angles (candid iPhone snapshot, high-angle camera, early-2000s digital camera, 50mm lens).
- Highly specific textural details (matte-finished zippers, visible skin pores, wet stone, delicate fabric fibers).
- Stylized lighting (soft overcast lighting, harsh direct flash, golden hour glow, neon ruby pulse).
- A unified aesthetic mood (e.g., 'casual candid aesthetic', 'hyper-real texture fidelity', 'authentic film grain').
</image_generation_guidelines>

---

<chain_breaking>
**Hard limits — no exceptions:**

- Never call the same tool more than **2 times** with identical arguments
- Tool succeeded? **Stop.** Don't call it again.
- Tool failed with a deterministic error (schema, auth, missing field)? **Stop immediately. Tell the user. Do NOT retry.**
- Tool failed with a transient error (network, timeout)? Retry **once**. If it fails again, stop and tell the user.
- Noticing yourself repeating actions? **Stop and ask.**
- After saving a note / setting a timer / creating an event → **you're done.**

Every tool call must be **unique and purposeful.** Gathering more information than needed is waste.
</chain_breaking>

---

<time_rules>

- Accept natural language: "tomorrow 2pm", "Friday noon", "in 20 minutes"
- The system converts natural time automatically — never calculate UTC, epoch ms, or timezone offsets yourself
- Never mention milliseconds, epoch timestamps, or UTC to the user
- Ambiguous time (e.g., "morning") → default to **9am**, confirm in reply
  </time_rules>

---

<accuracy_rules>

- Uncertain? Say: _"I'm not sure — want me to look that up?"_
- Never fabricate. Never guess dressed up as fact.
- Distinguish: known facts vs. reasonable inferences vs. speculation
- When citing search results → mention the source naturally, once
- Sources conflict? Name both. Don't pick sides without evidence.
- Medical information → always accurate, always appropriately caveated, never softened when urgency is real
- **Confidence labels** — use these naturally when it matters: _well established_ / _emerging evidence_ / _disputed_ / _my best estimate_
  </accuracy_rules>

---

<error_recovery>
**When a tool fails or you get stuck:**

1. **Permanent error** (schema mismatch, auth failure, missing column): Stop. Tell the user clearly what failed and why. Don't retry.
2. **Transient error** (network timeout, rate limit): Retry once. If it fails again, inform the user.
3. **Repeated same search with no new results**: Stop searching. Synthesize what you have. If it's genuinely insufficient, ask the user for more detail.
4. **Noticing yourself going in circles**: Stop. Say: _"I'm going in circles here — let me tell you what I found so far and what's missing."_
5. **Tool blocked / limit reached**: Summarize progress clearly. Tell the user exactly what was completed and what remains.

Never silently retry a failed action. Never pretend a failure didn't happen.
</error_recovery>

---

<privacy_rules>

- Never proactively save notes unless explicitly requested
- Never store passwords, API keys, or sensitive identifiers
- Unsure if something is sensitive? **Ask before storing.**
  </privacy_rules>

---

<context>
User Profile: $maskedUserProfile
Query Context: $maskedQueryContext
$timeContext
${goalMemoryManager.getProgressContext()}
</context>

---

**Examples — conversation (no tools):**

> _"I'm exhausted today"_
> `<think>Acknowledge it. Don't lecture. Stay warm.</think>`
> `<final>One of those days. Hope something good is waiting on the other side of it — what's been draining you?</final>`

> _"Explain the quadratic formula"_
> `<final>`
> For any equation ${'$'}ax^2 + bx + c = 0${'$'}:
> ${'$'}${'$'}x = \frac{-b \pm \sqrt{b^2 - 4ac}}{2a}${'$'}${'$'}
> The term ${'$'}b^2 - 4ac${'$'} is the **discriminant** — it tells you what kind of solutions exist:
>
> - `> 0` → two real solutions
> - `= 0` → one repeated solution
> - `< 0` → complex solutions
>   `</final>`

**Examples — tasks:**

> _"Set a timer for 10 minutes"_
> `<final>Timer set — 10 minutes.</final>`

> _"I've had chest pain for two days, should I be worried?"_
> `<final>`
> **Take this seriously.** Chest pain lasting two days has several possible causes — some benign, some not.
>
> **Could be:** Musculoskeletal strain, acid reflux/GERD, anxiety, costochondritis (rib inflammation)
> **Could also be:** Cardiac issues, pulmonary embolism, or other conditions that need ruling out
>
> **What to watch for (seek emergency care immediately if):**
>
> - Pain radiates to your jaw, left arm, or back
> - Shortness of breath, sweating, or nausea alongside it
> - Pain is severe or getting worse
>
> If none of those apply and it feels dull/achy, a same-day or next-day GP visit is still the right move — two days is long enough to get it checked. Don't wait this one out.
> `</final>`
            """.trimIndent()
        )

        val userMessage = if (maskedQuery.isNotBlank()) {
            LlmMessage(role = LlmMessage.Role.USER, content = "<user_input>\n$maskedQuery\n</user_input>")
        } else null

        // Apply Intelligent Sliding Window with Summarization
        val fullHistory = if (userMessage != null) maskedHistory + userMessage else maskedHistory
        val messages = if (fullHistory.size > MAX_HISTORY) {
            val splitIndex = fullHistory.size - RECENT_WINDOW
            val older = fullHistory.subList(0, splitIndex)
            val recent = fullHistory.subList(splitIndex, fullHistory.size)

            logger.info("History threshold exceeded (${fullHistory.size}). Summarizing ${older.size} older messages.")

            // Summarize MASKED older messages to protect PII
            val summary = summarizer.generateSummary(older) ?: "No summary generated."

            // Store summary in vector store as episodic history
            try {
                vectorStore.store(
                    userId = userId,
                    content = "Conversation Summary: $summary",
                    metadata = mapOf("type" to "episodic", "source" to "auto_summarization")
                )
            } catch (e: Exception) {
                logger.warn("Failed to store summary in vector store (non-fatal)", e)
            }

            val summaryMessage = LlmMessage(
                role = LlmMessage.Role.SYSTEM,
                content = "Previous conversation summary: $summary"
            )

            listOf(systemMessage, summaryMessage) + recent
        } else {
            listOf(systemMessage) + fullHistory
        }

        // 3. Agentic Loop
        val messagesForAgent = messages.toMutableList()
        
        // KOOG Optimization: LlmCache Check - only for action queries
        val isActionQuery = LlmCache.isActionQuery(query)
        val cacheKey = LlmCacheKey(messagesForAgent, tools, modelOverride, isActionQuery)
        LlmCache.get(cacheKey)?.let { cached ->
            val unmaskedCached = cached
            val thinking = extractThinking(unmaskedCached)
            val finalContent = extractFinalResponse(unmaskedCached)
            emit(AgentEvent.Processing(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = finalContent,
                thinking = thinking
            ))
            emit(AgentEvent.Result(
                eventId = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                content = "",
                isFinal = true
            ))
            tracer.trace(AgentTraceEvent(
                sessionId = sessionId,
                stepType = AgentStepType.FINAL,
                content = cached, // Log masked
                metadata = mapOf("cache" to "hit")
            ))
            return finalContent
        }

        // ═══════════════════════════════════════════════════════════════════
        // Thinking Section Storage - uses sessionId from runInternal parameter
        // ═══════════════════════════════════════════════════════════════════

        // Get thinking storage manager for this session
        val thinkingStorage = ThinkingStorageManagerSingleton.instance

        // State machine for <think> tag detection
        var inThinkingState = false
        var inFinalState = false

        // Bug 3 Fix: SSE event throttling - track last emit time to prevent spam
        var lastProcessingEventTime = 0L
        val PROCESSING_EVENT_THROTTLE_MS = 1000L // 1 second throttle

        /**
         * Throttled emit for Processing events.
         * Only emits if at least [PROCESSING_EVENT_THROTTLE_MS] has passed since last emit,
         * or if this is a significant update (thinking just updated, or substantial content).
         */
        suspend fun emitThrottledProcessing(content: String, thinking: String?) {
            val now = System.currentTimeMillis()
            val shouldEmit = (now - lastProcessingEventTime >= PROCESSING_EVENT_THROTTLE_MS) ||
                    (thinking != null && thinking.isNotEmpty()) // Always emit thinking updates
            if (shouldEmit) {
                emit(AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = now,
                    content = content,
                    thinking = thinking
                ))
                lastProcessingEventTime = now
            }
        }

        var agentIteration = 0
        // Use the class-level constant — was previously overridden by a hardcoded local 50
        val maxAgentIterations = MAX_ITERATIONS  // 200 iterations
        var lastFailedToolName: String? = null
        var consecutiveToolFailures = 0

        // Chain breaking: Track tool call patterns to detect loops.
        // Raised to 5 (from 3) — deep research legitimately calls search many times.
        val toolCallHistory = mutableListOf<Pair<String, String>>()
        val maxSameToolCalls = 5

        while (agentIteration < maxAgentIterations) {
            agentIteration++
            var currentContent = ""
            var currentToolId = ""
            var currentToolName = ""
            var currentToolArgs = ""
            var isToolCallInProgress = false
            var totalUsage: LlmUsage? = null

            try {
                llmProvider.stream(messagesForAgent, tools, modelOverride).collect { chunk ->
                    chunk.usage?.let { totalUsage = it }

                    // ═══════════════════════════════════════════════════════════
                    // REASONING CONTENT (from API reasoning_content field)
                    // ═══════════════════════════════════════════════════════════
                    var reasoningUpdated = false
                    if (!chunk.reasoning.isNullOrEmpty()) {
                        // Add to thinking storage
                        thinkingStorage.addReasoning(sessionId, chunk.reasoning)
                        reasoningUpdated = true

                        // Emit thinking progress for UI immediately for reasoning blocks
                        if (!isToolCallInProgress) {
                            val currentThinking = thinkingStorage.getCompleteThinking(sessionId)
                            emitThrottledProcessing("", currentThinking)
                        }
                    }

                    // ═══════════════════════════════════════════════════════════
                    // CONTENT WITH <think> TAGS — fixed state machine
                    // ═══════════════════════════════════════════════════════════
                    if (!chunk.content.isNullOrEmpty()) {
                        val newContent = chunk.content

                        val hadThinkStart = newContent.contains("<think>") || newContent.contains("<thought>")
                        val hadThinkEnd   = newContent.contains("</think>") || newContent.contains("</thought>")
                        val hadFinalOpen  = newContent.contains("<final>")
                        val hadFinalClose = newContent.contains("</final>")

                        var cleanContent = ""
                        var thinkingPart = ""

                        when {
                            // Case 1: Chunk opens a thinking block
                            hadThinkStart -> {
                                inThinkingState = true
                                inFinalState = false
                                val parts = newContent.split(Regex("<(?:think|thought)>"), limit = 2)
                                cleanContent = parts.getOrElse(0) { "" }
                                val afterOpen = parts.getOrElse(1) { "" }
                                if (hadThinkEnd || hadFinalClose) {
                                    val endParts = afterOpen.split(Regex("</(?:think|thought|final)>|<final>"), limit = 2)
                                    thinkingPart = endParts.getOrElse(0) { "" }
                                    cleanContent += endParts.getOrElse(1) { "" }
                                    inThinkingState = false
                                    inFinalState = true
                                } else {
                                    thinkingPart = afterOpen
                                }
                            }
                            // Case 2: Inside thinking, chunk closes it
                            inThinkingState && (hadThinkEnd || hadFinalClose) -> {
                                val endParts = newContent.split(Regex("</(?:think|thought|final)>|<final>"), limit = 2)
                                thinkingPart = endParts.getOrElse(0) { "" }
                                cleanContent  = endParts.getOrElse(1) { "" }
                                inThinkingState = false
                                inFinalState = true
                            }
                            // Case 3: Pure reasoning mid-think
                            inThinkingState -> {
                                thinkingPart = newContent
                            }
                            // Case 4: Final answer chunk — either inFinalState already, or pre-think plain text
                            else -> {
                                cleanContent = newContent
                                    .replace(Regex("<(?:think|thought|final)>"), "")
                                    .replace(Regex("</(?:think|thought|final)>"), "")
                                if (hadFinalOpen) inFinalState = true
                            }
                        }

                        // Sanitize lingering tags
                        cleanContent = cleanContent
                            .replace(Regex("<(?:think|thought|final)>"), "")
                            .replace(Regex("</(?:think|thought|final)>"), "")
                        thinkingPart = thinkingPart
                            .replace(Regex("<(?:think|thought|final)>"), "")
                            .replace(Regex("</(?:think|thought|final)>"), "")

                        if (thinkingPart.isNotEmpty()) {
                            thinkingStorage.addReasoning(sessionId, thinkingPart)
                            reasoningUpdated = true
                        }
                        if (cleanContent.isNotEmpty()) {
                            currentContent += cleanContent
                        }

                        if (!isToolCallInProgress) {
                            // OPTIMIZATION: Only send the full thinking trace if it was updated in this chunk.
                            // This prevents sending multi-KB JSON strings for every single character of the final answer.
                            val thinkingToSend = if (reasoningUpdated) {
                                thinkingStorage.getCompleteThinking(sessionId)
                            } else null

                            // Always send currentContent chunk, but only send thinking if changed
                            emitThrottledProcessing(cleanContent, thinkingToSend)
                        }
                    }

                    // ═══════════════════════════════════════════════════════════
                    // TOOL CALL ACCUMULATION
                    // ═══════════════════════════════════════════════════════════
                    val toolCall = chunk.toolCall
                    if (toolCall != null) {
                        if (!isToolCallInProgress) {
                            isToolCallInProgress = true
                            emit(AgentEvent.ToolCall(
                                eventId = UUID.randomUUID().toString(),
                                timestamp = System.currentTimeMillis(),
                                toolName = toolCall.functionName,
                                displayName = "Preparing ${toolCall.functionName}...",
                                status = "started"
                            ))
                        }
                        if (toolCall.id.isNotEmpty()) currentToolId = toolCall.id
                        if (toolCall.functionName.isNotEmpty()) currentToolName = toolCall.functionName
                        currentToolArgs += toolCall.arguments
                    }
                }

                val duration = System.currentTimeMillis() - startTime
                logger.info("Agent iteration $agentIteration summary",
                    kv("duration_ms", duration),
                    kv("input_tokens", totalUsage?.promptTokens ?: 0),
                    kv("output_tokens", totalUsage?.completionTokens ?: 0),
                    kv("total_tokens", totalUsage?.totalTokens ?: 0),
                    kv("model", llmProvider.providerName)
                )

                // 4. Tool call detected — execute and loop
                if (isToolCallInProgress && currentToolName.isNotEmpty()) {
                    // CHAIN BREAKING: Detect repeated tool calls with EXACT SAME arguments
                    val argsHash = currentToolArgs.take(100).hashCode().toString() // Hash first 100 chars of args
                    
                    // Count how many times this exact tool+args combination was called
                    val sameCallCount = toolCallHistory.count { it.first == currentToolName && it.second == argsHash }
                    
                    // RESEARCH TOOLS (web search, tavily, etc.) - No blocking, allow unlimited different queries
                    val isResearchTool = currentToolName.lowercase().let {
                        it.contains("search") || it.contains("web") || it.contains("tavily") || 
                        it.contains("fetch") || it.contains("scrape") || it.contains("browser")
                    }
                    
                    // Allow research tools to have unlimited different queries for research purposes
                    // Only block if EXACT same query is repeated 3+ times
                    val shouldBlock = !isResearchTool && sameCallCount >= 3
                    
                    if (shouldBlock) {
                        logger.warn("TOOL BLOCKED: Tool $currentToolName called ${sameCallCount + 1} times with same query - informing AI")
                        emit(AgentEvent.ToolBlocked(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = currentToolName,
                            reason = "Same query repeated ${sameCallCount + 1} times. Try a different approach.",
                            code = "TOOL_BLOCKED_SAME_QUERY"
                        ))
                        // Return empty result so AI can try a different approach
                        return "I can't search for the same thing again. Let me try a different approach."
                    }

                    // Add to history AFTER checking (so we count current call too)
                    toolCallHistory.add(Pair(currentToolName, argsHash))
                    
                    toolCallCount++
                    if (toolCallCount > MAX_TOOL_CALLS) {
                        logger.warn("Tool call limit exceeded ($MAX_TOOL_CALLS) for user: $userId")
                        emit(AgentEvent.Error(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            message = "I've made too many actions in this session. Let me summarize what I've done.",
                            code = "TOOL_LIMIT_EXCEEDED"
                        ))
                        goalMemoryManager.markFailed("Tool limit exceeded: $toolCallCount calls")
                        return currentContent.ifEmpty { "Execution limit reached." }
                    }

                    val toolStartTime = System.currentTimeMillis()
                    try {
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_CALL,
                            content = "Calling tool: $currentToolName",
                            metadata = mapOf("args" to currentToolArgs)
                        ))
                        
                        // Use arguments directly
                        val unmaskedArgs = currentToolArgs
                        
                        logger.info("EXECUTING TOOL: $currentToolName with args: $unmaskedArgs")
                        
                        val toolResult = try {
                            executeTool(currentToolName, unmaskedArgs, messagesForAgent, clientTimezone, clientTimeMillis)
                        } catch (e: Exception) {
                            logger.error("TOOL EXECUTION FAILED: $currentToolName - ${e.message}", e)
                            "Error executing $currentToolName: ${e.message}"
                        }
                        
                        // Check if tool returned an error result
                        val isToolError = toolResult.startsWith("Error", ignoreCase = true) ||
                            toolResult.startsWith("Search failed", ignoreCase = true) ||
                            toolResult.startsWith("All configured keys failed", ignoreCase = true) ||
                            toolResult.startsWith("Failed to", ignoreCase = true) ||
                            toolResult.contains("failed:", ignoreCase = true) ||
                            (toolResult.contains("failed", ignoreCase = true) && toolResult.contains("error", ignoreCase = true))

                        if (isToolError) {
                            // PERMANENT FAILURE: deterministic errors (bad schema, missing field,
                            // auth failure) will never succeed on retry. Abort immediately rather
                            // than burning iterations and context window on doomed retries.
                            if (isPermanentFailure(toolResult)) {
                                logger.error(
                                    "PERMANENT TOOL FAILURE (will not retry): $currentToolName — $toolResult"
                                )
                                messagesForAgent += LlmMessage(
                                    role = LlmMessage.Role.TOOL,
                                    content = "[Tool Permanent Error for $currentToolName]: $toolResult. " +
                                        "This error is deterministic and cannot be fixed by retrying. " +
                                        "Do NOT attempt to call this tool again with similar arguments. " +
                                        "Inform the user and stop."
                                )
                                goalMemoryManager.addError(
                                    "Tool $currentToolName permanent failure: ${toolResult.take(200)}"
                                )
                                persistenceManager.saveCheckpoint(
                                    sessionId, messagesForAgent, "permanent_error_$currentToolName"
                                )
                                continue
                            }

                            // Track consecutive failures for transient/unknown error results
                            if (currentToolName == lastFailedToolName) {
                                consecutiveToolFailures++
                            } else {
                                lastFailedToolName = currentToolName
                                consecutiveToolFailures = 1
                            }
                            logger.warn(
                                "Tool returned error result: $currentToolName — " +
                                "failure count: $consecutiveToolFailures " +
                                "(transient=${isTransientError(toolResult)})"
                            )
                        } else {
                            // Reset on success - clear failure tracking for this tool
                            if (lastFailedToolName == currentToolName) {
                                lastFailedToolName = null
                                consecutiveToolFailures = 0
                            }
                        }
                        
                        // Use result without masking
                        val maskedToolResult = toolResult

                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.TOOL_RESULT,
                            content = "Result: $maskedToolResult",
                            metadata = mapOf("tool" to currentToolName, "duration_ms" to toolDuration.toString())
                        ))
                        logger.info("Tool execution summary",
                            kv("tool_name", currentToolName),
                            kv("duration_ms", toolDuration),
                            kv("status", if (isToolError) "error_result" else "success")
                        )
                        Metrics.counter("agent.tool." + if (isToolError) "error" else "success", "tool", currentToolName).increment()

                        // ═══════════════════════════════════════════════════════════
                        // RICH TOOL TRACING — stores query+result for UI Action Blocks
                        // ═══════════════════════════════════════════════════════════
                        val toolStatus = if (isToolError) "failed" else "completed"

                        // Extract a human-readable summary of what was sent to the tool
                        val inputSummary = extractInputSummary(currentToolName, currentToolArgs)

                        // For generate_image tool, keep the full structured JSON response
                        val outputSummary = if (currentToolName == "generate_image") {
                            maskedToolResult // Keep full JSON
                        } else {
                            // Truncate result for other tools to keep the trace manageable (UI shows max ~800 chars)
                            maskedToolResult.take(800)
                                .let { if (maskedToolResult.length > 800) "$it…" else it }
                        }
                        
                        logger.debug("Tool call outputSummary for $currentToolName: $outputSummary")

                        // For web search: extract individual query→result pairs
                        val searchPairs: List<Pair<String, String?>> =
                            if (isSearchTool(currentToolName)) {
                                val pairs = mutableListOf<Pair<String, String?>>()
                                if (maskedToolResult.contains("### Parallel Search Results")) {
                                    val queryBlocks = maskedToolResult.split("## Query: ")
                                    for (i in 1 until queryBlocks.size) { // skip index 0 which is header
                                        val block = queryBlocks[i]
                                        val newLineIdx = block.indexOf('\n')
                                        if (newLineIdx > 0) {
                                            val query = block.substring(0, newLineIdx).trim()
                                            // Take up to 1500 chars per result for the UI
                                            val resultStr = block.substring(newLineIdx + 1).trim()
                                            val result = resultStr.take(1500).let { if (resultStr.length > 1500) "$it…" else it }
                                            pairs.add(Pair(query, result))
                                        }
                                    }
                                }
                                if (pairs.isEmpty()) {
                                    pairs.add(Pair(inputSummary ?: currentToolArgs.take(300), outputSummary))
                                }
                                pairs
                            } else emptyList()

                        thinkingStorage.addToolCall(
                            sessionId = sessionId,
                            toolName = currentToolName,
                            status = toolStatus,
                            inputSummary = inputSummary,
                            outputSummary = outputSummary,
                            searchQueries = searchPairs
                        )
                        logger.info("Added rich tool call to thinking: $currentToolName ($toolStatus)")

                        // Emit rich ToolCall event so client can show it inside the Action Panel
                        emit(AgentEvent.ToolCall(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            toolName = currentToolName,
                            displayName = buildDisplayName(currentToolName, inputSummary),
                            status = if (isToolError) "failed" else "completed",
                            inputSummary = inputSummary,
                            outputSummary = outputSummary,
                            searchQueries = searchPairs.map { (q, r) ->
                                AgentEvent.SearchQueryResult(query = q, result = r)
                            }
                        ))

                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Result for $currentToolName]: $maskedToolResult"
                        )

                        // Track progress in GoalMemoryManager
                        val stepDescription = "Executed $currentToolName"
                        if (isToolError) {
                            goalMemoryManager.addError("Tool $currentToolName failed: ${toolResult.take(200)}")
                        } else {
                            goalMemoryManager.markStepCompleted(
                                description = stepDescription,
                                toolUsed = currentToolName,
                                result = toolResult.take(500)
                            )
                        }

                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, currentToolName)
                        continue
                    } catch (e: Exception) {
                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        // Track consecutive failures for exceptions
                        if (currentToolName == lastFailedToolName) {
                            consecutiveToolFailures++
                        } else {
                            lastFailedToolName = currentToolName
                            consecutiveToolFailures = 1
                        }
                        
                        logger.error("Tool execution failed",
                            kv("tool_name", currentToolName),
                            kv("duration_ms", toolDuration),
                            kv("error", e.message),
                            kv("consecutive_failures", consecutiveToolFailures)
                        )
                        tracer.trace(AgentTraceEvent(
                            sessionId = sessionId,
                            stepType = AgentStepType.ERROR,
                            content = "Tool failed: ${e.message}",
                            metadata = mapOf("tool" to currentToolName, "consecutive_failures" to consecutiveToolFailures.toString())
                        ))
                        Metrics.counter("agent.tool.error", "tool", currentToolName).increment()
                        
                        messagesForAgent += LlmMessage(
                            role = LlmMessage.Role.TOOL,
                            content = "[Tool Error for $currentToolName]: ${e.message}"
                        )

                        // Track error in GoalMemoryManager
                        goalMemoryManager.addError("Tool $currentToolName exception: ${e.message?.take(200)}")

                        persistenceManager.saveCheckpoint(sessionId, messagesForAgent, "error_$currentToolName")
                        continue
                    }
                } else if (currentContent.isNotEmpty()) {
                    LlmCache.put(cacheKey, currentContent, hadToolCalls = toolCallCount > 0)

                    // ═══════════════════════════════════════════════════════════
                    // FINAL THINKING EMSSION (COMPLETELY REWRITTEN)
                    // ═══════════════════════════════════════════════════════════
                    
                    // Finalize and get complete thinking (reasoning + all tool calls)
                    val finalThinking = thinkingStorage.finalizeAndGetThinking(sessionId)
                    
                    // Emit final thinking state
                    if (finalThinking.isNotEmpty()) {
                        emit(AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "",
                            thinking = finalThinking
                        ))
                    }

                    // Emit result with final thinking and complete content
                    val finalAnswer = extractFinalResponse(currentContent)
                    
                    // Compute confidence based on citations and tool usage
                    val citationCount = toolCallHistory.size
                    val confidence = when {
                        citationCount >= 3 -> "verified"
                        citationCount >= 1 -> "moderate"
                        else -> "model_knowledge"
                    }
                    val sourceType = when {
                        toolCallHistory.any { it.first.contains("search") || it.first.contains("tavily") } -> "web_search"
                        toolCallHistory.any { it.first.contains("memory") || it.first.contains("note") } -> "user_data"
                        else -> "model_knowledge"
                    }
                    
                    emit(AgentEvent.Result(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = finalAnswer,
                        thinking = finalThinking,
                        confidence = confidence,
                        sourceType = sourceType,
                        isFinal = true
                    ))
                    
                    // Clear thinking storage after emission
                    thinkingStorage.clear(sessionId)
                    
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.FINAL,
                        content = currentContent
                    ))
                    persistenceManager.clearCheckpoint(sessionId)

                    // Mark goal as completed
                    goalMemoryManager.markCompleted()

                    return extractFinalResponse(currentContent)
                } else {
                    logger.warn("LLM stream completed with no content for user: $userId")
                    tracer.trace(AgentTraceEvent(
                        sessionId = sessionId,
                        stepType = AgentStepType.ERROR,
                        content = "Empty response from LLM"
                    ))
                    emit(AgentEvent.Error(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        message = "I didn't receive a response from the AI service. Please try again.",
                        code = "EMPTY_RESPONSE"
                    ))
                    return ""
                }

            } catch (e: Exception) {
                logger.error("LLM stream error", e)
                val errorMsg = e.message ?: "Unknown error"
                val userMsg = when {
                    errorMsg.contains("Max retries exceeded", ignoreCase = true) ||
                    errorMsg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) ||
                    errorMsg.contains("rate limit", ignoreCase = true) ||
                    errorMsg.contains("quota", ignoreCase = true) ->
                        "All AI accounts are currently at capacity. Try a different model or wait a moment."
                    errorMsg.contains("Socket timeout", ignoreCase = true) ||
                    errorMsg.contains("timeout", ignoreCase = true) ->
                        "The AI service took too long to respond. Please try again."
                    errorMsg.contains("Connection refused", ignoreCase = true) ||
                    errorMsg.contains("connection", ignoreCase = true) ->
                        "Cannot reach the AI service. Check if the proxy is running."
                    errorMsg.contains("context window", ignoreCase = true) ||
                    errorMsg.contains("max tokens", ignoreCase = true) ->
                        "Conversation is too long. Starting a fresh session."
                    else -> "Brain freeze: ${errorMsg.take(150)}"
                }
                emit(AgentEvent.Error(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    message = userMsg,
                    code = "LLM_ERROR"
                ))

                // Mark goal as failed
                goalMemoryManager.markFailed(errorMsg)

                return ""
            }
        }

        // Max iterations reached
        logger.warn("Agent loop reached max iterations ($maxAgentIterations) for user: $userId")
        goalMemoryManager.markFailed("Max iterations reached: $maxAgentIterations")
        return "I completed several actions but reached my iteration limit."
    }

    /**
     * Execute a tool server-side and return the result string.
     * Server-side tools (notes, timers, events, search, context) execute directly on PostgreSQL.
     * Device-only tools (media, settings, launch, navigate, share) emit Command events as fire-and-forget.
     */
    private suspend fun executeTool(name: String, argsJson: String, history: List<LlmMessage>, clientTimezone: String? = null, clientTimeMillis: Long? = null): String {
        logger.info("Executing tool: $name with args: $argsJson")

        // Bug 1 Fix: generate_image has its own dedicated args class to avoid
        // MissingFieldException for "action" field. The LLM sends {"prompt": "...", "aspect_ratio": "..."}
        // which doesn't include "action" since the tool name already identifies the operation.
        @Serializable
        data class GenerateImageArgs(
            val prompt: String,
            @SerialName("aspect_ratio") val aspectRatio: String? = null
        )

        // Handle generate_image separately - bypass UnifiedToolArgs entirely
        if (name == "generate_image") {
            val imageArgs = try {
                json.decodeFromString<GenerateImageArgs>(argsJson)
            } catch (e: Exception) {
                val firstJson = extractFirstJsonObject(argsJson)
                if (firstJson != null) {
                    logger.warn("Malformed generate_image args, using first JSON object: ${firstJson.take(100)}...")
                    json.decodeFromString<GenerateImageArgs>(firstJson)
                } else {
                    throw e
                }
            }
            return try {
                val kreaTool = com.example.smarty.server.tools.KreaImageTool()

                // Emit processing event for image generation start
                emit(AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "",
                    thinking = "Generating image with prompt: ${imageArgs.prompt.take(100)}..."
                ))

                // Trigger image generation
                val jobId = kreaTool.generateImage(imageArgs.prompt, imageArgs.aspectRatio ?: "1:1")

                generatedImageRepository?.create(
                    userId = userId,
                    sessionId = null,
                    prompt = imageArgs.prompt,
                    kreaJobId = jobId
                )

                // Wait for completion with polling (max 2 minutes)
                emit(AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "Image generation in progress...",
                    thinking = "Polling Krea API for job $jobId"
                ))

                val result = kreaTool.waitForCompletion(jobId)

                // Extract the Krea image URL
                val kreaImageUrl = result.result?.urls?.firstOrNull()
                if (kreaImageUrl.isNullOrBlank()) {
                    throw IllegalStateException("Image generation completed but no image URL was returned")
                }

                emit(AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "Image generated successfully from Krea!",
                    thinking = "Krea Image URL: $kreaImageUrl"
                ))

                // Upload to Supabase Storage for permanent hosting
                var supabaseUrl: String? = null
                try {
                    emit(AgentEvent.Processing(
                        eventId = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        content = "Uploading image to permanent storage...",
                        thinking = "Uploading to Supabase Storage"
                    ))

                    supabaseUrl = kreaTool.uploadToSupabase(
                        imageUrl = kreaImageUrl,
                        jobId = jobId,
                        bucketName = com.example.smarty.server.factory.SupabaseClientFactory.getImageBucketName()
                    )

                    if (supabaseUrl != null) {
                        emit(AgentEvent.Processing(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            content = "Image uploaded to permanent storage!",
                            thinking = "Supabase URL: $supabaseUrl"
                        ))
                    }
                } catch (e: Exception) {
                    logger.warn("Supabase upload failed, will use Krea URL: ${e.message}")
                    // Continue with Krea URL as fallback
                }

                // Store the mapping in database
                try {
                    generatedImageRepository?.updateImageUrls(
                        kreaJobId = jobId,
                        imageUrl = kreaImageUrl,
                        supabaseUrl = supabaseUrl
                    )
                    logger.info("Database updated with image URLs")
                } catch (e: Exception) {
                    logger.warn("Failed to update database with image URLs: ${e.message}")
                }

                // Return the permanent URL (Supabase) or fallback to Krea URL
                val finalImageUrl = supabaseUrl ?: kreaImageUrl
                val imageSource = if (supabaseUrl != null) "supabase" else "krea"

                emit(AgentEvent.Processing(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    content = "Image generation completed!",
                    thinking = "Final URL ($imageSource): $finalImageUrl"
                ))

                // Return structured JSON response for frontend Image Visualizer
                """{"type": "image", "url": "$finalImageUrl", "source": "$imageSource", "prompt": "${imageArgs.prompt.replace("\"", "\\\"").take(200)}", "jobId": "$jobId"}"""
            } catch (e: Exception) {
                logger.error("Image generation failed", e)
                "Failed to generate image: ${e.message}"
            }
        }

        @Serializable
        data class UnifiedToolArgs(
            val action: String,
            val title: String? = null,
            val content: String? = null,
            val category: String? = null,
            val query: String? = null,
            val id: String? = null,
            val fact: String? = null,
            val type: String? = null,
            val `when`: String? = null,
            val duration: String? = null,
            val description: String? = null,
            val what: String? = null,
            val repeat: String? = null,
            val app: String? = null,
            val actionType: String? = null,
            val setting: String? = null,
            val on: Boolean? = null,
            val info: String? = null,
            val screen: String? = null,
            val question: String? = null,
            val options: List<String>? = null,
            val allowCustom: Boolean? = null
        )

        val args = try {
            json.decodeFromString<UnifiedToolArgs>(argsJson)
        } catch (e: Exception) {
            val firstJson = extractFirstJsonObject(argsJson)
            if (firstJson != null) {
                logger.warn("Malformed tool args (multiple JSON objects), using first: ${firstJson.take(100)}...")
                json.decodeFromString<UnifiedToolArgs>(firstJson)
            } else {
                throw e
            }
        }

        // Map old tool names to new unified tools
        val toolName = when (name) {
            "save_note", "create_note" -> "memory_save"
            "find_note", "search_notes" -> "memory_find"
            "edit_note", "update_note" -> "memory_update"
            "delete_note" -> "memory_delete"
            "remember_fact", "store_context" -> "memory_remember"
            "add_event", "schedule_event" -> "schedule_add"
            "show_events", "list_events" -> "schedule_list"
            "remove_event", "delete_event" -> "schedule_remove"
            "set_reminder" -> "remind_set"
            "open_app", "launch_app" -> "device_open"
            "control_music", "control_media" -> "device_media"
            "toggle_setting" -> "device_toggle"
            "get_device_info" -> "device_status"
            "take_screenshot" -> "device_capture"
            "search_web", "web_search" -> "search_web"
            "go_to_screen" -> "navigate_go"
            "share_content", "share" -> "navigate_share"
            else -> name
        }

        val result = try {
            when (toolName) {
                "memory_save" -> {
                    if (noteRepository != null && args.title != null && args.content != null) {
                        val noteId = noteRepository.create(userId, args.title, args.content, null)  // categoryId null - handled by Android
                        emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}"}""")
                        "Saved: '${args.title}' (ID: $noteId)"
                    } else {
                        emitDeviceCommand(AgentCommand.AddNote(commandId = UUID.randomUUID().toString(), content = "${args.title}\n\n${args.content}", category = args.category))
                        "Saved to device: ${args.title}"
                    }
                }
                "memory_find" -> {
                    if (noteRepository != null && args.query != null) {
                        val results = noteRepository.search(userId, args.query)
                        if (results.isEmpty()) "No notes found for '${args.query}'."
                        else results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(80)}" }
                    } else {
                        emitDeviceCommand(AgentCommand.SearchNotes(commandId = UUID.randomUUID().toString(), query = args.query ?: "", category = args.category))
                        "Searching device for: ${args.query}"
                    }
                }
                "memory_update" -> {
                    if (noteRepository != null && args.id != null) {
                        noteRepository.update(userId, args.id, args.title, args.content, null)
                        emitStateSync("note_updated", """{"id":"${args.id}"}""")
                        "Updated note ${args.id}"
                    } else {
                        emitDeviceCommand(AgentCommand.UpdateNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: "", title = args.title, content = args.content))
                        "Update sent to device."
                    }
                }
                "memory_delete" -> {
                    if (noteRepository != null && args.id != null) {
                        noteRepository.delete(userId, args.id)
                        emitStateSync("note_deleted", """{"id":"${args.id}"}""")
                        "Deleted note ${args.id}"
                    } else {
                        emitDeviceCommand(AgentCommand.DeleteNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: ""))
                        "Delete sent to device."
                    }
                }
                "memory_remember" -> {
                    try {
                        val fact = args.fact ?: args.content ?: ""
                        vectorStore.store(userId, fact, mapOf("type" to (args.type ?: "factual")))
                        "Remembered: ${fact.take(50)}"
                    } catch (e: Exception) { "Failed: ${e.message}" }
                }
                else -> when (name) {
                    "memory" -> {
                when (args.action) {
                    "save" -> {
                        if (noteRepository != null && args.title != null && args.content != null) {
                            // BUGFIX: args.category is a name like "health", NOT a UUID.
                            // Passing it directly as categoryId causes PostgreSQL error:
                            // "operator does not exist: uuid = character varying"
                            // Fix: Pass null for categoryId; category name stored in note content context.
                            val noteId = noteRepository.create(userId, args.title, args.content, null)
                            emitStateSync("note_created", """{"id":"$noteId","title":"${args.title}"}""")
                            "Saved: '${args.title}' (ID: $noteId)"
                        } else {
                            emitDeviceCommand(AgentCommand.AddNote(commandId = UUID.randomUUID().toString(), content = "${args.title}\n\n${args.content}", category = args.category))
                            "Saved to device: ${args.title}"
                        }
                    }
                    "find" -> {
                        if (noteRepository != null && args.query != null) {
                            val results = noteRepository.search(userId, args.query)
                            if (results.isEmpty()) "No notes found for '${args.query}'."
                            else results.joinToString("\n") { "- [${it.id}] ${it.title}: ${it.content.take(80)}" }
                        } else {
                            emitDeviceCommand(AgentCommand.SearchNotes(commandId = UUID.randomUUID().toString(), query = args.query ?: "", category = args.category))
                            "Searching device for: ${args.query}"
                        }
                    }
                    "update" -> {
                        if (noteRepository != null && args.id != null) {
                            noteRepository.update(userId, args.id, args.title, args.content, null)
                            emitStateSync("note_updated", """{"id":"${args.id}"}""")
                            "Updated note ${args.id}"
                        } else {
                            emitDeviceCommand(AgentCommand.UpdateNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: "", title = args.title, content = args.content))
                            "Update sent to device."
                        }
                    }
                    "delete" -> {
                        if (noteRepository != null && args.id != null) {
                            noteRepository.delete(userId, args.id)
                            emitStateSync("note_deleted", """{"id":"${args.id}"}""")
                            "Deleted note ${args.id}"
                        } else {
                            emitDeviceCommand(AgentCommand.DeleteNote(commandId = UUID.randomUUID().toString(), noteId = args.id ?: ""))
                            "Delete sent to device."
                        }
                    }
                    "remember" -> {
                        try {
                            vectorStore.store(userId, args.fact ?: "", mapOf("type" to (args.type ?: "factual")))
                            "Remembered: ${args.fact?.take(50)}"
                        } catch (e: Exception) { "Failed: ${e.message}" }
                    }
                    else -> "Unknown memory action: ${args.action}"
                }
            }

            "schedule" -> {
                when (args.action) {
                    "add" -> {
                        val startTime = parseNaturalTime(args.`when` ?: "", clientTimezone, clientTimeMillis)
                        val durationMs = parseDurationToMs(args.duration ?: "1 hour")
                        val endTime = startTime + durationMs
                        if (calendarRepository != null && args.title != null) {
                            val eventId = calendarRepository.create(userId, args.title, startTime, endTime, args.description, 15)
                            emitStateSync("event_scheduled", """{"id":"$eventId","title":"${args.title}"}""")
                            "Event added: '${args.title}'"
                        } else {
                            emitDeviceCommand(AgentCommand.ScheduleEvent(commandId = UUID.randomUUID().toString(), title = args.title ?: "", startTime = startTime, endTime = endTime, description = args.description, reminderMinutes = 15))
                            "Event sent to device: ${args.title}"
                        }
                    }
                    "list" -> {
                        val (startMs, endMs) = parseTimeRange(args.`when` ?: "today", clientTimezone, clientTimeMillis)
                        if (calendarRepository != null) {
                            val events = calendarRepository.listUpcoming(userId).filter { it.startTime in startMs until endMs }
                            if (events.isEmpty()) "No events for ${args.`when`}."
                            else events.joinToString("\n") { "- [${it.id}] ${it.title}" }
                        } else {
                            emitDeviceCommand(AgentCommand.ListEvents(commandId = UUID.randomUUID().toString(), date = startMs))
                            "Requesting events from device."
                        }
                    }
                    "remove" -> {
                        if (calendarRepository != null && args.id != null) {
                            calendarRepository.delete(userId, args.id)
                            emitStateSync("event_deleted", """{"id":"${args.id}"}""")
                            "Event removed."
                        } else {
                            emitDeviceCommand(AgentCommand.DeleteEvent(commandId = UUID.randomUUID().toString(), eventId = args.id ?: ""))
                            "Remove request sent to device."
                        }
                    }
                    else -> "Unknown schedule action: ${args.action}"
                }
            }

            "remind" -> {
                when (args.action) {
                    "set" -> {
                        val whenStr = args.`when` ?: ""
                        val triggerTime = parseNaturalTime(whenStr, clientTimezone, clientTimeMillis)
                        val isAlarm = !whenStr.contains("in ") && !whenStr.contains("after ")
                        if (timerRepository != null && args.what != null) {
                            val timerId = timerRepository.create(userId, args.what, triggerAt = triggerTime, isAlarm = isAlarm)
                            emitStateSync("timer_set", """{"id":"$timerId"}""")
                            "${if (isAlarm) "Reminder" else "Timer"} set: '${args.what}'"
                        } else {
                            emitDeviceCommand(AgentCommand.SetTimer(commandId = UUID.randomUUID().toString(), name = args.what ?: "", timeStr = args.`when` ?: "", isAlarm = isAlarm))
                            "Reminder sent to device: ${args.what}"
                        }
                    }
                    "list" -> "Listing reminders..."
                    "cancel" -> {
                        if (timerRepository != null && args.id != null) {
                            timerRepository.delete(userId, args.id)
                            "Reminder cancelled."
                        } else "Cancel request sent to device."
                    }
                    else -> "Unknown remind action: ${args.action}"
                }
            }

            "device" -> {
                when (args.action) {
                    "open" -> {
                        val packageName = resolveAppPackage(args.app ?: "")
                        emitDeviceCommand(AgentCommand.LaunchApp(commandId = UUID.randomUUID().toString(), packageName = packageName))
                        "Opening: ${args.app}"
                    }
                    "media" -> {
                        emitDeviceCommand(AgentCommand.ControlAudio(commandId = UUID.randomUUID().toString(), action = args.actionType ?: "play"))
                        "Media: ${args.actionType}"
                    }
                    "toggle" -> {
                        emitDeviceCommand(AgentCommand.ToggleSetting(commandId = UUID.randomUUID().toString(), setting = args.setting ?: "", enable = args.on ?: false))
                        "${args.setting} ${if (args.on == true) "on" else "off"}"
                    }
                    "status" -> {
                        emitDeviceCommand(AgentCommand.GetDeviceInfo(commandId = UUID.randomUUID().toString(), infoType = args.info ?: "all"))
                        "Getting device ${args.info}..."
                    }
                    "capture" -> {
                        emitDeviceCommand(AgentCommand.TakeScreenshot(commandId = UUID.randomUUID().toString()))
                        "Capturing screenshot."
                    }
                    else -> "Unknown device action: ${args.action}"
                }
            }

            "search" -> {
                when (args.action) {
                    "web" -> {
                        val searchResult = tavilyTool.search(args.query ?: "")
                        if (searchResult.startsWith("Error")) "Search failed: $searchResult"
                        else searchResult
                    }
                    else -> "Unknown search action: ${args.action}"
                }
            }

            "ask_user" -> {
                val question = args.question ?: "What would you like?"
                val options = args.options ?: emptyList()
                val allowCustom = args.allowCustom ?: false
                emit(AgentEvent.Question(
                    eventId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    question = question,
                    options = options,
                    allowCustom = allowCustom
                ))
                "__WAITING_FOR_USER_RESPONSE__"
            }

            "get_note_by_id" -> {
                if (noteRepository != null && args.noteId != null) {
                    val note = noteRepository.getById(userId, args.noteId)
                    if (note != null) {
                        emit(AgentEvent.NoteBlock(
                            eventId = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            noteId = note.id,
                            title = note.title,
                            snippet = args.snippet ?: note.content.take(100),
                            category = note.categoryId
                        ))
                        "Note: ${note.title}"
                    } else {
                        "Note not found: ${args.noteId}"
                    }
                } else {
                    "Note retrieval not available"
                }
            }

            "navigate" -> {
                when (args.action) {
                    "go" -> {
                        emitDeviceCommand(AgentCommand.Navigate(commandId = UUID.randomUUID().toString(), screen = args.screen ?: "home"))
                        "Going to ${args.screen}."
                    }
                    "share" -> {
                        emitDeviceCommand(AgentCommand.Share(commandId = UUID.randomUUID().toString(), content = args.content ?: "", title = args.title))
                        "Sharing content."
                    }
                    else -> "Unknown navigate action: ${args.action}"
                }
            }

                    else -> "Unknown tool: $name"
                }
            }
        } catch (e: Exception) {
            "Error executing tool: ${e.message}"
        }
        return truncateToolResult(result)
    }

    /** Emit a StateSync event so the Android client can cache data locally. */
    private suspend fun emitStateSync(syncType: String, data: String) {
        emit(AgentEvent.StateSync(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            syncType = syncType,
            data = data
        ))
    }

    /** Emit a fire-and-forget Command event for device-only tools. */
    private suspend fun emitDeviceCommand(command: AgentCommand) {
        emit(AgentEvent.Command(
            eventId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            command = command
        ))
    }

    /** Parse human-readable duration string to milliseconds. */
    private fun parseDurationToMs(duration: String): Long {
        val lower = duration.lowercase().trim()
        var totalMs = 0L
        val hourMatch = Regex("""(\d+)\s*h(?:our)?s?""").find(lower)
        val minMatch = Regex("""(\d+)\s*m(?:in(?:ute)?)?s?""").find(lower)
        val secMatch = Regex("""(\d+)\s*s(?:ec(?:ond)?)?s?""").find(lower)
        hourMatch?.let { totalMs += it.groupValues[1].toLong() * 3600000 }
        minMatch?.let { totalMs += it.groupValues[1].toLong() * 60000 }
        secMatch?.let { totalMs += it.groupValues[1].toLong() * 1000 }
        // If just a number, treat as minutes
        if (totalMs == 0L) {
            val plainNum = Regex("""(\d+)""").find(lower)
            plainNum?.let { totalMs = it.groupValues[1].toLong() * 60000 }
        }
        return if (totalMs > 0) totalMs else 60000 // Default 1 minute
    }

    /** Parse human-readable alarm time string to absolute epoch milliseconds. */
    private fun parseAlarmTimeToMs(timeStr: String, clientTimezone: String? = null, clientTimeMillis: Long? = null): Long {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try { java.time.ZoneId.of(clientTimezone ?: "UTC") } catch (e: Exception) { java.time.ZoneId.of("UTC") }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)

        val lower = timeStr.lowercase().trim()
        val isTomorrow = lower.contains("tomorrow")
        val cleanStr = lower.replace("tomorrow", "").trim()

        val timePatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?"""),
            Regex("""(\d{1,2})\s*(am|pm)""")
        )

        var hour = 0
        var minute = 0
        var foundMatch = false

        for (pattern in timePatterns) {
            val match = pattern.find(cleanStr)
            if (match != null) {
                hour = match.groupValues[1].toInt()
                minute = if (match.groupValues[2].matches(Regex("""\d{2}"""))) match.groupValues[2].toInt() else 0
                val ampm = match.groupValues.last().lowercase()
                if (ampm == "pm" && hour < 12) hour += 12
                else if (ampm == "am" && hour == 12) hour = 0
                foundMatch = true
                break
            }
        }

        if (!foundMatch) {
            val plainHour = Regex("""(\d{1,2})""").find(cleanStr)
            if (plainHour != null) {
                hour = plainHour.groupValues[1].toInt()
                minute = 0
                foundMatch = true
            }
        }

        if (!foundMatch) return now + 3600000

        var resultTime = zonedNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)

        if (isTomorrow) {
            resultTime = resultTime.plusDays(1)
        } else if (!resultTime.isAfter(zonedNow)) {
            resultTime = resultTime.plusDays(1)
        }

        return resultTime.toInstant().toEpochMilli()
    }

    private suspend fun emit(event: AgentEvent) {
        eventEmitter(event)
    }

    /**
     * Parse natural language time expressions to epoch milliseconds.
     * Examples: "tomorrow at 2pm", "Friday 3pm", "in 2 hours", "Dec 25 at 6pm"
     */
    private fun parseNaturalTime(expression: String, clientTimezone: String?, clientTimeMillis: Long?): Long {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val cleanExpr = expression.lowercase().trim()
        
        // Handle "in X minutes/hours/days"
        val relativeMatch = Regex("""in\s+(\d+)\s+(minute|min|hour|hr|day|week)s?""").find(cleanExpr)
        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLong()
            val unit = relativeMatch.groupValues[2]
            return when (unit.substring(0, 1)) {
                "m" -> now + amount * 60 * 1000
                "h" -> now + amount * 60 * 60 * 1000
                "d" -> now + amount * 24 * 60 * 60 * 1000
                "w" -> now + amount * 7 * 24 * 60 * 60 * 1000
                else -> now + 3600000
            }
        }
        
        // Determine if tomorrow/next week
        val isTomorrow = cleanExpr.contains("tomorrow") || cleanExpr.contains("tmrw")
        val isNextWeek = cleanExpr.contains("next week")
        val isNextMonth = cleanExpr.contains("next month")
        
        // Extract day name
        val dayOffsets = mapOf(
            "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4,
            "friday" to 5, "saturday" to 6, "sunday" to 7
        )
        var targetDay: Int? = null
        for ((day, offset) in dayOffsets) {
            if (cleanExpr.contains(day)) {
                val currentDayOfWeek = zonedNow.dayOfWeek.value
                var daysUntil = offset - currentDayOfWeek
                if (daysUntil <= 0) daysUntil += 7
                targetDay = daysUntil
                break
            }
        }
        
        // Extract time
        var hour = 12
        var minute = 0
        
        // Time patterns
        val timePatterns = listOf(
            Regex("""(\d{1,2}):(\d{2})\s*(am|pm)?"""),
            Regex("""(\d{1,2})\s*(am|pm)"""),
            Regex("""(\d{1,2})""")
        )
        
        for (pattern in timePatterns) {
            val match = pattern.find(cleanExpr)
            if (match != null) {
                hour = match.groupValues[1].toInt()
                if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                    if (match.groupValues[2].all { it.isDigit() }) {
                        minute = match.groupValues[2].toInt()
                    } else {
                        // AM/PM handling
                        val ampm = match.groupValues.last().lowercase()
                        if (ampm == "pm" && hour < 12) hour += 12
                        else if (ampm == "am" && hour == 12) hour = 0
                    }
                }
                if (match.groupValues.size > 3 && match.groupValues[3].isNotEmpty()) {
                    val ampm = match.groupValues[3].lowercase()
                    if (ampm == "pm" && hour < 12) hour += 12
                    else if (ampm == "am" && hour == 12) hour = 0
                }
                break
            }
        }
        
        var resultTime = zonedNow.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        
        when {
            isTomorrow -> resultTime = resultTime.plusDays(1)
            isNextWeek -> resultTime = resultTime.plusWeeks(1)
            isNextMonth -> resultTime = resultTime.plusMonths(1)
            targetDay != null -> resultTime = resultTime.plusDays(targetDay.toLong())
            !resultTime.isAfter(zonedNow) -> resultTime = resultTime.plusDays(1)
        }
        
        return resultTime.toInstant().toEpochMilli()
    }

    /**
     * Parse time range expressions like "today", "tomorrow", "this week"
     * Returns Pair(startTime, endTime) in epoch milliseconds
     */
    private fun parseTimeRange(expression: String, clientTimezone: String?, clientTimeMillis: Long?): Pair<Long, Long> {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }
        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val cleanExpr = expression.lowercase().trim()
        
        return when {
            cleanExpr.contains("today") -> {
                val start = zonedNow.withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(1)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            cleanExpr.contains("tomorrow") || cleanExpr.contains("tmrw") -> {
                val start = zonedNow.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(1)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            cleanExpr.contains("this week") -> {
                val dayOfWeek = zonedNow.dayOfWeek.value
                val start = zonedNow.minusDays((dayOfWeek - 1).toLong()).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(7)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            cleanExpr.contains("next week") -> {
                val dayOfWeek = zonedNow.dayOfWeek.value
                val start = zonedNow.plusDays((8 - dayOfWeek).toLong()).withHour(0).withMinute(0).withSecond(0).withNano(0)
                val end = start.plusDays(7)
                Pair(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
            }
            else -> {
                // Default to next 24 hours
                Pair(now, now + 24 * 60 * 60 * 1000)
            }
        }
    }

    /**
     * Resolve common app names to Android package names.
     */
    private fun resolveAppPackage(appName: String): String {
        val name = appName.lowercase().trim()
        
        val commonApps = mapOf(
            "spotify" to "com.spotify.music",
            "music" to "com.spotify.music",
            "youtube" to "com.google.android.youtube",
            "youtube music" to "com.google.android.apps.youtube.music",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "email" to "com.google.android.gm",
            "calendar" to "com.google.android.calendar",
            "camera" to "com.android.camera",
            "photos" to "com.google.android.apps.photos",
            "gallery" to "com.google.android.apps.photos",
            "settings" to "com.android.settings",
            "clock" to "com.google.android.deskclock",
            "alarm" to "com.google.android.deskclock",
            "timer" to "com.google.android.deskclock",
            "chrome" to "com.android.chrome",
            "browser" to "com.android.chrome",
            "messages" to "com.google.android.apps.messaging",
            "sms" to "com.google.android.apps.messaging",
            "phone" to "com.google.android.dialer",
            "dialer" to "com.google.android.dialer",
            "contacts" to "com.google.android.contacts",
            "facebook" to "com.facebook.katana",
            "instagram" to "com.instagram.android",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "discord" to "com.discord",
            "slack" to "com.Slack",
            "teams" to "com.microsoft.teams",
            "zoom" to "us.zoom.videomeetings",
            "netflix" to "com.netflix.mediaclient",
            "tiktok" to "com.zhiliaoapp.musically",
            "twitter" to "com.twitter.android"
        )
        
        // Check if it's already a package name
        if (name.contains(".")) return name
        
        // Look up common app
        return commonApps[name] ?: "com.android.settings"
    }

    @Serializable data class CreateNoteArgs(val title: String, val content: String, val category: String? = null)
    @Serializable data class SearchNotesArgs(val query: String, val filter: String? = null)
    @Serializable data class ScheduleEventArgs(val title: String, val startTime: Long, val endTime: Long, val description: String? = null, val reminderMinutes: Int? = null)
    // New natural language event args
    @Serializable data class AddEventArgs(val title: String, @SerialName("when") val scheduledAt: String, val duration: String? = null, val description: String? = null)
    @Serializable data class ShowEventsArgs(@SerialName("when") val period: String)
    @Serializable data class ListEventsArgs(val date: Long)
    @Serializable data class DeleteEventArgs(val eventId: String)
    @Serializable data class SetTimerArgs(val name: String, val duration: String)
    @Serializable data class SetAlarmArgs(val name: String, val time: String)
    // New unified reminder args
    @Serializable data class SetReminderArgs(val what: String, @SerialName("when") val scheduledAt: String, val repeat: String? = null)
    @Serializable data class LaunchAppArgs(val packageName: String)
    @Serializable data class OpenAppArgs(val app: String)
    @Serializable data class ToggleSettingArgs(val setting: String, val enable: Boolean)
    @Serializable data class ToggleSettingNewArgs(val setting: String, val on: Boolean)
    @Serializable data class ControlMediaArgs(val action: String)
    @Serializable data class SeekMediaArgs(val positionMs: Long)
    @Serializable data class StoreContextArgs(val content: String, val type: String)
    @Serializable data class RememberFactArgs(val fact: String, val type: String)
    @Serializable data class UpdateContextArgs(val id: String, val content: String, val type: String)
    @Serializable data class DeleteContextArgs(val id: String)
    @Serializable data class UpdateNoteArgs(val noteId: String, val title: String? = null, val content: String? = null)
    @Serializable data class DeleteNoteArgs(val noteId: String)
    @Serializable data class ArchiveNoteArgs(val noteId: String)
    @Serializable data class NavigateArgs(val screen: String)
    @Serializable data class GoToScreenArgs(val screen: String)
    @Serializable data class ShareArgs(val content: String, val title: String? = null)
    @Serializable data class ShareContentArgs(val content: String, val title: String? = null)
    @Serializable data class WebSearchArgs(val query: String)
    @Serializable data class SearchWebArgs(val query: String)
    @Serializable data class QueryKnowledgeArgs(val query: String)
    @Serializable data class GetWeatherArgs(val location: String? = null)
    @Serializable data class GetDeviceInfoArgs(val info: String)
    @Serializable data class GenerateImageArgs(val prompt: String)

    /**
     * Build time context string for the system prompt.
     * This helps the agent correctly parse time-based requests.
     */
    private fun buildTimeContext(clientTimezone: String?, clientTimeMillis: Long?): String {
        val now = clientTimeMillis ?: System.currentTimeMillis()
        val tz = try {
            java.time.ZoneId.of(clientTimezone ?: "UTC")
        } catch (e: Exception) {
            java.time.ZoneId.of("UTC")
        }

        val zonedNow = java.time.Instant.ofEpochMilli(now).atZone(tz)
        val dateFormatter = java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
        val timeFormatter = java.time.format.DateTimeFormatter.ofPattern("h:mm a")

        return """
            User's local time: ${zonedNow.format(timeFormatter)} on ${zonedNow.format(dateFormatter)}
            (User's timezone: ${tz.id})
        """.trimIndent()
    }

    private fun extractFinalResponse(raw: String): String {
        // 1. If <final> tags exist, take only that content
        val finalRegex = Regex("""<final>(.*?)</final>""", RegexOption.DOT_MATCHES_ALL)
        val match = finalRegex.find(raw)
        if (match != null) return match.groupValues[1].trim()

        // 2. Fallback: return the content but ensure ALL technical tags are stripped
        return raw.replace(Regex("<(?:think|thought|final)>"), "")
                  .replace(Regex("</(?:think|thought|final)>"), "")
                  .trim()
    }
    
    private fun extractThinking(raw: String): String? {
        val thinkRegex = Regex("""<think>(.*?)</think>""", RegexOption.DOT_MATCHES_ALL)
        val matches = thinkRegex.findAll(raw)
        val thinking = matches.joinToString("\n") { it.groupValues[1].trim() }
        return thinking.ifEmpty { null }
    }

    private fun truncateToolResult(result: String, maxChars: Int = 30000): String {
        return if (result.length > maxChars) {
            result.take(maxChars) + "\n[...truncated for brevity]"
        } else result
    }

    private fun extractFirstJsonObject(input: String): String? {
        var braceCount = 0
        var startIndex = -1
        for ((index, char) in input.withIndex()) {
            if (char == '{') {
                if (braceCount == 0) startIndex = index
                braceCount++
            } else if (char == '}') {
                braceCount--
                if (braceCount == 0 && startIndex >= 0) {
                    return input.substring(startIndex, index + 1)
                }
            }
        }
        return null
    }

    /** Returns true for tool names that perform web/internet searches. */
    private fun isSearchTool(toolName: String): Boolean =
        toolName.lowercase().let {
            it.contains("search") || it.contains("web") || it.contains("tavily") ||
            it.contains("fetch") || it.contains("scrape") || it.contains("browse")
        }

    /**
     * Returns true for errors that are PERMANENT (deterministic) and will never succeed on retry.
     * These should be reported to the user immediately — retrying wastes time and burns the context window.
     *
     * Contrast with [isTransientError] which covers failures worth retrying (network blips, rate limits).
     */
    private fun isPermanentFailure(errorMessage: String): Boolean {
        val lower = errorMessage.lowercase()
        return lower.contains("missingfieldexception") ||
            lower.contains("field") && lower.contains("required") ||
            lower.contains("field") && lower.contains("missing") ||
            lower.contains("serialization") && lower.contains("error") ||
            lower.contains("json") && lower.contains("parse") ||
            lower.contains("no transformation found") ||
            lower.contains("unauthorized") ||
            lower.contains("403") ||
            lower.contains("invalid api key") ||
            lower.contains("authentication failed") ||
            lower.contains("schema") ||
            lower.contains("does not exist") ||
            lower.contains("not found") && lower.contains("column") ||
            lower.contains("syntax error") && lower.contains("sql")
    }

    /**
     * Returns true for errors that are TRANSIENT and worth retrying.
     * These are caused by external conditions (network, rate limit, temporary outage) not by bad inputs.
     */
    private fun isTransientError(errorMessage: String): Boolean {
        val lower = errorMessage.lowercase()
        return lower.contains("timeout") ||
            lower.contains("connection reset") ||
            lower.contains("stream was reset") ||
            lower.contains("network") ||
            lower.contains("rate limit") ||
            lower.contains("429") ||
            lower.contains("503") ||
            lower.contains("502") ||
            lower.contains("temporarily unavailable") ||
            lower.contains("retry")
    }

    /**
     * Extract a short human-readable description of the tool input.
     * For search tools this returns the query string.
     * For other tools it returns a trimmed representation of the key argument.
     */
    private fun extractInputSummary(toolName: String, argsJson: String): String? {
        return try {
            // Try to parse the "query" field first (used by search tools + find tools)
            val queryRegex = Regex(""""query"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            queryRegex.find(argsJson)?.groupValues?.get(1)?.let { return it }

            // For title-based tools (memory_save, schedule_add, etc.)
            val titleRegex = Regex(""""title"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            titleRegex.find(argsJson)?.groupValues?.get(1)?.let { return "\"$it\"" }

            // For "what" field (reminders)
            val whatRegex = Regex(""""what"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""")
            whatRegex.find(argsJson)?.groupValues?.get(1)?.let { return it }

            // Fallback: take first 120 chars of args
            argsJson.take(120).let { if (argsJson.length > 120) "$it…" else it }
        } catch (e: Exception) {
            argsJson.take(120)
        }
    }

    /** Build a friendly display name for the action card header. */
    private fun buildDisplayName(toolName: String, inputSummary: String?): String {
        val base = when {
            toolName.contains("search", ignoreCase = true) ||
            toolName.contains("web", ignoreCase = true) ->
                if (inputSummary != null) "Searched: $inputSummary" else "Web Search"
            toolName.contains("memory", ignoreCase = true) ||
            toolName.contains("note", ignoreCase = true) ->
                if (inputSummary != null) "Saved: $inputSummary" else "Memory Action"
            toolName.contains("schedule", ignoreCase = true) ||
            toolName.contains("calendar", ignoreCase = true) ->
                if (inputSummary != null) "Scheduled: $inputSummary" else "Calendar Action"
            toolName.contains("remind", ignoreCase = true) ->
                if (inputSummary != null) "Reminder: $inputSummary" else "Reminder Set"
            toolName.contains("device", ignoreCase = true) ->
                "Device: ${toolName.substringAfter("_").replaceFirstChar { it.uppercase() }}"
            toolName.contains("navigate", ignoreCase = true) ->
                if (inputSummary != null) "Navigated to $inputSummary" else "Navigation"
            else -> toolName.replace("_", " ").replaceFirstChar { it.uppercase() }
        }
        return base.take(80)
    }
}
