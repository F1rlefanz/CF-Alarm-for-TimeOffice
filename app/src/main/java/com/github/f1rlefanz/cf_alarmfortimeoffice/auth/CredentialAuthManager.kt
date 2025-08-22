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

class CredentialAuthManager(private val context: Context) {

    private val credentialManager = CredentialManager.create(context)
    // Use BuildConfig for Web Client ID
    private val googleWebClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID

    suspend fun signIn(activityContext: Context): SignInResult {
        if (googleWebClientId.isBlank()) {
            Logger.e(LogTags.AUTH, "Web Client ID is empty!")
            return SignInResult(success = false, error = "Web Client ID nicht konfiguriert")
        }

        Logger.d(LogTags.AUTH, "Using Web Client ID: ${googleWebClientId.take(20)}...")
        Logger.d(LogTags.AUTH, "Package name: ${context.packageName}")
        Logger.d(LogTags.AUTH, "Activity context: ${activityContext.javaClass.simpleName}")
        Logger.d(LogTags.AUTH, "Debug SHA-1 should be: 98:1F:ED:CF:28:31:A0:10:7C:03:1B:A2:F2:4F:7C:88:06:99:20:D9")

        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)  // WICHTIG: Alle Konten anzeigen
            .setServerClientId(googleWebClientId)
            .setAutoSelectEnabled(false)  // Benutzer soll wählen können
            .build()

        val request: GetCredentialRequest = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        Logger.d(LogTags.AUTH, "Requesting credentials...")
        return try {
            // CRITICAL FIX: Use activityContext instead of stored application context
            val result = credentialManager.getCredential(context = activityContext, request = request)
            Logger.business(LogTags.AUTH, "Credential successfully obtained", result.credential.type)
            SignInResult(success = true, credentialResponse = result)

        } catch (e: GetCredentialCancellationException) {
            Logger.w(LogTags.AUTH, "Sign-in cancelled by user", e)
            SignInResult(success = false, error = "Anmeldung wurde abgebrochen.", exception = e)
        } catch (e: NoCredentialException) {
            Logger.w(LogTags.AUTH, "No Google accounts found", e)
            val detailedError = when {
                e.message?.contains("Developer console") == true -> {
                    """
                    Google Sign-In ist nicht korrekt konfiguriert:
                    1. Überprüfen Sie die SHA-1 Fingerprints in der Google Cloud Console
                    2. Debug SHA-1 muss für Package: ${context.packageName} hinzugefügt sein
                    3. OAuth 2.0 Web Client ID muss korrekt sein
                    """.trimIndent()
                }
                else -> "Kein Google-Konto auf diesem Gerät gefunden. Bitte fügen Sie ein Google-Konto in den Einstellungen hinzu."
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

    fun extractUserInfo(response: GetCredentialResponse?): Triple<String?, String?, String?> {
        val credential = response?.credential
        
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                
                val userId = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                val email = googleIdTokenCredential.id // ID ist normalerweise die E-Mail
                
                Logger.business(LogTags.AUTH, "Extracted user info: Email=$email, Name=$displayName")
                return Triple(userId, displayName, email)
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "Error parsing Google ID Token Credential", e)
            }
        }
        
        Logger.w(LogTags.AUTH, "Credential is not of expected type")
        return Triple(null, null, null)
    }
}