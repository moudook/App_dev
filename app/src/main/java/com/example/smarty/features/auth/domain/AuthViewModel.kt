@file:Suppress("DEPRECATION")
package com.example.smarty.features.auth.domain

import android.app.Application
import android.content.Intent
import android.util.Log
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

import com.example.smarty.features.auth.domain.AuthFeatureManager

import com.example.smarty.data.local.SecurePreferences

import com.example.smarty.di.ServiceLocator

/**
 * ViewModel for Authentication
 * Delegated to AuthFeatureManager for centralized logic.
 */
@Suppress("DEPRECATION")
class AuthViewModel(
    application: Application,
    private val authRepository: AuthRepository
) : AndroidViewModel(application) {

    private val authFeatureManager: AuthFeatureManager by lazy {
        AuthFeatureManager(
            application,
            viewModelScope,
            authRepository,
            SecurePreferences.getInstance(application),
            ServiceLocator.provideRepository(application)
        )
    }

    val currentUser: StateFlow<FirebaseUser?> = authFeatureManager.currentUser
    val isLoading: StateFlow<Boolean> = authFeatureManager.isLoading
    val error: StateFlow<String?> = authFeatureManager.error
    val authState: StateFlow<AuthFeatureManager.AuthState> = authFeatureManager.authState

    fun getGoogleSignInIntent(): Intent = authFeatureManager.getGoogleSignInIntent()

    fun handleGoogleSignInResult(result: ActivityResult) {
        authFeatureManager.handleGoogleSignInResult(result)
    }

    fun signIn(email: String, password: String) {
        authFeatureManager.signIn(email, password)
    }

    fun signUp(email: String, password: String) {
        authFeatureManager.signUp(email, password)
    }

    fun signOut() {
        authFeatureManager.signOut()
    }

    fun resetPassword(email: String) {
        authFeatureManager.resetPassword(email)
    }

    fun clearError() {
        authFeatureManager.clearError()
    }

    fun setError(message: String) {
        authFeatureManager.setError(message)
    }
}

class AuthViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            try {
                val repository = FirebaseAuthRepository()
                return AuthViewModel(application, repository) as T
            } catch (e: Exception) {
                Log.e("AuthViewModelFactory", "Failed to create AuthRepository", e)
                com.example.smarty.core.common.util.CrashLogger.log(application, "AuthRepository init failed: ${e.message}")
                // Rethrowing might crash, but at least we logged it.
                // Creating a dummy repo or returning a safe state is hard without changing the constructor.
                throw RuntimeException("Failed to initialize Auth Repository", e)
            }
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
