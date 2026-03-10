package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.Category
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.LocalShapes
import androidx.compose.ui.graphics.luminance
import com.example.smarty.ui.theme.rememberMonochromeAccent

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
    onRenameCategory: (Category, String) -> Unit = { _, _ -> },
    contentPadding: PaddingValues,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier,
    onSyncCategoryCounts: (() -> Unit)? = null  // Optional sync callback
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current

    // Sync category counts when this view is opened
    LaunchedEffect(Unit) {
        onSyncCategoryCounts?.invoke()
    }

    // Show all categories
    val visibleCategories = categories

    // Create category sheet state
    var showCreateSheet by remember { mutableStateOf(false) }
    val createSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Rename state
    var categoryToRename by remember { mutableStateOf<Category?>(null) }
    var showRenameSheet by remember { mutableStateOf(false) }
    val renameSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Delete confirmation state
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Add Category Button
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(modifier = Modifier.size(40.dp)) {
                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                showCreateSheet = true
                            },
                            shape = CircleShape,
                            color = accentColor,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.create_stack),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        // Directional inner glow from bottom-right
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        ) {
                            val radius = size.minDimension / 2
                            val centerX = size.width * 0.7f
                            val centerY = size.height * 0.7f
                            
                            drawCircle(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.3f),
                                        Color.Transparent
                                    ),
                                    center = androidx.compose.ui.geometry.Offset(centerX, centerY),
                                    radius = radius * 0.8f
                                )
                            )
                        }
                    }
                }
            }

            if (isLoading) {
                item {
                    com.example.smarty.ui.components.CategoriesLoadingState(
                        count = 4,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                }
            } else if (visibleCategories.isEmpty()) {
                item {
                    com.example.smarty.ui.components.StacksEmptyState(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp)
                    )
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
                                        categoryToRename = category
                                        showRenameSheet = true
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

    // Rename/Edit category sheet
    if (showRenameSheet && categoryToRename != null) {
        CreateCategorySheet(
            sheetState = renameSheetState,
            initialName = categoryToRename?.name ?: "",
            isRename = true,
            onDismiss = {
                showRenameSheet = false
                categoryToRename = null
            },
            onCreate = { newName ->
                categoryToRename?.let { onRenameCategory(it, newName) }
                showRenameSheet = false
                categoryToRename = null
            },
            onDeleteRequest = {
                categoryToDelete = categoryToRename
                showDeleteDialog = true
                showRenameSheet = false
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && categoryToDelete != null) {
        Dialog(onDismissRequest = {
            showDeleteDialog = false
            categoryToDelete = null
        }) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.delete_stack),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.delete_stack_confirm, categoryToDelete?.name.orEmpty().lowercase()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Visual reassurance that notes are safe
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Default.Folder, // Explicit path to avoid import issues
                            contentDescription = null, // Decorative icon - "Notes will be unfiled" text provides context
                            tint = LocalAccentColor.current,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.notes_will_be_unfiled, categoryToDelete?.noteCount ?: 0),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showDeleteDialog = false
                                categoryToDelete = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(stringResource(R.string.cancel))
                        }

                        Button(
                            onClick = {
                                categoryToDelete?.let { onDeleteCategory(it) }
                                showDeleteDialog = false
                                categoryToDelete = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }
        }
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
    val monochromeColor = rememberMonochromeAccent()
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
    val cardBgColor = MaterialTheme.colorScheme.surface
    val textColor = MaterialTheme.colorScheme.onSurface

    // Physical Stack Effect
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = scale * pressScale
                scaleY = scale * pressScale
                this.alpha = alpha
            }
    ) {
        // Physical Stack Layers (Visual depth)
        val stackDepth = kotlin.math.min(category.noteCount / 2, 3)

        repeat(stackDepth) { i ->
            val offset = (i + 1).dp * 3
            val layerAlpha = 0.2f / (i + 1)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = offset, y = offset)
                    .graphicsLayer {
                        shape = RoundedCornerShape(22.dp)
                        clip = true
                    }
                    .background(cardBgColor.copy(alpha = layerAlpha))
                    .border(
                        width = 1.dp,
                        color = textColor.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(22.dp)
                    )
            )
        }

        // Main Card
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shadowElevation = if (isDark) 8.dp.toPx() else 4.dp.toPx()
                    shape = RoundedCornerShape(22.dp)
                    clip = true
                }
                .background(cardBgColor)
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
                            color = monochromeColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.notes),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp,
                                letterSpacing = 1.sp
                            ),
                            color = textColor.copy(alpha = 0.5f)
                        )
                    }

                    // Dynamic Icon
                    val categoryIcon = remember(category.name) {
                        val name = category.name.lowercase()
                        when {
                            name.contains("work") || name.contains("job") -> Icons.Default.Category
                            name.contains("idea") || name.contains("think") -> Icons.Default.AutoAwesome
                            else -> Icons.Default.Category
                        }
                    }

                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null, // Decorative icon - category name displayed in text
                        tint = textColor.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Density Pattern (Subtle dots)
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxWidth().height(30.dp)) {
                    val density = kotlin.math.min(category.noteCount, 12)
                    val spacing = size.width / (density + 1)
                    for (i in 1..density) {
                        drawCircle(
                            color = monochromeColor.copy(alpha = 0.1f),
                            radius = 1.5.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(i * spacing, size.height / 2)
                        )
                    }
                }

                // Bottom Content
                Column {
                    Text(
                        text = stringResource(R.string.stack),
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = textColor.copy(alpha = 0.4f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = category.name.lowercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        ),
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCategorySheet(
    sheetState: SheetState,
    initialName: String = "",
    isRename: Boolean = false,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onDeleteRequest: () -> Unit = {}
) {
    var categoryName by remember { mutableStateOf(initialName) }
    val isValid = categoryName.isNotBlank() && categoryName.length <= 20
    val shapes = LocalShapes.current
    val accentColor = LocalAccentColor.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Icon
            Icon(
                imageVector = if (isRename) Icons.Default.Edit else Icons.Default.Add,
                contentDescription = null, // Decorative icon - title provides context
                tint = accentColor,
                modifier = Modifier.size(32.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isRename) stringResource(R.string.edit_stack) else stringResource(R.string.new_stack),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRename) stringResource(R.string.rename_your_stack) else stringResource(R.string.define_a_new_category),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = categoryName,
                    onValueChange = { if (it.length <= 20) categoryName = it },
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(accentColor),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (categoryName.isEmpty()) {
                    Text(
                        text = stringResource(R.string.stack_name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Character count
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    text = stringResource(R.string.character_count_limit, categoryName.length, 20),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (categoryName.length > 18) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Actions
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }

                    Button(
                        onClick = { onCreate(categoryName.trim()) },
                        enabled = isValid,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text(if (isRename) stringResource(R.string.save) else stringResource(R.string.new_stack))
                    }
                }

                if (isRename) {
                    TextButton(
                        onClick = onDeleteRequest,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp)) // Decorative icon - "Delete stack" text provides context
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.delete_stack))
                    }
                }
            }
        }
    }
}
