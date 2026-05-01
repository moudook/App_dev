package com.example.smarty.features.auth.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.R
import com.example.smarty.features.auth.domain.AuthFeatureManager
import com.example.smarty.features.auth.domain.AuthViewModel
import com.example.smarty.features.auth.domain.AuthViewModelFactory
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.ui.components.SmartyButton
import com.example.smarty.ui.components.SmartyOutlinedTextField
import com.example.smarty.ui.components.SmartySettingsCard
import com.example.smarty.ui.components.SmartySettingsRow
import com.example.smarty.ui.theme.SmartyIcons

@Composable
fun RefinedLoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    shouldSkipSplash: Boolean = false,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(application))
    val focusManager = LocalFocusManager.current
    val accentColor = LocalAccentColor.current

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val authState by viewModel.authState.collectAsState()

    var isLoginMode by remember { mutableStateOf(true) }
    var showForm by remember(shouldSkipSplash) { mutableStateOf(shouldSkipSplash) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var emailFocused by remember { mutableStateOf(false) }
    var passwordFocused by remember { mutableStateOf(false) }

    val trimmedEmail = email.trim()
    val passwordMismatch = !isLoginMode && confirmPassword.isNotEmpty() && confirmPassword != password
    val canSubmit = !isLoading && trimmedEmail.isNotBlank() && password.isNotBlank() && !passwordMismatch

    val googleSignInLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.handleGoogleSignInResult(result)
        }

    LaunchedEffect(authState) {
        if (authState == AuthFeatureManager.AuthState.SUCCESS) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(isLoginMode, showForm) {
        if (!isLoginMode) {
            passwordVisible = false
        }
        if (!showForm) {
            confirmPassword = ""
            passwordVisible = false
            focusManager.clearFocus()
        }
        viewModel.clearError()
    }

    fun submit() {
        focusManager.clearFocus()
        if (isLoginMode) {
            viewModel.signIn(trimmedEmail, password)
        } else if (!passwordMismatch) {
            viewModel.signUp(trimmedEmail, password)
        }
    }

    if (!showForm) {
        AuthLandingContent(
            modifier = modifier,
            isLoading = isLoading,
            onContinueWithGoogle = {
                if (!isLoading) {
                    googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                }
            },
            onContinueWithEmail = { showForm = true },
        )
        return
    }

    BackHandler { showForm = false }

    var formVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { formVisible = true }
    val formAlpha by animateFloatAsState(if (formVisible) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "formAlpha")
    val formOffsetY by animateFloatAsState(if (formVisible) 0f else 24f, spring(), label = "formOffset")

    Box(modifier = modifier.fillMaxSize()) {
        StaticPremiumBackground()

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer {
                        alpha = formAlpha
                        translationY = formOffsetY
                    },
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Surface(
                    onClick = { showForm = false },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                    modifier = Modifier.size(44.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = SmartyIcons.Back,
                            contentDescription = stringResource(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                    modifier = Modifier.size(76.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                            contentDescription = "Smarty",
                            tint = accentColor,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = if (isLoginMode) "Sign in" else "Create account",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (isLoginMode) "Pick up where you left off in Smarty." else "Start with a clean private workspace and simple AI chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
                )

                Spacer(modifier = Modifier.height(28.dp))

                SmartySettingsCard(horizontalPadding = 0.dp) {
                    Column(
                        modifier = Modifier.fillMaxWidth().animateContentSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SmartySettingsRow(
                            label = "Continue with Google",
                            subtitle = "Use your Google account for a faster setup",
                            onClick = {
                                if (!isLoading) {
                                    googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                                }
                            },
                            showChevron = true,
                            leadingContent = {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_google_logo),
                                    contentDescription = "Google",
                                    modifier = Modifier.size(24.dp),
                                    tint = Color.Unspecified,
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                            },
                        )

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            Text("or use email", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                        }

                        SmartyOutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                viewModel.clearError()
                            },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { emailFocused = it.isFocused },
                            label = stringResource(R.string.email),
                            singleLine = true,
                            enabled = !isLoading,
                            isError = error != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                            trailingIcon = {
                                Icon(
                                    imageVector = SmartyIcons.Email,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (emailFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            },
                        )

                        SmartyOutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                viewModel.clearError()
                            },
                            modifier = Modifier.fillMaxWidth().onFocusChanged { passwordFocused = it.isFocused },
                            label = stringResource(R.string.password),
                            singleLine = true,
                            enabled = !isLoading,
                            isError = error != null || passwordMismatch,
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (isLoginMode) ImeAction.Done else ImeAction.Next),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                onDone = { if (isLoginMode && canSubmit) submit() },
                            ),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        imageVector = if (passwordVisible) SmartyIcons.Visibility else SmartyIcons.VisibilityOff,
                                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                                        tint = if (passwordFocused) accentColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                            },
                        )

                        AnimatedVisibility(visible = !isLoginMode) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                PasswordStrengthBar(password = password)
                                SmartyOutlinedTextField(
                                    value = confirmPassword,
                                    onValueChange = {
                                        confirmPassword = it
                                        viewModel.clearError()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = "Confirm password",
                                    singleLine = true,
                                    enabled = !isLoading,
                                    isError = error != null || passwordMismatch,
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                                    trailingIcon = {
                                        Icon(
                                            imageVector = SmartyIcons.Lock,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        )
                                    },
                                )
                            }
                        }

                        AnimatedVisibility(visible = passwordMismatch, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Text(
                                text = "Passwords don't match",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                AnimatedVisibility(visible = error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(imageVector = SmartyIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                            Text(text = error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(visible = isLoginMode) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            text = stringResource(R.string.forgot_password),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = accentColor,
                            modifier =
                                Modifier
                                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                        if (trimmedEmail.isNotBlank()) {
                                            viewModel.resetPassword(trimmedEmail)
                                        } else {
                                            viewModel.setError(context.getString(R.string.error_email_required_reset))
                                        }
                                    }
                                    .padding(vertical = 4.dp, horizontal = 2.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                SmartyButton(
                    onClick = ::submit,
                    enabled = canSubmit,
                    isLoading = isLoading,
                    text = if (isLoginMode) "Sign In" else "Create Account",
                )

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = if (isLoginMode) "Don't have an account?" else "Already have an account?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isLoginMode) "Create one" else "Sign in",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = accentColor,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    isLoginMode = !isLoginMode
                                }
                                .padding(4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthLandingContent(
    modifier: Modifier,
    isLoading: Boolean,
    onContinueWithGoogle: () -> Unit,
    onContinueWithEmail: () -> Unit,
) {
    val accentColor = LocalAccentColor.current

    Box(modifier = modifier.fillMaxSize()) {
        StaticPremiumBackground()

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.08f)),
                    modifier = Modifier.size(96.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                            contentDescription = "Smarty",
                            modifier = Modifier.size(48.dp),
                            tint = accentColor,
                        )
                    }
                }

                Text(
                    text = "Welcome to Smarty",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Keep your notes, memory, and AI chat in one calm private workspace.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SmartySettingsCard(horizontalPadding = 0.dp) {
                    SmartySettingsRow(
                        label = "Continue with Google",
                        subtitle = "Use your Google account for a faster setup",
                        onClick = { if (!isLoading) onContinueWithGoogle() },
                        showChevron = true,
                        leadingContent = {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_google_logo),
                                contentDescription = "Google",
                                modifier = Modifier.size(24.dp),
                                tint = Color.Unspecified,
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f))
                    SmartySettingsRow(
                        label = "Continue with email",
                        subtitle = "Use your email address and password",
                        icon = SmartyIcons.Email,
                        onClick = onContinueWithEmail,
                        showChevron = true,
                    )
                }

                Text(
                    text = "Simple automatic AI mode is ready by default after sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PasswordStrengthBar(password: String) {
    val strength = when {
        password.length < 6 -> 0.2f
        password.length < 8 -> 0.4f
        password.length < 10 && password.any { it.isDigit() } -> 0.6f
        password.length >= 10 && password.any { it.isDigit() } && password.any { it.isUpperCase() } -> 1.0f
        password.length >= 8 -> 0.8f
        else -> 0.4f
    }
    val color = when {
        strength <= 0.2f -> Color(0xFFFF4D4D)
        strength <= 0.4f -> Color(0xFFFF9500)
        strength <= 0.6f -> Color(0xFFFFCC00)
        strength <= 0.8f -> Color(0xFF34C759)
        else -> Color(0xFF30D158)
    }
    val label = when {
        strength <= 0.2f -> "Too short"
        strength <= 0.4f -> "Weak"
        strength <= 0.6f -> "Fair"
        strength <= 0.8f -> "Good"
        else -> "Strong"
    }
    val animatedStrength by animateFloatAsState(strength, tween(400), label = "strength")

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Password strength", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), color = color)
        }
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(animatedStrength)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(color),
            )
        }
    }
}
