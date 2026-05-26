package com.example.smarty.features.auth.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smarty.data.local.SecurePreferences
import kotlinx.coroutines.delay

@Composable
fun OnboardingScreen(
    securePreferences: SecurePreferences,
    onFinish: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }

    // User Data
    var name by remember { mutableStateOf(securePreferences.getUserName()) }
    var goals by remember { mutableStateOf(securePreferences.getUserGoals()) }
    var potatoPreference by remember { mutableStateOf("") }
    
    // Fake loading states
    val loadingMessages = listOf(
        "Initializing local databases...",
        "Configuring server instances...",
        "Encrypting memory sectors...",
        "Teaching AI about potatoes...",
        "Polishing the UI...",
        "Finalizing setup..."
    )
    var loadingMessageIndex by remember { mutableIntStateOf(0) }

    // Handle fake loading progression
    LaunchedEffect(currentStep) {
        if (currentStep == 4) {
            for (i in loadingMessages.indices) {
                loadingMessageIndex = i
                delay(1200L) // fake delay for each step
            }
            delay(500L)
            currentStep = 5 // Go to final step
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                fadeIn(animationSpec = tween(500)) togetherWith fadeOut(animationSpec = tween(500))
            },
            label = "OnboardingSteps"
        ) { step ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (step) {
                    0 -> {
                        // Intro Step
                        Text(
                            text = "Welcome to Smarty.",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "We need to set up a few things before you get started. It's a one-time process.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(48.dp))
                        Button(
                            onClick = { currentStep = 1 },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Let's Go", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    1 -> {
                        // Name Step
                        Text(
                            text = "What should I call you?",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            placeholder = { Text("Enter your preferred name") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) currentStep = 2 }),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { currentStep = 2 },
                            enabled = name.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Next", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    2 -> {
                        // Goals Step
                        Text(
                            text = "Nice to meet you, $name!\nWhat are your primary goals here?",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        OutlinedTextField(
                            value = goals,
                            onValueChange = { goals = it },
                            placeholder = { Text("e.g. Coding, writing, learning...") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { if (goals.isNotBlank()) currentStep = 3 }),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { currentStep = 3 },
                            enabled = goals.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Next", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    3 -> {
                        // Humorous Step
                        Text(
                            text = "Important question:\nIf you were a potato, how would you prefer to be cooked?",
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        
                        val potatoOptions = listOf("Fried (Classic)", "Baked (Cozy)", "Mashed (Comforting)", "Raw (Rebellious)")
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            potatoOptions.forEach { option ->
                                val isSelected = potatoPreference == option
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primaryContainer 
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { potatoPreference = option }
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = option,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                        ),
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer 
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(32.dp))
                        Button(
                            onClick = { currentStep = 4 },
                            enabled = potatoPreference.isNotBlank(),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Configure Server", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    4 -> {
                        // Loading / Configuring Step
                        CircularProgressIndicator(
                            modifier = Modifier.size(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 6.dp
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        Text(
                            text = loadingMessages[loadingMessageIndex],
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                    5 -> {
                        // Final Step
                        Text(
                            text = "All Set!",
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Your private workspace is configured and ready to go.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(48.dp))
                        Button(
                            onClick = {
                                securePreferences.setUserName(name.trim())
                                securePreferences.setUserGoals(goals.trim())
                                // We combine goals and potato preference for AI context
                                securePreferences.setUserPreferences("Potato preference: $potatoPreference")
                                securePreferences.setOnboarded(true)
                                onFinish()
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(56.dp)
                        ) {
                            Text("Start Using Smarty", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
