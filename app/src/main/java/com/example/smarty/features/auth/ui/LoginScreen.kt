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
import com.example.smarty.ui.theme.SmartyIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.drawscope.withTransform
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
import com.example.smarty.ui.theme.PinkAccent
import com.example.smarty.ui.theme.PinkLight
import com.example.smarty.ui.theme.PinkMedium
import com.example.smarty.ui.theme.PinkDark
import com.example.smarty.ui.theme.ComponentSpacing
import com.example.smarty.ui.theme.IconSize
import com.example.smarty.ui.theme.LocalShapes
import com.example.smarty.ui.components.SmartySettingsCard
import com.example.smarty.ui.components.SmartySettingsRow
import com.example.smarty.ui.theme.rememberMonochromeAccent
import com.example.smarty.ui.components.GeometricGradientBackground
import com.example.smarty.ui.components.SmartyButton

// 
// LOGIN SCREEN - STACKS / NOTECARD DESIGN
// 

@Composable
fun StaticPremiumBackground(modifier: Modifier = Modifier) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // Theme-adaptive background with neutral colors (no purple)
    val bgColor = if (isDark) Color(0xFF0A0A0C) else Color(0xFFFAFAFC)
    val accent1 = if (isDark) Color(0xFF2A2A3C).copy(alpha = 0.5f) else Color(0xFFE8E8F0).copy(alpha = 0.7f)
    val accent2 = if (isDark) Color(0xFF1A1A2E).copy(alpha = 0.4f) else Color(0xFFF0F0F8).copy(alpha = 0.8f)
    val accent3 = if (isDark) Color(0xFF0F0F1A).copy(alpha = 0.3f) else Color(0xFFF8F8FC).copy(alpha = 0.6f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Subtle diagonal gradient for depth
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(bgColor, if (isDark) Color(0xFF12121A) else Color.White, bgColor),
                    start = androidx.compose.ui.geometry.Offset(0f, 0f),
                    end = androidx.compose.ui.geometry.Offset(width, height)
                )
            )

            // Top Right Bloom
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(accent1, Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(width * 0.9f, -height * 0.1f),
                    radius = width * 1.2f
                ),
                center = androidx.compose.ui.geometry.Offset(width * 0.9f, -height * 0.1f),
                radius = width * 1.2f
            )

            // Bottom Left Bloom
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(accent2, Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(-width * 0.2f, height * 1.1f),
                    radius = width * 1.3f
                ),
                center = androidx.compose.ui.geometry.Offset(-width * 0.2f, height * 1.1f),
                radius = width * 1.3f
            )

            // Center subtle presence
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(accent3, Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(width * 0.6f, height * 0.5f),
                    radius = width * 0.8f
                ),
                center = androidx.compose.ui.geometry.Offset(width * 0.6f, height * 0.5f),
                radius = width * 0.8f
            )
        }
    }
}

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
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()

        Box(modifier = modifier.fillMaxSize()) {
            // Static Premium Background
            StaticPremiumBackground()

            // Top Illustration (Clean, Floating Logo)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f),
                contentAlignment = Alignment.Center
            ) {
                // You could use a sleek floating animation here if desired
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                    contentDescription = "Smarty Logo",
                    modifier = Modifier.size(120.dp),
                    tint = if (isDark) Color.White else Color.Black
                )
            }

            // Bottom Content Section (Glassy & Floating instead of a hard box)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxHeight(0.6f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp, vertical = 40.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title - "Meet your" adapts to theme, "AI Agent" stays pink
                        val meetYourColor = if (isDark) Color(0xFFE8E8F0) else Color(0xFF1A1A2E)
                        
                        androidx.compose.material3.Text(
                            text = androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = meetYourColor)) {
                                    append("Meet your ")
                                }
                                withStyle(androidx.compose.ui.text.SpanStyle(color = PinkAccent)) {
                                    append("AI Agent")
                                }
                                append(".")
                            },
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            letterSpacing = (-1.5).sp,
                            textAlign = TextAlign.Center
                        )

                        // Description - Theme adaptive text color
                        Text(
                            text = "Initialize the entity that remembers, organizes, and acts autonomously.",
                            color = if (isDark) Color(0xFFD0D0D8) else Color(0xFF4A4A5C),
                            fontSize = 17.sp,
                            lineHeight = 26.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    // Buttons
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp) 
                    ) {
                        // Combined Button Card: Continue with Google & Email
                        SmartySettingsCard(horizontalPadding = 0.dp) {
                            // Option 1: Google
                            SmartySettingsRow(
                                label = "Continue with Google",
                                onClick = {
                                    if (!isLoading) googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                                },
                                showChevron = true,
                                leadingContent = {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_google_logo),
                                        contentDescription = "Google Logo",
                                        modifier = Modifier.size(24.dp),
                                        tint = Color.Unspecified
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                }
                            )

                            // Option 2: Email
                            SmartySettingsRow(
                                label = "Continue with Email",
                                icon = SmartyIcons.Email,
                                onClick = { showForm = true },
                                showChevron = true
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // Handle system back press to return from the form to landing
    BackHandler {
        showForm = false
        viewModel.clearError()
    }

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val shapes = LocalShapes.current

    Box(modifier = modifier.fillMaxSize()) {
        StaticPremiumBackground()
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
                modifier = Modifier.size(IconSize.brandLogo),
                tint = PinkAccent
            )
        }

        // Floating Content Container for Form (No solid background block to preserve orb visibility)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .background(
                    color = if (isDark) Color(0xFF101016).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(24.dp)
                )
                .border(1.dp, if (isDark) Color.White.copy(0.1f) else Color.White.copy(0.8f), RoundedCornerShape(24.dp))
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
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    androidx.compose.material3.Text(
                        text = androidx.compose.ui.text.buildAnnotatedString {
                            if (isLoginMode) {
                                append("Agent ")
                                withStyle(androidx.compose.ui.text.SpanStyle(color = PinkAccent)) {
                                    append("Login")
                                }
                            } else {
                                append("Initialize ")
                                withStyle(androidx.compose.ui.text.SpanStyle(color = PinkAccent)) {
                                    append("Agent")
                                }
                            }
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        letterSpacing = (-1.0).sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isLoginMode) "Authenticate to resume session." else "Establish neural link.",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Constants for internal fields - Super sleek glass
                val pillBackground = if (isDark) Color.White.copy(alpha = 0.03f) else Color.White.copy(alpha = 0.4f)
                val pillBorder = if (isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.8f)
                val focusedBorder = PinkAccent
                val pillShape = RoundedCornerShape(16.dp)

                // 2. FORM FIELDS
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                // Email Field

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
                        focusedBorderColor = focusedBorder,
                        unfocusedBorderColor = pillBorder,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        focusedLabelColor = focusedBorder,
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
                                if (passwordVisible) SmartyIcons.VisibilityOff
                                else SmartyIcons.Visibility,
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
                        focusedBorderColor = focusedBorder,
                        unfocusedBorderColor = pillBorder,
                        errorBorderColor = MaterialTheme.colorScheme.error,
                        focusedLabelColor = focusedBorder,
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
                            focusedBorderColor = focusedBorder,
                            unfocusedBorderColor = pillBorder,
                            errorBorderColor = MaterialTheme.colorScheme.error,
                            focusedLabelColor = focusedBorder,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            errorLabelColor = MaterialTheme.colorScheme.error,
                            cursorColor = MaterialTheme.colorScheme.onSurface,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
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
                                shapes.skeleton
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

                Spacer(modifier = Modifier.height(20.dp))

                val isPasswordMatch = isLoginMode || (password.isNotEmpty() && password == confirmPassword)
                val isEnabled = !isLoading && email.isNotBlank() && password.isNotBlank() && isPasswordMatch

                // Animated Button state
                var isPressed by remember { mutableStateOf(false) }
                val scale by animateFloatAsState(if (isPressed) 0.96f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))

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
                        .height(58.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent, // Managed by gradient background
                    contentColor = Color.White
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = if (isEnabled) listOf(PinkAccent, PinkDark) else listOf(Color.Gray.copy(0.3f), Color.Gray.copy(0.3f))
                                )
                            ),
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
                                text = if (isLoginMode) "Authenticate" else "Initialize",
                                color = Color.White,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.0.sp
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
                        text = if (isLoginMode) "New user?" else "Existing agent?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isLoginMode) "Initialize" else "Authenticate",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(shapes.skeleton)
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
