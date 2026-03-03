package com.example.smarty.ui.components.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.core.domain.model.ClarificationRequest

/**
 * Interactive bubble that displays AI clarification questions with
 * selectable options and optional custom text input.
 *
 * Once an option is selected or custom input submitted, the bubble
 * becomes disabled to prevent double-submission.
 */
@Composable
fun ClarificationBubble(
    request: ClarificationRequest,
    onSubmit: (String) -> Unit,
    accentColor: Color
) {
    var customInput by remember { mutableStateOf("") }
    var isSubmitted by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.clarification_needed),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    ),
                    color = accentColor
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = request.question,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                request.options.forEach { option ->
                    Surface(
                        onClick = {
                            if (!isSubmitted) {
                                isSubmitted = true
                                onSubmit(option)
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSubmitted) MaterialTheme.colorScheme.surfaceVariant else accentColor.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, if (isSubmitted) Color.Transparent else accentColor.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isSubmitted
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp
                            ),
                            color = if (isSubmitted) MaterialTheme.colorScheme.onSurfaceVariant else accentColor,
                            modifier = Modifier.padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            }

            if (request.allowCustomInput) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        placeholder = { Text(stringResource(R.string.other), fontSize = 14.sp) },
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isSubmitted,
                        trailingIcon = {
                            if (customInput.isNotBlank() && !isSubmitted) {
                                IconButton(
                                    onClick = {
                                        isSubmitted = true
                                        onSubmit(customInput)
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = stringResource(R.string.submit),
                                        tint = accentColor
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
