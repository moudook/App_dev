package com.example.smarty.features.auth.ui

import android.app.Application

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.example.smarty.ui.theme.SmartyIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource

import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.R
import com.example.smarty.features.auth.domain.AuthViewModel
import com.example.smarty.features.auth.domain.AuthViewModelFactory
import com.example.smarty.features.auth.domain.AuthFeatureManager
import com.example.smarty.ui.theme.PinkAccent
import com.example.smarty.ui.theme.PinkDark
import com.example.smarty.ui.components.SmartySettingsCard
import com.example.smarty.ui.components.SmartySettingsRow
import com.example.smarty.ui.LocalAccentColor

// 
// LOGIN SCREEN - STACKS / NOTECARD DESIGN
// 

@Composable
fun StaticPremiumBackground(modifier: Modifier = Modifier) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val bgColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    
    // Theme-adaptive background with subtle accent blooms matching the app
    val accentColor = LocalAccentColor.current
    val accent1 = accentColor.copy(alpha = if (isDark) 0.15f else 0.12f)
    val accent2 = accentColor.copy(alpha = if (isDark) 0.10f else 0.08f)
    val accent3 = accentColor.copy(alpha = if (isDark) 0.08f else 0.05f)

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
                    colors = listOf(bgColor, surfaceColor, bgColor),
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
                        val meetYourColor = MaterialTheme.colorScheme.onBackground
                        
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
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
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
    // ── Theme-adaptive colors using native MaterialTheme ─────────────────────────
    val surfaceColor = MaterialTheme.colorScheme.background
    val textPrimary = MaterialTheme.colorScheme.onSurface
    val textSecondary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)

    // ── Animated entry ──────────────────────────────────────────────
    var formVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { formVisible = true }

    val formAlpha by animateFloatAsState(
        targetValue = if (formVisible) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "formAlpha"
    )
    val formOffsetY by animateFloatAsState(
        targetValue = if (formVisible) 0f else 40f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "formOffsetY"
    )

    Box(modifier = modifier.fillMaxSize()) {
        StaticPremiumBackground()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .graphicsLayer {
                    alpha = formAlpha
                    translationY = formOffsetY
                }
        ) {
            // ── Back Button ─────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Surface(
                    onClick = {
                        showForm = false
                        viewModel.clearError()
                    },
                    shape = CircleShape,
                    color = if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.04f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            imageVector = SmartyIcons.Back,
                            contentDescription = "Back",
                            tint = textPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // ── Scrollable Content ──────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // ── Logo ───────────────────────────────────────────
                Icon(
                    painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                    contentDescription = "Smarty",
                    modifier = Modifier.size(72.dp),
                    tint = PinkAccent
                )

                Spacer(modifier = Modifier.height(20.dp))

                // ── Header ─────────────────────────────────────────
                androidx.compose.material3.Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        if (isLoginMode) {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = textPrimary)) {
                                append("Welcome ")
                            }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = PinkAccent)) {
                                append("Back")
                            }
                        } else {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = textPrimary)) {
                                append("Create ")
                            }
                            withStyle(androidx.compose.ui.text.SpanStyle(color = PinkAccent)) {
                                append("Account")
                            }
                        }
                    },
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    letterSpacing = (-1.0).sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (isLoginMode) "Sign in to resume your session" else "Set up your credentials to get started",
                    color = textSecondary,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // ── Form Card (Uses Smarty Settings standards) ──────────────────────────────────────
                SmartySettingsCard(horizontalPadding = 0.dp) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Email field
                        com.example.smarty.ui.components.SmartyOutlinedTextField(
                            value = email,
                            onValueChange = { email = it; viewModel.clearError() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { emailFocused = it.isFocused },
                            label = stringResource(R.string.email),
                            trailingIcon = {
                                Icon(
                                    imageVector = SmartyIcons.Email,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (emailFocused) LocalAccentColor.current else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            },
                            singleLine = true,
                            enabled = !isLoading,
                            isError = error != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            )
                        )

                        // Password field
                        com.example.smarty.ui.components.SmartyOutlinedTextField(
                            value = password,
                            onValueChange = { password = it; viewModel.clearError() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { passwordFocused = it.isFocused },
                            label = stringResource(R.string.password),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) SmartyIcons.Visibility else SmartyIcons.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            },
                            singleLine = true,
                            enabled = !isLoading,
                            isError = error != null || (!isLoginMode && confirmPassword.isNotEmpty() && confirmPassword != password),
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = if (isLoginMode) ImeAction.Done else ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                onDone = {
                                    if (isLoginMode) {
                                        focusManager.clearFocus()
                                        viewModel.signIn(email, password)
                                    }
                                }
                            )
                        )

                        // ── Password Strength (signup only) ────────────
                        AnimatedVisibility(
                            visible = !isLoginMode && password.isNotEmpty(),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            val strength = when {
                                password.length < 6 -> 0.2f
                                password.length < 8 -> 0.4f
                                password.length < 10 && password.any { it.isDigit() } -> 0.6f
                                password.length >= 10 && password.any { it.isDigit() } && password.any { it.isUpperCase() } -> 1.0f
                                password.length >= 8 -> 0.8f
                                else -> 0.4f
                            }
                            val strengthColor = when {
                                strength <= 0.2f -> Color(0xFFFF4D4D)
                                strength <= 0.4f -> Color(0xFFFF9500)
                                strength <= 0.6f -> Color(0xFFFFCC00)
                                strength <= 0.8f -> Color(0xFF34C759)
                                else -> Color(0xFF30D158)
                            }
                            val strengthLabel = when {
                                strength <= 0.2f -> "Too short"
                                strength <= 0.4f -> "Weak"
                                strength <= 0.6f -> "Fair"
                                strength <= 0.8f -> "Good"
                                else -> "Strong"
                            }
                            val animatedStrength by animateFloatAsState(
                                targetValue = strength,
                                animationSpec = tween(400),
                                label = "strengthAnim"
                            )

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Password strength",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = textSecondary
                                    )
                                    Text(
                                        text = strengthLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = strengthColor
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(if (isDark) Color.White.copy(alpha = 0.06f) else Color.Black.copy(alpha = 0.06f))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(animatedStrength)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(strengthColor)
                                    )
                                }
                            }
                        }

                        // Confirm Password (Sign Up)
                        AnimatedVisibility(
                            visible = !isLoginMode,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            com.example.smarty.ui.components.SmartyOutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it; viewModel.clearError() },
                                modifier = Modifier.fillMaxWidth(),
                                label = "Confirm password",
                                trailingIcon = {
                                    Icon(
                                        imageVector = SmartyIcons.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                },
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
                                        }
                                    }
                                )
                            )
                        }

                        // Password mismatch hint
                        AnimatedVisibility(
                            visible = !isLoginMode && confirmPassword.isNotEmpty() && confirmPassword != password,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                text = "Passwords don't match",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                }

                // ── Error Message ──────────────────────────────────
                AnimatedVisibility(
                    visible = error != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (isDark) Color(0xFF3D1515).copy(alpha = 0.7f) else Color(0xFFFFF0F0),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = SmartyIcons.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── Forgot Password (login only) ───────────────────
                AnimatedVisibility(
                    visible = isLoginMode,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        Text(
                            text = stringResource(R.string.forgot_password),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = LocalAccentColor.current,
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    if (email.isNotBlank()) viewModel.resetPassword(email)
                                    else viewModel.setError(context.getString(R.string.error_email_required_reset))
                                }
                                .padding(vertical = 4.dp, horizontal = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // ── Primary Button ─────────────────────────────────
                val isPasswordMatch = isLoginMode || (password.isNotEmpty() && password == confirmPassword)
                val isEnabled = !isLoading && email.isNotBlank() && password.isNotBlank() && isPasswordMatch

                com.example.smarty.ui.components.SmartyButton(
                    onClick = {
                        focusManager.clearFocus()
                        if (isLoginMode) {
                            viewModel.signIn(email, password)
                        } else {
                            if (password == confirmPassword) {
                                viewModel.signUp(email, password)
                            }
                        }
                    },
                    enabled = isEnabled,
                    isLoading = isLoading,
                    text = if (isLoginMode) "Sign In" else "Create Account"
                )

                Spacer(modifier = Modifier.height(24.dp))

                // ── Mode Toggle ────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = if (isLoginMode) "Don't have an account?" else "Already have an account?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textSecondary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isLoginMode) "Sign Up" else "Sign In",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = LocalAccentColor.current,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
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
