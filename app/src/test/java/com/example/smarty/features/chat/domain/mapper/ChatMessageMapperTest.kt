package com.example.smarty.features.chat.domain.mapper

import com.example.smarty.core.domain.model.ChatMessage
import com.example.smarty.core.domain.model.ChatRole
import com.example.smarty.features.chat.domain.state.ChatState
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ChatMessageMapper.
 * 
 * Tests verify:
 * - Content cleaning (THINK tag removal)
 * - Thinking extraction
 * - Message formatting
 * - State updates
 */
class ChatMessageMapperTest {
    
    @Test
    fun `cleanContent removes THINK tags`() {
        val content = "<think>This is thinking</think>This is the response"
        val cleaned = ChatMessageMapper.cleanContent(content)
        
        assertEquals("This is the response", cleaned)
    }
    
    @Test
    fun `cleanContent returns content without THINK tags unchanged`() {
        val content = "This is a normal message"
        val cleaned = ChatMessageMapper.cleanContent(content)
        
        assertEquals("This is a normal message", cleaned)
    }
    
    @Test
    fun `cleanContent handles empty string`() {
        val content = ""
        val cleaned = ChatMessageMapper.cleanContent(content)
        
        assertEquals("", cleaned)
    }
    
    @Test
    fun `cleanContent handles multiline THINK tags`() {
        val content = """
            <think>
            This is multiline
            thinking content
           </think>
            This is the response
        """.trimIndent()
        
        val cleaned = ChatMessageMapper.cleanContent(content)
        assertEquals("This is the response", cleaned.trim())
    }
    
    @Test
    fun `extractThinking returns thinking content`() {
        val content = "<think>This is thinking</think>This is the response"
        val thinking = ChatMessageMapper.extractThinking(content)
        
        assertEquals("This is thinking", thinking)
    }
    
    @Test
    fun `extractThinking returns null when no THINK tags`() {
        val content = "This is just a response"
        val thinking = ChatMessageMapper.extractThinking(content)
        
        assertNull(thinking)
    }
    
    @Test
    fun `hasPartialThinkTag detects partial opening tag`() {
        val content = "<think>This is thinking"
        val hasPartial = ChatMessageMapper.hasPartialThinkTag(content)
        
        assertTrue(hasPartial)
    }
    
    @Test
    fun `hasPartialThinkTag returns false for complete tags`() {
        val content = "<think>This is thinking</think>Response"
        val hasPartial = ChatMessageMapper.hasPartialThinkTag(content)
        
        assertFalse(hasPartial)
    }
    
    @Test
    fun `isMessageComplete returns true for complete messages`() {
        val content = "<think>Thinking</think>Response"
        val isComplete = ChatMessageMapper.isMessageComplete(content)
        
        assertTrue(isComplete)
    }
    
    @Test
    fun `isMessageComplete returns false for partial tags`() {
        val content = "<think>Thinking"
        val isComplete = ChatMessageMapper.isMessageComplete(content)
        
        assertFalse(isComplete)
    }
    
    @Test
    fun `formatForDisplay returns user content as-is`() {
        val message = ChatMessage(
            id = "1",
            role = ChatRole.USER,
            content = "User message",
            timestamp = System.currentTimeMillis()
        )
        
        val formatted = ChatMessageMapper.formatForDisplay(message)
        
        assertEquals("User message", formatted)
    }
    
    @Test
    fun `formatForDisplay cleans assistant content`() {
        val message = ChatMessage(
            id = "1",
            role = ChatRole.SMARTY,
            content = "<think>Thinking</think>Assistant message",
            timestamp = System.currentTimeMillis()
        )
        
        val formatted = ChatMessageMapper.formatForDisplay(message)
        
        assertEquals("Assistant message", formatted)
    }
    
    @Test
    fun `calculateDisplayPosition starts from 0 when streaming`() {
        val position = ChatMessageMapper.calculateDisplayPosition(
            messageId = "1",
            fullText = "Hello World",
            isStreaming = true,
            previousPosition = 0
        )
        
        assertEquals(2, position) // charsPerFrame = 2
    }
    
    @Test
    fun `calculateDisplayPosition returns full length when not streaming`() {
        val position = ChatMessageMapper.calculateDisplayPosition(
            messageId = "1",
            fullText = "Hello World",
            isStreaming = false,
            previousPosition = 5
        )
        
        assertEquals(11, position) // Full length
    }
    
    @Test
    fun `getVisibleText returns substring based on position`() {
        val visible = ChatMessageMapper.getVisibleText(
            fullText = "Hello World",
            displayPosition = 5
        )
        
        assertEquals("Hello", visible)
    }
    
    @Test
    fun `getVisibleText returns full text when position equals length`() {
        val visible = ChatMessageMapper.getVisibleText(
            fullText = "Hello World",
            displayPosition = 11
        )
        
        assertEquals("Hello World", visible)
    }
    
    @Test
    fun `addMessageToState adds message and updates timestamp`() {
        val initialState = ChatState.initial()
        val message = ChatMessage(
            id = "1",
            role = ChatRole.USER,
            content = "Test message",
            timestamp = System.currentTimeMillis()
        )
        
        val newState = ChatMessageMapper.addMessageToState(initialState, message)
        
        assertEquals(1, newState.messages.size)
        assertEquals(message, newState.messages.first())
        assertTrue(newState.lastUpdated >= initialState.lastUpdated)
    }
    
    @Test
    fun `updateMessageInState updates correct message`() {
        val message1 = ChatMessage(
            id = "1",
            role = ChatRole.SMARTY,
            content = "Old content",
            timestamp = System.currentTimeMillis()
        )
        val initialState = ChatState.initial().copy(messages = listOf(message1))
        
        val newState = ChatMessageMapper.updateMessageInState(
            currentState = initialState,
            messageId = "1",
            content = "New content",
            thinking = "New thinking"
        )
        
        val updatedMessage = newState.messages.first()
        assertEquals("New content", updatedMessage.content)
        assertEquals("New thinking", updatedMessage.thinking)
    }
    
    @Test
    fun `markMessageCompleteInState sets isStreaming to false`() {
        val message1 = ChatMessage(
            id = "1",
            role = ChatRole.SMARTY,
            content = "Content",
            timestamp = System.currentTimeMillis(),
            isStreaming = true
        )
        val initialState = ChatState.initial().copy(messages = listOf(message1))
        
        val newState = ChatMessageMapper.markMessageCompleteInState(
            currentState = initialState,
            messageId = "1"
        )
        
        val updatedMessage = newState.messages.first()
        assertFalse(updatedMessage.isStreaming)
    }
}
