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
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.squishClick
import com.example.smarty.ui.theme.appleShapes

@Composable
fun ModelSelectorSheet(
    isVisible: Boolean,
    onDismiss: () -> Unit,
    availableModels: List<Pair<String, String>>,
    modelVariantMap: Map<String, List<String>>,
    selectedModel: String,
    selectedVariant: String?,
    onModelSelected: (String) -> Unit,
    onVariantSelected: (String?) -> Unit,
) {
    if (isVisible) {
        // We use a Popup anchored to the model pill, rather than a full-screen BottomSheet
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset(0, -120), // Float it above the input block, growing upwards
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true, dismissOnClickOutside = true, clippingEnabled = false),
        ) {
            val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
            val surfaceColor = if (isDark) Color(0xFF1E1E1E) else Color.White
            val borderColor = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)

            val shapes = MaterialTheme.appleShapes

            // Styled EXACTLY like the Smarty Input Block (graphicsLayer shadows, borders, rounded shape)
            Column(
                modifier =
                    Modifier
                        .widthIn(min = 220.dp, max = 280.dp)
                        .graphicsLayer {
                            shadowElevation = 24.dp.toPx()
                            shape = shapes.large
                            clip = true
                            ambientShadowColor = Color.Black.copy(alpha = 0.05f)
                            spotShadowColor = Color.Black.copy(alpha = 0.12f)
                        }.background(surfaceColor)
                        .border(1.dp, borderColor, shapes.large)
                        .padding(8.dp),
            ) {
                // Title (Optional, keeping it very minimal)
                Text(
                    text = "Select Model",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                )

                // Content
                var expandedVariantModel by remember { mutableStateOf<String?>(null) }
                var searchQuery by remember { mutableStateOf("") }

                // Search Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clip(shapes.small)
                        .background(if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.03f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = "Search",
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = 14.sp,
                            color = if (isDark) Color.White else Color.Black
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text("Search models...", fontSize = 14.sp, color = Color.Gray)
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                val filteredModels = remember(searchQuery, availableModels) {
                    if (searchQuery.isBlank()) availableModels
                    else availableModels.filter { it.second.contains(searchQuery, ignoreCase = true) || it.first.contains(searchQuery, ignoreCase = true) }
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filteredModels.forEach { (modelId, label) ->
                        val cleanLabel =
                            label
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
                            },
                        )

                        // Variants Dropdown (Animated accordion)
                        AnimatedVisibility(
                            visible = isExpanded && hasVariants,
                            enter = expandVertically(spring(dampingRatio = 0.8f, stiffness = 300f)) + fadeIn(),
                            exit = shrinkVertically(tween(200)) + fadeOut(),
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
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
                                        },
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

@Composable
private fun ModelRow(
    label: String,
    isSelected: Boolean,
    hasVariants: Boolean,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    val bg = if (isSelected) accentColor.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (isSelected) accentColor else (if (isDark) Color.White else Color.Black)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.appleShapes.medium)
                .background(bg)
                .squishClick {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
            modifier = Modifier.weight(1f),
        )

        if (hasVariants) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Expand",
                tint = if (isDark) Color.Gray else Color.DarkGray,
                modifier =
                    Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = if (isExpanded) 180f else 0f },
            )
        } else if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = accentColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun VariantRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val accentColor = LocalAccentColor.current
    val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f

    val bg = if (isSelected) accentColor.copy(alpha = 0.1f) else Color.Transparent
    val textColor = if (isSelected) accentColor else (if (isDark) Color.LightGray else Color.DarkGray)

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.appleShapes.small)
                .background(bg)
                .squishClick {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            color = textColor,
            modifier = Modifier.weight(1f),
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = accentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
