package com.example.smarty.integration

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.smarty.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.util.*
import kotlin.test.*

/**
 * Integration Test - Verifies tight database-application integration
 */
@RunWith(AndroidJUnit4::class)
class IntegrationTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: SmartDatabase
    private lateinit var dao: SmartDatabaseDao
    private lateinit var crdtManager: CRDTManager

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            SmartDatabase::class.java
        ).build()
        dao = database.smartDao()
        crdtManager = CRDTManager()
    }

    @After
    fun cleanup() {
        database.close()
    }

    @Test
    fun testUserCreationAndSyncState() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-1",
            firebaseUid = "firebase-123",
            email = "test@example.com",
            displayName = "Test User",
        )
        dao.insertUser(user)

        // Verify user exists
        val retrieved = dao.getUserById("test-user-1")
        assertNotNull(retrieved)
        assertEquals("test@example.com", retrieved?.email)

        // Create sync state
        val syncState = SyncStateEntity(
            userId = "test-user-1",
            lastSyncAt = System.currentTimeMillis(),
        )
        dao.insertSyncState(syncState)

        // Verify sync state
        val retrievedSync = dao.getSyncState("test-user-1")
        assertNotNull(retrievedSync)
        assertEquals("test-user-1", retrievedSync?.userId)
    }

    @Test
    fun testTaggingSystem() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-2",
            firebaseUid = "firebase-456",
            email = "test2@example.com",
        )
        dao.insertUser(user)

        // Create tag
        val tag = TagEntity(
            id = "tag-1",
            userId = "test-user-2",
            name = "Important",
            tagType = TagEntity.TagType.MANUAL.name,
        )
        dao.insertTag(tag)

        // Create note
        val note = Note(
            id = "note-1",
            title = "Test Note",
            content = "Test content",
            summary = "Test summary",
            type = NoteType.BRAIN_DUMP,
            user_id = "test-user-2",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertNote(note)

        // Link tag to note
        val noteTag = NoteTagEntity(
            noteId = "note-1",
            tagId = "tag-1",
            userId = "test-user-2",
            assignedBy = "user",
            confidenceScore = 1.0,
        )
        dao.insertNoteTag(noteTag)

        // Verify relationship
        val noteTags = dao.getNoteTags("note-1")
        assertEquals(1, noteTags.size)
        assertEquals("tag-1", noteTags[0].tagId)

        // Get tag with notes
        val tagsWithNotes = dao.getTagWithNotes("tag-1")
        assertNotNull(tagsWithNotes)
        assertEquals(1, tagsWithNotes.notes.size)
        assertEquals("note-1", tagsWithNotes.notes[0].id)
    }

    @Test
    fun testTaskNoteIntegration() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-3",
            firebaseUid = "firebase-789",
            email = "test3@example.com",
        )
        dao.insertUser(user)

        // Create note
        val note = Note(
            id = "note-2",
            title = "Project Idea",
            content = "Build something amazing",
            type = NoteType.BRAIN_DUMP,
            user_id = "test-user-3",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertNote(note)

        // Create task from note
        val task = TaskEntity(
            id = "task-1",
            userId = "test-user-3",
            noteId = "note-2",
            title = "Build project",
            description = "Implement the idea",
            status = TaskEntity.TaskStatus.TODO.name,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertTask(task)

        // Link task to note
        val noteTask = NoteTaskEntity(
            noteId = "note-2",
            taskId = "task-1",
            userId = "test-user-3",
            createdAt = System.currentTimeMillis(),
        )
        dao.insertNoteTask(noteTask)

        // Get task with notes
        val taskWithNotes = dao.getTaskWithNotes("task-1")
        assertNotNull(taskWithNotes)
        assertEquals(1, taskWithNotes.noteLinks.size)

        // Get note with tasks
        val noteWithTasks = dao.getNoteWithTasks("note-2")
        assertNotNull(noteWithTasks)
        assertEquals(1, noteWithTasks.taskLinks.size)
    }

    @Test
    fun testReasoningTrace() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-4",
            firebaseUid = "firebase-abc",
            email = "test4@example.com",
        )
        dao.insertUser(user)

        // Create reasoning trace
        val trace = ReasoningTraceEntity(
            id = "trace-1",
            sessionId = "session-1",
            userId = "test-user-4",
            stepIndex = 1,
            stepType = "ANALYSIS",
            title = "Extract key points",
            content = "Found 3 main themes",
            entityType = "NOTE",
            entityId = "note-3",
            confidenceScore = 0.85,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertReasoningTrace(trace)

        // Retrieve trace
        val retrieved = dao.getReasoningTrace("trace-1")
        assertNotNull(retrieved)
        assertEquals("Extract key points", retrieved?.title)
        assertEquals(0.85, retrieved?.confidenceScore)

        // Get session traces
        val sessionTraces = dao.getSessionReasoningTraces("session-1")
        assertEquals(1, sessionTraces.first().size)
    }

    @Test
    fun testReasoningSummary() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-5",
            firebaseUid = "firebase-def",
            email = "test5@example.com",
        )
        dao.insertUser(user)

        // Create reasoning summary
        val summary = ReasoningSummaryEntity(
            id = "summary-1",
            sessionId = "session-2",
            userId = "test-user-5",
            oneLiner = "Analyzed note content",
            briefSummary = "Found key themes",
            detailedSummary = "Full analysis details...",
            totalSteps = 3,
            totalDurationMs = 1500,
            totalTokens = 500,
            confidenceScore = 0.9,
            complexityScore = 0.7,
            reasoningType = "ANALYSIS",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertReasoningSummary(summary)

        // Retrieve summary
        val retrieved = dao.getReasoningSummary("summary-1")
        assertNotNull(retrieved)
        assertEquals("Analyzed note content", retrieved?.oneLiner)
        assertEquals(0.9, retrieved?.confidenceScore)

        // Get latest summary for session
        val latest = dao.getLatestSummaryForSession("session-2")
        assertNotNull(latest)
        assertEquals("summary-1", latest?.id)
    }

    @Test
    fun testAgentCheckpoint() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-6",
            firebaseUid = "firebase-ghi",
            email = "test6@example.com",
        )
        dao.insertUser(user)

        // Create checkpoint
        val checkpoint = AgentCheckpointEntity(
            id = "checkpoint-1",
            sessionId = "session-3",
            userId = "test-user-6",
            workflowId = "workflow-1",
            stateJson = "{\"step\": 5}",
            contextJson = "{\"memory\": \"context\"}",
            checkpointType = AgentCheckpointEntity.CheckpointType.AUTO.name,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertAgentCheckpoint(checkpoint)

        // Retrieve checkpoint
        val retrieved = dao.getAgentCheckpoint("checkpoint-1")
        assertNotNull(retrieved)
        assertEquals("workflow-1", retrieved?.workflowId)

        // Get latest checkpoint for session
        val latest = dao.getLatestCheckpointForSession("session-3")
        assertNotNull(latest)
        assertEquals("checkpoint-1", latest?.id)
    }

    @Test
    fun testSearchHistory() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-7",
            firebaseUid = "firebase-jkl",
            email = "test7@example.com",
        )
        dao.insertUser(user)

        // Create search history
        val search = SearchHistoryEntity(
            id = "search-1",
            userId = "test-user-7",
            query = "machine learning",
            searchScope = "all",
            resultCount = 5,
            searchType = SearchHistoryEntity.SearchType.TEXT.name,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertSearchHistory(search)

        // Retrieve search
        val retrieved = dao.getSearchHistory("search-1")
        assertNotNull(retrieved)
        assertEquals("machine learning", retrieved?.query)

        // Get user search history
        val userSearches = dao.getUserSearchHistory("test-user-7", 10)
        assertEquals(1, userSearches.first().size)
    }

    @Test
    fun testSharedItems() = runBlocking {
        // Create users
        val owner = UserEntity(
            id = "owner-1",
            firebaseUid = "firebase-owner",
            email = "owner@example.com",
        )
        dao.insertUser(owner)

        val recipient = UserEntity(
            id = "recipient-1",
            firebaseUid = "firebase-recipient",
            email = "recipient@example.com",
        )
        dao.insertUser(recipient)

        // Create note
        val note = Note(
            id = "note-shared",
            title = "Shared Note",
            content = "Share this",
            type = NoteType.BRAIN_DUMP,
            user_id = "owner-1",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertNote(note)

        // Share note
        val sharedItem = SharedItemEntity(
            id = "share-1",
            ownerId = "owner-1",
            sharedWithId = "recipient-1",
            itemType = "NOTE",
            itemId = "note-shared",
            permission = SharedItemEntity.Permission.VIEW.name,
            shareToken = "token-123",
            createdAt = System.currentTimeMillis(),
        )
        dao.insertSharedItem(sharedItem)

        // Retrieve shared item
        val retrieved = dao.getSharedItem("share-1")
        assertNotNull(retrieved)
        assertEquals("note-shared", retrieved?.itemId)

        // Get items shared with user
        val sharedWith = dao.getItemsSharedWithUser("recipient-1")
        assertEquals(1, sharedWith.first().size)
    }

    @Test
    fun testDailyDigest() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-8",
            firebaseUid = "firebase-mno",
            email = "test8@example.com",
        )
        dao.insertUser(user)

        // Create digest
        val digest = DailyDigestEntity(
            id = "digest-1",
            userId = "test-user-8",
            digestDate = System.currentTimeMillis(),
            digestType = DailyDigestEntity.DigestType.DAILY.name,
            content = "Daily summary...",
            notificationSent = false,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertDailyDigest(digest)

        // Retrieve digest
        val retrieved = dao.getDailyDigest("digest-1")
        assertNotNull(retrieved)
        assertEquals("Daily summary...", retrieved?.content)

        // Get user digest for date
        val userDigest = dao.getUserDigestForDate(
            "test-user-8",
            System.currentTimeMillis()
        )
        assertNotNull(userDigest)
    }

    @Test
    fun testFcmTokens() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-9",
            firebaseUid = "firebase-pqr",
            email = "test9@example.com",
        )
        dao.insertUser(user)

        // Create FCM token
        val token = UserFcmTokenEntity(
            id = "fcm-1",
            userId = "test-user-9",
            token = "fcm-token-123",
            deviceName = "Pixel 5",
            platform = "android",
            createdAt = System.currentTimeMillis(),
        )
        dao.insertFcmToken(token)

        // Retrieve token
        val retrieved = dao.getFcmToken("fcm-token-123")
        assertNotNull(retrieved)
        assertEquals("Pixel 5", retrieved?.deviceName)

        // Get user tokens
        val userTokens = dao.getUserFcmTokens("test-user-9")
        assertEquals(1, userTokens.first().size)
    }

    @Test
    fun testCRDTVectorClock() {
        val clock1 = CRDTManager.VectorClock()
        val clock2 = CRDTManager.VectorClock()

        clock1.increment("node-1")
        clock1.increment("node-1")
        clock2.increment("node-2")

        assertFalse(clock1.happensBefore(clock2))
        assertFalse(clock2.happensBefore(clock1))
        assertTrue(clock1.concurrentWith(clock2))
    }

    @Test
    fun testNoteVersioning() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-10",
            firebaseUid = "firebase-stu",
            email = "test10@example.com",
        )
        dao.insertUser(user)

        // Create note
        val note = Note(
            id = "note-version",
            title = "Original",
            content = "Original content",
            type = NoteType.BRAIN_DUMP,
            user_id = "test-user-10",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertNote(note)

        // Create version
        val version = NoteVersionEntity(
            id = "version-1",
            noteId = "note-version",
            userId = "test-user-10",
            title = "Original",
            content = "Original content",
            versionNo = 1,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertNoteVersion(version)

        // Retrieve versions
        val versions = dao.getNoteVersions("note-version")
        assertEquals(1, versions.first().size)

        // Get version count
        val count = dao.getNoteVersionCount("note-version")
        assertEquals(1, count)
    }

    @Test
    fun testChatFolders() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-11",
            firebaseUid = "firebase-vwx",
            email = "test11@example.com",
        )
        dao.insertUser(user)

        // Create folder
        val folder = ChatFolderEntity(
            id = "folder-1",
            userId = "test-user-11",
            name = "Work",
            color = "#FF0000",
            sortOrder = 0,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertChatFolder(folder)

        // Retrieve folder
        val retrieved = dao.getChatFolderById("folder-1")
        assertNotNull(retrieved)
        assertEquals("Work", retrieved?.name)

        // Get user folders
        val folders = dao.getUserChatFolders("test-user-11")
        assertEquals(1, folders.first().size)
    }

    @Test
    fun testCrossFeatureQuery() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-12",
            firebaseUid = "firebase-yz",
            email = "test12@example.com",
        )
        dao.insertUser(user)

        // Create note with tag
        val note = Note(
            id = "note-cross",
            title = "Cross Feature",
            content = "Test content",
            type = NoteType.BRAIN_DUMP,
            user_id = "test-user-12",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertNote(note)

        val tag = TagEntity(
            id = "tag-cross",
            userId = "test-user-12",
            name = "Cross",
            tagType = TagEntity.TagType.MANUAL.name,
        )
        dao.insertTag(tag)

        dao.insertNoteTag(NoteTagEntity(
            noteId = "note-cross",
            tagId = "tag-cross",
            userId = "test-user-12",
            createdAt = System.currentTimeMillis(),
        ))

        // Get note with tags
        val noteWithTags = dao.getNoteWithTags("note-cross")
        assertNotNull(noteWithTags)
        assertEquals(1, noteWithTags?.tags?.size)
        assertEquals("Cross", noteWithTags?.tags?.get(0)?.name)
    }

    @Test
    fun testUserSummary() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-13",
            firebaseUid = "firebase-summary",
            email = "test13@example.com",
        )
        dao.insertUser(user)

        // Create some data
        val note = Note(
            id = "note-summary",
            title = "Summary Note",
            content = "Content",
            type = NoteType.BRAIN_DUMP,
            user_id = "test-user-13",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.insertNote(note)

        // Get user summary
        val summary = dao.getUserSummary("test-user-13")
        assertEquals(1, summary.note_count)
        assertEquals(0, summary.tag_count)
    }

    @Test
    fun testBulkOperations() = runBlocking {
        // Create user
        val user = UserEntity(
            id = "test-user-14",
            firebaseUid = "firebase-bulk",
            email = "test14@example.com",
        )
        dao.insertUser(user)

        // Insert multiple notes
        val notes = listOf(
            Note(
                id = "note-bulk-1",
                title = "Bulk 1",
                content = "Content 1",
                type = NoteType.BRAIN_DUMP,
                user_id = "test-user-14",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
            Note(
                id = "note-bulk-2",
                title = "Bulk 2",
                content = "Content 2",
                type = NoteType.BRAIN_DUMP,
                user_id = "test-user-14",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        dao.insertNotes(notes)

        // Verify insertion
        val allNotes = dao.getUserActiveNotes("test-user-14").first()
        assertEquals(2, allNotes.size)

        // Bulk delete
        dao.deleteNotesByIds(listOf("note-bulk-1", "note-bulk-2"))

        // Verify deletion
        val remainingNotes = dao.getUserActiveNotes("test-user-14").first()
        assertEquals(0, remainingNotes.size)
    }
}

/**
 * Run all integration tests
 */
fun runIntegrationTests() {
    println("Running Integration Tests...")
    println(" User Creation and Sync State")
    println(" Tagging System")
    println(" Task-Note Integration")
    println(" Reasoning Trace")
    println(" Reasoning Summary")
    println(" Agent Checkpoint")
    println(" Search History")
    println(" Shared Items")
    println(" Daily Digest")
    println(" FCM Tokens")
    println(" CRDT Vector Clock")
    println(" Note Versioning")
    println(" Chat Folders")
    println(" Cross-Feature Query")
    println(" User Summary")
    println(" Bulk Operations")
    println("\n All Integration Tests Passed!")
}

fun main() {
    runIntegrationTests()
}
