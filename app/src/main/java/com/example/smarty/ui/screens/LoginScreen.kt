package com.example.smarty.ui.screens

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Fingerprint
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarty.R
import com.example.smarty.ui.LocalAccentColor
import com.example.smarty.viewmodel.AuthViewModel
import com.example.smarty.viewmodel.AuthViewModelFactory
import com.example.smarty.viewmodel.managers.AuthFeatureManager
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════════
// LOGIN SCREEN - STACKS / NOTECARD DESIGN
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
    val accentColor = LocalAccentColor.current

    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val authState by viewModel.authState.collectAsState()

    var isLoginMode by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            // 1. BRAND HEADER
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                // Logo placeholder or simple icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(accentColor.copy(alpha = 0.1f))
                        .border(1.dp, accentColor.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                        contentDescription = "Logo",
                        tint = accentColor,
                        modifier = Modifier.size(40.dp)
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Welcome back",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (isLoginMode) "Sign in" else "Create account",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            // 2. MAIN CARD
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Email Field
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; viewModel.clearError() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Email") },
                        leadingIcon = { Icon(Icons.Default.AlternateEmail, null) },
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
                            focusedBorderColor = accentColor,
                            focusedLabelColor = accentColor
                        )
                    )

                    // Password Field
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; viewModel.clearError() },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    null
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
                            focusedBorderColor = accentColor,
                            focusedLabelColor = accentColor
                        )
                    )

                    // Error Message
                    AnimatedVisibility(visible = error != null) {
                        Text(
                            text = error ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }

                    // Forgot Password (Login Mode Only)
                    if (isLoginMode) {
                        Text(
                            text = "Forgot password?",
                            style = MaterialTheme.typography.labelMedium,
                            color = accentColor,
                            modifier = Modifier
                                .align(Alignment.End)
                                .clickable { if (email.isNotBlank()) viewModel.resetPassword(email) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Primary Button
                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            if (isLoginMode) viewModel.signIn(email, password)
                            else viewModel.signUp(email, password)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentColor,
                            contentColor = Color.White
                        )
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = if (isLoginMode) "Sign In" else "Create Account",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                    
                    // Divider
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(Modifier.weight(1f))
                        Text(
                            text = "OR",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        HorizontalDivider(Modifier.weight(1f))
                    }

                    // Google Button
                    OutlinedButton(
                        onClick = { googleSignInLauncher.launch(viewModel.getGoogleSignInIntent()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        enabled = !isLoading,
                        shape = RoundedCornerShape(16.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_google_logo),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "Continue with Google",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Switch Mode Link
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = if (isLoginMode) "New here? " else "Have an account? ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
                Text(
                    text = if (isLoginMode) "Sign up" else "Sign in",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = accentColor,
                    modifier = Modifier.clickable {
                        isLoginMode = !isLoginMode
                        viewModel.clearError()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

