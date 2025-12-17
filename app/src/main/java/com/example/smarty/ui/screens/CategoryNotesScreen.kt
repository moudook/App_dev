package com.example.smarty.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.smarty.data.model.Category
import com.example.smarty.data.model.Note
import com.example.smarty.ui.components.NoteCard
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.util.CategoryShareManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryNotesScreen(
    category: Category,
    notes: List<Note>,
    onBackClick: () -> Unit,
    onNoteClick: (Note) -> Unit,
    onArchiveNote: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val categoryNotes = notes.filter { it.categoryId == category.id }
    var showQRDialog by remember { mutableStateOf(false) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Generate QR code when dialog is shown
    LaunchedEffect(showQRDialog) {
        if (showQRDialog && qrBitmap == null) {
            qrBitmap = CategoryShareManager.generateCategoryQRCode(category, categoryNotes, 512)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = category.name,
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "${categoryNotes.size} item${if (categoryNotes.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // QR Code button
                    IconButton(onClick = { showQRDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.QrCode2,
                            contentDescription = "Show QR Code",
                            tint = LocalAccentColor.current
                        )
                    }
                    // Share button
                    IconButton(
                        onClick = {
                            CategoryShareManager.shareCategory(
                                context = context,
                                category = category,
                                notes = categoryNotes,
                                includeQRCode = true
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Category"
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
        if (categoryNotes.isEmpty()) {
            com.example.smarty.ui.components.CategoryEmptyState(
                categoryName = category.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = categoryNotes,
                    key = { it.id }
                ) { note ->
                    NoteCard(
                        note = note,
                        onClick = { onNoteClick(note) },
                        onDelete = { /* No direct delete from category view */ },
                        onOpenTodo = { onNoteClick(note) },     // Open note detail for todos
                        isArchiveView = false,  // Category view: swipe right = archive
                        onArchive = { onArchiveNote(note.id) },
                        modifier = Modifier.animateItem()
                    )
                }
            }
        }
    }

    // QR Code Dialog
    if (showQRDialog) {
        QRCodeDialog(
            categoryName = category.name,
            noteCount = categoryNotes.size,
            qrBitmap = qrBitmap,
            onDismiss = { showQRDialog = false },
            onShare = {
                CategoryShareManager.shareCategory(
                    context = context,
                    category = category,
                    notes = categoryNotes,
                    includeQRCode = true
                )
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
                    text = "Share Category",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = categoryName,
                    style = MaterialTheme.typography.titleMedium,
                    color = LocalAccentColor.current
                )

                Text(
                    text = "$noteCount notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // QR Code
                if (qrBitmap != null) {
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color.White)
                            .padding(8.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "QR Code for $categoryName",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = LocalAccentColor.current)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Scan to import this category",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text("Close")
                    }

                    Button(
                        onClick = onShare,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LocalAccentColor.current,
                            contentColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Share")
                    }
                }
            }
        }
    }
}




