package com.example.smarty.agent.models

import kotlinx.serialization.Serializable

/**
 * Image display item for ViewImageTool callback.
 * Contains information needed to display an image inline in chat.
 */
@Serializable
data class ImageDisplayItem(
    val uri: String,
    val fileName: String,
    val noteTitle: String
)
