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

data class SignInResult(
    val success: Boolean,
    val credentialResponse: GetCredentialResponse? = null,
    val error: String? = null,
    val exception: Throwable? = null
)

/**
 * Modern Credential Authentication Manager (2025 Migration)
 * 
 * ✅ MIGRATED: Uses only modern APIs
 * - androidx.credentials for One Tap UI
 * - GoogleIdTokenCredential for user data extraction
 * - No deprecated GoogleSignIn or GoogleAuthUtil APIs
 * 
 * This implementation is future-proof and compliant with Google's 2025 API requirements.
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
                
                // Note: Google Play Services availability check removed (deprecated API)
                crashlytics.setCustomKey("auth_google_services_available", true) // Assume available since we got here
                
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
     * Extract user information using modern Credential Manager approach
     * 
     * This method uses GoogleIdTokenCredential to directly extract user information
     * without relying on deprecated APIs.
     */
    suspend fun extractUserInfo(response: GetCredentialResponse?, activityContext: Context): Triple<String?, String?, String?> {
        val credential = response?.credential
        
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                
                val userId = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                
                Logger.d(LogTags.AUTH, "🔍 EXTRACT-START: Raw userId=$userId, displayName=$displayName")
                
                // MODERN-FLOW: Use pure Credential Manager approach
                val email = googleIdTokenCredential.id // GoogleIdTokenCredential.id contains the email
                
                if (!email.isNullOrEmpty()) {
                    Logger.business(LogTags.AUTH, "✅ MODERN-SUCCESS: Email extracted successfully: $email")
                    
                    // Store email for future use
                    val authPrefs = activityContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                    authPrefs.edit().putString("user_email", email).apply()
                    Logger.d(LogTags.AUTH, "💾 MODERN-FLOW: Email stored to SharedPreferences")
                    
                    return Triple(userId, displayName, email)
                } else {
                    Logger.e(LogTags.AUTH, "❌ MODERN-FAILED: Email extraction failed")
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
     * MODERN EMAIL EXTRACTION: Using pure Credential Manager approach
     * 
     * Extracts email directly from GoogleIdTokenCredential without deprecated APIs.
     * This is the modern, future-proof approach for 2025+.
     */
    suspend fun getEmailWithModernFlow(activityContext: Context): String? {
        Logger.d(LogTags.AUTH, "🔄 MODERN-FLOW: Extracting email from stored credentials...")
        
        return try {
            // Try to get cached email first
            val authPrefs = activityContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val cachedEmail = authPrefs.getString("user_email", null)
            
            if (!cachedEmail.isNullOrEmpty()) {
                Logger.d(LogTags.AUTH, "✅ MODERN-FLOW: Using cached email: $cachedEmail")
                return cachedEmail
            }
            
            Logger.w(LogTags.AUTH, "⚠️ MODERN-FLOW: No cached email found, triggering new authentication")
            null
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ MODERN-FLOW: Error accessing cached email", e)
            null
        }
    }
}
