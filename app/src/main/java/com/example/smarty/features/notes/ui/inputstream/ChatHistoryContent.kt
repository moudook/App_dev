package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.core.domain.model.ChatSession
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.ChatHistoryEmptyState
import com.example.smarty.ui.components.common.SmartyDialog
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.theme.softCardShadow
import java.text.SimpleDateFormat
import java.util.*

/**
 * Redesigned Memory Lane / Journey layout for chat history.
 * Intentional design that makes browsing past conversations feel like
 * scrolling through a chronological journey, not just a utilitarian list.
 */
@Composable
fun ChatHistoryContent(
    sessions: List<ChatSession>,
    currentSessionId: String?,
    onSelectSession: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onBackToChat: () -> Unit,
    contentPadding: PaddingValues,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    var sessionToDeleteId by remember { mutableStateOf<String?>(null) }
    val sessionToDelete = sessionToDeleteId?.let { id -> sessions.find { it.id == id } }
    val accentColor = LocalAccentColor.current
    
    // Multi-select state
    val selectedSessions = remember { mutableStateListOf<String>() }
    val selectionMode = selectedSessions.isNotEmpty()
    var showMultipleDeleteDialog by remember { mutableStateOf(false) }

    // Track cumulative scale for zoom-in gesture (opposite of zoom-out in chat)
    var cumulativeScale by remember { mutableFloatStateOf(1f) }
    var pointerCount by remember { mutableIntStateOf(0) }

    // Grouping sessions for the journey timeline
    val groupedSessions = remember(sessions) {
        groupSessionsByTime(sessions)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        pointerCount = event.changes.count { it.pressed }
                        
                        if (pointerCount >= 2) {
                            val zoom = event.calculateZoom()
                            cumulativeScale *= zoom
                            
                            if (cumulativeScale > 1.3f) {
                                onBackToChat()
                                cumulativeScale = 1f 
                            }
                            
                            if (zoom < 1f && cumulativeScale < 1f) {
                                cumulativeScale = 1f
                            }
                            
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    cumulativeScale = 1f
                }
            }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 16.dp,
                bottom = contentPadding.calculateBottomPadding() + 100.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isLoading) {
                item {
                    com.example.smarty.ui.components.ChatHistoryLoadingState(
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else if (sessions.isEmpty()) {
                item {
                    ChatHistoryEmptyState(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp)
                    )
                }
            } else {
                // Intro header to set the mood
                item {
                    Column(modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                        Text(
                            text = "your",
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.Light,
                                letterSpacing = 2.sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                        )
                        Text(
                            text = "journey",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-1).sp
                            ),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }

                groupedSessions.forEach { (timeGroup, groupSessions) ->
                    item {
                        TimeGroupHeader(title = timeGroup, accentColor = accentColor)
                    }
                    
                    itemsIndexed(groupSessions, key = { _, s -> s.id }) { index, session ->
                        val isLastInGroup = index == groupSessions.lastIndex
                        MemoryLaneItem(
                            session = session,
                            isSelected = session.id == currentSessionId,
                            isSelectedForDeletion = selectedSessions.contains(session.id),
                            isLastInGroup = isLastInGroup,
                            accentColor = accentColor,
                            index = index, // Staggering animation
                            onClick = {
                                if (selectionMode) {
                                    if (selectedSessions.contains(session.id)) {
                                        selectedSessions.remove(session.id)
                                    } else {
                                        selectedSessions.add(session.id)
                                    }
                                } else {
                                    onSelectSession(session.id)
                                    onBackToChat()
                                }
                            },
                            onLongClick = {
                                if (!selectionMode) {
                                    selectedSessions.add(session.id)
                                }
                            }
                        )
                    }
                }
            }
        }

        // Multi-select PillBar (Standardized with Notes section but tailored for Chat History)
        AnimatedVisibility(
            visible = selectionMode,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)
            ) + fadeIn(),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(200)
            ) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp)
                .padding(horizontal = 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .softCardShadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(28.dp)
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side: Close button + Count
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { selectedSessions.clear() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close selection",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = accentColor.copy(alpha = 0.15f),
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = "${selectedSessions.size} selected",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = accentColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    // Right side: Delete action
                    IconButton(
                        onClick = { showMultipleDeleteDialog = true },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete",
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }

    // Multiple Delete Dialog
    if (showMultipleDeleteDialog) {
        SmartyDialog(
            title = stringResource(R.string.delete_chat),
            text = "Let go of these ${selectedSessions.size} conversations?",
            onConfirm = {
                selectedSessions.forEach { onDeleteSession(it) }
                selectedSessions.clear()
                showMultipleDeleteDialog = false
            },
            onDismiss = { showMultipleDeleteDialog = false },
            confirmText = stringResource(R.string.delete),
            dismissText = "keep them",
            isDestructive = true
        )
    }

    // Single Delete Dialog (kept for fallback)
    sessionToDelete?.let { session ->
        SmartyDialog(
            title = stringResource(R.string.delete_chat),
            text = "Let go of this conversation?",
            onConfirm = {
                onDeleteSession(session.id)
                sessionToDeleteId = null
            },
            onDismiss = { sessionToDeleteId = null },
            confirmText = stringResource(R.string.delete),
            dismissText = "keep it",
            isDestructive = true
        )
    }
}

@Composable
private fun TimeGroupHeader(title: String, accentColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 38.dp, top = 24.dp, bottom = 8.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Small glowing dot for the timeline start
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.6f))
        )
        Spacer(modifier = Modifier.width(28.dp))
        Text(
            text = title.lowercase(),
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemoryLaneItem(
    session: ChatSession,
    isSelected: Boolean,
    isSelectedForDeletion: Boolean,
    isLastInGroup: Boolean,
    accentColor: Color,
    index: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isNewChat = session.title.isBlank() ||
        session.title.equals("new_chat", ignoreCase = true) ||
        session.title.equals("new_conversation", ignoreCase = true)

    val displayTitle = if (isSelected && isNewChat) "current chat" else session.title.ifBlank { "new conversation" }.lowercase()
    val displayPreview = session.lastMessagePreview.ifBlank { "start a new conversation..." }.lowercase()

    // Staggered entrance animation
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(index * 40L)
        appeared = true
    }

    val slideOffset by animateFloatAsState(
        targetValue = if (appeared) 0f else 50f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "slide"
    )
    
    val alphaAnim by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(400),
        label = "alpha"
    )

    // Selection glow/pulse
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    // Colors
    val surfaceColor = when {
        isSelectedForDeletion -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        isSelected -> accentColor.copy(alpha = pulseAlpha)
        else -> MaterialTheme.colorScheme.surface
    }
    val onSurfaceColor = if (isSelected || isSelectedForDeletion) accentColor else MaterialTheme.colorScheme.onSurface
    val mutedColor = if (isSelected || isSelectedForDeletion) accentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .offset(y = slideOffset.dp)
            .alpha(alphaAnim)
    ) {
        // Timeline Column (Time, Dot and Line)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(48.dp)
        ) {
            // Time
            Text(
                text = formatJustTime(session.updatedAt),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp
                ),
                color = mutedColor,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
            )

            // Circle node
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) accentColor else MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(accentColor))
                }
            }

            // Connecting line (dashed if last in group, solid otherwise)
            if (!isLastInGroup) {
                Canvas(modifier = Modifier.width(2.dp).weight(1f).padding(top = 6.dp)) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(size.width / 2f, 0f),
                        end = Offset(size.width / 2f, size.height),
                        strokeWidth = 2.dp.toPx()
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Session Card
        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .softCardShadow(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp))
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick
                ),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 8.dp),
            color = surfaceColor,
            border = if (isSelectedForDeletion) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                     else if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)) 
                     else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon Area
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) accentColor else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    val hour = Calendar.getInstance().apply { timeInMillis = session.updatedAt }.get(Calendar.HOUR_OF_DAY)
                    val memoryIcon = if (isNewChat) {
                        Icons.Default.AutoAwesome
                    } else {
                        when {
                            hour in 5..11 -> Icons.Rounded.LocalCafe // Morning Coffee
                            hour in 12..17 -> Icons.Rounded.WbSunny // Afternoon Sun
                            else -> Icons.Rounded.NightsStay // Evening/Night
                        }
                    }
                    Icon(
                        imageVector = memoryIcon,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.onPrimary else onSurfaceColor,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Content Area
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = displayTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            letterSpacing = (-0.3).sp
                        ),
                        color = onSurfaceColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = displayPreview,
                        style = MaterialTheme.typography.bodySmall.copy(
                            lineHeight = 16.sp,
                            letterSpacing = 0.1.sp
                        ),
                        color = mutedColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// Logic to group sessions into meaningful time buckets for the "Journey"
