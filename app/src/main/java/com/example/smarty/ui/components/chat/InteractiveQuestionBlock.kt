package com.example.smarty.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.core.domain.model.ClarificationRequest

@Composable
fun InteractiveQuestionBlock(
    requests: List<ClarificationRequest>,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (requests.isEmpty()) return

    var currentIndex by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateListOf<String>() }

    val currentRequest = requests.getOrNull(currentIndex) ?: return

    // Dynamic theme colors instead of forced dark mode as per user request
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val dividerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
    val numberBgColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    val numberTextColor = MaterialTheme.colorScheme.surfaceVariant

    Surface(
        color = backgroundColor,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(top = 24.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            // Header Row: Question and Controls
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = currentRequest.question,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 18.sp
                    ),
                    color = textColor,
                    modifier = Modifier.weight(1f)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "< ${currentIndex + 1} of ${requests.size} >",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = textColor.copy(alpha = 0.6f)
                        )
                    )
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = textColor.copy(alpha = 0.6f),
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { onSkip() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            val handleSubmit = { answer: String ->
                answers.add(answer)
                if (currentIndex < requests.size - 1) {
                    currentIndex++
                } else {
                    // Combine all answers into a single string for now
                    val finalResponse = answers.joinToString("\n") { it }
                    onSubmit(finalResponse)
                }
            }

            // Options List
            currentRequest.options.forEachIndexed { index, option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { handleSubmit(option) }
                        .padding(vertical = 12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .background(numberBgColor, CircleShape)
                    ) {
                        Text(
                            text = "${index + 1}",
                            color = numberTextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    Text(
                        text = option,
                        style = MaterialTheme.typography.bodyLarge,
                        color = textColor,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    Text(
                        text = "→",
                        color = textColor.copy(alpha = 0.6f),
                        fontSize = 18.sp
                    )
                }
                
                HorizontalDivider(color = dividerColor)
            }

            // Custom Input Option
            if (currentRequest.allowCustomInput) {
                var isEditing by remember { mutableStateOf(false) }
                var customText by remember { mutableStateOf("") }
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .background(numberBgColor, CircleShape)
                            .clickable { isEditing = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Custom input",
                            tint = numberTextColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    if (isEditing) {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            modifier = Modifier.weight(1f),
                            textStyle = LocalTextStyle.current.copy(color = textColor),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                        Button(
                            onClick = { if (customText.isNotBlank()) handleSubmit(customText) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.Transparent,
                                contentColor = textColor
                            ),
                            elevation = null
                        ) {
                            Text("Send")
                        }
                    } else {
                        Text(
                            text = "Something else",
                            style = MaterialTheme.typography.bodyLarge,
                            color = textColor.copy(alpha = 0.6f),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { isEditing = true }
                        )
                    }
                    
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    OutlinedButton(
                        onClick = onSkip,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = textColor
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
                    ) {
                        Text("Skip")
                    }
                }
            }
        }
    }
}
