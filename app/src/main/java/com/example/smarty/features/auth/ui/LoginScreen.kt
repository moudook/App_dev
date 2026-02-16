package com.example.smarty.features.auth.ui

import android.app.Application

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.features.auth.domain.AuthViewModel
import com.example.smarty.features.auth.domain.AuthViewModelFactory
import com.example.smarty.features.auth.domain.AuthFeatureManager
import com.example.smarty.ui.theme.rememberMonochromeAccent

// 
// LOGIN SCREEN - STACKS / NOTECARD DESIGN
// 

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    shouldSkipSplash: Boolean = false
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application

    val viewModel: AuthViewModel = viewModel(
        factory = AuthViewModelFactory(application)
    )
    val focusManager = LocalFocusManager.current
    val accentColor = LocalAccentColor.current
    // Use a softer version of accent color for backgrounds
    val softAccent = accentColor.copy(alpha = 0.08f)

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val authState by viewModel.authState.collectAsState()

    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    var emailFocused by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleGoogleSignInResult(result)
    }

    LaunchedEffect(authState) {
        if (authState == AuthFeatureManager.AuthState.SUCCESS) {
            onLoginSuccess()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        modifier = modifier.fillMaxSize()
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding() // Shunts content upward when keyboard is open
                .padding(horizontal = 32.dp), // Increased horizontal padding for cleaner look
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. DENSITY AT BOTTOM: Top section mostly empty
            // If skipping splash, we might want to adjust spacing but keeping it consistent for now
Spacer(modifier = Modifier.weight(1f))

            Spacer(modifier = Modifier.height(40.dp))


            // 1. MODERN MINIMAL HEADER
            // "Same team as header section" -> Monochrome & Pill aesthetics

            // Standard Pill Constants matching SmartyInputField
            val monochromeColor = rememberMonochromeAccent()
            val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
            val pillBackground = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface
            val pillBorder = if (isDark) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            val pillShape = RoundedCornerShape(26.dp)

            Text(
                text = if (isLoginMode) stringResource(R.string.welcome) else stringResource(R.string.create_account),
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp)) // Condensed (was 8dp)

            Text(
                text = if (isLoginMode) stringResource(R.string.sign_in_subtitle) else stringResource(R.string.sign_up_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(24.dp)) // Condensed (was 32dp)

            // 2. FORM FIELDS (Directly on background, no Card)
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp), // Ultra-Condensed (was 16dp)
                modifier = Modifier.widthIn(max = 400.dp)
            ) {
                // Email Field (Legend/Label on Top Border)
                // Theme-aware focus color: White for Dark Mode, Black for Light Mode
                val focusHighlightColor = monochromeColor

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; viewModel.clearError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.email)) },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = error != null,
                    shape = pillShape,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = pillBackground,
                        unfocusedContainerColor = pillBackground,
                        disabledContainerColor = pillBackground,
                        errorContainerColor = pillBackground,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = pillBorder,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        errorLabelColor = MaterialTheme.colorScheme.error,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                // Password Field (Legend/Label on Top Border)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; viewModel.clearError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.password)) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                stringResource(if (passwordVisible) R.string.hide else R.string.show),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    },
                    singleLine = true,
                    enabled = !isLoading,
                    isError = error != null,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (isLoginMode) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (isLoginMode) {
                                focusManager.clearFocus()
                                viewModel.signIn(email, password)
                            }
                        },
                        onNext = {
                            if (!isLoginMode) focusManager.moveFocus(FocusDirection.Down)
                        }
                    ),
                    shape = pillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = pillBackground,
                        unfocusedContainerColor = pillBackground,
                        disabledContainerColor = pillBackground,
                        errorContainerColor = pillBackground,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = pillBorder,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        errorLabelColor = MaterialTheme.colorScheme.error,
                        cursorColor = MaterialTheme.colorScheme.onSurface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    )
                )

                // Confirm Password Field (Only in Sign-Up Mode)
                AnimatedVisibility(
                    visible = !isLoginMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; viewModel.clearError() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.confirm_password)) },
                        singleLine = true,
                        enabled = !isLoading,
                        isError = error != null || (confirmPassword.isNotEmpty() && confirmPassword != password),
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (password == confirmPassword) {
                                    viewModel.signUp(email, password)
                                } else {
                                    // Local validation error if needed, although primary button handles it
                                }
                            }
                        ),
                        shape = pillShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = pillBackground,
                            unfocusedContainerColor = pillBackground,
                            disabledContainerColor = pillBackground,
                            errorContainerColor = pillBackground,
                            focusedBorderColor = focusHighlightColor,
                            unfocusedBorderColor = pillBorder,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            focusedLabelColor = focusHighlightColor,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            errorLabelColor = MaterialTheme.colorScheme.error,
                            cursorColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                // Error Message
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Fixed-height container to prevent layout shifts when switching modes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp), // Reserves space even when text is hidden
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (isLoginMode) {
                        Text(
                            text = stringResource(R.string.forgot_password),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), // Monochrome
                            modifier = Modifier
                                .clickable {
                                    if (email.isNotBlank()) {
                                        viewModel.resetPassword(email)
                                    } else {
                                        // Trigger error if email is missing
                                        viewModel.setError(context.getString(R.string.error_email_required_reset))
                                    }
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Google Button (Matching History Pill Style: Text Only, Minimal Pill, No Click Animation)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                             if (!isLoading) googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                        },
                        shape = pillShape,
                        color = pillBackground,
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, pillBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.continue_with_google),
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 0.5.sp
                                ),
                                color = monochromeColor
                            )
                        }
                    }
                }

                // Primary Button (Pill Style)
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val isPasswordMatch = isLoginMode || (password.isNotEmpty() && password == confirmPassword)
                    val isEnabled = !isLoading && email.isNotBlank() && password.isNotBlank() && isPasswordMatch
                    val activePillBorder = if (isEnabled) MaterialTheme.colorScheme.onSurface else pillBorder

                    Surface(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isEnabled
                            ) {
                                focusManager.clearFocus()
                                if (isLoginMode) {
                                    viewModel.signIn(email, password)
                                } else {
                                    if (password == confirmPassword) {
                                        viewModel.signUp(email, password)
                                    }
                                }
                            },
                        shape = pillShape,
                        color = pillBackground,
                        border = BorderStroke(0.5.dp, activePillBorder) // Highlight border if ready
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (isLoginMode) stringResource(R.string.sign_in) else stringResource(R.string.sign_up),
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = if (isEnabled) monochromeColor else monochromeColor.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.2f))

            // Footer
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = if (isLoginMode) stringResource(R.string.new_here) else stringResource(R.string.have_account),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isLoginMode) stringResource(R.string.sign_up) else stringResource(R.string.sign_in),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = accentColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            isLoginMode = !isLoginMode
                            viewModel.clearError()
                        }
                        .padding(4.dp)
                )
            }

            // Note: Column already has imePadding() so no additional spacer needed
        }
    }
}


