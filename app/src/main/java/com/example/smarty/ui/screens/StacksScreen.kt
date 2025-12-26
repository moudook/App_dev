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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
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
import android.os.Build
import com.example.smarty.data.model.Category
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.CogniShadow
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.SafetyOrange
import kotlin.math.min

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
    val accentColor = LocalAccentColor.current
    
    // Staggered Entry Animation
    var appeared by remember { mutableStateOf(false) }
    val staggerDelay = com.example.smarty.ui.animation.StaggerCalculator.fibonacci(index, 30)

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(staggerDelay.toLong())
        appeared = true
    }

    // OPTIMIZED: Single animation progress drives all derived values (reduces 3 animations to 1)
    val appearProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "appearProgress"
    )
    // Derive scale and alpha from single progress value
    val scale = 0.5f + appearProgress * 0.5f  // 0.5 -> 1.0
    val alpha = appearProgress               // 0.0 -> 1.0

    // Press Animation
    var isPressed by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 400f),
        label = "pressScale"
    )

    // Folder Stack Design
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp) // Increased card height for visibility
            .graphicsLayer {
                scaleX = scale * pressScale
                scaleY = scale * pressScale
                this.alpha = alpha
            }
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
        val density = LocalDensity.current
        
        // 1. Back Folder Panel (Dark)
        val containerShape = RoundedCornerShape(24.dp)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.75f) 
                .background(
                    color = accentColor, // Use App Theme Accent Color
                    shape = containerShape
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.1f),
                    shape = containerShape
                )
                .shadow(
                    elevation = 20.dp,
                    shape = containerShape,
                    spotColor = accentColor.copy(alpha = 0.5f) // Colored shadow
                )
        )

        // 2. Stacked Papers (Sharp - Background)
        val papersToShow = min(3, category.noteCount) // Show 0 papers when empty
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp) // Papers are inset
                // padding(top = 15.dp) removed to fix bottom leak
        ) {
            StackedPapers(papersToShow = papersToShow, isBlurred = false)
        }

        // 3. Front Glass Folder Panel
        // We use a custom shape for clipping and drawing
        // Shape definition:
        val cornerRadiusPx = with(density) { 24.dp.toPx() }
        val tabHeightPx = with(density) { 20.dp.toPx() }
        
        val folderFrontShape = remember(cornerRadiusPx, tabHeightPx) {
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density
                ): Outline {
                    val width = size.width
                    val height = size.height
                    
                    val path = Path().apply {
                         moveTo(0f, cornerRadiusPx) 
                         // Top Left Corner
                         arcTo(
                             rect = Rect(0f, 0f, cornerRadiusPx * 2, cornerRadiusPx * 2),
                             startAngleDegrees = 180f,
                             sweepAngleDegrees = 90f,
                             forceMoveTo = false
                         )
                         // Tab Line
                         lineTo(width * 0.4f, 0f) 
                         // Saling Curve down
                         cubicTo(
                            width * 0.5f, 0f, 
                            width * 0.45f, tabHeightPx + 10f, 
                            width * 0.6f, tabHeightPx + 10f
                         )
                         // Top Right Line
                         lineTo(width - cornerRadiusPx, tabHeightPx + 10f)
                         // Top Right Corner
                         arcTo(
                             rect = Rect(width - cornerRadiusPx * 2, tabHeightPx + 10f, width, tabHeightPx + 10f + cornerRadiusPx * 2),
                             startAngleDegrees = 270f,
                             sweepAngleDegrees = 90f,
                             forceMoveTo = false
                         )
                         // Right Side
                         lineTo(width, height - cornerRadiusPx)
                         // Bottom Right Corner
                         arcTo(
                             rect = Rect(width - cornerRadiusPx * 2, height - cornerRadiusPx * 2, width, height),
                             startAngleDegrees = 0f,
                             sweepAngleDegrees = 90f,
                             forceMoveTo = false
                         )
                         // Bottom Line
                         lineTo(cornerRadiusPx, height)
                         // Bottom Left Corner
                         arcTo(
                             rect = Rect(0f, height - cornerRadiusPx * 2, cornerRadiusPx * 2, height),
                             startAngleDegrees = 90f,
                             sweepAngleDegrees = 90f,
                             forceMoveTo = false
                         )
                         close()
                    }
                    return Outline.Generic(path)
                }
            }
        }

        // GLASS CONTAINER
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.6f) // Covers bottom 60%
                .clip(folderFrontShape) // Start clipping content to the glass shape
        ) {
            
            // 0. Occluder Layer
            // Hides the sharp papers that are physically behind this glass area.
            Box(
                modifier = Modifier
                .fillMaxSize()
                .background(accentColor)
            )

            // A. Blurred Papers Layer (Duplicate)
            val parentHeight = 180.dp
            val glassHeightFactor = 0.6f
            val shiftUp = parentHeight * (1f - glassHeightFactor)
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(y = -shiftUp) // Counteract the constrained box position
                    .padding(horizontal = 20.dp)
                    // padding(top = 15.dp) removed to fix bottom leak
                    .then(
                        if (Build.VERSION.SDK_INT >= 31) {
                            Modifier.blur(50.dp) // Heavily increased blur radius
                        } else {
                            Modifier // API < 31 fallback
                        }
                    )
            ) {
                 StackedPapers(papersToShow = papersToShow, isBlurred = true)
            }
            
            // B. Glass Overlay (Gradient & Tint)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.3f), // Darker glass at top
                                Color.Black.copy(alpha = 0.5f), // Medium dark in middle
                            )
                        )
                    )
            )

            // C. Border Stroke
            // Since we clipped the box, we can't easily draw a border on the outside pixels.
            // We can draw it inside.
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outline = folderFrontShape.createOutline(size, layoutDirection, this)
                val borderPath = when (outline) {
                    is Outline.Generic -> outline.path
                    else -> Path() // Fallback empty path
                }
                drawPath(
                    path = borderPath,
                    style = Stroke(width = 1.dp.toPx()),
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.4f),
                            Color.Transparent
                        )
                    )
                )
            }
            
            // D. Content on Glass (Icon, Text)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Bottom
            ) {
                // Header (Icon + AI)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Check if this is a predefined system category
                    val isPredefined = category.name.equals("Private Notes", ignoreCase = true) || 
                                     category.name.startsWith("Saved ", ignoreCase = true)

                    if (category.isAiGenerated && !isPredefined) {
                        Surface(
                            color = accentColor.copy(alpha = 0.2f),
                            shape = CircleShape,
                            modifier = Modifier.height(24.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "AI",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = accentColor
                                )
                            }
                        }
                    } else {
                         Spacer(modifier = Modifier.width(1.dp))
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Count Badge
                        Surface(
                            color = Color.Black.copy(alpha = 0.3f),
                            shape = CircleShape
                        ) {
                            Text(
                                text = "${category.noteCount} items",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }

                        // LOGICAL-002: Accessible dropdown menu for delete (alternative to long-press)
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More options",
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    onClick = {
                                        showMenu = false
                                        onLongPress()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Title
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Text(
                    text = "Collection",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun BoxScope.StackedPapers(papersToShow: Int, isBlurred: Boolean) {
    // Render visually: Back -> Front
    for (i in (0 until papersToShow).reversed()) {
         // Logic: 0 is Front, papersToShow-1 is Back
         // We loop reversed to draw Back first
        
        // Visual Index: 0 = Front, 1 = Mid, 2 = Back
        val visualIndex = i 
        
        // Stagger Logic
        // Fix: Adjusted offset to prevent bottom leaking with reduced card height
        val yOffset = (visualIndex * -10).dp + 15.dp  
        val rotation = when(visualIndex) {
            2 -> 6f // Back right
            1 -> -3f // Mid left
            0 -> 0f  // Front straight
            else -> 0f
        }
        
        val scaleVal = 1f - (visualIndex * 0.05f)

        // Paper Card
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = yOffset)
                .fillMaxWidth(0.9f * scaleVal) // Taper back stack
                .height(70.dp)
                .rotate(rotation)
                .background(
                    color = if (visualIndex == 0) Color(0xFFF5F5F5) else Color(0xFFEEEEEE), 
                    shape = RoundedCornerShape(16.dp)
                )
                .border(
                    width = 0.5.dp,
                    color = Color.Black.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp)
                )
        ) {
            // "Pill" Text Lines (Skeleton UI)
            // If blurred, we can optionally simplify the drawing to save perfume,
            // but for correctness let's draw same components.
            Column(
                modifier = Modifier
                    .padding(top = 26.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
            ) {
                // Title Pill
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(8.dp)
                        .background(Color.Gray.copy(alpha = 0.2f), CircleShape)
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Body Pills
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(6.dp)
                        .background(Color.Gray.copy(alpha = 0.1f), CircleShape)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(6.dp)
                        .background(Color.Gray.copy(alpha = 0.1f), CircleShape)
                )
                 Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(6.dp)
                        .background(Color.Gray.copy(alpha = 0.1f), CircleShape)
                )
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
