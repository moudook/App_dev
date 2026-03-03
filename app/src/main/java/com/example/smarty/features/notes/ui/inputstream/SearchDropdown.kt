package com.example.smarty.features.notes.ui.inputstream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor

/**
 * Displays a dropdown of recent search suggestions.
 * Shows when search field is focused and has suggestions available.
 */
@Composable
fun SearchSuggestionsDropdown(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.recent_searches),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            TextButton(onClick = onClearHistory) {
                Text(
                    stringResource(R.string.clear),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalAccentColor.current.copy(alpha = 0.7f)
                )
            }
        }

        suggestions.forEach { suggestion ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionClick(suggestion) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = suggestion,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
