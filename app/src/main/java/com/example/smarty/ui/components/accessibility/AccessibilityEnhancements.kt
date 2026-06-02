package com.example.smarty.ui.components.accessibility

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.example.smarty.core.common.util.MinTouchTargetSize

/**
 * Minimum touch target size modifier (48dp)
 * Ensures WCAG 2.1 AA compliance
 */
fun Modifier.minimumTouchTarget(): Modifier =
    this.sizeIn(
        minWidth = MinTouchTargetSize,
        minHeight = MinTouchTargetSize,
    )

/**
 * Accessibility heading
 * Marks content as a heading for screen readers
 */
fun Modifier.heading(): Modifier =
    this.semantics {
        heading()
    }

/**
 * Progress bar accessibility
 * Adds proper ARIA attributes for progress indicators
 */
fun Modifier.progressBar(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    label: String = "Progress",
): Modifier =
    this.semantics {
        this.progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps = 0)
        this.contentDescription = "$label: ${(value / (valueRange.endInclusive - valueRange.start)) * 100}% complete"
    }

/**
 * Button with accessibility
 * Ensures proper role and state
 */
fun Modifier.accessibleButton(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
): Modifier =
    this
        .semantics {
            role = Role.Button
            contentDescription = label
            if (!enabled) disabled()
        }.focusable(enabled = enabled)
        .minimumTouchTarget()

/**
 * Checkbox with accessibility
 * Proper state announcement
 */
fun Modifier.accessibleCheckbox(
    checked: Boolean,
    label: String,
    enabled: Boolean = true,
): Modifier =
    this
        .semantics {
            role = Role.Checkbox
            contentDescription = "$label, ${if (checked) "checked" else "unchecked"}"
            if (!enabled) disabled()
            stateDescription = if (checked) "Checked" else "Unchecked"
        }.focusable(enabled = enabled)
        .minimumTouchTarget()

/**
 * Toggle switch with accessibility
 */
fun Modifier.accessibleToggle(
    checked: Boolean,
    label: String,
    enabled: Boolean = true,
): Modifier =
    this
        .semantics {
            role = Role.Switch
            contentDescription = "$label, ${if (checked) "on" else "off"}"
            if (!enabled) disabled()
            stateDescription = if (checked) "On" else "Off"
        }.focusable(enabled = enabled)
        .minimumTouchTarget()
