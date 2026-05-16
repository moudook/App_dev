package com.example.smarty.features.searchhistory.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.components.common.EmptyStatePlaceholder

/**
 * Search History Screen
 * Displays user's search history with ability to view and clear
 * Reuses: EmptyStatePlaceholder, Card, IconButton
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHistoryScreen(
    searchHistory: List<SearchHistoryItem>,
    isLoading: Boolean = false,
    onSearchItemClick: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onDeleteItem: (String) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Search History",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${searchHistory.size} searches",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (searchHistory.isNotEmpty()) {
                        IconButton(onClick = onClearHistory) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                "Clear All",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (searchHistory.isEmpty()) {
                EmptyStatePlaceholder(
                    icon = Icons.Default.Search,
                    title = "No Search History",
                    subtitle = "Your recent searches will appear here.",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchHistory, key = { it.id }) { item ->
                        SearchHistoryItem(
                            item = item,
                            onClick = { onSearchItemClick(item.query) },
                            onDeleteClick = { onDeleteItem(item.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHistoryItem(
    item: SearchHistoryItem,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.query,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = item.scope,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${item.resultCount} results",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Default.Close,
                    "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

data class SearchHistoryItem(
    val id: String,
    val query: String,
    val scope: String,
    val resultCount: Int,
    val timestamp: Long
)
