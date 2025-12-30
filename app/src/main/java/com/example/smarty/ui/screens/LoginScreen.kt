package com.example.smarty.ui.screens

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.viewmodel.AuthViewModel
import com.example.smarty.viewmodel.AuthViewModelFactory
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════════
// LOGIN SCREEN COMPOSABLE - No startup animation for faster launch
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    modifier: Modifier = Modifier
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
    var passwordVisible by remember { mutableStateOf(false) }

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        isVisible = true
    }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleGoogleSignInResult(result)
    }

    LaunchedEffect(authState) {
        if (authState == AuthViewModel.AuthState.SUCCESS) {
            onLoginSuccess()
        }
    }

    // Design System Colors for Glassy Look
    val whiteText = Color.White
    val mutedText = Color.White.copy(alpha = 0.7f)
    val glassBorder = Color.White.copy(alpha = 0.3f)

    // Simple gradient background - no animation
    com.example.smarty.ui.components.GeometricGradientBackground(
        modifier = modifier.fillMaxSize()
    ) {
        // Content Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))

            // Welcome Text
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500)) + slideInVertically(
                    initialOffsetY = { 24 },
                    animationSpec = tween(500, easing = EaseOutCubic)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Access",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Light, // Thin elegant font
                            letterSpacing = 2.sp
                        ),
                        color = whiteText
                    )
                    Text(
                        text = "Intelligence",
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-1).sp
                        ),
                        color = whiteText
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = if (isLoginMode) "Authenticate to continue" else "Initialize new identity",
                        style = MaterialTheme.typography.bodyLarge,
                        color = mutedText,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 2. GLASSY FORM CARD
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500, delayMillis = 150)) + slideInVertically(
                    initialOffsetY = { 32 },
                    animationSpec = tween(500, delayMillis = 150, easing = EaseOutCubic)
                )
            ) {
                com.example.smarty.ui.components.GlassySurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Glassy Input Fields
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; viewModel.clearError() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Email Identity", color = mutedText) },
                            leadingIcon = { Icon(Icons.Default.Email, null, tint = whiteText) },
                            singleLine = true,
                            enabled = !isLoading,
                            isError = error != null,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = whiteText,
                                unfocusedBorderColor = glassBorder,
                                focusedLabelColor = whiteText,
                                unfocusedLabelColor = mutedText,
                                cursorColor = whiteText,
                                focusedTextColor = whiteText,
                                unfocusedTextColor = whiteText
                            )
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; viewModel.clearError() },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Passcode", color = mutedText) },
                            leadingIcon = { Icon(Icons.Default.Lock, null, tint = whiteText) },
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(
                                        if (passwordVisible) Icons.Default.VisibilityOff
                                        else Icons.Default.Visibility,
                                        null, tint = whiteText
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
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    if (isLoginMode) viewModel.signIn(email, password)
                                    else viewModel.signUp(email, password)
                                }
                            ),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = whiteText,
                                unfocusedBorderColor = glassBorder,
                                focusedLabelColor = whiteText,
                                unfocusedLabelColor = mutedText,
                                cursorColor = whiteText,
                                focusedTextColor = whiteText,
                                unfocusedTextColor = whiteText
                            )
                        )

                        AnimatedVisibility(visible = error != null) {
                            Text(
                                text = error ?: "",
                                color = Color(0xFFFF8A80), // Red accent for dark bg
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }

                        if (isLoginMode) {
                            Text(
                                text = "Recover Access",
                                style = MaterialTheme.typography.labelMedium,
                                color = whiteText,
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .clickable { if (email.isNotBlank()) viewModel.resetPassword(email) }
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // High-contrast Action Button
                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                if (isLoginMode) viewModel.signIn(email, password)
                                else viewModel.signUp(email, password)
                            },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color.Black,
                                disabledContainerColor = Color.White.copy(alpha = 0.5f)
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black)
                            } else {
                                Text(
                                    if (isLoginMode) "INITIALIZE SESSION" else "REGISTER IDENTITY",
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                )
                            }
                        }

                        // Glassy Divider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            HorizontalDivider(Modifier.weight(1f), color = glassBorder)
                            Text(" OR ", color = mutedText, style = MaterialTheme.typography.labelSmall)
                            HorizontalDivider(Modifier.weight(1f), color = glassBorder)
                        }

                        // Glassy Google Button
                        OutlinedButton(
                            onClick = { googleSignInLauncher.launch(viewModel.getGoogleSignInIntent()) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = !isLoading,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = whiteText)
                        ) {
                            Icon(
                                painter = androidx.compose.ui.res.painterResource(
                                    id = com.example.smarty.R.drawable.ic_google_logo
                                ),
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.Unspecified
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Google Access", fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Switch Mode Link
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500, delayMillis = 300))
            ) {
                TextButton(
                    onClick = { isLoginMode = !isLoginMode; viewModel.clearError() }
                ) {
                    Text(
                        if (isLoginMode) "Create new identity" else "Access existing session",
                        color = whiteText.copy(alpha = 0.9f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
