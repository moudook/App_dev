package com.example.smarty.features.auth.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
import com.example.smarty.ui.theme.SmartyIcons
import kotlinx.coroutines.delay

enum class LoginStep { LANDING, EMAIL, PASSWORD }

// ── S-TIER PHYSICS ───────────────────────────────────────────────
@Composable
fun Modifier.squishClick(
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onClick: () -> Unit
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "squish"
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale; clip = true }
        .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
}

// ── S-TIER OMNI-CORE (The Agent Soul) ────────────────────────────
@Composable
fun OmniCore(isThinking: Boolean, scale: Float, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "coreSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(if (isThinking) 2000 else 8000, easing = LinearEasing)),
        label = "rotation"
    )
    
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = if (isThinking) 1.2f else 1.05f,
        animationSpec = infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "pulse"
    )

    val accent = LocalAccentColor.current

    Box(
        modifier = modifier
            .size(160.dp)
            .graphicsLayer { 
                scaleX = scale * pulse
                scaleY = scale * pulse
            }
            .drawWithCache {
                onDrawBehind {
                    rotate(rotation) {
                        // The Siri-like mesmerizing sweep gradient
                        drawCircle(
                            brush = Brush.sweepGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.0f),
                                    accent.copy(alpha = 0.6f),
                                    Color(0xFFB373F2).copy(alpha = 0.8f), // Secondary premium purple
                                    accent.copy(alpha = 0.0f)
                                )
                            ),
                            radius = size.width / 2
                        )
                    }
                    // Inner solid core
                    drawCircle(
                        color = Color.White,
                        radius = (size.width / 2) * 0.45f
                    )
                }
            }
            .blur(if (isThinking) 16.dp else 24.dp) // Softens the gradient edges perfectly
    )
}

// ── S-TIER AMBIENT BACKGROUND ────────────────────────────────────
@Composable
fun CinematicAmbientBackground(modifier: Modifier = Modifier) {
    val isDark = true // Force dark/premium theme for the awakening flow
    val bgColor = if (isDark) Color(0xFF0A0A0C) else Color(0xFFF4F4F7)
    val accent = LocalAccentColor.current

    val infiniteTransition = rememberInfiniteTransition(label = "bgShift")
    val shiftY by infiniteTransition.animateFloat(
        initialValue = -0.2f, targetValue = 1.2f,
        animationSpec = infiniteRepeatable(tween(15000, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "bgShiftY"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .drawWithCache {
                onDrawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(accent.copy(alpha = 0.15f), Color.Transparent),
                            center = Offset(size.width * 0.8f, size.height * shiftY)
                        ),
                        radius = size.width
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFB373F2).copy(alpha = 0.1f), Color.Transparent),
                            center = Offset(size.width * 0.2f, size.height * (1f - shiftY))
                        ),
                        radius = size.width * 1.2f
                    )
                }
            }
    )
}

// ── S-TIER INPUT FIELD ───────────────────────────────────────────
@Composable
fun STierAuthInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isPassword: Boolean = false,
    onSubmit: () -> Unit,
    focusRequester: FocusRequester,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    val accent = LocalAccentColor.current
    val haptic = LocalHapticFeedback.current
    val canSend = value.isNotBlank()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        modifier = Modifier.fillMaxWidth().graphicsLayer { clip = true }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                textStyle = TextStyle(fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium),
                singleLine = true,
                cursorBrush = SolidColor(accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSubmit() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) Text(placeholder, color = Color.White.copy(alpha = 0.3f), fontSize = 18.sp)
                        innerTextField()
                    }
                }
            )

            AnimatedVisibility(visible = canSend, enter = scaleIn(spring(0.7f, 400f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .background(accent, CircleShape)
                        .squishClick {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSubmit()
                        }
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, "Next", tint = Color.White, modifier = Modifier.size(18.dp).offset(x = 1.dp))
                }
            }
        }
    }
}

