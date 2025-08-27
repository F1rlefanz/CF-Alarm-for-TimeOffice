package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.google.android.gms.common.GoogleApiAvailability

data class SignInResult(
    val success: Boolean,
    val credentialResponse: GetCredentialResponse? = null,
    val error: String? = null,
    val exception: Throwable? = null
)

/**
 * Modern Credential Authentication Manager
 * 
 * Uses the successful Hybrid-Flow approach:
 * 1. androidx.credentials for modern One Tap UI
 * 2. GoogleSignInClient.silentSignIn() for reliable email extraction
 * 
 * This approach solves the OAuth2 BAD_AUTHENTICATION problem by combining
 * modern UI with reliable data extraction methods.
 */
class CredentialAuthManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)
    private val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    suspend fun signIn(activityContext: Context): SignInResult {
        if (googleWebClientId.isBlank()) {
            Logger.e(LogTags.AUTH, "Web Client ID is empty!")
            return SignInResult(success = false, error = "Web Client ID nicht konfiguriert")
        }

        Logger.d(LogTags.AUTH, "Starting credential sign-in with Web Client ID: ${googleWebClientId.take(20)}...")

        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)  // Show all accounts
            .setServerClientId(googleWebClientId)
            .setAutoSelectEnabled(false)  // Let user choose
            .setNonce(null) // No nonce required for standard flow
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        Logger.d(LogTags.AUTH, "Requesting credentials...")
        return try {
            val result = credentialManager.getCredential(context = activityContext, request = request)
            Logger.business(LogTags.AUTH, "Credential successfully obtained")
            SignInResult(success = true, credentialResponse = result)

        } catch (e: GetCredentialCancellationException) {
            Logger.w(LogTags.AUTH, "Sign-in cancelled by user", e)
            SignInResult(success = false, error = "Anmeldung wurde abgebrochen.", exception = e)
        } catch (e: NoCredentialException) {
            Logger.w(LogTags.AUTH, "No Google accounts found", e)
            
            // FIREBASE CRASHLYTICS: Critical auth failure reporting
            try {
                val crashlytics = com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                crashlytics.setCustomKey("auth_flow_step", "getCredential")
                crashlytics.setCustomKey("auth_error_type", "no_credential_found")
                
                // Check Google Play Services availability
                val googleServicesAvailable = GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
                crashlytics.setCustomKey("auth_google_services_available", googleServicesAvailable)
                
                crashlytics.log("AUTH CRITICAL: No Google credentials found. User cannot sign in.")
                
                // Report as non-fatal error for monitoring
                if (!BuildConfig.DEBUG) {
                    crashlytics.recordException(e)
                }
                
                Logger.d(LogTags.AUTH, "📊 NoCredentialException reported to Firebase Crashlytics")
            } catch (ex: Exception) {
                Logger.e(LogTags.AUTH, "Failed to report NoCredentialException to Firebase", ex)
            }
            
            val detailedError = when {
                e.message?.contains("Developer console") == true -> {
                    "Google Sign-In Konfigurationsfehler. Bitte überprüfen Sie die SHA-1 Fingerprints in der Google Cloud Console."
                }
                else -> "Kein Google-Konto gefunden. Bitte fügen Sie ein Google-Konto in den Einstellungen hinzu."
            }
            SignInResult(success = false, error = detailedError, exception = e)
        } catch (e: GetCredentialException) {
            Logger.e(LogTags.AUTH, "GetCredentialException (Type: ${e.type})", e)
            val errorMessage = when {
                e.message?.contains("10:") == true -> "Google Play Services Fehler. Bitte aktualisieren Sie Google Play Services."
                e.message?.contains("Developer console") == true -> "Google Sign-In Konfigurationsfehler. Bitte Entwickler kontaktieren."
                else -> e.message ?: "Fehler bei der Anmeldung (${e.type})"
            }
            SignInResult(success = false, error = errorMessage, exception = e)
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "Unexpected error", e)
            SignInResult(success = false, error = "Unerwarteter Fehler: ${e.message}", exception = e)
        }
    }

    fun signOutLocally() {
        Logger.d(LogTags.AUTH, "Local sign-out")
    }

    /**
     * Extract user information using the successful Hybrid-Flow approach
     * 
     * This method uses GoogleSignInClient.silentSignIn() to reliably extract
     * the email address after successful Credential Manager authentication.
     */
    suspend fun extractUserInfo(response: GetCredentialResponse?, activityContext: Context): Triple<String?, String?, String?> {
        val credential = response?.credential
        
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                
                val userId = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                
                Logger.d(LogTags.AUTH, "🔍 EXTRACT-START: Raw userId=$userId, displayName=$displayName")
                
                // HYBRID-FLOW: Use Google Sign-In silentSignIn for reliable email extraction
                val email = getEmailWithHybridFlow(activityContext)
                
                if (!email.isNullOrEmpty()) {
                    Logger.business(LogTags.AUTH, "✅ HYBRID-SUCCESS: Email extracted successfully: $email")
                    return Triple(userId, displayName, email)
                } else {
                    Logger.e(LogTags.AUTH, "❌ HYBRID-FAILED: Email extraction failed")
                    return Triple(userId, displayName, null)
                }
                
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ EXTRACT-FATAL: Critical error parsing credential", e)
            }
        }
        
        Logger.w(LogTags.AUTH, "❌ EXTRACT-TYPE: Credential is not GoogleIdTokenCredential type")
        return Triple(null, null, null)
    }

    /**
     * HYBRID-FLOW EMAIL EXTRACTION: The proven solution
     * 
     * Uses Google Sign-In silentSignIn after Credential Manager success.
     * This approach reliably extracts the email address and solves the 
     * BAD_AUTHENTICATION problem that plagued the legacy approaches.
     */
    @Suppress("DEPRECATION") // GoogleSignIn API: Required for reliable email extraction
    suspend fun getEmailWithHybridFlow(activityContext: Context): String? {
        Logger.d(LogTags.AUTH, "🔄 HYBRID-FLOW: Starting silent sign-in for email extraction...")
        
        return try {
            // Create GoogleSignInOptions with email request
            val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
            )
                .requestIdToken(googleWebClientId) // Same Web Client ID
                .requestEmail() // Explicitly request email
                .requestProfile() // Request profile for display name
                .build()
            
            val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(activityContext, gso)
            
            Logger.d(LogTags.AUTH, "🔄 HYBRID-FLOW: Performing silent sign-in...")
            
            // Use silentSignIn() - this works after successful Credential Manager flow
            val silentSignInTask = googleSignInClient.silentSignIn()
            
            // Convert to coroutine-friendly approach
            val account = try {
                // Check if task is already complete (cached)
                if (silentSignInTask.isComplete) {
                    silentSignInTask.result
                } else {
                    // Wait for task completion
                    kotlinx.coroutines.suspendCancellableCoroutine<com.google.android.gms.auth.api.signin.GoogleSignInAccount?> { continuation ->
                        silentSignInTask.addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                continuation.resume(task.result) { /* cleanup */ }
                            } else {
                                continuation.resume(null) { /* cleanup */ }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.w(LogTags.AUTH, "⚠️ HYBRID-FLOW: Silent sign-in task failed", e)
                null
            }
            
            if (account != null) {
                val email = account.email
                val displayName = account.displayName
                val userId = account.id
                
                if (!email.isNullOrEmpty()) {
                    Logger.business(LogTags.AUTH, "✅ HYBRID-FLOW: Successfully extracted email: $email")
                    Logger.d(LogTags.AUTH, "✅ HYBRID-FLOW: Profile data - userId=$userId, displayName=$displayName")
                    
                    // Store email for future use
                    val authPrefs = activityContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    authPrefs.edit().putString("user_email", email).apply()
                    Logger.d(LogTags.AUTH, "💾 HYBRID-FLOW: Email stored to SharedPreferences")
                    
                    return email
                } else {
                    Logger.w(LogTags.AUTH, "⚠️ HYBRID-FLOW: Silent sign-in succeeded but no email in account")
                }
            } else {
                Logger.w(LogTags.AUTH, "⚠️ HYBRID-FLOW: Silent sign-in returned null account")
            }
            
            Logger.e(LogTags.AUTH, "❌ HYBRID-FLOW: Failed to extract email via silent sign-in")
            null
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ HYBRID-FLOW: Critical error in hybrid flow", e)
            null
        }
    }
}
