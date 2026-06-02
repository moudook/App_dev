package com.example.smarty.features.runtime

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.ui.components.timeline.*

@Composable
fun AgentRuntimeScreen(viewModel: AgentRuntimeViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    // Auto-connect the live stream when the screen enters composition
    LaunchedEffect(Unit) {
        viewModel.connectLiveStream()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Connection status chip
        if (!uiState.isLiveStreamConnected) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
            ) {
                Text(
                    text = "Live stream disconnected — retrying…",
                    modifier = Modifier.padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFBBF24),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(
                items = uiState.timelineNodes,
                key = { it.id },
            ) { node ->
                RuntimeTimelineNodeItem(node = node, viewModel = viewModel)
            }
            if (uiState.isRunning) {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeTimelineNodeItem(
    node: TimelineNode,
    viewModel: AgentRuntimeViewModel,
) {
    when (node) {
        is TimelineNode.ToolExecution -> ToolCallCard(node = node)
        is TimelineNode.ApprovalGate ->
            ApprovalCard(
                node = node,
                onGrant = { viewModel.sendApproval(node.toolId, true) },
                onDeny = { viewModel.sendApproval(node.toolId, false) },
                onTextSubmit = { text -> viewModel.sendApproval(node.toolId, true, text) },
            )
        is TimelineNode.ErrorNode -> ErrorCard(node = node)
        is TimelineNode.RecoveryNode -> RecoveryCard(node = node)
        is TimelineNode.SystemActivity -> SystemActivityCard(node = node)
        is TimelineNode.SubagentGroup ->
            SubagentGroupCard(
                node = node,
                nodeRenderer = { childNode -> RuntimeTimelineNodeItem(node = childNode, viewModel = viewModel) },
            )
    }
}
