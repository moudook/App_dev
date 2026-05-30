package com.example.smarty.ui.components.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.squishClick

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    availableModels: List<Pair<String, String>>,
    modelVariantMap: Map<String, List<String>>,
    selectedModel: String,
    selectedVariant: String?,
    onModelSelected: (String) -> Unit,
    onVariantSelected: (String?) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    if (isVisible) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = Color.Transparent, // We'll draw our own premium surface
            dragHandle = null
        ) {
            val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
            val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
            val borderColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                shape = RoundedCornerShape(28.dp),
                color = surfaceColor,
                border = border(1.dp, borderColor, RoundedCornerShape(28.dp)),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    // Drag handle & Title
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.Gray.copy(alpha = 0.3f))
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Select AI Model",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isDark) Color.White else Color.Black
                        )
                    }

                    // Content
                    var expandedVariantModel by remember { mutableStateOf<String?>(null) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        availableModels.forEach { (modelId, label) ->
                            val cleanLabel = label
                                .replace(Regex("(?i)-free\\b"), "")
                                .replace(Regex("(?i)\\bfree\\b"), "")
                                .replace(Regex("(?i)\\s*\\(free\\)"), "")
                                .trim()
                            
                            val variants = modelVariantMap[modelId]
                            val hasVariants = !variants.isNullOrEmpty()
                            val isExpanded = expandedVariantModel == modelId
                            val isSelectedModel = selectedModel == modelId

                            ModelRow(
                                label = cleanLabel,
                                isSelected = isSelectedModel && (selectedVariant == null || !hasVariants),
                                hasVariants = hasVariants,
                                isExpanded = isExpanded,
                                onClick = {
                                    if (hasVariants) {
                                        expandedVariantModel = if (isExpanded) null else modelId
                                    } else {
                                        onModelSelected(modelId)
                                        onDismiss()
                                    }
                                }
                            )

                            // Variants Dropdown
                            AnimatedVisibility(
                                visible = isExpanded && hasVariants,
                                enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) + fadeIn(),
                                exit = shrinkVertically(tween(200)) + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 24.dp, top = 4.dp, bottom = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    variants?.forEach { variant ->
                                        val isSelectedVariant = isSelectedModel && variant == selectedVariant
                                        VariantRow(
                                            label = variant,
                                            isSelected = isSelectedVariant,
                                            onClick = {
                                                onModelSelected(modelId)
                                                onVariantSelected(variant)
                                                onDismiss()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun border(width: androidx.compose.ui.unit.Dp, color: Color, shape: androidx.compose.ui.graphics.Shape): androidx.compose.foundation.BorderStroke {
    return androidx.compose.foundation.BorderStroke(width, color)
}

@Composable
private fun ModelRow(
    label: String,
    isSelected: Boolean,
    hasVariants: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    
    val bg = if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent
    val textColor = if (isSelected) accentColor else (if (isDark) Color.White else Color.Black)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .squishClick {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 16.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        
        if (hasVariants) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = if (isDark) Color.Gray else Color.DarkGray,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (isExpanded) 180f else 0f }
            )
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun VariantRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    
    val bg = if (isSelected) accentColor.copy(alpha = 0.08f) else Color.Transparent
    val textColor = if (isSelected) accentColor else (if (isDark) Color.LightGray else Color.DarkGray)
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .squishClick {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        
        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = accentColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
