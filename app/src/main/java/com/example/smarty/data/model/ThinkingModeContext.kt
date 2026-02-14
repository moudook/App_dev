package com.example.smarty.data.model
 
import com.example.smarty.core.domain.model.Note

data class ThinkingModeContext(
    val isThinkingMode: Boolean = false,
    val targetNote: Note? = null,
    val relatedNotes: List<Note> = emptyList(),
    val userQuery: String = "",
    val fullDocumentContent: String = "",
    val documentFileName: String? = null,
    val documentChunks: List<DocumentChunk> = emptyList(),
    val totalChars: Int = 0,
    val totalChunks: Int = 0
) {
    companion object {
        const val MAX_DOCUMENT_SIZE = 50000
        const val CHARS_PER_CHUNK = 12000
        const val OVERLAP_CHARS = 1000
    }
}
