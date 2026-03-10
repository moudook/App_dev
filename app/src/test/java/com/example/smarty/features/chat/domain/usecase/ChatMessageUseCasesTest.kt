package com.example.smarty.features.chat.domain.usecase

import com.example.smarty.core.domain.model.Attachment
import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.data.repository.ChatRepository
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for Chat Message Use Cases.
 * 
 * Tests verify:
 * - Correct repository interactions
 * - Proper message creation and updates
 * - Error handling
 */
class ChatMessageUseCasesTest {
    
    private lateinit var chatRepository: ChatRepository
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var updateMessageUseCase: UpdateMessageUseCase
    private lateinit var getMessagesUseCase: GetMessagesUseCase
    private lateinit var clearMessagesUseCase: ClearMessagesUseCase
    private lateinit var deleteMessageUseCase: DeleteMessageUseCase
    
    @Before
    fun setup() {
        chatRepository = mock()
        sendMessageUseCase = SendMessageUseCase(chatRepository)
        updateMessageUseCase = UpdateMessageUseCase(chatRepository)
        getMessagesUseCase = GetMessagesUseCase(chatRepository)
        clearMessagesUseCase = ClearMessagesUseCase(chatRepository)
        deleteMessageUseCase = DeleteMessageUseCase(chatRepository)
    }
    
    @Test
    fun `SendMessageUseCase throws when content is blank and no attachments`() = runTest {
        assertFailsWith<IllegalArgumentException> {
            sendMessageUseCase.execute("session1", "")
        }
    }
    
    @Test
    fun `SendMessageUseCase creates and saves user message`() = runTest {
        val sessionId = "session1"
        val content = "Hello, AI!"
        
        val message = sendMessageUseCase.execute(sessionId, content)
        
        assertEquals(ChatRole.USER, message.role)
        assertEquals(content, message.content)
        assertNotNull(message.id)
        assertNotNull(message.timestamp)
        
        verify(chatRepository).saveMessage(eq(sessionId), eq(message))
    }
    
    @Test
    fun `SendMessageUseCase creates message with attachments`() = runTest {
        val sessionId = "session1"
        val content = "Check this image"
        val attachments = listOf(
            Attachment(uri = "content://image1", fileName = "image1.jpg")
        )
        
        val message = sendMessageUseCase.execute(sessionId, content, attachments)
        
        assertEquals(attachments, message.attachments)
        verify(chatRepository).saveMessage(eq(sessionId), eq(message))
    }
    
    @Test
    fun `UpdateMessageUseCase updates message content`() = runTest {
        val sessionId = "session1"
        val messageId = "msg1"
        val newContent = "Updated content"
        
        updateMessageUseCase.execute(sessionId, messageId, newContent)
        
        verify(chatRepository).updateMessage(eq(sessionId), eq(messageId), any())
    }
    
    @Test
    fun `UpdateMessageUseCase updates thinking when provided`() = runTest {
        val sessionId = "session1"
        val messageId = "msg1"
        val content = "Content"
        val thinking = "This is thinking"
        
        updateMessageUseCase.execute(sessionId, messageId, content, thinking)
        
        verify(chatRepository).updateMessage(eq(sessionId), eq(messageId), any { message ->
            assertEquals(content, message.content)
            assertEquals(thinking, message.thinking)
            true
        })
    }
    
    @Test
    fun `UpdateMessageUseCase sets isStreaming flag`() = runTest {
        val sessionId = "session1"
        val messageId = "msg1"
        
        updateMessageUseCase.execute(
            sessionId = sessionId,
            messageId = messageId,
            content = "Content",
            isStreaming = false
        )
        
        verify(chatRepository).updateMessage(eq(sessionId), eq(messageId), any { message ->
            assertFalse(message.isStreaming)
            true
        })
    }
    
    @Test
    fun `GetMessagesUseCase returns flow of messages`() = runTest {
        val sessionId = "session1"
        val messages = listOf(
            ChatMessage(id = "1", role = ChatRole.USER, content = "Hi", timestamp = 1000L),
            ChatMessage(id = "2", role = ChatRole.SMARTY, content = "Hello!", timestamp = 2000L)
        )
        
        whenever(chatRepository.getMessagesFlow(sessionId)).thenReturn(flowOf(messages))
        
        val result = getMessagesUseCase.execute(sessionId)
        
        assertNotNull(result)
    }
    
    @Test
    fun `GetMessagesUseCase getAllMessages returns list`() = runTest {
        val sessionId = "session1"
        val messages = listOf(
            ChatMessage(id = "1", role = ChatRole.USER, content = "Hi", timestamp = 1000L)
        )
        
        whenever(chatRepository.getMessages(sessionId)).thenReturn(messages)
        
        val result = getMessagesUseCase.getAllMessages(sessionId)
        
        assertEquals(messages, result)
        verify(chatRepository).getMessages(sessionId)
    }
    
    @Test
    fun `ClearMessagesUseCase clears messages for session`() = runTest {
        val sessionId = "session1"
        
        clearMessagesUseCase.execute(sessionId)
        
        verify(chatRepository).clearMessages(sessionId)
    }
    
    @Test
    fun `DeleteMessageUseCase deletes specific message`() = runTest {
        val sessionId = "session1"
        val messageId = "msg1"
        
        deleteMessageUseCase.execute(sessionId, messageId)
        
        verify(chatRepository).deleteMessage(sessionId, messageId)
    }
}
