package com.example.smarty.viewmodel.managers

import android.util.Log
import com.example.smarty.data.local.AIMemoryDao
import com.example.smarty.data.local.CogniDatabase
import com.example.smarty.data.model.AIMemory
import com.example.smarty.data.model.MemoryType
import com.example.smarty.data.model.Note
import com.example.smarty.data.model.NoteType
import com.example.smarty.data.remote.AIService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * =============================================================================
 * MEMORY SYNC MANAGER
 * =============================================================================
 *
 * Dedicated manager for extracting user behavior patterns from notes.
 * This is a simple behavior extraction process, NOT an agentic flow.
 *
 * It runs strictly as a background process to "read" notes and extract
 * meaningful insights about the user (interests, travel, habits).
 *
 * KEY FEATURES:
 * - Processes ORIGINAL note content (not AI summaries)
 * - Limits large documents to first ~8-10 pages (approx 25k chars)
 * - Extracts ONLY behavioral insights
 * - Uses simple LLM chat call via AIService
 *
 * =============================================================================
 */
class MemorySyncManager(
    private val database: CogniDatabase,
    private val aiMemoryDao: AIMemoryDao,
    private val aiService: AIService
) {
    companion object {
        private const val TAG = "MemorySyncManager"

        // Content limits
        // Avg ~3000 chars per page. 10 pages = ~30,000 chars.
        // Setting to 25,000 to be safe and efficient.
        private const val MAX_CONTENT_LENGTH = 25000
        private const val BATCH_SIZE = 50 // Notes per sync batch

        // Memory extraction system prompt
        private val MEMORY_EXTRACTION_SYSTEM_PROMPT = """
            <identity>
                You are a Privacy-First Behavioral Analyst. Your goal is to extract abstract, high-value behavioral patterns from user notes while strictly obfuscating all sensitive details.
            </identity>

            <objective>
                Analyze the provided note and extract exactly ONE meaningful behavioral insight. 
                If the content is trivial, generic, or lacks long-term value, you MUST return: EMPTY_RESULT.
            </objective>

            <strict_guidelines>
                1. EXTREME SELECTIVITY: Only keep insights that help personalize an AI's tone or knowledge of the user's habits (e.g., communication style, recurring interests, core values).
                2. CATEGORICAL ABSTRACTION: Prefer categories over specifics. Never store "User likes [Song]", store "User enjoys [Musical Genre]".
                3. NO TRIVIALITY: Shopping lists, one-off reminders, and casual status updates must be ignored (return EMPTY_RESULT).
                4. REASONING: Before outputting, ask: "Does this insight persist for more than a week?" If no, return EMPTY_RESULT.
            </strict_guidelines>

            <privacy_safeguards>
                1. MANDATORY FUZZY DATES: NEVER use exact dates (e.g., "Jan 15"). ALWAYS use intervals: "early next week", "mid-month", "late [Season]", "periodically".
                2. EVENT GENERALIZATION: NEVER use specific event names (e.g., "Board Meeting", "Surgery"). Use categories: "professional commitment", "health-related appointment", "social gathering".
                3. ENTITY MASKING: Replace names of people, specific companies, and precise venues with roles or generic descriptors (e.g., "a colleague", "a local establishment").
                4. LOCATION OBFUSCATION: Never store addresses. Use general regions if critical (e.g., "User is active in the downtown area").
            </privacy_safeguards>

            <output_format>
                Return ONLY the insight in the format: [TYPE] Insight text.
                NO bolding, NO intro text, NO markdown.
                Example: [PREFERENCE] User prefers concise, data-driven summaries for work tasks.
                If no insight: EMPTY_RESULT
            </output_format>

            <examples>
                <example>
                    Input: "Flight to London on March 12th for the tech conference. Need to prep slides."
                    Output: [FACT] User is participating in professional development events in Europe during mid-March.
                </example>
                <example>
                    Input: "I've been listening to a lot of Miles Davis and Coltrane lately."
                    Output: [PREFERENCE] User has a deep appreciation for classical Jazz movements.
                </example>
                <example>
                    Input: "Buy milk and call mom."
                    Output: EMPTY_RESULT
                </example>
            </examples>
        """.trimIndent()

        private val CONSOLIDATE_MEMORIES_SYSTEM_PROMPT = """
            <identity>
                You are a Memory Synthesis Engine. Your goal is to merge multiple fragmented memories into a single, highly abstract, and privacy-safe behavioral profile.
            </identity>

            <objective>
                Review the memory list and identify clusters of related points. Merge them into a broader, meta-level insight.
            </objective>

            <synthesis_rules>
                1. THEMATIC AGGREGATION: Group related interests (e.g., Python, Java, SQL) into one (e.g., "User is proficient in multiple programming paradigms").
                2. ESCALATING ABSTRACTION: Move from "User travels in Jan/Feb" to "User tends to be active with travel during the winter season".
                3. REDUNDANCY REMOVAL: Discard specific items that are already covered by a broader consolidated memory.
                4. QUALITY FILTER: If a memory group is too fragmented to merge safely, discard the lower-quality pieces.
            </synthesis_rules>

            <privacy_rules>
                - ABSOLUTE ANONYMITY: Ensure NO names, names of cities, or specific company names survive consolidation.
                - FUZZY TIMELINES: Use broad timeframes like "typically", "seasonally", or "recurringly".
            </privacy_rules>

            <strict_knowledge_preservation>
                - COMPREHENSIVENESS: You MUST include ALL meaningful information from the input. 
                - NO DATA LOSS: If a memory cannot be merged with others, YOU MUST keep it as a standalone item in the output.
                - DO NOT SUMMARIZE INTO OBLIVION: The goal is to reduce redundancy, not to delete unique user facts.
            </strict_knowledge_preservation>

            <output_format>
                Return a list of consolidated memories, each on a new line.
                Format: [TYPE] Consolidated text
                NO markdown.
            </output_format>

            <examples>
                <example>
                    Input: ["[FACT] User travels in early Jan", "[FACT] User has a trip mid-Feb", "[PREFERENCE] User likes winter sports"]
                    Output: [PATTERN] User frequently engages in seasonal travel and outdoor activities during the winter months.
                </example>
                <example>
                    Input: ["[PREFERENCE] User likes Jazz", "[PREFERENCE] User listens to Blues periodically", "[FACT] User is learning Saxophone"]
                    Output: [PATTERN] User is deeply immersed in classic music genres and is actively pursuing musical education.
                </example>
            </examples>
        """.trimIndent()
    }

    // Sync state
    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncResult = MutableStateFlow<String?>(null)
    val syncResult: StateFlow<String?> = _syncResult.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    init {
        // Initial count refresh
        refreshUnreadCount()
    }

    /**
     * Consolidates existing memories to improve abstraction.
     * Merges specifics into categories (e.g., song titles -> genres).
     */
    suspend fun consolidateAllMemories() {
        Log.i(TAG, "Starting memory consolidation...")
        try {
            val allMemories = aiMemoryDao.getAllMemories()
            if (allMemories.size < 3) return // Not enough to consolidate

            val memoryListText = allMemories.joinToString("\n") { "[${it.type}] ${it.content}" }
            
            val response = aiService.simpleChat(
                systemPrompt = CONSOLIDATE_MEMORIES_SYSTEM_PROMPT,
                userPrompt = "Please consolidate these memories:\n\n$memoryListText"
            )

            val consolidated = parseAiResponse(response)
            
            if (consolidated.isNotEmpty()) {
                // Determine if consolidation actually yielded something useful
                // We shouldn't clear everything if the AI returned a garbage response or just one line for 50 memories
                val isSafeToUpdate = consolidated.size >= (allMemories.size / 3).coerceAtLeast(1)
                
                if (isSafeToUpdate) {
                    // Consolidation was successful and seems comprehensive
                    aiMemoryDao.clearAllMemories()
                    consolidated.forEach { mem ->
                        aiMemoryDao.insertMemory(com.example.smarty.data.model.AIMemory(
                            type = mem.type,
                            content = mem.content,
                            confidence = 0.9f,
                            source = "Consolidated from existing patterns"
                        ))
                    }
                    Log.i(TAG, "Consolidation complete. Updated memory store: ${allMemories.size} -> ${consolidated.size} memories.")
                } else {
                    Log.w(TAG, "Consolidation yielded too few results (${consolidated.size} vs ${allMemories.size}). Aborting to prevent data loss.")
                }
            } else {
                Log.d(TAG, "No consolidation needed or possible.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Consolidation failed: ${e.message}")
        }
    }

    /**
     * Refreshes the unread count from the database.
     */
    fun refreshUnreadCount() {
        // Use database executor to avoid blocking main thread if called from non-suspend context
        database.queryExecutor.execute {
            try {
                val count = database.noteDao().getUnreadForMemoryCountSync()
                _unreadCount.value = count
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh unread count", e)
            }
        }
    }

    /**
     * Main entry point: Sync memories from unprocessed notes.
     */
    suspend fun syncMemoriesFromNotes(): SyncResult = withContext(Dispatchers.IO) {
        if (_isSyncing.value) {
            Log.w(TAG, "Memory sync already in progress")
            return@withContext SyncResult.AlreadyRunning
        }

        _isSyncing.value = true
        _syncResult.value = null

        try {
            // 1. Check if AI is available
            if (!aiService.isAiAvailable()) {
                val msg = "AI provider not configured. Please check settings."
                _syncResult.value = "Failed: $msg"
                return@withContext SyncResult.Error(msg)
            }

            val noteDao = database.noteDao()
            var memoriesCreated = 0
            var memoriesUpdated = 0
            var notesProcessed = 0
            var notesSkipped = 0
            var notesFailed = 0

            Log.i(TAG, "Starting memory sync - processing notes singly...")

            // 2. Loop until no unread notes remain
            while (isActive) {
                val unreadNotes = noteDao.getNotesNotReadForMemory(1)

                if (unreadNotes.isEmpty()) {
                    // Null State Recovery: If we processed nothing but have notes & no memories
                    if (notesProcessed == 0) {
                        val totalNotes = noteDao.getNoteCount()
                        val memoryCount = aiMemoryDao.getMemoryCount()
                        if (totalNotes > 0 && memoryCount == 0) {
                            Log.i(TAG, "No memories found but notes exist. Resetting all notes to unread state.")
                            noteDao.resetAllMemoryReadStatus()
                            continue 
                        }
                    }
                    break 
                }

                val note = unreadNotes.first()

                try {
                    // Step A: Extract Content
                    val contentToAnalyze = extractNoteContent(note)
                    Log.d(TAG, "[$notesProcessed] Extracted content from '${note.title}' (${contentToAnalyze.length} chars). Preview: ${contentToAnalyze.take(100)}...")

                    // Skip empty/too short notes
                    if (contentToAnalyze.length < 50) {
                        Log.i(TAG, "[$notesProcessed] Skipping '${note.title}' - content too short (${contentToAnalyze.length} chars)")
                        noteDao.markNotesAsReadForMemory(listOf(note.id))
                        notesSkipped++
                        notesProcessed++
                        continue
                    }

                    // Step B: AI Extraction
                    Log.d(TAG, "[$notesProcessed] Sending to AI extraction...")
                    val extractedMemories = performAiExtraction(contentToAnalyze)
                    Log.d(TAG, "[$notesProcessed] AI response parsed into ${extractedMemories.size} memories")

                    // Step C: Save Memories
                    for (mem in extractedMemories) {
                        val saveResult = saveMemory(mem)
                        if (saveResult.isNew) {
                            Log.i(TAG, "[$notesProcessed] Created NEW memory: ${mem.content.take(50)}...")
                            memoriesCreated++
                        } else {
                            Log.v(TAG, "[$notesProcessed] Updated existing memory/usage count")
                            memoriesUpdated++
                        }
                    }

                    // Success - mark as read
                    noteDao.markNotesAsReadForMemory(listOf(note.id))
                    notesProcessed++

                } catch (e: Exception) {
                    Log.e(TAG, "[$notesProcessed] Error processing note ${note.id}: ${e.message}", e)
                    // Mark as read anyway but count as failed
                    noteDao.markNotesAsReadForMemory(listOf(note.id))
                    notesFailed++
                    notesProcessed++
                }
            }

            // 3. Final Result
            val resultMsg = when {
                notesProcessed == 0 -> "All notes have been analyzed."
                memoriesCreated > 0 || memoriesUpdated > 0 -> 
                    "Analyzed $notesProcessed notes. Learned ${memoriesCreated + memoriesUpdated} new things!"
                notesFailed > 0 ->
                    "Processed $notesProcessed notes ($notesFailed failed). No new patterns found."
                else -> 
                    "Analyzed $notesProcessed notes. No new behavior patterns detected."
            }
            
            _syncResult.value = resultMsg
            Log.i(TAG, "Sync complete: $resultMsg")
            
            // 4. Run consolidation to abstract specific memories into categories
            consolidateAllMemories()
            
            // Refresh count at the end
            refreshUnreadCount()
            
            return@withContext SyncResult.Success(notesProcessed, memoriesCreated, memoriesUpdated)

        } catch (e: Exception) {
            Log.e(TAG, "Fatal error during memory sync", e)
            _syncResult.value = "Sync failed: ${e.localizedMessage}"
            return@withContext SyncResult.Error(e.message ?: "Unknown error")
        } finally {
            _isSyncing.value = false
            // Final safety refresh
            refreshUnreadCount()
        }
    }

    /**
     * Extracts text from the note.
     * CRITICAL: Uses ORIGINAL content, not AI summaries.
     * Truncates to MAX_CONTENT_LENGTH (first 5-10 pages).
     */
    private fun extractNoteContent(note: Note): String {
        val sb = StringBuilder()
        sb.append("Title: ${note.title}\n")
        sb.append("Type: ${note.type.name}\n\n")

        // We use note.content directly.
        // Assumption: Note.content contains the extracted text from files/PDFs.
        // If content is huge, we take the substring.
        val originalText = note.content ?: ""
        
        if (originalText.isNotEmpty()) {
            if (originalText.length > MAX_CONTENT_LENGTH) {
                sb.append(originalText.substring(0, MAX_CONTENT_LENGTH))
                sb.append("\n...(content truncated to first ~10 pages)...")
            } else {
                sb.append(originalText)
            }
        } else {
            // Fallback for cases where content might be empty but we have a description?
            // Usually PDFTextExtractor fills 'content'.
            // If empty, we can't do much for memory extraction.
             sb.append("(No text content available)")
        }

        return sb.toString()
    }

    /**
     * Calls AI to extract memory.
     */
    private suspend fun performAiExtraction(content: String): List<ExtractedMemory> {
        val userPrompt = """
            Here is the note content:
            
            $content
            
            Extract any user behavioral insights given the system prompt rules.
        """.trimIndent()

        try {
            val response = aiService.simpleChat(
                systemPrompt = MEMORY_EXTRACTION_SYSTEM_PROMPT,
                userPrompt = userPrompt
            )

            return parseAiResponse(response)
        } catch (e: Exception) {
            Log.e(TAG, "AI request failed: ${e.message}")
            return emptyList()
        }
    }

    /**
     * Parses the LLM response.
     * Expected format: "[TYPE] Insight text"
     */
    private fun parseAiResponse(response: String): List<ExtractedMemory> {
        val results = mutableListOf<ExtractedMemory>()
        val lines = response.split("\n")

        for (line in lines) {
            val clean = line.trim()
            if (clean.isBlank() || 
                clean.equals("EMPTY_RESULT", ignoreCase = true) || 
                clean.equals("null", ignoreCase = true)) continue

            // Regex for [TYPE] Content
            val match = Regex("^\\[(FACT|PATTERN|PREFERENCE|STYLE)\\]\\s*(.*)", RegexOption.IGNORE_CASE).find(clean)
            if (match != null) {
                val typeStr = match.groupValues[1].uppercase()
                val insightText = match.groupValues[2].trim()

                if (insightText.isNotEmpty()) {
                    results.add(ExtractedMemory(
                        type = MemoryType.valueOf(typeStr),
                        content = insightText,
                        confidence = 0.9f
                    ))
                }
            }
        }
        return results
    }

    /**
     * Saves memory to DB. Checks for duplicates.
     */
    private suspend fun saveMemory(mem: ExtractedMemory): SaveResult {
        // Simple duplicate check specifically for the "content"
        // In a real app, we might use embeddings, but here we use text match.
        val exists = aiMemoryDao.memoryExists(mem.content, mem.type)
        
        if (exists) {
            // If exists, maybe we just "refresh" it or increment usage? 
            // The Dao method memoryExists returns boolean.
            // We can search to get the ID if we want to update it.
            val existingList = aiMemoryDao.searchMemories(mem.content)
            val output = existingList.firstOrNull { it.content == mem.content } // Exact match check logic
            
            return if (output != null) {
                aiMemoryDao.incrementUsage(output.id)
                SaveResult(false, output.id)
            } else {
                // If fuzzy match passed but exact failed, treat as new or ignore?
                // Let's create new if exact match fails to be safe.
                 val newMem = AIMemory(
                    type = mem.type,
                    content = mem.content,
                    confidence = mem.confidence,
                    source = "Extracted from note"
                )
                aiMemoryDao.insertMemory(newMem)
                SaveResult(true, newMem.id)
            }
        }

        val newMem = AIMemory(
            type = mem.type,
            content = mem.content,
            confidence = mem.confidence,
            source = "Extracted from note"
        )
        aiMemoryDao.insertMemory(newMem)
        return SaveResult(true, newMem.id)
    }
    
    /**
     * Get count of notes that haven't been analyzed for memory.
     * Delegated to from ViewModel.
     */
    suspend fun getUnreadForMemoryCount(): Int = withContext(Dispatchers.IO) {
        val count = database.noteDao().getUnreadForMemoryCount()
        return@withContext count
    }

    /**
     * Clear the sync result message.
     */
    fun clearSyncResult() {
        _syncResult.value = null
    }

    // Helper Data Classes
    data class ExtractedMemory(
        val type: MemoryType,
        val content: String,
        val confidence: Float
    )

    data class SaveResult(val isNew: Boolean, val memoryId: String)

    sealed class SyncResult {
        data class Success(val notesProcessed: Int, val memoriesCreated: Int, val memoriesUpdated: Int) : SyncResult()
        data class Error(val message: String) : SyncResult()
        object AlreadyRunning : SyncResult()
    }
}
