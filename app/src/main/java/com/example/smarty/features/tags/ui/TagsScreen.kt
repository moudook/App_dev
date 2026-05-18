package com.example.smarty.features.tags.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.core.domain.model.Tag
import com.example.smarty.features.tags.domain.TagTypeFilter
import com.example.smarty.features.tags.domain.TagsViewModel
import com.example.smarty.ui.components.common.EmptyStatePlaceholder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    onNavigateBack: () -> Unit = {},
    onTagClick: (String) -> Unit = {},
    viewModel: TagsViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val filteredTags = viewModel.getFilteredTags()

    var showCreateDialog by remember { mutableStateOf(false) }
    var tagToEdit by remember { mutableStateOf<Tag?>(null) }
    var tagToDelete by remember { mutableStateOf<Tag?>(null) }

    BackHandler(onBack = onNavigateBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Tags",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${tags.size} tags",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Add Tag")
            }
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            // Search field
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search tags...") },
                leadingIcon = { Icon(Icons.Default.Search, "Search") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // Type filter chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(TagTypeFilter.entries) { filter ->
                    FilterChip(
                        selected = uiState.selectedTagType == filter,
                        onClick = { viewModel.setTagTypeFilter(filter) },
                        label = { Text(filter.name) },
                        leadingIcon = if (uiState.selectedTagType == filter) {
                            { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }

            // Error message
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }

            // Tags list
            Box(modifier = Modifier.fillMaxSize()) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredTags.isEmpty()) {
                    EmptyStatePlaceholder(
                        icon = Icons.Outlined.Tag,
                        title = if (uiState.searchQuery.isNotEmpty()) "No matching tags" else "No Tags",
                        subtitle = if (uiState.searchQuery.isNotEmpty()) "Try a different search" else "Tap + to create your first tag"
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filteredTags, key = { it.id }) { tag ->
                            TagItem(
                                tag = tag,
                                onClick = { onTagClick(tag.id) },
                                onEdit = { tagToEdit = tag },
                                onDelete = { tagToDelete = tag }
                            )
                        }
                    }
                }
            }
        }
    }

    // Create tag dialog
    if (showCreateDialog) {
        TagDialog(
            title = "New Tag",
            onDismiss = { showCreateDialog = false },
            onSave = { name, color ->
                viewModel.createTag(name, color)
                showCreateDialog = false
            }
        )
    }

    // Edit tag dialog
    tagToEdit?.let { tag ->
        TagDialog(
            title = "Edit Tag",
            initialName = tag.name,
            initialColor = tag.color,
            onDismiss = { tagToEdit = null },
            onSave = { name, color ->
                viewModel.updateTag(tag.copy(name = name, color = color))
                tagToEdit = null
            }
        )
    }

    // Delete confirmation dialog
    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete Tag") },
            text = { Text("Delete \"${tag.name}\"? This will also remove it from all notes.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTag(tag.id)
                        tagToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { tagToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun TagItem(
    tag: Tag,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color indicator
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = runCatching { Color(android.graphics.Color.parseColor(tag.color)) }.getOrDefault(Color.Gray),
                        shape = CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(
                    text = tag.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text(
                        text = tag.typeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${tag.usageCount} uses",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, "Edit", tint = MaterialTheme.colorScheme.primary)
            }

            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDialog(
    title: String,
    initialName: String = "",
    initialColor: String = "#6200EE",
    onDismiss: () -> Unit,
    onSave: (name: String, color: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Tag name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (name.isNotBlank()) onSave(name, selectedColor) }
                    )
                )

                Text("Color", style = MaterialTheme.typography.labelMedium)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(Tag.defaultColors) { colorHex ->
                        val color = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(Color.Gray)
                        val isSelected = colorHex == selectedColor
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(color, CircleShape)
                                .clickable { selectedColor = colorHex }
                                .then(
                                    if (isSelected) {
                                        Modifier.padding(2.dp).background(MaterialTheme.colorScheme.onSurface, CircleShape).padding(2.dp)
                                    } else Modifier
                                )
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    null,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .size(18.dp),
                                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name, selectedColor) },
                enabled = name.isNotBlank()
            ) {
                Text(if (initialName.isNotEmpty()) "Update" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
