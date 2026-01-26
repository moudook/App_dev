package com.example.smarty.viewmodel.managers

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.activity.result.ActivityResult
import com.example.smarty.R
import com.example.smarty.data.repository.AuthRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Centralized manager for Authentication operations.
 * Hybridizes logic for:
 * - Firebase Email/Password Auth
 * - Google Sign-In integration
 * - Password recovery
 * - User session management
 *
 * This manager ensures both the UI and AI interact with the same auth state.
 */
class AuthFeatureManager(
    private val application: Application,
    private val scope: CoroutineScope,
    private val authRepository: AuthRepository
) {
    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _authState = MutableStateFlow(AuthState.IDLE)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val googleSignInClient: GoogleSignInClient

    enum class AuthState {
        IDLE, SUCCESS, ERROR
    }

    init {
        // Configure Google Sign-In
        val webClientId = try {
            application.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            Log.w(TAG, "OAuth Client ID not found. Configure Firebase OAuth in console.")
            null
        }

        val gso = if (webClientId != null && webClientId != "YOUR_WEB_CLIENT_ID_HERE") {
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
        } else {
            Log.w(TAG, "Google Sign-In configured without ID token - Firebase Auth will not work")
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
        }

        googleSignInClient = GoogleSignIn.getClient(application, gso)

        scope.launch {
            authRepository.currentUser.collect { user ->
                _currentUser.value = user
            }
        }
    }

    fun getGoogleSignInIntent(): Intent = googleSignInClient.signInIntent

    fun handleGoogleSignInResult(result: ActivityResult) {
        scope.launch {
            _isLoading.value = true
            _error.value = null
            _authState.value = AuthState.IDLE

            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account?.idToken

                if (idToken != null) {
                    val authResult = authRepository.signInWithGoogleCredential(idToken)
                    if (authResult.isSuccess) {
                        _authState.value = AuthState.SUCCESS
                    } else {
                        _error.value = authResult.exceptionOrNull()?.localizedMessage ?: "Google sign-in failed"
                        _authState.value = AuthState.ERROR
                    }
                } else {
                    _error.value = "Failed to get ID token from Google"
                    _authState.value = AuthState.ERROR
                }
            } catch (e: ApiException) {
                _error.value = "Google sign-in failed: ${e.statusCode}"
                _authState.value = AuthState.ERROR
            } catch (e: Exception) {
                _error.value = e.localizedMessage ?: "Google sign-in failed"
                _authState.value = AuthState.ERROR
            }

            _isLoading.value = false
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _error.value = "Email and password cannot be empty"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            _authState.value = AuthState.IDLE

            val result = authRepository.signIn(email, password)
            if (result.isSuccess) {
                _authState.value = AuthState.SUCCESS
            } else {
                _error.value = result.exceptionOrNull()?.localizedMessage ?: "Sign in failed"
                _authState.value = AuthState.ERROR
            }
            _isLoading.value = false
        }
    }

    fun signUp(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _error.value = "Email and password cannot be empty"
            return
        }

        scope.launch {
            _isLoading.value = true
            _error.value = null
            _authState.value = AuthState.IDLE

            val result = authRepository.signUp(email, password)
            if (result.isSuccess) {
                _authState.value = AuthState.SUCCESS
            } else {
                _error.value = result.exceptionOrNull()?.localizedMessage ?: "Sign up failed"
                _authState.value = AuthState.ERROR
            }
            _isLoading.value = false
        }
    }

    fun signOut() {
        scope.launch {
            authRepository.signOut()
            googleSignInClient.signOut()
        }
    }

    fun resetPassword(email: String) {
        if (email.isBlank()) {
            _error.value = "Please enter your email"
            return
        }
        scope.launch {
            _isLoading.value = true
            _error.value = null

            val result = authRepository.resetPassword(email)
            if (result.isSuccess) {
                // Success state handled by UI if needed
            } else {
                _error.value = result.exceptionOrNull()?.localizedMessage ?: "Reset password failed"
            }
            _isLoading.value = false
        }
    }

    fun clearError() {
        _error.value = null
        _authState.value = AuthState.IDLE
    }

    companion object {
        private const val TAG = "AuthFeatureManager"
    }
}
