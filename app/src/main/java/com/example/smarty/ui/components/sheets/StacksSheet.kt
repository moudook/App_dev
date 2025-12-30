package com.example.smarty.ui.components.sheets

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.smarty.data.model.Category
import com.example.smarty.ui.screens.StacksScreen

/**
 * Stacks/Categories as a full-page overlay.
 * Completely covers the main screen for focused, centralized experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StacksSheet(
    categories: List<Category>,
    sheetState: SheetState, // Keep for API compatibility, but not used
    onDismiss: () -> Unit,
    onCategoryClick: (Category) -> Unit,
    onCreateCategory: (String) -> Unit,
    onDeleteCategory: (Category) -> Unit = {}
) {
    // Handle back button
    BackHandler(enabled = true) {
        onDismiss()
    }

    // Full-page overlay
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        StacksScreen(
            categories = categories,
            onCategoryClick = { category ->
                onCategoryClick(category)
            },
            onBackClick = onDismiss,
            onCreateCategory = onCreateCategory,
            onDeleteCategory = onDeleteCategory
        )
    }
}
