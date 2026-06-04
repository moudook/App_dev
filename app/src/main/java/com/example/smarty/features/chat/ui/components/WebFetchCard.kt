package com.example.smarty.features.chat.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import java.util.Locale

private const val TRACK_WIDTH_DP = 120
private const val TRACK_HEIGHT_DP = 30
private const val FAVICON_SIZE_DP = 20
private const val FAVICON_GAP_DP = 8

/**
 * Card that surfaces web-fetch activity emitted by the OpenCode plugin v3
 * ([AgentEvent.WebFetchUrl] / [AgentEvent.WebFetchResult]).
 *
 * Collapsed (default):  "fetched" label + the primary domain favicon +
 *                       a truncated URL.
 * Expanded:              each fetch is rendered in its own row with the
 *                       full URL, an edge-masked row of domain favicon
 *                       circles, a "fetched N chars" result length, and a
 *                       horizontal row of clickable domain chips that
 *                       reveal per-domain details inline.
 */
@Composable
fun WebFetchCard(
    fetches: List<AgentEvent.WebFetchUrl>,
    results: List<AgentEvent.WebFetchResult>,
    modifier: Modifier = Modifier,
) {
    if (fetches.isEmpty() && results.isEmpty()) return

    val resultsByCallId = remember(results) { results.associateBy { it.callId } }
    val allDomains = remember(fetches, results) { collectDomains(fetches, results) }
    val primaryUrl = remember(fetches, results) {
        fetches.firstOrNull()?.url ?: results.firstOrNull()?.url ?: ""
    }
    val primaryDomain = remember(fetches, results) {
        fetches.firstOrNull()?.domain?.takeIf { it.isNotEmpty() }
            ?: primaryUrl.takeIf { it.isNotEmpty() }?.let { extractDomain(it) }
            ?: allDomains.firstOrNull()
            ?: ""
    }

    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
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
                Icon(
                    imageVector = Icons.Default.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = if (fetches.size > 1) "fetched ${fetches.size}" else "fetched",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (primaryDomain.isNotEmpty()) {
                    DomainFavicon(domain = primaryDomain, sizeDp = 16)
                }
                if (primaryUrl.isNotEmpty()) {
                    Text(
                        text = primaryUrl,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fetches.forEach { fetch ->
                        FetchRow(
                            fetch = fetch,
                            result = resultsByCallId[fetch.callId],
                        )
                    }
                    val matchedCallIds = fetches.map { it.callId }.toSet()
                    results.filter { it.callId !in matchedCallIds }.forEach { result ->
                        FetchRow(fetch = null, result = result)
                    }
                }
            }
        }
    }
}

@Composable
private fun FetchRow(
    fetch: AgentEvent.WebFetchUrl?,
    result: AgentEvent.WebFetchResult?,
) {
    val displayUrl = fetch?.url ?: result?.url ?: ""
    val domainList = result?.domains.orEmpty()
    val fetchDomain =
        fetch?.domain?.takeIf { it.isNotEmpty() }
            ?: displayUrl.takeIf { it.isNotEmpty() }?.let { extractDomain(it) }
            ?: ""

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (fetchDomain.isNotEmpty()) {
                DomainFavicon(domain = fetchDomain, sizeDp = 14)
            }
            Text(
                text = displayUrl,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        if (domainList.isNotEmpty()) {
            EdgeMaskedFaviconTrack(
                domains = domainList,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (result != null && result.resultLength > 0) {
            Text(
                text = "fetched ${result.resultLength} chars",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }

        if (domainList.isNotEmpty()) {
            DomainChipsRow(domains = domainList)
        }
    }
}

@Composable
private fun EdgeMaskedFaviconTrack(
    domains: List<String>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .height(TRACK_HEIGHT_DP.dp)
                .clip(RoundedCornerShape(8.dp))
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val trackPx = size.width
                    val mask =
                        Brush.horizontalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    Color.Black,
                                    Color.Black,
                                    Color.Transparent,
                                ),
                            startX = 0f,
                            endX = trackPx,
                        )
                    onDrawWithContent {
                        drawContent()
                        drawRect(brush = mask, blendMode = BlendMode.DstIn)
                    }
                },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FAVICON_GAP_DP.dp),
            modifier = Modifier.padding(horizontal = 2.dp),
        ) {
            domains.forEach { DomainFavicon(domain = it, sizeDp = FAVICON_SIZE_DP) }
        }
    }
}

@Composable
private fun DomainChipsRow(domains: List<String>) {
    var expandedDomain by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            domains.take(6).forEach { domain ->
                DomainChip(
                    domain = domain,
                    isExpanded = expandedDomain == domain,
                    onClick = {
                        expandedDomain = if (expandedDomain == domain) null else domain
                    },
                )
            }
            if (domains.size > 6) {
                Text(
                    text = "+${domains.size - 6}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expandedDomain != null) {
            val domain = expandedDomain
            if (domain != null) {
                DomainDetailRow(domain = domain)
            }
        }
    }
}

@Composable
private fun DomainChip(
    domain: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color =
            if (isExpanded) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            },
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            DomainFavicon(domain = domain, sizeDp = 12)
            Text(
                text = domain,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DomainDetailRow(domain: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border =
            BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            DomainFavicon(domain = domain, sizeDp = 18)
            Text(
                text = domain,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DomainFavicon(
    domain: String,
    sizeDp: Int,
) {
    val letter = domain.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    Box(
        modifier =
            Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = "https://$domain/favicon.ico",
            contentDescription = domain,
            modifier = Modifier.size((sizeDp - 6).coerceAtLeast(10).dp),
        )
        if (letter.isNotEmpty()) {
            Text(
                text = letter,
                fontSize = (sizeDp / 2.5f).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun extractDomain(url: String): String {
    val cleaned = url.trim().removePrefix("https://").removePrefix("http://")
    val host =
        cleaned
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    return host.lowercase(Locale.ROOT)
}

private fun collectDomains(
    fetches: List<AgentEvent.WebFetchUrl>,
    results: List<AgentEvent.WebFetchResult>,
): List<String> {
    val out = LinkedHashSet<String>()
    fetches.forEach { f ->
        val d = f.domain.takeIf { it.isNotEmpty() } ?: extractDomain(f.url)
        if (d.isNotEmpty()) out.add(d)
    }
    results.forEach { r ->
        r.domains.forEach { d -> if (d.isNotEmpty()) out.add(d) }
        if (r.domains.isEmpty()) {
            val d = extractDomain(r.url)
            if (d.isNotEmpty()) out.add(d)
        }
    }
    return out.toList()
}
