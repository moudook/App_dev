package com.example.smarty.core.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class Notification(
    val id: String = "",
    val userId: String = "",
    val type: String = "",
    val title: String = "",
    val body: String? = null,
    val data: String = "{}",
    val isRead: Boolean = false,
    val readAt: String? = null,
    val createdAt: String? = null,
) {
    companion object {
        const val TYPE_INFO = "info"
        const val TYPE_WARNING = "warning"
        const val TYPE_SUCCESS = "success"
        const val TYPE_ERROR = "error"
        const val TYPE_DIGEST = "digest"
        const val TYPE_REMINDER = "reminder"
        const val TYPE_SYSTEM = "system"
    }

    val isUnread: Boolean get() = !isRead
}

@Serializable
data class NotificationsResponse(
    val success: Boolean,
    val notifications: List<Notification> = emptyList(),
    val message: String? = null,
)

@Serializable
data class NotificationResponse(
    val success: Boolean,
    val message: String? = null,
)