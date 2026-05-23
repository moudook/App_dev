package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.core.domain.model.Note
import com.example.smarty.core.domain.model.TodoItem
import com.example.smarty.core.domain.model.getTodos
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.MonoFont
import com.example.smarty.ui.theme.softCardShadow
import com.example.smarty.ui.theme.activeGlow
import java.util.UUID

/**
 * Bottom sheet for managing todos associated with a note.
 * Shows original note content and allows adding/managing todo items.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteTodoSheet(
    note: Note,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSaveTodos: (List<TodoItem>) -> Unit
) {
    // Use note.id AND note.todoContent as keys to properly refresh when todos change
    var todos by remember(note.id, note.todoContent) { mutableStateOf(note.getTodos()) }
    // Reset text input when note changes to avoid stale text from previous note
    var newTodoText by remember(note.id) { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                LocalAccentColor.current.copy(alpha = 0.3f),
                                LocalAccentColor.current,
                                LocalAccentColor.current.copy(alpha = 0.3f)
                            )
                        )
                    )
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // 
            // HEADER with accent styling
            // 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Accent dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(LocalAccentColor.current)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.tasks),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Close button with subtle background
                Surface(
                    modifier = Modifier.size(36.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 
            // PROGRESS BAR - Visual completion status
            // 
            val completedCount = todos.count { it.isCompleted }
            val totalCount = todos.size
            val progress by animateFloatAsState(
                targetValue = if (totalCount > 0) completedCount.toFloat() / totalCount else 0f,
                animationSpec = spring(stiffness = 100f),
                label = "progress"
            )
            
            if (totalCount > 0) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.tasks_completed, completedCount, totalCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.percentage_format, (progress * 100).toInt()),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = MonoFont
                            ),
                            color = LocalAccentColor.current
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Progress track
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        // Progress fill with gradient
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progress)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(3.dp))
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(
                                            LocalAccentColor.current,
                                            LocalAccentColor.current.copy(alpha = 0.7f)
                                        )
                                    )
                                )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // 
            // ORIGINAL NOTE - Card with icon (dark mode optimized)
            // 
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .softCardShadow(shape = RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                color = LocalAccentColor.current.copy(alpha = 0.1f), // Electric Blue tint
                border = BorderStroke(1.dp, LocalAccentColor.current.copy(alpha = 0.3f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    // Note icon
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Notes,
                        contentDescription = null,
                        tint = LocalAccentColor.current,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = note.content,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = MonoFont,
                            lineHeight = 20.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 
            // TASKS SECTION LABEL
            // 

            // Todo items list - all actions auto-save
            if (todos.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(todos, key = { it.id }) { todo ->
                        TodoItemRow(
                            todo = todo,
                            onToggle = {
                                val updatedTodos = todos.map {
                                    if (it.id == todo.id) it.copy(isCompleted = !it.isCompleted)
                                    else it
                                }
                                todos = updatedTodos
                                // Auto-save when toggling
                                onSaveTodos(updatedTodos)
                            },
                            onDelete = {
                                val updatedTodos = todos.filter { it.id != todo.id }
                                todos = updatedTodos
                                // Auto-save when deleting
                                onSaveTodos(updatedTodos)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Add new todo input - dark mode optimized
            val isInputActive = newTodoText.isNotEmpty()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isInputActive) Modifier.activeGlow(shape = RoundedCornerShape(16.dp))
                        else Modifier.softCardShadow(shape = RoundedCornerShape(16.dp))
                    ),
                shape = RoundedCornerShape(16.dp),
                color = LocalAccentColor.current.copy(alpha = 0.1f), // Electric Blue tint
                border = BorderStroke(
                    1.5.dp,
                    if (isInputActive) LocalAccentColor.current else LocalAccentColor.current.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 14.dp)
                    ) {
                        BasicTextField(
                            value = newTodoText,
                            onValueChange = { newTodoText = it },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = MonoFont,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(LocalAccentColor.current),
                            singleLine = true
                        )
                        if (newTodoText.isEmpty()) {
                            Text(
                                text = stringResource(R.string.add_a_new_task),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }

                    // Add button - auto-saves when adding new todo
                    AnimatedVisibility(
                        visible = newTodoText.isNotBlank(),
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        IconButton(
                            onClick = {
                                if (newTodoText.isNotBlank()) {
                                    // Parse input - could be multiple items separated by newlines or commas
                                    val newItems = parseNewTodos(newTodoText)
                                    val updatedTodos = todos + newItems
                                    todos = updatedTodos
                                    newTodoText = ""
                                    // Auto-save immediately when adding todo
                                    onSaveTodos(updatedTodos)
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.add),
                                tint = LocalAccentColor.current
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.task_tip),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )


        }
    }
}


/**
 * Individual todo item row with custom circular checkbox, text, and delete button
 * Features: Animated checkbox, smooth color transitions, premium styling
 */
@Composable
private fun TodoItemRow(
    todo: TodoItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    // Animations for smooth transitions
    val scale by animateFloatAsState(
        targetValue = if (todo.isCompleted) 0.98f else 1f,
        animationSpec = spring(stiffness = 300f),
        label = "scale"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (todo.isCompleted) 1f else 0f,
        animationSpec = spring(stiffness = 400f),
        label = "checkScale"
    )
    val targetBgColor = if (todo.isCompleted) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        LocalAccentColor.current.copy(alpha = 0.1f) // Electric Blue tint
    }
    val backgroundColor by animateColorAsState(
        targetValue = targetBgColor,
        animationSpec = tween(200),
        label = "bgColor"
    )
    val textAlpha by animateFloatAsState(
        targetValue = if (todo.isCompleted) 0.5f else 1f,
        animationSpec = tween(200),
        label = "textAlpha"
    )
    
    val accentColor = LocalAccentColor.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(backgroundColor)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onToggle() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Custom circular checkbox
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(
                    width = 2.dp,
                    color = if (todo.isCompleted) accentColor else MaterialTheme.colorScheme.outline,
                    shape = CircleShape
                )
                .background(
                    if (todo.isCompleted) accentColor else MaterialTheme.colorScheme.surface
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            // Animated checkmark
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .size(14.dp)
                    .scale(checkScale)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Task text
        Text(
            text = todo.text,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = MonoFont,
                fontWeight = if (todo.isCompleted) FontWeight.Normal else FontWeight.Medium
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            textDecoration = if (todo.isCompleted) TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
            maxLines = 5,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Delete button - subtle until hovered/focused
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f))
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = stringResource(R.string.remove_task),
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

// Precompiled regex for todo item splitting (avoids re-creation on every parse)
private val TODO_SPLIT_REGEX = Regex("[\\n,;]|[-•*]\\s")

/**
 * Parse input text into todo items.
 * Supports multiple items separated by newlines, commas, or bullet points.
 */
private fun parseNewTodos(input: String): List<TodoItem> {
    // Split by newlines, commas, or common bullet characters
    val items = input
        .split(TODO_SPLIT_REGEX)
        .map { it.trim() }
        .filter { it.isNotBlank() }

    return items.map { text ->
        TodoItem(
            id = UUID.randomUUID().toString(),
            text = text,
            isCompleted = false
        )
    }
}
