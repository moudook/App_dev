package com.example.smarty.features.auth.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.local.SecurePreferences
import kotlinx.coroutines.delay

enum class OnboardingStep { WELCOME, NAME, GOAL, FINAL }

@Composable
fun OnboardingScreen(
    securePreferences: SecurePreferences,
    onFinish: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var currentStep by remember { mutableStateOf(OnboardingStep.WELCOME) }
    
    var name by remember { mutableStateOf(securePreferences.getUserName() ?: "") }
    var primaryGoal by remember { mutableStateOf("") }
    
    val nameFocus = remember { FocusRequester() }
    val goalFocus = remember { FocusRequester() }

    LaunchedEffect(currentStep) {
        delay(400) 
        when (currentStep) {
            OnboardingStep.NAME -> nameFocus.requestFocus()
            OnboardingStep.GOAL -> goalFocus.requestFocus()
            OnboardingStep.FINAL -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                delay(2000)
                securePreferences.setUserName(name.trim())
                securePreferences.setUserGoals(primaryGoal.trim())
                securePreferences.setOnboarded(true)
                onFinish()
            }
            else -> {}
        }
    }
    
    // Cinematic Auto-advance
    LaunchedEffect(Unit) {
        delay(2500)
        currentStep = OnboardingStep.NAME
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CinematicAmbientBackground() // Uses the GPU background we built!

        val orbOffsetY by animateFloatAsState(
            targetValue = if (currentStep == OnboardingStep.WELCOME || currentStep == OnboardingStep.FINAL) 0f else -300f,
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f), label = "orbOffset"
        )
        val orbScale by animateFloatAsState(
            targetValue = when (currentStep) {
                OnboardingStep.WELCOME -> 1.5f
                OnboardingStep.FINAL -> 15f // Massive engulfing effect
                else -> 0.5f
            },
            animationSpec = tween(if (currentStep == OnboardingStep.FINAL) 1500 else 800, easing = FastOutSlowInEasing),
            label = "orbScale"
        )

        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer { translationY = orbOffsetY },
            contentAlignment = Alignment.Center
        ) {
            OmniCore(isThinking = currentStep == OnboardingStep.FINAL, scale = orbScale)
        }

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
                label = "OnboardingSteps"
            ) { step ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    when (step) {
                        OnboardingStep.WELCOME -> {
                            Text(
                                text = "System Online.",
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1.5).sp,
                                color = Color.White
                            )
                        }
                        OnboardingStep.NAME -> {
                            Text("Identity.", fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                            Text("How should I address you?", fontSize = 16.sp, color = Color.White.copy(0.6f), modifier = Modifier.padding(bottom = 32.dp))
                            STierAuthInput(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = "Your Name",
                                focusRequester = nameFocus,
                                onSubmit = { if (name.isNotBlank()) currentStep = OnboardingStep.GOAL }
                            )
                        }
                        OnboardingStep.GOAL -> {
                            Text("Directive.", fontSize = 36.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                            Text("Understood, $name. What is our primary goal?", fontSize = 16.sp, color = Color.White.copy(0.6f), textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 32.dp))
                            STierAuthInput(
                                value = primaryGoal,
                                onValueChange = { primaryGoal = it },
                                placeholder = "e.g., Coding, Planning",
                                focusRequester = goalFocus,
                                onSubmit = { if (primaryGoal.isNotBlank()) currentStep = OnboardingStep.FINAL }
                            )
                        }
                        OnboardingStep.FINAL -> {
                            Text(
                                text = "Awakening Workspace...",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = (-1).sp,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}
