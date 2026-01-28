package com.example.smarty.ui.screens.inputstream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Category
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange

/**
 * Inline stacks/categories content that displays in the main content area.
 *
 * This replaces the full-page overlay approach - stacks are shown in the same
 * layer as note cards, behind the gradient input field.
 *
 * Fully functional: Create categories, filter notes by category, delete categories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StacksContent(
    categories: List<Category>,
    onCategoryClick: (Category) -> Unit,
    onCreateCategory: (String) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onSyncCategoryCounts: (() -> Unit)? = null  // Optional sync callback
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current

    // Sync category counts when this view is opened
    LaunchedEffect(Unit) {
        onSyncCategoryCounts?.invoke()
    }

    // Filter out empty categories
    val visibleCategories = remember(categories) {
        categories.filter { it.noteCount > 0 }
    }
    
    // Create category sheet state
    var showCreateSheet by remember { mutableStateOf(false) }
    val createSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    // Delete confirmation state
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Add Category Button (title removed - shown in action bar)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            showCreateSheet = true
                        },
                        shape = CircleShape,
                        color = accentColor,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Default.AutoAwesome, // Creative: Burst
                                contentDescription = "Create Category",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
            
            if (visibleCategories.isEmpty()) {
                item {
                    // Empty state
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 60.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Category, // Creative: Topic
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Text(
                                text = "No categories yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = "Tap + to create one",
                                style = MaterialTheme.typography.bodySmall,
                                color = accentColor.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            } else {
                // Categories in a 2-column grid pattern using pairs
                val pairs = visibleCategories.chunked(2)
                pairs.forEachIndexed { pairIndex, pair ->
                    item(key = "row_$pairIndex") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pair.forEachIndexed { index, category ->
                                InlineCategoryCard(
                                    category = category,
                                    onClick = { onCategoryClick(category) },
                                    onLongPress = {
                                        categoryToDelete = category
                                        showDeleteDialog = true
                                    },
                                    index = pairIndex * 2 + index,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Fill empty space if odd number of categories
                            if (pair.size == 1) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
            
            // Bottom spacer for input field
            item {
                Spacer(modifier = Modifier.height(100.dp))
            }
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog && categoryToDelete != null) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = "Delete Category?",
            text = "Are you sure you want to delete \"${categoryToDelete?.name}\"? Notes in this category will not be deleted.",
            onConfirm = {
                categoryToDelete?.let { onDeleteCategory(it) }
                showDeleteDialog = false
                categoryToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                categoryToDelete = null
            },
            confirmText = "Delete",
            dismissText = "Cancel",
            isDestructive = true
        )
    }
    
    // Create category bottom sheet
    if (showCreateSheet) {
        CreateCategorySheet(
            sheetState = createSheetState,
            onDismiss = { showCreateSheet = false },
            onCreate = { name ->
                onCreateCategory(name)
                showCreateSheet = false
            }
        )
    }
}

@Composable
private fun InlineCategoryCard(
    category: Category,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    index: Int,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    
    // Animation State
    var appeared by remember { mutableStateOf(false) }
    val staggerDelay = com.example.smarty.ui.animation.StaggerCalculator.fibonacci(index, 30)
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(staggerDelay.toLong())
        appeared = true
    }
    
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "appearProgress"
    )
    
    val scale = 0.8f + appearProgress * 0.2f
    val alpha = appearProgress
    
    // Press Animation
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "pressScale"
    )
    
    // Colors
    val isDark = isSystemInDarkTheme()
    val cardBgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface
    
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale * pressScale
                scaleY = scale * pressScale
                this.alpha = alpha
                shadowElevation = if (isDark) 8.dp.toPx() else 4.dp.toPx()
                shape = RoundedCornerShape(22.dp)
                clip = true
            }
            .background(cardBgColor, RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = category.noteCount.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = accentColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "NOTES",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 1.sp
                        ),
                        color = textColor.copy(alpha = 0.5f)
                    )
                }
                
                Icon(
                    imageVector = Icons.Default.Category, // Creative: Topic
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp)
                )
            }
            
            // Bottom Content
            Column {
                Text(
                    text = "Category",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = textColor.copy(alpha = 0.5f),
                        fontSize = 10.sp
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCategorySheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var categoryName by remember { mutableStateOf("") }
    val isValid = categoryName.isNotBlank() && categoryName.length <= 10
    val shapes = LocalShapes.current
    val accentColor = LocalAccentColor.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Create Category",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = categoryName,
                onValueChange = { newValue ->
                    // Allow input but validate character count (max 10 characters)
                    if (newValue.length <= 10) {
                        categoryName = newValue
                    }
                },
                label = { Text("Category name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                isError = categoryName.length > 10
            )

            Text(
                text = "${categoryName.length}/10 characters",
                style = MaterialTheme.typography.labelSmall,
                color = if (categoryName.length > 10) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onCreate(categoryName.trim()) },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = Color.White
                    )
                ) {
                    Text("Create")
                }
            }
        }
    }
}
