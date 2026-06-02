package com.example.smarty.features.notes.domain

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.smarty.core.common.util.ContentTypeDetector
import com.example.smarty.core.common.util.FileStorageHelper
import com.example.smarty.core.common.util.PrivacyGuard
import com.example.smarty.core.common.util.UrlMetadataExtractor
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.NoteAttachment
import com.example.smarty.core.domain.model.NoteType
import com.example.smarty.core.domain.model.ProcessingStatus
import com.example.smarty.core.domain.model.SharedContent
import com.example.smarty.data.repository.SmartyRepository
import com.example.smarty.ui.components.PendingFileInfo
import com.example.smarty.ui.components.PendingShareData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class ShareFlowManager(
    private val repository: SmartyRepository,
    private val context: Context,
    private val scope: CoroutineScope,
    private val getNotesSnapshot: () -> List<Note>,
) {
    companion object {
        private const val TAG = "ShareFlowManager"

        private fun String.sha256(): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(this.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private val _pendingShare = MutableStateFlow<PendingShareData?>(null)
    val pendingShare: StateFlow<PendingShareData?> = _pendingShare.asStateFlow()
    private val _pendingShareFullPrivacy = MutableStateFlow(false)
    val pendingShareFullPrivacy: StateFlow<Boolean> = _pendingShareFullPrivacy.asStateFlow()
    private val _isActiveShareMode = MutableStateFlow(false)
    val isActiveShareMode: StateFlow<Boolean> = _isActiveShareMode.asStateFlow()

    fun interceptShareForPreview(sharedContent: SharedContent) {
        scope.launch {
            _isActiveShareMode.value = true
            _pendingShareFullPrivacy.value = false
            var type =
                when {
                    sharedContent.fileUri != null -> ContentTypeDetector.detectTypeFromMime(sharedContent.mimeType)
                    sharedContent.text != null -> ContentTypeDetector.detectContentType(sharedContent.text)
                    else -> NoteType.FILE
                }
            var enhancedText = sharedContent.text
            var urlMetadata: UrlMetadataExtractor.UrlMetadata? = null
            if (sharedContent.text != null && UrlMetadataExtractor.containsUrl(sharedContent.text)) {
                val url = UrlMetadataExtractor.extractUrl(sharedContent.text)
                if (url != null) {
                    Log.d(TAG, "Web clipper: Fetching metadata for URL: $url")
                    urlMetadata = UrlMetadataExtractor.fetchMetadata(url)
                    if (urlMetadata != null) {
                        Log.d(TAG, "Web clipper: Got metadata - title: ${urlMetadata.title}")
                        type = NoteType.WEBSITE
                        enhancedText =
                            buildString {
                                append(" ${urlMetadata.title}\n")
                                append(" ${urlMetadata.domain ?: url}\n")
                                urlMetadata.description?.let { append("\n$it\n") }
                                append("\nSource: $url")
                                urlMetadata.articleContent?.let { article ->
                                    append("\n\n--- Article Content ---\n\n")
                                    append(article)
                                }
                            }
                        Log.d(TAG, "Web clipper: Saved ${urlMetadata.articleContent?.length ?: 0} chars of article content")
                    }
                }
            }
            var copiedFileUri: String? = null
            var copiedFileName: String? = null
            var copiedFileSize: Long? = null
            if (sharedContent.fileUri != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val sourceUri = Uri.parse(sharedContent.fileUri)
                        val result =
                            FileStorageHelper.compressAndStore(
                                context = context,
                                sourceUri = sourceUri,
                                mimeType = sharedContent.mimeType,
                                originalFileName = sharedContent.fileName,
                            )
                        if (result != null) {
                            copiedFileUri = result.uri
                            copiedFileName = result.fileName
                            copiedFileSize = result.compressedSize
                            Log.d(TAG, "File copied immediately: ${sharedContent.fileName} -> ${result.fileName}")
                        } else {
                            copiedFileUri = sharedContent.fileUri
                            copiedFileName = sharedContent.fileName
                            copiedFileSize = sharedContent.fileSize
                            Log.w(TAG, "File copy returned null, using original URI")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy shared file immediately: ${e.message}", e)
                        copiedFileUri = sharedContent.fileUri
                        copiedFileName = sharedContent.fileName
                        copiedFileSize = sharedContent.fileSize
                    }
                }
            }
            val copiedFiles =
                if (sharedContent.files.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        sharedContent.files.mapNotNull { file ->
                            try {
                                val sourceUri = Uri.parse(file.fileUri)
                                val result =
                                    FileStorageHelper.compressAndStore(
                                        context = context,
                                        sourceUri = sourceUri,
                                        mimeType = file.mimeType,
                                        originalFileName = file.fileName,
                                    )
                                if (result != null) {
                                    Log.d(TAG, "File copied immediately: ${file.fileName} -> ${result.fileName}")
                                    PendingFileInfo(
                                        fileUri = result.uri,
                                        fileName = result.fileName,
                                        mimeType = file.mimeType,
                                        fileSize = result.compressedSize,
                                    )
                                } else {
                                    Log.w(TAG, "File copy returned null for ${file.fileName}")
                                    PendingFileInfo(
                                        fileUri = file.fileUri,
                                        fileName = file.fileName,
                                        mimeType = file.mimeType,
                                        fileSize = file.fileSize,
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to copy shared file immediately: ${e.message}", e)
                                PendingFileInfo(
                                    fileUri = file.fileUri,
                                    fileName = file.fileName,
                                    mimeType = file.mimeType,
                                    fileSize = file.fileSize,
                                )
                            }
                        }
                    }
                } else {
                    emptyList()
                }
            val relatedNotes = findRelatedNotes(sharedContent)
            _pendingShare.value =
                PendingShareData(
                    text = enhancedText,
                    fileUri = copiedFileUri ?: sharedContent.fileUri,
                    fileName = urlMetadata?.title ?: copiedFileName ?: sharedContent.fileName,
                    mimeType = sharedContent.mimeType,
                    fileSize = copiedFileSize ?: sharedContent.fileSize,
                    detectedType = type,
                    suggestedCategory = null,
                    relatedNotes = relatedNotes,
                    files = copiedFiles,
                )
        }
    }

    interface ShareConfirmCallback {
        suspend fun processNoteWithAi(note: Note)
    }

    suspend fun confirmShare(
        selectedCategory: String?,
        aiInstructions: String,
        callback: ShareConfirmCallback,
    ): Note? {
        val pending = _pendingShare.value ?: return null
        val isFullPrivacy = _pendingShareFullPrivacy.value
        val allFiles = pending.getAllFiles()
        val processedAttachments =
            allFiles.map { file ->
                NoteAttachment(
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    uri = file.fileUri,
                    fileName = file.fileName ?: "unknown",
                    mimeType =
                        file.mimeType ?: "application/octet-stream",
                    fileSize = file.fileSize ?: 0,
                )
            }
        val firstFile = allFiles.firstOrNull()
        val firstFileUri = firstFile?.fileUri
        val firstFileName = firstFile?.fileName
        val firstMimeType = firstFile?.mimeType
        val firstFileSize = firstFile?.fileSize
        if (allFiles.isEmpty() && pending.fileUri != null) Log.w(TAG, "BUG-061: No files in pending share data")
        val content =
            buildString {
                pending.text?.let { append(it) }
                if (processedAttachments.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    if (processedAttachments.size == 1) {
                        append("File: ${processedAttachments[0].fileName}")
                        append("\nType: ${processedAttachments[0].mimeType}")
                        append("\nSize: ${formatSize(context, processedAttachments[0].fileSize)}")
                    } else {
                        append("${processedAttachments.size} files attached:")
                        processedAttachments.forEachIndexed { idx, att -> append("\n${idx + 1}. ${att.fileName}") }
                    }
                }
                if (aiInstructions.isNotBlank() && !isFullPrivacy) append("\n\n[User Context: $aiInstructions]")
            }
        val title =
            when {
                processedAttachments.size > 1 -> "${processedAttachments.size} ${getTypeNamePlural(pending.detectedType)}"
                firstFileName != null -> firstFileName
                else -> generateTitle(context, pending.text ?: "", pending.detectedType)
            }
        val attachmentsJson =
            if (processedAttachments.size > 1) {
                com.google.gson
                    .Gson()
                    .toJson(processedAttachments)
            } else {
                null
            }
        val contentHash = content.sha256()
        val note =
            Note(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                title = title,
                content = content,
                fileUri = firstFileUri,
                fileName = firstFileName,
                fileMimeType = firstMimeType,
                fileSize = firstFileSize,
                imageUri = if (pending.detectedType == NoteType.IMAGE) firstFileUri else null,
                type = pending.detectedType,
                categoryName =
                    if (isFullPrivacy) {
                        context.getString(
                            com.example.smarty.R.string.category_private_notes,
                        )
                    } else {
                        selectedCategory
                    },
                processingStatus = if (isFullPrivacy || selectedCategory != null) ProcessingStatus.PENDING else ProcessingStatus.PROCESSING,
                contentHash = contentHash,
                processedContentHash = if (isFullPrivacy || selectedCategory != null) contentHash else null,
                isFullPrivacy = isFullPrivacy,
                excludeFromAiChat = isFullPrivacy,
                attachmentsJson = attachmentsJson,
            )
        repository.insertNote(note)
        try {
            if (isFullPrivacy) {
                saveNoteWithoutAiProcessing(note)
                Log.d(TAG, "Note saved in full privacy mode: id=${note.id.take(8)}...")
            } else if (selectedCategory != null) {
                val category = repository.getOrCreateCategory(selectedCategory)
                repository.updateNote(note.copy(categoryId = category.id, processingStatus = ProcessingStatus.COMPLETED))
            } else {
                callback.processNoteWithAi(note)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing shared note: ${e.message}", e)
            try {
                repository.updateNote(note.copy(processingStatus = ProcessingStatus.COMPLETED, updatedAt = System.currentTimeMillis()))
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to save fallback note: ${e2.message}", e2)
            }
        } finally {
            resetShareState()
        }
        return note
    }

    fun cancelShare() {
        resetShareState()
    }

    fun toggleFullPrivacy() {
        _pendingShareFullPrivacy.value = !_pendingShareFullPrivacy.value
    }

    fun isInShareMode(): Boolean = _isActiveShareMode.value

    private fun resetShareState() {
        _pendingShare.value = null
        _isActiveShareMode.value = false
        _pendingShareFullPrivacy.value = false
    }

    private suspend fun saveNoteWithoutAiProcessing(note: Note) {
        val category = repository.getOrCreateCategory(context.getString(com.example.smarty.R.string.category_private_notes))
        val contentHash = note.contentHash ?: note.content.sha256()
        val savedNote =
            note.copy(
                isFullPrivacy = true,
                excludeFromAiChat = true,
                categoryId = category.id,
                categoryName = category.name,
                processingStatus = ProcessingStatus.COMPLETED,
                contentHash = contentHash,
                processedContentHash = contentHash,
                summary = null,
                whySaved = null,
                updatedAt = System.currentTimeMillis(),
            )
        repository.updateNote(savedNote)
    }

    private fun findRelatedNotes(sharedContent: SharedContent): List<Note> {
        val searchText = sharedContent.text?.lowercase() ?: sharedContent.fileName?.lowercase() ?: ""
        if (searchText.length < 3) return emptyList()
        val keywords = searchText.split(Regex("[\\s,;.!?]+")).filter { it.length >= 3 }.take(5)
        return PrivacyGuard
            .getAiVisibleNotes(getNotesSnapshot())
            .filter { note ->
                keywords.any { keyword -> note.title.lowercase().contains(keyword) || note.content.lowercase().contains(keyword) }
            }.take(5)
    }

    private fun formatSize(
        context: Context,
        bytes: Long,
    ): String = ContentTypeDetector.formatSize(context, bytes)

    private fun getTypeNamePlural(type: NoteType): String =
        when (type) {
            NoteType.IMAGE -> context.getString(com.example.smarty.R.string.note_type_images)
            NoteType.VIDEO -> context.getString(com.example.smarty.R.string.note_type_videos)
            NoteType.AUDIO -> context.getString(com.example.smarty.R.string.note_type_audio_files)
            NoteType.DOCUMENT -> context.getString(com.example.smarty.R.string.note_type_documents)
            else -> context.getString(com.example.smarty.R.string.note_type_files)
        }

    private fun generateTitle(
        context: Context,
        content: String,
        type: NoteType,
    ): String =
        when {
            content.isNotBlank() && content.length > 30 -> content.take(30) + "..."
            content.isNotBlank() -> content
            else -> ContentTypeDetector.getDefaultTitle(context, type)
        }
}
