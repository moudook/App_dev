package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ChatFolder(
    val id: String = "",
    val userId: String = "",
    val name: String = "",
    val color: String = "#6200EE",
    val sortOrder: Int = 0,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    companion object {
        val defaultColors =
            listOf(
                "#6200EE",
                "#03DAC6",
                "#FF6B6B",
                "#4ECDC4",
                "#45B7D1",
                "#96CEB4",
                "#FFEAA7",
                "#DDA0DD",
                "#98D8C8",
                "#F7DC6F",
                "#BB8FCE",
                "#85C1E9",
                "#F8C471",
                "#82E0AA",
                "#F1948A",
                "#AED6F1",
            )
    }
}

@Serializable
data class ChatFolderCreateRequest(
    val name: String,
    val color: String = "#6200EE",
    val sortOrder: Int = 0,
)

@Serializable
data class ChatFoldersResponse(
    val success: Boolean,
    val folders: List<ChatFolder> = emptyList(),
    val message: String? = null,
)

@Serializable
data class ChatFolderResponse(
    val success: Boolean,
    val message: String? = null,
)

@Serializable
data class ChatFolderCreateResponse(
    val success: Boolean,
    val id: String,
    val message: String? = null,
)
