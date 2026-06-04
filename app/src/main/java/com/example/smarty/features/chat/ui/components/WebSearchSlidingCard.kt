package com.example.smarty.features.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.example.smarty.protocol.AgentEvent
import kotlinx.coroutines.delay

private const val TRACK_WIDTH_DP = 120
private const val TRACK_HEIGHT_DP = 30
private const val ICON_SIZE_DP = 30
private const val ICON_GAP_DP = 12
private const val SLIDE_DURATION_MS = 4000
private const val DOT_TICK_MS = 375L

/**
 * Sliding icon-track card that surfaces web-search activity emitted by the
 * OpenCode plugin v3 ([AgentEvent.WebSearchQuery] / [AgentEvent.WebSearchResult]).
 *
 * Collapsed:  "searching…" or "found" + a horizontally sliding carousel of
 *             domain favicons inside a 120x30 track with an edge mask gradient.
 * Expanded:   the same header plus a vertically stacked list of every
 *             (query, domains) pair, where each query row can be tapped to
 *             reveal its domain list.
 */
@Composable
fun WebSearchSlidingCard(
    queries: List<AgentEvent.WebSearchQuery>,
    results: List<AgentEvent.WebSearchResult>,
    modifier: Modifier = Modifier,
) {
    if (queries.isEmpty() && results.isEmpty()) return

    val isSearching by remember(queries, results) {
        derivedStateOf { hasOutstandingQueries(queries, results) }
    }
    val domains = remember(queries, results) { collectDomains(queries, results) }

    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusLabel(
                    isSearching = isSearching,
                    resultCount = results.size,
                )
                SlidingIconTrack(
                    domains = domains,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                ExpandedSearchList(
                    queries = queries,
                    results = results,
                )
            }
        }
    }
}

@Composable
private fun StatusLabel(
    isSearching: Boolean,
    resultCount: Int,
) {
    var dots by remember { mutableStateOf("") }
    LaunchedEffect(isSearching) {
        if (!isSearching) {
            dots = ""
            return@LaunchedEffect
        }
        dots = ""
        while (true) {
            delay(DOT_TICK_MS)
            dots =
                when (dots) {
                    "" -> "."
                    "." -> ".."
                    ".." -> "..."
                    else -> ""
                }
        }
    }

    val text =
        when {
            isSearching -> "searching$dots"
            resultCount == 0 -> "search"
            resultCount == 1 -> "found"
            else -> "found $resultCount"
        }

    Text(
        text = text,
        fontSize = 13.sp,
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Clip,
        modifier = Modifier.width(80.dp),
    )
}

@Composable
private fun SlidingIconTrack(
    domains: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(width = TRACK_WIDTH_DP.dp, height = TRACK_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val trackPx = TRACK_WIDTH_DP.dp.toPx()
                    val mask =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.Black, Color.Black, Color.Transparent),
                            startX = 0f,
                            endX = trackPx,
                        )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
                },
    ) {
        if (domains.isEmpty()) {
            EmptySlidingTrack()
        } else {
            AnimatedSlidingTrack(domains = domains)
        }
    }
}

@Composable
private fun EmptySlidingTrack() {
    val placeholders = remember { List(4) { Unit } }
    val transition = rememberInfiniteTransition(label = "empty-slide")
    val slide by transition.animateFloat(
        initialValue = 0f,
        targetValue = -50f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SLIDE_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "empty-slide-x",
    )

    Row(
        modifier = Modifier.graphicsLayer { translationX = slide },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_GAP_DP.dp),
    ) {
        // Two mirrored passes (same content) so the loop seam is invisible
        placeholders.forEach { DomainCirclePlaceholder(letter = "") }
        placeholders.forEach { DomainCirclePlaceholder(letter = "") }
    }
}

@Composable
private fun AnimatedSlidingTrack(domains: List<String>) {
    val transition = rememberInfiniteTransition(label = "slide")
    val slide by transition.animateFloat(
        initialValue = 0f,
        targetValue = -50f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = SLIDE_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
        label = "slide-x",
    )

    Row(
        modifier = Modifier.graphicsLayer { translationX = slide },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ICON_GAP_DP.dp),
    ) {
        // Duplicated for a seamless loop: the second half exactly mirrors the first
        domains.forEach { DomainCircle(domain = it) }
        domains.forEach { DomainCircle(domain = it) }
    }
}

@Composable
private fun DomainCircle(domain: String) {
    val fallbackLetter =
        domain
            .firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            .orEmpty()
    Box(
        modifier =
            Modifier
                .size(ICON_SIZE_DP.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = "https://$domain/favicon.ico",
            contentDescription = domain,
            modifier = Modifier.size(18.dp),
        )
        if (fallbackLetter.isNotEmpty()) {
            Text(
                text = fallbackLetter,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DomainCirclePlaceholder(letter: String) {
    Box(
        modifier =
            Modifier
                .size(ICON_SIZE_DP.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (letter.isNotEmpty()) {
            Text(
                text = letter,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ExpandedSearchList(
    queries: List<AgentEvent.WebSearchQuery>,
    results: List<AgentEvent.WebSearchResult>,
) {
    val resultsByCallId = remember(results) { results.associateBy { it.callId } }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        queries.forEach { query ->
            QueryRow(
                query = query,
                result = resultsByCallId[query.callId],
            )
        }
    }
}

@Composable
private fun QueryRow(
    query: AgentEvent.WebSearchQuery,
    result: AgentEvent.WebSearchResult?,
) {
    var showDomains by remember { mutableStateOf(false) }
    val domainList = result?.domains.orEmpty()

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .clickable { if (domainList.isNotEmpty()) showDomains = !showDomains }
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = query.query,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (domainList.isNotEmpty()) {
                Text(
                    text = "${domainList.size}",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            } else if (result == null) {
                Text(
                    text = "…",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        AnimatedVisibility(visible = showDomains && domainList.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                domainList.forEach { domain ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(start = 18.dp, top = 2.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)),
                        )
                        Text(
                            text = domain,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (result != null && result.resultLength > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${result.resultLength} chars",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 18.dp),
            )
        }
    }
}

private fun hasOutstandingQueries(
    queries: List<AgentEvent.WebSearchQuery>,
    results: List<AgentEvent.WebSearchResult>,
): Boolean {
    if (queries.isEmpty()) return false
    val knownCallIds = results.mapTo(mutableSetOf()) { it.callId }
    return queries.any { it.callId !in knownCallIds }
}

private fun collectDomains(
    queries: List<AgentEvent.WebSearchQuery>,
    results: List<AgentEvent.WebSearchResult>,
): List<String> {
    if (results.isEmpty()) return emptyList()
    val byCallId = results.associateBy { it.callId }
    val out = LinkedHashSet<String>()
    queries.forEach { q ->
        byCallId[q.callId]?.domains?.forEach { out.add(it) }
    }
    if (out.isEmpty()) {
        results.forEach { r -> r.domains.forEach { out.add(it) } }
    }
    return out.toList()
}
