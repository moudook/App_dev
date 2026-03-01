package com.example.smarty.features.auth.ui

import android.app.Application

import androidx.activity.compose.BackHandler
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

    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        if (authState == AuthFeatureManager.AuthState.SUCCESS) {
            onLoginSuccess()
        }
    }

    // --- LANDING SCREEN ---
    if (!showForm) {
        val imageOrange = Color(0xFFD66A48) // Extracted from the user's image

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background) // Keeping dark theme
        ) {
            // Top Illustration Placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.35f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                    contentDescription = "App Illustration",
                    modifier = Modifier.size(160.dp),
                    tint = imageOrange
                )
            }

            // Bottom Content Card
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.65f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // Badge "New"
                        Box(
                            modifier = Modifier
                                .border(1.dp, imageOrange.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                .background(imageOrange.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("New", color = imageOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        // Title
                        Text(
                            text = "Smarty: Your New App",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            lineHeight = 36.sp
                        )

                        // Description
                        Text(
                            text = "Experience the power of Smarty, our most advanced cognitive assistant for smarter living and productivity.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Item 1
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Icon(painterResource(R.drawable.ic_shortcut_stacks), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                            Column {
                                Text("Smart Task Management:", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("Organize your day with intelligence.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // Buttons
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp) 
                    ) {
                        // Main Button: Continue with Email
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(26.dp))
                                .clickable { showForm = true }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AlternateEmail,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Continue with Email",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        // Secondary Button: Continue with Google
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(26.dp))
                                .clickable {
                                    if (!isLoading) googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                                }
                                .padding(horizontal = 24.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google Logo",
                                modifier = Modifier.size(24.dp),
                                tint = Color.Unspecified
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = "Continue with Google",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
        return // Short-circuit, don't show the form if we are on the landing page
    }

    // Handle system back press to return from the form to landing
    BackHandler {
        showForm = false
        viewModel.clearError()
    }

    val imageOrange = Color(0xFFD66A48) // Extracted from the user's image

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Illustration Placeholder
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.35f),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                contentDescription = "App Illustration",
                modifier = Modifier.size(160.dp),
                tint = imageOrange
            )
        }

        // Bottom Content Card
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.65f)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 24.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
            ) {
                // Header
                Column(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (isLoginMode) stringResource(R.string.welcome) else stringResource(R.string.create_account),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        lineHeight = 36.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isLoginMode) stringResource(R.string.sign_in_subtitle) else stringResource(R.string.sign_up_subtitle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // Constants for internal fields
                val monochromeColor = rememberMonochromeAccent()
                val isDark = MaterialTheme.colorScheme.surface.luminance() <= 0.51f
                val pillBackground = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f) else MaterialTheme.colorScheme.surface
                val pillBorder = if (isDark) Color.White.copy(alpha = 0.15f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                val pillShape = RoundedCornerShape(26.dp)

                // 2. FORM FIELDS
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
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

                // Fixed-height container for "Forgot Password" to prevent layout shifts
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (isLoginMode) {
                        Text(
                            text = stringResource(R.string.forgot_password),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier
                                .clickable {
                                    if (email.isNotBlank()) viewModel.resetPassword(email)
                                    else viewModel.setError(context.getString(R.string.error_email_required_reset))
                                }
                                .padding(vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                val isPasswordMatch = isLoginMode || (password.isNotEmpty() && password == confirmPassword)
                val isEnabled = !isLoading && email.isNotBlank() && password.isNotBlank() && isPasswordMatch

                // Single Primary Submit Button - Pill Design with border
                val buttonBackgroundColor = MaterialTheme.colorScheme.onSurface
                val buttonBorderColor = if (isDark) Color.White.copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.2f)
                
                Surface(
                    onClick = {
                        if (isEnabled) {
                            focusManager.clearFocus()
                            if (isLoginMode) {
                                viewModel.signIn(email, password)
                            } else {
                                if (password == confirmPassword) {
                                    viewModel.signUp(email, password)
                                }
                            }
                        }
                    },
                    enabled = isEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .border(1.dp, buttonBorderColor, pillShape),
                    shape = pillShape,
                    color = buttonBackgroundColor,
                    contentColor = if (isDark) Color.Black else Color.White
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = if (isDark) Color.Black else Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isLoginMode) stringResource(R.string.sign_in) else stringResource(R.string.sign_up),
                                color = if (isDark) Color.Black else Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Footer Toggle (e.g. "New here? Sign up.")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
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
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable {
                                isLoginMode = !isLoginMode
                                viewModel.clearError()
                            }
                            .padding(4.dp)
                    )
                }
            }
        }
    }
}
}
