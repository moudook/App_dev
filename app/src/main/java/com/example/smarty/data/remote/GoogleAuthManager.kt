@file:Suppress("DEPRECATION")

package com.example.smarty.data.remote

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages Google Sign-In for Google Drive backup functionality.
 *
 * Handles authentication flow, account state, and sign-out.
 * Uses DRIVE_FILE and DRIVE_APPDATA scopes for backup operations.
 */
class GoogleAuthManager(private val context: Context) {

    private val _signedInAccount = MutableStateFlow<GoogleSignInAccount?>(null)
    val signedInAccount: StateFlow<GoogleSignInAccount?> = _signedInAccount.asStateFlow()

    private val _isSignedIn = MutableStateFlow(false)
    val isSignedIn: StateFlow<Boolean> = _isSignedIn.asStateFlow()

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(DriveScopes.DRIVE_FILE),
                Scope(DriveScopes.DRIVE_APPDATA)
            )
            .build()

        GoogleSignIn.getClient(context, gso)
    }

    init {
        // Check if user is already signed in
        checkExistingSignIn()
    }

    /**
     * Check for existing sign-in on app launch.
     */
    private fun checkExistingSignIn() {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account != null && hasRequiredScopes(account)) {
            _signedInAccount.value = account
            _isSignedIn.value = true
        }
    }

    /**
     * Silent sign-in to refresh the account and token.
     */
    suspend fun silentSignIn(): Result<GoogleSignInAccount> {
        return try {
            val account = googleSignInClient.silentSignIn().await()
            if (account != null && hasRequiredScopes(account)) {
                _signedInAccount.value = account
                _isSignedIn.value = true
                Result.success(account)
            } else {
                Result.failure(Exception("Silent sign-in failed or missing permissions"))
            }
        } catch (e: Exception) {
            _signedInAccount.value = null
            _isSignedIn.value = false
            Result.failure(e)
        }
    }

    /**
     * Verify that the account has the required Drive scopes.
     */
    private fun hasRequiredScopes(account: GoogleSignInAccount): Boolean {
        val driveFileScope = Scope(DriveScopes.DRIVE_FILE)
        val appDataScope = Scope(DriveScopes.DRIVE_APPDATA)

        return GoogleSignIn.hasPermissions(account, driveFileScope, appDataScope)
    }

    /**
     * Get the sign-in intent to launch the Google Sign-In flow.
     */
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Handle the result from the Google Sign-In activity.
     */
    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            if (account != null) {
                if (hasRequiredScopes(account)) {
                    _signedInAccount.value = account
                    _isSignedIn.value = true
                    Result.success(account)
                } else {
                    // This can happen if the user didn't check the scope boxes in the UI
                    Result.failure(Exception("Missing required Drive permissions. Please try again and grant access."))
                }
            } else {
                Result.failure(Exception("Sign-in failed: Account is null"))
            }
        } catch (e: ApiException) {
            val message = when (e.statusCode) {
                CommonStatusCodes.SIGN_IN_REQUIRED -> "Sign-in required"
                CommonStatusCodes.NETWORK_ERROR -> "Network error, please check your connection"
                CommonStatusCodes.INVALID_ACCOUNT -> "Invalid Google account"
                else -> "Sign-in failed: ${e.message} (Status code: ${e.statusCode})"
            }
            _signedInAccount.value = null
            _isSignedIn.value = false
            Result.failure(Exception(message, e))
        } catch (e: Exception) {
            _signedInAccount.value = null
            _isSignedIn.value = false
            Result.failure(e)
        }
    }

    /**
     * Helper to determine if an exception is a UserRecoverableAuthException or UserRecoverableAuthIOException.
     * If true, the UI should use the intent from the exception to ask the user for permission.
     */
    fun isUserRecoverable(throwable: Throwable?): Intent? {
        return when (throwable) {
            is UserRecoverableAuthException -> throwable.intent
            is UserRecoverableAuthIOException -> throwable.intent
            else -> null
        }
    }

    /**
     * Sign out from the Google account.
     *
     * @param onComplete Callback when sign-out is complete
     */
    fun signOut(onComplete: () -> Unit = {}) {
        googleSignInClient.signOut().addOnCompleteListener {
            _signedInAccount.value = null
            _isSignedIn.value = false
            onComplete()
        }
    }

    /**
     * Revoke access and sign out.
     * This removes the app's access to the user's Google account.
     *
     * @param onComplete Callback when revocation is complete
     */
    fun revokeAccess(onComplete: () -> Unit = {}) {
        googleSignInClient.revokeAccess().addOnCompleteListener {
            _signedInAccount.value = null
            _isSignedIn.value = false
            onComplete()
        }
    }

    /**
     * Get the currently signed-in account, if any.
     */
    fun getCurrentAccount(): GoogleSignInAccount? {
        return _signedInAccount.value
    }

    /**
     * Get the email of the signed-in user.
     */
    fun getSignedInEmail(): String? {
        return _signedInAccount.value?.email
    }

    /**
     * Get the display name of the signed-in user.
     */
    fun getSignedInDisplayName(): String? {
        return _signedInAccount.value?.displayName
    }

    /**
     * Get the photo URL of the signed-in user.
     */
    fun getSignedInPhotoUrl(): String? {
        return _signedInAccount.value?.photoUrl?.toString()
    }
}
