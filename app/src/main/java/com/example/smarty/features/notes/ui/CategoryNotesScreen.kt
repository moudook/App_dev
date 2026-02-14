package com.example.smarty.features.notes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.Category
import com.example.smarty.core.domain.model.Note
import com.example.smarty.ui.components.CategoryEmptyState
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.core.common.util.CategoryShareManager
import kotlinx.coroutines.launch
import kotlin.math.min

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryNotesScreen(
    category: Category,
    notes: List<Note>,
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    onEditCategory: (Category) -> Unit = {},
    onDeleteCategory: (Category) -> Unit = {},
    bottomContentPadding: androidx.compose.ui.unit.Dp = 0.dp,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Use derivedStateOf to avoid filtering on every recomposition
    val categoryNotes by remember(category.id, notes) {
        derivedStateOf { notes.filter { it.categoryId == category.id } }
    }
    var showQRDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Delete confirmation state
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Generate QR code when dialog is shown
    LaunchedEffect(showQRDialog) {
        if (showQRDialog && qrBitmap == null) {
            qrBitmap = CategoryShareManager.generateCategoryQRCode(category, categoryNotes, 512)
        }
    }

    // Intercept system back button
    BackHandler(onBack = onBackClick)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = category.name.lowercase(),
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.5).sp
                            )
                        )
                        Text(
                            text = stringResource(R.string.items_count, categoryNotes.size),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp,
                                color = LocalAccentColor.current.copy(alpha = 0.7f)
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
                actions = {
                    // QR Code button
                    IconButton(onClick = { showQRDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.show_qr_code),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    // Edit button
                    IconButton(onClick = { onEditCategory(category) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.edit_stack),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete_stack),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                        )
                    }
                    // Share button
                    IconButton(
                        onClick = {
                            scope.launch {
                                CategoryShareManager.shareCategory(
                                    context = context,
                                    category = category,
                                    notes = categoryNotes,
                                    includeQRCode = true
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_stack),
                            tint = LocalAccentColor.current
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        val accentColor = LocalAccentColor.current
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {


            if (isLoading) {
                com.example.smarty.ui.components.NotesLoadingState(
                    count = 4,
                    modifier = Modifier.fillMaxSize().padding(16.dp)
                )
            } else if (categoryNotes.isEmpty()) {
                com.example.smarty.ui.components.CategoryEmptyState(
                    categoryName = category.name,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = 16.dp + bottomContentPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = categoryNotes,
                        key = { it.id }
                    ) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onNoteClick(note) },
                            onDelete = { /* Handled via swipe to archive in this view */ },
                            onOpenTodo = { onNoteClick(note) },
                            isArchiveView = false,
                            onArchive = { onArchiveNote(note.id) },
                            onLongPress = { /* Optional: can enable Pinterest menu here too */ },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        com.example.smarty.ui.components.common.SmartyDialog(
            title = stringResource(R.string.delete_stack),
            text = stringResource(R.string.delete_stack_confirm, category.name.lowercase()),
            onConfirm = {
                onDeleteCategory(category)
                showDeleteDialog = false
            },
            onDismiss = {
                showDeleteDialog = false
            },
            confirmText = stringResource(R.string.delete),
            dismissText = stringResource(R.string.cancel),
            isDestructive = true
        )
    }

    // QR Code Dialog
    if (showQRDialog) {
        QRCodeDialog(
            categoryName = category.name,
            noteCount = categoryNotes.size,
            qrBitmap = qrBitmap,
            onDismiss = { showQRDialog = false },
            onShare = {
                scope.launch {
                    CategoryShareManager.shareCategory(
                        context = context,
                        category = category,
                        notes = categoryNotes,
                        includeQRCode = true
                    )
                }
                showQRDialog = false
            }
        )
    }
}

@Composable
private fun QRCodeDialog(
    categoryName: String,
    noteCount: Int,
    qrBitmap: Bitmap?,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.border(
                1.dp,
                MaterialTheme.colorScheme.outline,
                RoundedCornerShape(18.dp)
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.share_stack),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = categoryName.lowercase(),
                    style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 0.2.sp),
                    color = LocalAccentColor.current
                )

                Text(
                    text = stringResource(R.string.notes_count, noteCount),
                    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                // QR Code
                if (qrBitmap != null) {
                    // Use a slightly softer white in dark mode to reduce harsh contrast, but keep it light for scanning
                    val qrBackground = if (isSystemInDarkTheme()) androidx.compose.ui.graphics.Color(0xFFF5F5F5) else androidx.compose.ui.graphics.Color.White

                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(qrBackground)
                            .padding(8.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.qr_code_description, categoryName),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = LocalAccentColor.current,
                            strokeWidth = 2.dp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.scan_to_import_this_stack),
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(stringResource(R.string.close), style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current,
                            contentColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.share), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}




