package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Tag(
    val id: String = "",
    val userId: String = "",
    val name: String = "",
    val color: String = "#6200EE",
    val tagType: String = "MANUAL",
    val confidenceScore: Double = 1.0,
    val usageCount: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    companion object {
        const val TYPE_MANUAL = "MANUAL"
        const val TYPE_AUTO = "AUTO"
        const val TYPE_AI = "AI"

        val defaultColors =
            listOf(
                "#6200EE", "#03DAC6", "#FF6B6B", "#4ECDC4",
                "#45B7D1", "#96CEB4", "#FFEAA7", "#DDA0DD",
                "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E9",
                "#F8C471", "#82E0AA", "#F1948A", "#AED6F1",
            )
    }

    val displayName: String
        get() = name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    val typeLabel: String
        get() =
            when (tagType) {
                TYPE_AUTO -> "Auto"
                TYPE_AI -> "AI"
                else -> "Manual"
            }
}

@Serializable
data class TagCreateRequest(
    val name: String,
    val color: String = "#6200EE",
    val tagType: String = "MANUAL",
)

@Serializable
data class TagsResponse(
    val success: Boolean,
    val tags: List<Tag> = emptyList(),
    val message: String? = null,
)

@Serializable
data class TagResponse(
    val success: Boolean,
    val message: String? = null,
)

@Serializable
data class TagCreateResponse(
    val success: Boolean,
    val id: String,
    val message: String? = null,
)

@Serializable
data class TagNotesResponse(
    val success: Boolean,
    val notes: List<NoteForTag> = emptyList(),
    val message: String? = null,
)

@Serializable
data class NoteForTag(
    val id: String,
    val title: String,
    val content: String? = null,
    val summary: String? = null,
    val type: String? = null,
    val categoryId: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val tagsJson: String? = null,
)
