package com.example.smarty.server.agent2

import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.store.memory.chat.ChatMemoryStore
import org.slf4j.LoggerFactory

class CompactingChatMemoryStore(
    private val delegate: PostgresChatMemoryStore,
    private val compactor: IntelligentCompactor,
    private val personality: String? = null,
) : ChatMemoryStore {
    private val logger = LoggerFactory.getLogger(CompactingChatMemoryStore::class.java)

    override fun getMessages(memoryId: Any): List<ChatMessage> {
        return delegate.getMessages(memoryId)
    }

    override fun updateMessages(memoryId: Any, messages: List<ChatMessage>) {
        if (compactor.shouldCompact(messages)) {
            logger.info("[CompactingChatMemoryStore] Compacting ${messages.size} messages for $memoryId")
            val plan = compactor.plan(messages, personality)
            val compacted = compactor.execute(plan)
            logger.info("[CompactingChatMemoryStore] Compacted to ${compacted.size} messages (dropped ${plan.droppedCount})")
            delegate.updateMessages(memoryId, compacted)
        } else {
            delegate.updateMessages(memoryId, messages)
        }
    }

    override fun deleteMessages(memoryId: Any) {
        delegate.deleteMessages(memoryId)
    }
}
