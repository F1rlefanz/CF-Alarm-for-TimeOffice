package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.CustomCredential
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import android.util.Base64
import org.json.JSONObject
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig

data class SignInResult(
    val success: Boolean,
    val credentialResponse: GetCredentialResponse? = null,
    val error: String? = null,
    val exception: Throwable? = null
)

/**
 * Modern Credential Authentication Manager - OAuth2 Modernized
 * 
 * ✅ MODERNIZED: Uses pure androidx.credentials approach with JWT token decoding
 * ✅ NO DEPRECATED APIs: Removed GoogleSignInClient.silentSignIn() hybrid flow
 * ✅ STANDARD APPROACH: Extracts email directly from JWT ID Token payload
 * 
 * This is the industry-standard method for extracting user information from
 * Google OAuth2 credentials without requiring deprecated APIs or additional permissions.
 */
class CredentialAuthManager(context: Context) {

    private val credentialManager = CredentialManager.create(context)
    private val googleWebClientId: String = BuildConfig.GOOGLE_WEB_CLIENT_ID

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
     * ✅ MODERNIZED: Extract user information using JWT token decoding
     * 
     * This is the standard, modern approach used by most Android apps.
     * The email is extracted directly from the JWT ID Token payload,
     * eliminating the need for deprecated GoogleSignIn APIs.
     */
    fun extractUserInfo(response: GetCredentialResponse?): Triple<String?, String?, String?> {
        val credential = response?.credential
        
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                
                val userId = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                
                Logger.d(LogTags.AUTH, "🔍 EXTRACT-START: Raw userId=$userId, displayName=$displayName")
                
                // ✅ MODERN JWT-BASED EMAIL EXTRACTION
                val email = extractEmailFromIdToken(googleIdTokenCredential.idToken)
                
                if (!email.isNullOrEmpty()) {
                    Logger.business(LogTags.AUTH, "✅ JWT-SUCCESS: Email extracted from ID Token: $email")
                    return Triple(userId, displayName, email)
                } else {
                    Logger.e(LogTags.AUTH, "❌ JWT-FAILED: Email extraction from ID Token failed")
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
     * ✅ STANDARD MODERN APPROACH: Extract email from JWT ID Token
     * 
     * This is how most Android apps extract user information from Google OAuth2 credentials.
     * 
     * JWT Structure: Header.Payload.Signature
     * The Payload contains user claims including:
     * - email: User's email address
     * - email_verified: Whether email is verified
     * - name: User's display name
     * - picture: User's profile picture URL
     * - sub: User's unique Google ID
     * 
     * @param idToken The JWT ID Token from GoogleIdTokenCredential
     * @return The user's email address, or null if extraction fails
     */
    private fun extractEmailFromIdToken(idToken: String): String? {
        return try {
            Logger.d(LogTags.AUTH, "🔍 JWT-DECODE: Starting JWT token decoding for email extraction")
            
            // JWT Format: Header.Payload.Signature
            val segments = idToken.split(".")
            
            if (segments.size < 2) {
                Logger.e(LogTags.AUTH, "❌ JWT-ERROR: Invalid JWT format - not enough segments (${segments.size})")
                return null
            }
            
            // Decode Payload (second segment) - Base64 URL-safe decoding
            val payloadBytes = Base64.decode(
                segments[1], 
                Base64.URL_SAFE or Base64.NO_PADDING
            )
            val payloadJson = JSONObject(String(payloadBytes, Charsets.UTF_8))
            
            // Extract user information from JWT payload
            val email = payloadJson.optString("email", "")
            val emailVerified = payloadJson.optBoolean("email_verified", false)
            val name = payloadJson.optString("name", "")
            val sub = payloadJson.optString("sub", "")
            
            Logger.d(LogTags.AUTH, "📧 JWT-PAYLOAD: email=$email, verified=$emailVerified, name=$name, sub=$sub")
            
            if (email.isNullOrEmpty()) {
                Logger.e(LogTags.AUTH, "❌ JWT-ERROR: No email found in JWT payload")
                return null
            }
            
            if (!emailVerified) {
                Logger.w(LogTags.AUTH, "⚠️ JWT-WARNING: Email is not verified by Google")
            }
            
            Logger.business(LogTags.AUTH, "✅ JWT-SUCCESS: Email extracted from JWT: $email (verified=$emailVerified)")
            email
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ JWT-EXCEPTION: Failed to extract email from ID Token", e)
            null
        }
    }
}
