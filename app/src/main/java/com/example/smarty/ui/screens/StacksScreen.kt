package com.example.smarty.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.model.Category
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.CogniShadow
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StacksScreen(
    categories: List<Category>,
    onCategoryClick: (Category) -> Unit,
    onBackClick: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onDeleteCategory: (Category) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    var showCreateSheet by remember { mutableStateOf(false) }
    val createSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Delete confirmation state
    var categoryToDelete by remember { mutableStateOf<Category?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "stacks_",
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
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
                contentColor = MaterialTheme.colorScheme.surface
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create Category"
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        if (categories.isEmpty()) {
            com.example.smarty.ui.components.StacksEmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp + bottomContentPadding),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalItemSpacing = 12.dp
            ) {
                itemsIndexed(
                    items = categories,
                    key = { _, category -> category.id }
                ) { index, category ->
                    CategoryCard(
                        category = category,
                        onClick = { onCategoryClick(category) },
                        onLongPress = {
                            categoryToDelete = category
                            showDeleteDialog = true
                        },
                        index = index
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && categoryToDelete != null) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                categoryToDelete = null
            },
            title = { Text("Delete Category?") },
            text = {
                Text("Are you sure you want to delete \"${categoryToDelete?.name}\"? Notes in this category will not be deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    categoryToDelete?.let { onDeleteCategory(it) }
                    showDeleteDialog = false
                    categoryToDelete = null
                }) {
                    Text("Delete", color = SafetyOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    categoryToDelete = null
                }) {
                    Text("Cancel")
                }
            },
            shape = LocalShapes.current.cardMedium
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
private fun CategoryCard(
    category: Category,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    index: Int = 0
) {
    val haptic = LocalHapticFeedback.current
    val isRecentlyCreated = System.currentTimeMillis() - category.createdAt < 60000 // 1 minute

    // Staggered Entry Animation
    var appeared by remember { mutableStateOf(false) }
    val staggerDelay = com.example.smarty.ui.animation.StaggerCalculator.fibonacci(index, 30)

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(staggerDelay.toLong())
        appeared = true
    }

    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.5f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "scale"
    )

    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(300),
        label = "alpha"
    )

    // Press Animation
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "pressScale"
    )

    val shapes = LocalShapes.current
    val accentColor = LocalAccentColor.current

    // Background Animation for Card
    val infiniteTransition = rememberInfiniteTransition(label = "card_pulse")
    val offset1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 10f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset1"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale * pressScale
                scaleY = scale * pressScale
                this.alpha = alpha
            }
            .clip(RoundedCornerShape(26.dp)) // Squircle shape like reference
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
            },
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            1.dp, 
            MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // Header: Icon + AI Tag
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Icon Avatar
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = if (isRecentlyCreated) accentColor else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (category.isAiGenerated) {
                    Surface(
                        shape = CircleShape,
                        color = accentColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f))
                    ) {
                        Text(
                            text = "AI",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Body: Title
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    letterSpacing = 0.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Collection",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Footer: Divider + Count
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f),
                thickness = 1.dp
            )
            
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${category.noteCount} Items",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Bottom sheet for creating a new user category
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateCategoryBottomSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var categoryName by remember { mutableStateOf("") }
    val isValid = categoryName.isNotBlank() && categoryName.length <= 20
    val shapes = LocalShapes.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = shapes.bottomSheet,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ComponentSpacing.screenPadding)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Create Category",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = "Add a custom category to organize your notes. AI will learn to use this category for relevant content.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Input field
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        if (categoryName.isNotBlank()) 2.dp else 1.dp,
                        if (categoryName.isNotBlank()) LocalAccentColor.current else MaterialTheme.colorScheme.outline.copy(
                            alpha = 0.3f
                        ),
                        shapes.inputLarge
                    ),
                shape = shapes.inputLarge,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(
                    modifier = Modifier.padding(20.dp)
                ) {
                    BasicTextField(
                        value = categoryName,
                        onValueChange = { if (it.length <= 20) categoryName = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(LocalAccentColor.current),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (categoryName.isEmpty()) {
                        Text(
                            text = "Category name...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            // Character count
            Text(
                text = "${categoryName.length}/20 characters",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = shapes.buttonLarge
                ) {
                    Text("Cancel")
                }

                Button(
                    onClick = { onCreate(categoryName.trim()) },
                    enabled = isValid,
                    modifier = Modifier.weight(1f),
                    shape = shapes.buttonLarge,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalAccentColor.current,
                        contentColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Text("Create")
                }
            }
        }
    }
}
