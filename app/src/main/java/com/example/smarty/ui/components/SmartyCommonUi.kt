package com.example.smarty.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.theme.appleSpacing
import com.example.smarty.ui.theme.appleShapes
import com.example.smarty.ui.theme.IconSize

/**
 * Standard Primary Button for Smarty App
 * Linked to the centralized Design System.
 */
@Composable
fun SmartyButton(
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    containerColor: Color = LocalAccentColor.current,
    contentColor: Color = Color.White,
) {
    Button(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp),
        enabled = enabled && !isLoading,
        shape = MaterialTheme.appleShapes.pill,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor.copy(alpha = 0.5f),
                disabledContentColor = contentColor.copy(alpha = 0.7f),
            ),
        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = 0.dp,
                pressedElevation = 2.dp,
            ),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconSize.xl),
                color = contentColor,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}

/**
 * Standard Outlined Text Field for Smarty App
 *
 * @param value Current text value
 * @param onValueChange Callback when value changes
 * @param label Field label
 * @param modifier Compose modifier
 * @param enabled Whether field is enabled
 * @param isError Whether field has error
 * @param errorMessage Error message to display
 * @param keyboardOptions Keyboard configuration
 * @param keyboardActions Keyboard actions
 * @param trailingIcon Optional trailing icon
 * @param visualTransformation Text visual transformation
 * @param singleLine Whether to display as single line
 */
@Composable
@Suppress("LongParameterList") // Required for Compose UI component flexibility
fun SmartyOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    errorMessage: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    singleLine: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Animate border color on focus/error
    val borderColor = if (isError) MaterialTheme.colorScheme.error else LocalAccentColor.current

    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            label = { Text(label) },
            isError = isError,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            trailingIcon = trailingIcon,
            visualTransformation = visualTransformation,
            singleLine = singleLine,
            shape = MaterialTheme.appleShapes.pill,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = borderColor,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = borderColor,
                    errorBorderColor = MaterialTheme.colorScheme.error,
                    errorLabelColor = MaterialTheme.colorScheme.error,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            interactionSource = interactionSource,
        )

        if (!errorMessage.isNullOrBlank() && isError) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = MaterialTheme.appleSpacing.medium, top = 4.dp),
            )
        }
    }
}

/**
 * Google Sign-In Button
 */
@Composable
fun SmartyGoogleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .height(48.dp),
        shape = MaterialTheme.appleShapes.pill,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        enabled = !isLoading,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconSize.xl),
                color = LocalAccentColor.current,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = stringResource(R.string.continue_with_google),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
