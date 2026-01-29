package com.example.smarty.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.smarty.R
import com.example.smarty.data.model.Note
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.util.PrivacyGuard
import com.example.smarty.util.RelatedNotesProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Displays a "Related Knowledge" section showing semantically similar notes.
 * Uses the lightweight SemanticSearchEngine (no ML model required).
 * 
 * UI Design:
 * - Collapsible section with a hub icon
 * - Each related note shown as a compact card
 * - Tap to navigate to the related note
 */
@Composable
fun RelatedNotesSection(
    currentNote: Note,
    allNotes: List<Note>,
    onNoteClick: (Note) -> Unit,
    modifier: Modifier = Modifier
) {
    // Don't show for private notes
    if (currentNote.isFullPrivacy || currentNote.excludeFromAiChat) {
        return
    }
    
    // Check if we have enough data to search
    if (!RelatedNotesProvider.hasEnoughDataForRelatedSearch(currentNote)) {
        return
    }
    
    // Compute related notes (on background thread via remember + LaunchedEffect)
    var relatedNotes by remember { mutableStateOf<List<RelatedNotesProvider.RelatedNote>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(currentNote.id) {
        isLoading = true
        relatedNotes = withContext(Dispatchers.Default) {
            // Filter to only AI-visible notes
            val visibleNotes = PrivacyGuard.getAiVisibleNotes(allNotes)
            RelatedNotesProvider.findRelatedNotes(currentNote, visibleNotes)
        }
        isLoading = false
    }
    
    // Don't render if no related notes found
    if (!isLoading && relatedNotes.isEmpty()) {
        return
    }
    
    val accentColor = LocalAccentColor.current
    
    Column(modifier = modifier.fillMaxWidth()) {
        // Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Surface(
                shape = CircleShape,
                color = accentColor.copy(alpha = 0.1f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Assistant,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = stringResource(R.string.related_knowledge),
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.2.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (isLoading) {
                Spacer(modifier = Modifier.width(12.dp))
                com.example.smarty.ui.components.CalmThinkingDots(
                    dotSize = 3.dp,
                    dotSpacing = 3.dp
                )
            }
        }

        // Related Notes List
        if (isLoading) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(2) {
                    com.example.smarty.ui.components.CalmLoadingState(
                        height = 64.dp,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !isLoading && relatedNotes.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                relatedNotes.forEach { relatedNote ->
                    RelatedNoteCard(
                        note = relatedNote.note,
                        matchReason = relatedNote.matchReason,
                        score = relatedNote.score,
                        onClick = { onNoteClick(relatedNote.note) }
                    )
                }
            }
        }
    }
}

/**
 * Compact card for displaying a related note.
 */
@Composable
private fun RelatedNoteCard(
    note: Note,
    matchReason: String,
    score: Double,
    onClick: () -> Unit
) {
    val accentColor = LocalAccentColor.current
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Note type indicator
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = getNoteTypeColor(note.type).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = getNoteTypeIcon(note.type),
                    contentDescription = null,
                    tint = getNoteTypeColor(note.type),
                    modifier = Modifier.size(20.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Note info
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = note.title.lowercase(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.1.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(2.dp))

                // Match reason (subtle)
                Text(
                    text = matchReason.lowercase(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        letterSpacing = 0.3.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            
            // Relevance indicator (subtle dot)
            val dotColor = when {
                score >= 0.8 -> accentColor
                score >= 0.6 -> accentColor.copy(alpha = 0.7f)
                else -> accentColor.copy(alpha = 0.4f)
            }
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            // Chevron
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.open),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
