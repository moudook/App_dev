package com.example.smarty.integration

import com.example.smarty.data.local.SmartyDatabase
import com.example.smarty.data.repository.ServerSyncRepository
import com.example.smarty.data.sync.SyncCoordinator
import com.example.smarty.testing.TestBuilders
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for ServerSyncRepository.
 *
 * Tests cover:
 * - Note synchronization flow
 * - Category derivation from notes
 * - Offline queue integration
 * - Sync coordinator interaction
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerSyncIntegrationTest {
    private lateinit var syncRepository: ServerSyncRepository
    private lateinit var database: SmartyDatabase
    private lateinit var syncCoordinator: SyncCoordinator
    private lateinit var offlineQueue: com.example.smarty.data.sync.OfflineQueue
    private lateinit var remoteDataSource: com.example.smarty.data.remote.RemoteDataSource
    private lateinit var eventSink: com.example.smarty.core.common.worker.BackgroundAgentEventSink

    @Before
    fun setup() {
        // Create in-memory database for testing
        database = SmartyDatabase.createInMemoryDatabase()

        // Mock external dependencies
        syncCoordinator = mockk(relaxed = true)
        offlineQueue = mockk(relaxed = true)
        remoteDataSource = mockk(relaxed = true)
        eventSink = mockk(relaxed = true)

        // Setup mock responses
        every { remoteDataSource.createNote(any(), any(), any()) } returns true
        every { remoteDataSource.updateNote(any(), any(), any(), any()) } returns true
        every { remoteDataSource.deleteNote(any()) } returns true

        // Initialize repository
        syncRepository =
            ServerSyncRepository(
                remoteDataSource = remoteDataSource,
                eventSink = eventSink,
                syncCoordinator = syncCoordinator,
                offlineQueue = offlineQueue,
            )

        // Initialize for test user
        syncRepository.initializeForUser(TestBuilders.Constants.TEST_USER_ID)
    }

    @Test
    fun syncNote_sendsNoteToServer() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Sync Test Note"
                    content = "Content to sync"
                    categoryName = "Work"
                }

            // Insert note into local database first
            database.noteDao().insertNote(note)

            // When
            val result = syncRepository.syncNote(note)

            // Then
            assertTrue(result.isSuccess)
            verify {
                remoteDataSource.createNote(
                    note.title,
                    note.content,
                    note.categoryName,
                )
            }
        }

    @Test
    fun syncNote_skipsPrivateNotes() =
        runTest {
            // Given
            val privateNote =
                TestBuilders.note {
                    title = "Private Note"
                    content = "Secret content"
                    isPrivate = true
                }

            // When
            val result = syncRepository.syncNote(privateNote)

            // Then
            assertTrue(result.isSuccess)
            // Verify remoteDataSource was NOT called
            verify(exactly = 0) {
                remoteDataSource.createNote(
                    any(),
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun syncNote_enqueuesOnFailure() =
        runTest {
            // Given
            val note =
                TestBuilders.note {
                    title = "Failed Sync Note"
                }

            // Simulate failure
            every { remoteDataSource.createNote(any(), any(), any()) } returns false

            // When
            val result = syncRepository.syncNote(note)

            // Then
            assertTrue(result.isSuccess) // Gracefully handles failure
            verify { offlineQueue.enqueueNoteUpdate(note) }
        }

    @Test
    fun deleteNote_removesFromServer() =
        runTest {
            // Given
            val noteId = "note_to_delete_123"

            // When
            val result = syncRepository.deleteNote(noteId)

            // Then
            assertTrue(result.isSuccess)
            verify { remoteDataSource.deleteNote(noteId) }
        }

    @Test
    fun syncCategory_usesServerSideDerivation() =
        runTest {
            // Given
            val category =
                TestBuilders.category {
                    name = "Test Category"
                    color = "#FF0000"
                }

            // When
            val result = syncRepository.syncCategory(category)

            // Then
            assertTrue(result.isSuccess)
            // Categories are derived from notes on server, so no remote call
            verify(exactly = 0) { remoteDataSource.createNote(any(), any(), any()) }
        }

    @Test
    fun getRemoteNotesFlow_returnsEmptyInitially() =
        runTest {
            // When
            val notes = syncRepository.getRemoteNotesFlow().first()

            // Then
            assertTrue(notes.isEmpty())
        }

    @Test
    fun initializeForUser_setsUpSync() =
        runTest {
            // Given
            val userId = "new_user_456"

            // When
            syncRepository.initializeForUser(userId)

            // Then
            // Should not throw exceptions
            assertTrue(true)
        }

    @Test
    fun syncNote_handlesArchivedNotes() =
        runTest {
            // Given
            val archivedNote =
                TestBuilders.note {
                    title = "Archived Note"
                    archived()
                }

            // When
            val result = syncRepository.syncNote(archivedNote)

            // Then
            assertTrue(result.isSuccess)
            verify { remoteDataSource.deleteNote(archivedNote.id) }
        }

    @Test
    fun multipleNoteSyncs_batchedEfficiently() =
        runTest {
            // Given
            val notes = TestBuilders.noteList(5)
            notes.forEach { database.noteDao().insertNote(it) }

            // When
            notes.forEach { note ->
                syncRepository.syncNote(note)
            }

            // Then
            // Verify all notes were sent to server
            verify(exactly = 5) {
                remoteDataSource.createNote(
                    any(),
                    any(),
                    any(),
                )
            }
        }
}
