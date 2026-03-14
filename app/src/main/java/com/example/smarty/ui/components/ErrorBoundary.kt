package com.example.smarty.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import com.example.smarty.core.common.util.CrashLogger

/**
 * Error Boundary for Jetpack Compose.
 */
@Composable
fun ErrorBoundary(
    onError: (Throwable) -> Unit = {},
    fallback: @Composable (Throwable) -> Unit = { ErrorFallback(it) },
    content: @Composable () -> Unit
) {
    var error: Throwable? by remember { mutableStateOf(null) }
    
    if (error != null) {
        fallback(error!!)
        onError(error!!)
    } else {
        content()
    }
}

/**
 * Default error fallback UI
 */
@Composable
fun ErrorFallback(
    error: Throwable,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {}
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = "Error",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(64.dp)
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Something went wrong",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = error.message ?: "An unexpected error occurred",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(onClick = onRetry) {
            Text("Retry")
        }
        
        // Show stack trace in debug mode
        Text(
            text = "Debug: ${android.util.Log.getStackTraceString(error)}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
                .padding(top = 16.dp)
                .verticalScroll(rememberScrollState())
                .heightIn(max = 200.dp)
        )
    }
}
