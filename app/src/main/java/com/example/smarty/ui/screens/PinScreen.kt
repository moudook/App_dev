package com.example.smarty.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.example.smarty.ui.animation.CogniEasing
import com.example.smarty.ui.animation.CogniMotion
import com.example.smarty.ui.animation.StaggerCalculator
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.SafetyOrange
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

enum class PinMode {
    SETUP,          // First time - create PIN
    CONFIRM,        // Confirm the PIN during setup
    ENTER           // Enter existing PIN
}

@Composable
fun PinScreen(
    isSetup: Boolean,
    onPinComplete: (String) -> Boolean,
    onSetupComplete: (String) -> Unit = {},
    isEmbedded: Boolean = false,
    modifier: Modifier = Modifier
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(if (isSetup) PinMode.SETUP else PinMode.ENTER) }
    var error by remember { mutableStateOf<String?>(null) }
    var shake by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Reset shake animation
    LaunchedEffect(shake) {
        if (shake) {
            delay(500)
            shake = false
        }
    }

    // Handle PIN completion
    LaunchedEffect(pin) {
        if (pin.length == 6) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(100)

            when (mode) {
                PinMode.SETUP -> {
                    confirmPin = pin
                    pin = ""
                    mode = PinMode.CONFIRM
                    error = null
                }
                PinMode.CONFIRM -> {
                    if (pin == confirmPin) {
                        onSetupComplete(pin)
                    } else {
                        error = "PINs don't match. Try again."
                        shake = true
                        pin = ""
                        confirmPin = ""
                        mode = PinMode.SETUP
                    }
                }
                PinMode.ENTER -> {
                    val success = onPinComplete(pin)
                    if (!success) {
                        error = "Incorrect PIN"
                        shake = true
                        pin = ""
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isEmbedded) Color.Transparent else MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Reduce padding when embedded
            modifier = Modifier.padding(if (isEmbedded) 16.dp else ComponentSpacing.headerGap)
        ) {
            // Logo/Title - smaller or hidden when embedded
            if (!isEmbedded) {
                Text(
                    text = "cogni_",
                    style = MaterialTheme.typography.displayMedium,
                    color = LocalAccentColor.current
                )
                Spacer(modifier = Modifier.height(ComponentSpacing.cardHeaderGap))
            } else {
                 Text(
                    text = "cogni_",
                    style = MaterialTheme.typography.headlineMedium,
                    color = LocalAccentColor.current
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Subtitle based on mode
            Text(
                text = when (mode) {
                    PinMode.SETUP -> "Create your 6-digit PIN"
                    PinMode.CONFIRM -> "Confirm your PIN"
                    PinMode.ENTER -> "Enter your PIN"
                },
                style = if (isEmbedded) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Dynamic Spacer
            Spacer(modifier = Modifier.height(if (isEmbedded) 24.dp else ComponentSpacing.huge))

            // PIN dots
            PinDots(
                length = pin.length,
                shake = shake,
                modifier = Modifier.padding(horizontal = ComponentSpacing.headerGap)
            )

            // Error message
            AnimatedVisibility(
                visible = error != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = SafetyOrange,
                    modifier = Modifier.padding(top = if (isEmbedded) 8.dp else ComponentSpacing.screenPadding)
                )
            }

            // Dynamic Spacer
            Spacer(modifier = Modifier.height(if (isEmbedded) 24.dp else ComponentSpacing.huge))

            // Number pad
            NumberPad(
                onNumberClick = { number ->
                    if (pin.length < 6) {
                        pin += number
                        error = null
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                onBackspaceClick = {
                    if (pin.isNotEmpty()) {
                        pin = pin.dropLast(1)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                },
                isCompact = isEmbedded
            )

            // Back to setup hint in confirm mode
            if (mode == PinMode.CONFIRM) {
                Spacer(modifier = Modifier.height(if (isEmbedded) 8.dp else ComponentSpacing.sectionGap))
                TextButton(
                    onClick = {
                        mode = PinMode.SETUP
                        pin = ""
                        confirmPin = ""
                        error = null
                    },
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        text = "Start over",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

/**
 * Animated PIN dots with Apple-like spring physics
 * - Bouncy fill animation when digit is entered
 * - Damped harmonic shake on error: x(t) = A * e^(-γt) * sin(ωt)
 */
@Composable
private fun PinDots(
    length: Int,
    shake: Boolean,
    modifier: Modifier = Modifier
) {
    // Shake animation using damped harmonic oscillation
    var shakeOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(shake) {
        if (shake) {
            val amplitude = 12f
            val frequency = 4f
            val dampingFactor = 5f

            // Animate shake with mathematical decay
            val animatable = Animatable(1f)
            animatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(400, easing = LinearEasing)
            ) {
                // Damped harmonic motion: A * e^(-γt) * sin(ωt)
                val decay = exp(-dampingFactor * (1f - value))
                shakeOffset = amplitude * decay * sin(frequency * 2f * Math.PI.toFloat() * (1f - value))
            }
            shakeOffset = 0f
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(ComponentSpacing.pinDotGap), // 16dp
        modifier = modifier.graphicsLayer { translationX = shakeOffset }
    ) {
        repeat(6) { index ->
            AnimatedPinDot(
                filled = index < length,
                index = index,
                totalFilled = length
            )
        }
    }
}

/**
 * Individual PIN dot with bouncy fill animation
 * Uses spring physics for natural feel
 */
@Composable
private fun AnimatedPinDot(
    filled: Boolean,
    index: Int,
    totalFilled: Int
) {
    // Track if this dot was just filled for bounce effect
    val wasJustFilled = remember(totalFilled) { index == totalFilled - 1 && filled }

    // Scale animation - bounces when filled
    val scale by animateFloatAsState(
        targetValue = if (filled) 1f else 0.9f,
        animationSpec = if (wasJustFilled) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        },
        label = "dotScale"
    )

    // Fill progress for smooth color transition
    val fillProgress by animateFloatAsState(
        targetValue = if (filled) 1f else 0f,
        animationSpec = tween(150, easing = CogniEasing.appleEaseOut),
        label = "fillProgress"
    )

    // Inner circle scale for pop effect
    val innerScale by animateFloatAsState(
        targetValue = if (filled) 1f else 0f,
        animationSpec = if (wasJustFilled) {
            spring(
                dampingRatio = 0.5f,
                stiffness = 400f
            )
        } else {
            tween(100)
        },
        label = "innerScale"
    )

    Box(
        modifier = Modifier
            .size(ComponentSpacing.pinDotSize) // 20dp
            .scale(scale)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = LocalAccentColor.current.copy(alpha = 0.3f + fillProgress * 0.7f),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inner filled circle with pop animation (golden ratio: 20 * 0.618 ≈ 12, rounded to 14)
        Box(
            modifier = Modifier
                .size(ComponentSpacing.iconSizeSmall) // 14dp
                .scale(innerScale)
                .background(LocalAccentColor.current, CircleShape)
        )
    }
}

/**
 * Animated number pad with staggered entry animation
 */
@Composable
private fun NumberPad(
    onNumberClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    isCompact: Boolean = false
) {
    val numbers = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "back")
    )
    
    // Adjust sizes based on mode
    val rowGap = if (isCompact) 8.dp else ComponentSpacing.pinRowGap
    val colGap = if (isCompact) 16.dp else ComponentSpacing.pinButtonGap
    val buttonSize = if (isCompact) 56.dp else ComponentSpacing.pinButtonSize

    // Staggered entry animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(rowGap)
    ) {
        numbers.forEachIndexed { rowIndex, row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(colGap)
            ) {
                row.forEachIndexed { colIndex, item ->
                    val index = rowIndex * 3 + colIndex
                    val staggerDelay = StaggerCalculator.logarithmic(index, 30)

                    when (item) {
                        "" -> Spacer(modifier = Modifier.size(buttonSize))
                        "back" -> AnimatedNumberPadButton(
                            onClick = onBackspaceClick,
                            isBackspace = true,
                            visible = visible,
                            delay = staggerDelay,
                            size = buttonSize
                        )
                        else -> AnimatedNumberPadButton(
                            text = item,
                            onClick = { onNumberClick(item) },
                            visible = visible,
                            delay = staggerDelay,
                            size = buttonSize
                        )
                    }
                }
            }
        }
    }
}

/**
 * Number pad button with Apple-like press animation
 * - Scale down on press with spring physics
 * - Background highlight on press
 * - Staggered entry animation
 */
@Composable
private fun AnimatedNumberPadButton(
    text: String = "",
    onClick: () -> Unit,
    isBackspace: Boolean = false,
    visible: Boolean = true,
    delay: Int = 0,
    size: androidx.compose.ui.unit.Dp = ComponentSpacing.pinButtonSize
) {
    val haptic = LocalHapticFeedback.current
    var isPressed by remember { mutableStateOf(false) }

    // Entry animation
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            delay(delay.toLong())
            appeared = true
        }
    }

    val entryScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = 0.6f,
            stiffness = 300f
        ),
        label = "entryScale"
    )

    val entryAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(150),
        label = "entryAlpha"
    )

    // Press animation
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = CogniMotion.quick,
        label = "pressScale"
    )

    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(100),
        label = "bgColor"
    )

    val borderAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0.5f,
        animationSpec = tween(100),
        label = "borderAlpha"
    )

    Surface(
        modifier = Modifier
            .size(size)
            .scale(entryScale * pressScale)
            .graphicsLayer { alpha = entryAlpha }
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        tryAwaitRelease()
                        isPressed = false
                    },
                    onTap = { onClick() }
                )
            },
        shape = CircleShape,
        color = backgroundColor,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = borderAlpha)
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (isBackspace) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = "Backspace",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(ComponentSpacing.iconSizeLarge) // 24dp
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