// ── MAIN SCREEN ──────────────────────────────────────────────────
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    shouldSkipSplash: Boolean = false,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(application))
    val haptic = LocalHapticFeedback.current

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val authState by viewModel.authState.collectAsState()

    var currentStep by remember { mutableStateOf(LoginStep.LANDING) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUp by remember { mutableStateOf(false) }

    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        viewModel.handleGoogleSignInResult(result)
    }

    LaunchedEffect(authState) { if (authState == AuthFeatureManager.AuthState.SUCCESS) onLoginSuccess() }

    LaunchedEffect(currentStep) {
        delay(350)
        when (currentStep) {
            LoginStep.EMAIL -> emailFocus.requestFocus()
            LoginStep.PASSWORD -> passwordFocus.requestFocus()
            else -> {}
        }
    }

    BackHandler(enabled = currentStep != LoginStep.LANDING) {
        viewModel.clearError()
        currentStep = if (currentStep == LoginStep.PASSWORD) LoginStep.EMAIL else LoginStep.LANDING
    }

    Box(modifier = modifier.fillMaxSize()) {
        CinematicAmbientBackground()

        // TOP BACK BUTTON
        AnimatedVisibility(
            visible = currentStep != LoginStep.LANDING,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.White.copy(0.1f), CircleShape)
                    .border(1.dp, Color.White.copy(0.1f), CircleShape)
                    .squishClick {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.clearError()
                        currentStep = if (currentStep == LoginStep.PASSWORD) LoginStep.EMAIL else LoginStep.LANDING
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        // ORB ORCHESTRATION
        val orbOffsetY by animateFloatAsState(
            targetValue = if (currentStep == LoginStep.LANDING) 0f else -300f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f), label = "orbOffset"
        )
        val orbScale by animateFloatAsState(
            targetValue = if (currentStep == LoginStep.LANDING) 1f else 0.5f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f), label = "orbScale"
        )

        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = orbOffsetY },
            contentAlignment = Alignment.Center
        ) {
            OmniCore(isThinking = isLoading, scale = orbScale)
        }

        // CONTENT SECTION (iOS Push/Pop Navigation)
        Box(
            modifier = Modifier.fillMaxSize().imePadding().padding(bottom = 48.dp, start = 32.dp, end = 32.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedContent(
                targetState = currentStep,
                transitionSpec = {
                    val isForward = targetState > initialState
                    val slideSpring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.8f, stiffness = 300f)
                    val slideIn = slideInHorizontally(slideSpring) { if (isForward) it else -it }
                    val slideOut = slideOutHorizontally(slideSpring) { if (isForward) -it else it }
                    
                    (slideIn + fadeIn(tween(300))) togetherWith (slideOut + fadeOut(tween(300))) using SizeTransform { _, _ -> spring(0.7f, 350f) }
                },
                label = "LoginSteps"
            ) { step ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    when (step) {
                        LoginStep.LANDING -> {
                            Text(
                                text = "Awaken\nYour Agent",
                                fontSize = 42.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-2).sp,
                                lineHeight = 44.sp,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Text(
                                text = "Initialize the entity that remembers,\norganizes, and acts autonomously.",
                                fontSize = 16.sp,
                                color = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(bottom = 64.dp)
                            )
                            
                            // Apple-style Glass Button
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White,
                                modifier = Modifier.fillMaxWidth().height(60.dp).squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    if (!isLoading) googleSignInLauncher.launch(viewModel.getGoogleSignInIntent())
                                }
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(painterResource(R.drawable.ic_google_logo), "Google", modifier = Modifier.size(24.dp), tint = Color.Unspecified)
                                    Spacer(Modifier.width(12.dp))
                                    Text("Continue with Google", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = "Use Email Instead",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.squishClick {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    currentStep = LoginStep.EMAIL
                                }.padding(8.dp)
                            )
                        }
                        
                        LoginStep.EMAIL -> {
                            Text("Identity\nVerification.", fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 38.sp, modifier = Modifier.padding(bottom = 32.dp))
                            STierAuthInput(
                                value = email,
                                onValueChange = { email = it; viewModel.clearError() },
                                placeholder = "name@example.com",
                                focusRequester = emailFocus,
                                keyboardType = KeyboardType.Email,
                                onSubmit = { if (email.isNotBlank()) { currentStep = LoginStep.PASSWORD; viewModel.clearError() } }
                            )
                        }
                        
                        LoginStep.PASSWORD -> {
                            Text("Secure\nPasskey.", fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = Color.White, textAlign = TextAlign.Center, lineHeight = 38.sp, modifier = Modifier.padding(bottom = 12.dp))
                            Text(email, fontSize = 15.sp, color = LocalAccentColor.current, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 32.dp).background(LocalAccentColor.current.copy(0.1f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp))
                            
                            STierAuthInput(
                                value = password,
                                onValueChange = { password = it; viewModel.clearError() },
                                placeholder = "••••••••",
                                isPassword = true,
                                focusRequester = passwordFocus,
                                onSubmit = {
                                    if (password.isNotBlank()) {
                                        if (isSignUp) viewModel.signUp(email, password) else viewModel.signIn(email, password)
                                    }
                                }
                            )
                            
                            AnimatedVisibility(error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                Text(error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                            }
                            
                            Row(modifier = Modifier.padding(top = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isSignUp) "Already registered?" else "New here?", color = Color.White.copy(0.5f), fontSize = 14.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isSignUp) "Sign In" else "Create Account",
                                    color = LocalAccentColor.current,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.squishClick {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        isSignUp = !isSignUp
                                        viewModel.clearError()
                                    }.padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
