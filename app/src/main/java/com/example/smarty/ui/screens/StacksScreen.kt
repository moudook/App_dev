package com.example.smarty.ui.screens


import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Add
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.EaseInOutSine
import android.os.Build
import com.example.smarty.data.model.Category
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.SmartyShadow
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import kotlin.math.min
import com.example.smarty.R

import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StacksScreen(
    categories: List<Category>,
    onCategoryClick: (Category) -> Unit,
    onBackClick: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onDeleteCategory: (Category) -> Unit = {},
    onRenameCategory: (Category, String) -> Unit = { _, _ -> },
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Show all categories, but keep them sorted or grouped if needed
    val visibleCategories = categories

    var showCreateSheet by remember { mutableStateOf(false) }
    val createSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Rename state
    var categoryToRename by remember { mutableStateOf<Category?>(null) }
    var showRenameSheet by remember { mutableStateOf(false) }
    val renameSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Delete confirmation state
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Intercept system back button
    BackHandler(onBack = onBackClick)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.stack),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        )
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateSheet = true },
                containerColor = LocalAccentColor.current,
                contentColor = Color.White,
                shape = RoundedCornerShape(20.dp),
                elevation = FloatingActionButtonDefaults.elevation(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.new_stack)
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Background Knowledge Graph Visualization
            KnowledgeGraphBackground(accentColor = LocalAccentColor.current)

            if (isLoading) {
                com.example.smarty.ui.components.CategoriesLoadingState(
                    count = 6,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            } else if (visibleCategories.isEmpty()) {
                com.example.smarty.ui.components.StacksEmptyState(
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 100.dp + bottomContentPadding
                    ),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalItemSpacing = 24.dp
                ) {
                    itemsIndexed(
                        items = visibleCategories,
                        key = { _, category -> category.id }
                    ) { index, category ->
                        CategoryCard(
                            category = category,
                            onClick = { onCategoryClick(category) },
                            onLongPress = {
                                categoryToRename = category
                                showRenameSheet = true
                            },
                            index = index
                        )
                    }
                }
            }
        }
    }

    // Rename category bottom sheet
    if (showRenameSheet && categoryToRename != null) {
        CreateCategoryBottomSheet(
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
                // Note: categoryToRename will be cleared after delete dialog handles it
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog && categoryToDelete != null) {
        SmartyDialog(
            title = stringResource(R.string.delete_stack),
            text = stringResource(R.string.delete_stack_confirm, categoryToDelete?.name.orEmpty().lowercase()),
            onConfirm = {
                categoryToDelete?.let { onDeleteCategory(it) }
                showDeleteDialog = false
                categoryToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                categoryToDelete = null
            },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true,
            customContent = {
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
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
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
            }
        )
    }

    // Create category bottom sheet
    if (showCreateSheet) {
        CreateCategoryBottomSheet(
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
private fun KnowledgeGraphBackground(accentColor: Color) {
    val isDark = isSystemInDarkTheme()
    val baseAlpha = if (isDark) 0.05f else 0.03f
    val infiniteTransition = rememberInfiniteTransition(label = "graph_flow")

    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Deterministic but organic-looking nodes
        val nodes = listOf(
            Offset(width * 0.15f, height * 0.25f),
            Offset(width * 0.85f, height * 0.18f),
            Offset(width * 0.45f, height * 0.45f),
            Offset(width * 0.25f, height * 0.75f),
            Offset(width * 0.75f, height * 0.82f),
            Offset(width * 0.92f, height * 0.55f),
            Offset(width * 0.08f, height * 0.60f)
        )

        // Connectivity matrix (simulating knowledge relations)
        val connections = listOf(
            0 to 2, 1 to 2, 2 to 3, 2 to 4, 4 to 5, 1 to 5, 3 to 6, 0 to 6
        )

        connections.forEach { (start, end) ->
            val p1 = nodes[start]
            val p2 = nodes[end]

            // Subtle curved connections
            val path = Path().apply {
                moveTo(p1.x, p1.y)
                val midX = (p1.x + p2.x) / 2
                val midY = (p1.y + p2.y) / 2
                // Control points for organic curves
                cubicTo(
                    midX + 40f, p1.y,
                    midX - 40f, p2.y,
                    p2.x, p2.y
                )
            }

            drawPath(
                path = path,
                color = accentColor.copy(alpha = baseAlpha * pulse),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        nodes.forEach { node ->
            // Outer soft glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accentColor.copy(alpha = baseAlpha * 2), Color.Transparent),
                    center = node,
                    radius = 12.dp.toPx() * pulse
                ),
                radius = 12.dp.toPx() * pulse,
                center = node
            )

            // Core node
            drawCircle(
                color = accentColor.copy(alpha = baseAlpha * 4),
                radius = 3.dp.toPx(),
                center = node
            )
        }
    }
}

@Composable
private fun CategoryCard(
    category: Category,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    index: Int = 0
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
    val isEmpty = category.noteCount == 0
    val cardBgColor = if (isEmpty) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val textColor = MaterialTheme.colorScheme.onSurface
    val activeAccentColor = if (isEmpty) textColor.copy(alpha = 0.3f) else accentColor

    // Modern Minimalist Card with Physical Stack Effect
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f) // Square card
            .graphicsLayer {
                scaleX = scale * pressScale
                scaleY = scale * pressScale
                this.alpha = if (isEmpty) alpha * 0.7f else alpha
            }
    ) {
        // Physical Stack Layers (Visual depth)
        val stackDepth = if (isEmpty) 0 else min(category.noteCount / 2, 3) // Max 3 visible layers

        repeat(stackDepth) { i ->
            val offset = (i + 1).dp * 4
            val layerAlpha = 0.3f / (i + 1)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = offset, y = offset)
                    .graphicsLayer {
                        shape = RoundedCornerShape(26.dp)
                        clip = true
                    }
                    .background(cardBgColor.copy(alpha = layerAlpha))
                    .border(
                        width = 1.dp,
                        color = textColor.copy(alpha = 0.05f),
                        shape = RoundedCornerShape(26.dp)
                    )
            )
        }

        // Main Card
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shadowElevation = if (isEmpty) 0f else (if (isDark) 12.dp.toPx() else 6.dp.toPx())
                    shape = RoundedCornerShape(26.dp)
                    clip = true
                }
                .background(cardBgColor)
                .then(
                    if (isEmpty) {
                        Modifier.border(
                            width = 1.dp,
                            color = textColor.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(26.dp)
                        )
                    } else Modifier
                )
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
                    .padding(20.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // TOP ROW
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    // Note Count (Top Left)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = category.noteCount.toString(),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            ),
                            color = activeAccentColor
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.notes),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 1.sp
                            ),
                            color = textColor.copy(alpha = if (isEmpty) 0.2f else 0.5f)
                        )
                    }

                    // Icon (Top Right) - Dynamic based on name
                    val categoryIcon = remember(category.name) {
                        val name = category.name.lowercase()
                        when {
                            name.contains("work") || name.contains("job") -> Icons.Default.BusinessCenter
                            name.contains("personal") || name.contains("me") -> Icons.Default.Person
                            name.contains("code") || name.contains("dev") -> Icons.Default.Code
                            name.contains("idea") || name.contains("think") -> Icons.Default.Assistant
                            else -> Icons.Default.Folder
                        }
                    }

                    Icon(
                        imageVector = categoryIcon,
                        contentDescription = null,
                        tint = textColor.copy(alpha = if (isEmpty) 0.3f else 0.8f),
                        modifier = Modifier.size(22.dp)
                    )
                }

                // MIDDLE
                // Subtle Background Pattern representing density
                if (!isEmpty) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(40.dp)) {
                        val density = min(category.noteCount, 15)
                        val spacing = size.width / (density + 1)
                        for (i in 1..density) {
                            drawCircle(
                                color = accentColor.copy(alpha = 0.15f),
                                radius = 2.dp.toPx(),
                                center = Offset(i * spacing, size.height / 2)
                            )
                        }
                    }
                } else {
                    Spacer(modifier = Modifier.height(40.dp))
                }

                // BOTTOM CONTENT
                Column {
                    Text(
                        text = if (isEmpty) stringResource(R.string.empty_stack) else stringResource(R.string.stack),
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = textColor.copy(alpha = if (isEmpty) 0.2f else 0.4f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = category.name.lowercase(),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = if (isEmpty) FontWeight.Medium else FontWeight.SemiBold,
                            lineHeight = 28.sp,
                            fontSize = 20.sp
                        ),
                        color = if (isEmpty) textColor.copy(alpha = 0.5f) else textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun Modifier.shadow(
    elevation: androidx.compose.ui.unit.Dp,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.ui.graphics.RectangleShape,
    clip: Boolean = elevation > 0.dp,
    ambientColor: Color = androidx.compose.ui.graphics.Color.Black,
    spotColor: Color = androidx.compose.ui.graphics.Color.Black,
) = this.graphicsLayer {
    this.shadowElevation = elevation.toPx()
    this.shape = shape
    this.clip = clip
    this.ambientShadowColor = ambientColor
    this.spotShadowColor = spotColor
}

/**
 * Bottom sheet for creating a new user category
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCategoryBottomSheet(
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
            // Header with Standard Icon
            Icon(
                imageVector = if (isRename) Icons.Default.Edit else Icons.Default.Add,
                contentDescription = null,
                tint = LocalAccentColor.current,
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

            // Minimalist Input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(
                        width = if (categoryName.isNotBlank()) 2.dp else 1.dp,
                        color = if (categoryName.isNotBlank()) LocalAccentColor.current else Color.Transparent,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = categoryName,
                    onValueChange = { if (it.length <= 20) categoryName = it },
                    textStyle = MaterialTheme.typography.titleMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    ),
                    cursorBrush = SolidColor(LocalAccentColor.current),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
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
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }

                    Button(
                        onClick = { onCreate(categoryName.trim()) },
                        enabled = isValid,
                        modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current,
                            contentColor = Color.White
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp
                        )
                    ) {
                        Text(
                            if (isRename) stringResource(R.string.save) else stringResource(R.string.new_stack),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }

                if (isRename) {
                    TextButton(
                        onClick = onDeleteRequest,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.delete_stack),
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
