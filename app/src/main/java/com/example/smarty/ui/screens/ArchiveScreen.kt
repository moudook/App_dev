package com.example.smarty.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.Note
import com.example.smarty.ui.animation.JarvisEasing
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.LocalAccentColor
import kotlinx.coroutines.launch

/**
 * Archive screen showing all archived notes.
 * - Swipe right: Permanent delete (with confirmation)
 * - Swipe left: Unarchive (restore to main view)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    archivedNotes: List<Note>,
    onBackClick: () -> Unit,
    onDeleteNote: (String) -> Unit,
    onUnarchiveNote: (String) -> Unit,
    isEmbedded: Boolean = false,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Delete confirmation state
    var noteToDelete by remember { mutableStateOf<Note?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Safe Haven Background (Concentric Rings)
        SafeHavenBackground(accentColor = LocalAccentColor.current)

        if (isEmbedded) {
            // Embedded content (no Scaffold)
            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading) {
                    com.example.smarty.ui.components.NotesLoadingState(
                        count = 5,
                        modifier = Modifier.fillMaxSize().padding(16.dp)
                    )
                } else if (archivedNotes.isEmpty()) {
                    com.example.smarty.ui.components.ArchiveEmptyState(
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 100.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
                    ) {
                        items(
                            items = archivedNotes,
                            key = { it.id }
                        ) { note ->
                            ArchiveNoteItem(
                                note = note,
                                onDelete = {
                                    noteToDelete = note
                                    showDeleteDialog = true
                                },
                                onUnarchive = { onUnarchiveNote(note.id) }
                            )
                        }
                    }
                }
            }
        } else {
            // Intercept system back button
            androidx.activity.compose.BackHandler(onBack = onBackClick)

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(
                                    text = stringResource(R.string.archive),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = (-1).sp
                                    )
                                )
                                Text(
                                    text = stringResource(R.string.preserved_items, archivedNotes.size),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp,
                                        color = LocalAccentColor.current.copy(alpha = 0.6f)
                                    )
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onBackClick) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { paddingValues ->
                if (isLoading) {
                    com.example.smarty.ui.components.NotesLoadingState(
                        count = 5,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp)
                    )
                } else if (archivedNotes.isEmpty()) {
                    // Empty state
                    com.example.smarty.ui.components.ArchiveEmptyState(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = 100.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(ComponentSpacing.listItemGap)
                    ) {
                        items(
                            items = archivedNotes,
                            key = { it.id }
                        ) { note ->
                            ArchiveNoteItem(
                                note = note,
                                onDelete = {
                                    noteToDelete = note
                                    showDeleteDialog = true
                                },
                                onUnarchive = { onUnarchiveNote(note.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog && noteToDelete != null) {
        com.example.smarty.ui.components.common.JarvisDialog(
            title = stringResource(R.string.delete_permanently),
            text = stringResource(R.string.archive_delete_warning),
            onConfirm = {
                noteToDelete?.let { onDeleteNote(it.id) }
                showDeleteDialog = false
                noteToDelete = null
            },
            onDismiss = {
                showDeleteDialog = false
                noteToDelete = null
            },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }
}

@Composable
private fun SafeHavenBackground(accentColor: Color) {
    val isDark = isSystemInDarkTheme()
    val baseAlpha = if (isDark) 0.03f else 0.02f
    val infiniteTransition = rememberInfiniteTransition(label = "safe_pulse")

    val breathe by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathe"
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height / 3f // Offset towards top for visual weight

        // Draw soft concentric rings
        for (i in 1..4) {
            val radius = (100.dp.toPx() * i) * breathe
            drawCircle(
                color = accentColor.copy(alpha = baseAlpha / i),
                radius = radius,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/**
 * Note item for archive view with staggered animation
 */
@Composable
private fun ArchiveNoteItem(
    note: Note,
    onDelete: () -> Unit,
    onUnarchive: () -> Unit
) {
    // Track if item has appeared
    var appeared by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        appeared = true
    }

    // Scale animation with spring
    val scale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.85f,
        animationSpec = spring(
            dampingRatio = 0.7f,
            stiffness = 300f
        ),
        label = "itemScale"
    )

    // Alpha animation
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(200, easing = JarvisEasing.appleEaseOut),
        label = "itemAlpha"
    )

    NoteCard(
        note = note,
        onClick = { /* No click action in archive */ },
        onDelete = onDelete,  // Permanent delete
        onOpenTodo = { /* No todo in archive */ },
        isArchiveView = true,  // Archive view: swipe right = delete, swipe left = unarchive
        onUnarchive = onUnarchive,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha * 0.7f // Desaturated for "preserved" look
            }
    )
}