private fun groupSessionsByTime(sessions: List<ChatSession>): Map<String, List<ChatSession>> {
    val now = Calendar.getInstance()
    val today = now.clone() as Calendar
    today.set(Calendar.HOUR_OF_DAY, 0)
    today.set(Calendar.MINUTE, 0)
    today.set(Calendar.SECOND, 0)
    today.set(Calendar.MILLISECOND, 0)
    
    val yesterday = today.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    
    val thisWeek = today.clone() as Calendar
    thisWeek.add(Calendar.DAY_OF_YEAR, -7)
    
    val thisMonth = today.clone() as Calendar
    thisMonth.add(Calendar.MONTH, -1)

    val grouped = mutableMapOf<String, MutableList<ChatSession>>()
    
    for (session in sessions) {
        val sessionCal = Calendar.getInstance().apply { timeInMillis = session.updatedAt }
        
        val group = when {
            sessionCal >= today -> "today"
            sessionCal >= yesterday -> "yesterday"
            sessionCal >= thisWeek -> "this week"
            sessionCal >= thisMonth -> "this month"
            else -> "older"
        }
        
        grouped.getOrPut(group) { mutableListOf() }.add(session)
    }
    
    // Ensure chronological ordering of groups
    val orderMap = mapOf("today" to 0, "yesterday" to 1, "this week" to 2, "this month" to 3, "older" to 4)
    return grouped.toSortedMap(compareBy { orderMap[it] ?: 99 })
}

private fun formatMemoryTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000 -> "just now"
        diff < 3600_000 -> "${diff / 60_000}m ago"
        diff < 86400_000 -> "${diff / 3600_000}h ago"
        else -> {
            val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(Date(timestamp)).lowercase()
        }
    }
}

private fun formatJustTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date(timestamp)).lowercase()
}
