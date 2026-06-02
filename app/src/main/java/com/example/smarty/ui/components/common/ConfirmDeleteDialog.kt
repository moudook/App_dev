package com.example.smarty.ui.components.common

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

/**
 * Generic Confirm Delete Dialog.
 *
 * Single Responsibility: Only handles delete confirmation UI.
 * DRY: Replaces repeated delete dialog patterns in 5+ screens.
 *
 * @param title Dialog title (default: "Delete")
 * @param message Confirmation message (default: "Are you sure?")
 * @param confirmText Confirm button text (default: "Delete")
 * @param dismissText Dismiss button text (default: "Cancel")
 * @param isDestructive Whether to show destructive styling
 * @param onConfirm Confirm action
 * @param onDismiss Dismiss action
 */
@Composable
fun <T> ConfirmDeleteDialog(
    item: T?,
    title: String = "Delete",
    message: String = "Are you sure you want to delete this item?",
    confirmText: String = "Delete",
    dismissText: String = "Cancel",
    isDestructive: Boolean = true,
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    if (item != null) {
        SmartyDialog(
            title = title,
            text = message,
            onConfirm = { onConfirm(item) },
            onDismiss = onDismiss,
            confirmText = confirmText,
            dismissText = dismissText,
            isDestructive = isDestructive,
        )
    }
}

/**
 * Simple confirm dialog without item parameter.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    SmartyDialog(
        title = title,
        text = message,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmText = confirmText,
        dismissText = dismissText,
        isDestructive = isDestructive,
    )
}

/**
 * Generic action dialog.
 */
@Composable
fun <T> ActionDialog(
    item: T?,
    title: String,
    message: String,
    actionText: String,
    isDestructive: Boolean = false,
    onAction: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    if (item != null) {
        SmartyDialog(
            title = title,
            text = message,
            onConfirm = { onAction(item) },
            onDismiss = onDismiss,
            confirmText = actionText,
            isDestructive = isDestructive,
        )
    }
}
