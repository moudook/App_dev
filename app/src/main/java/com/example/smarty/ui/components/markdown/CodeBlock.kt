package com.example.smarty.ui.components.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Renders a fenced code block with language label and copy-to-clipboard support.
 *
 * @param code The code content to display
 * @param language The programming language for the header label
 * @param backgroundColor Background color for the code area
 * @param borderColor Border color around the block
 * @param headerBgColor Background for the language/copy header bar
 */
@Composable
fun CodeBlock(
    code: String,
    language: String,
    backgroundColor: Color,
    borderColor: Color,
    headerBgColor: Color = Color(0xFF343541)
) {
    val clipboardManager = LocalClipboardManager.current
    var isCopied by remember { mutableStateOf(false) }
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    // ElevenLabs Theme: Zinc colors passed from parent
    val textColor = if (isDark) Color(0xFFE4E4E7) else Color(0xFF18181B) // Zinc-200 / Zinc-950
    val headerColor = headerBgColor

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(headerColor)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = language.ifBlank { "code" }.lowercase(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                ),
                color = if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666)
            )

            Row(
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(code))
                        isCopied = true
                    }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = "Copy code",
                    tint = if (isCopied) MaterialTheme.colorScheme.primary else if (isDark) Color(0xFFAAAAAA) else Color(0xFF666666),
                    modifier = Modifier.size(14.dp)
                )
                if (isCopied) {
                    Text(
                        text = "Copied",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LaunchedEffect(Unit) {
                        delay(2000)
                        isCopied = false
                    }
                }
            }
        }

        Box(modifier = Modifier.padding(12.dp)) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 15.sp,
                    lineHeight = 23.sp
                ),
                color = textColor,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}
