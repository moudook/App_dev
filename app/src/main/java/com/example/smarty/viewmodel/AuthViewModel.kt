package com.example.smarty.viewmodel

import android.app.Application
import android.content.Intent
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.smarty.R
import com.example.smarty.data.repository.AuthRepository
import com.example.smarty.data.repository.FirebaseAuthRepository
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Authentication
 */
class AuthViewModel(
    application: Application,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _authState = MutableStateFlow(AuthState.IDLE)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // Google Sign-In Client
    private val googleSignInClient: GoogleSignInClient

    enum class AuthState {
        IDLE, SUCCESS, ERROR
    }

    init {
        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(application.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(application, gso)

        viewModelScope.launch {
            authRepository.currentUser.collect { user ->
                _currentUser.value = user
            }
        }
    }

    /**
     * Get the Google Sign-In Intent to launch
     */
    fun getGoogleSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Handle the result from Google Sign-In Activity
     */
    fun handleGoogleSignInResult(result: ActivityResult) {
        viewModelScope.launch {
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
        
        viewModelScope.launch {
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

        viewModelScope.launch {
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
        viewModelScope.launch {
            // Sign out from Firebase
            authRepository.signOut()
            // Sign out from Google
            googleSignInClient.signOut()
        }
    }

    fun resetPassword(email: String) {
        if (email.isBlank()) {
            _error.value = "Please enter your email"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            
            val result = authRepository.resetPassword(email)
            if (result.isSuccess) {
                // Optionally expose a separate success message state
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
}

class AuthViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            val repository = FirebaseAuthRepository()
            return AuthViewModel(application, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
