package com.example.smarty.features.auth.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.R
import com.example.smarty.data.local.SecurePreferences
import com.example.smarty.ui.theme.PinkAccent
import kotlinx.coroutines.delay

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun OnboardingScreen(
    securePreferences: SecurePreferences,
    onFinish: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // User Data
    var name by remember { mutableStateOf(securePreferences.getUserName()) }
    var primaryGoal by remember { mutableStateOf("") }
    
    val cardColor = if (isDark) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.02f)
    val cardBorder = if (isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)

    Box(modifier = Modifier.fillMaxSize()) {
        StaticPremiumBackground()
        
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                (fadeIn(animationSpec = tween(600)) + scaleIn(initialScale = 0.95f, animationSpec = tween(600))) togetherWith 
                (fadeOut(animationSpec = tween(400)) + scaleOut(targetScale = 1.05f, animationSpec = tween(400)))
            },
            label = "OnboardingSteps",
            modifier = Modifier.fillMaxSize()
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (step) {
                    0 -> {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                            contentDescription = "Smarty Logo",
                            modifier = Modifier.size(80.dp),
                            tint = PinkAccent
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = androidx.compose.ui.text.buildAnnotatedString {
                                withStyle(androidx.compose.ui.text.SpanStyle(color = MaterialTheme.colorScheme.onBackground)) {
                                    append("Welcome to ")
                                }
                                withStyle(androidx.compose.ui.text.SpanStyle(color = PinkAccent)) {
                                    append("Smarty")
                                }
                            },
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = (-1.0).sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your private, intelligent workspace awaits. Let's personalize your experience.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.height(48.dp))
                        com.example.smarty.ui.components.SmartyButton(
                            onClick = { currentStep = 1 },
                            text = "Get Started",
                            modifier = Modifier.fillMaxWidth(0.8f)
                        )
                    }
                    1 -> {
                        Surface(
                            shape = RoundedCornerShape(26.dp),
                            color = cardColor,
                            border = BorderStroke(1.dp, cardBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Identify Yourself",
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    letterSpacing = (-0.5).sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "How should I address you?",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(32.dp))
                                
                                com.example.smarty.ui.components.SmartyOutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it },
                                    label = "Your Name",
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                com.example.smarty.ui.components.SmartyOutlinedTextField(
                                    value = primaryGoal,
                                    onValueChange = { primaryGoal = it },
                                    label = "Primary Goal (e.g. Coding, Writing)",
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { 
                                        if (name.isNotBlank()) {
                                            securePreferences.setUserName(name.trim())
                                            securePreferences.setUserGoals(primaryGoal.trim())
                                            securePreferences.setOnboarded(true)
                                            onFinish()
                                        }
                                    }),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(40.dp))
                                com.example.smarty.ui.components.SmartyButton(
                                    onClick = {
                                        securePreferences.setUserName(name.trim())
                                        securePreferences.setUserGoals(primaryGoal.trim())
                                        securePreferences.setOnboarded(true)
                                        onFinish()
                                    },
                                    text = "Continue",
                                    enabled = name.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
