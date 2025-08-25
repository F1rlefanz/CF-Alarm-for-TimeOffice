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
    
    // ALTERNATIVE: For Calendar API, we might need to use Android Client approach
    // This would require using the OAuth2 client that matches the app's signing certificate

    suspend fun signIn(activityContext: Context): SignInResult {
        if (googleWebClientId.isBlank()) {
            Logger.e(LogTags.AUTH, "Web Client ID is empty!")
            return SignInResult(success = false, error = "Web Client ID nicht konfiguriert")
        }

        Logger.d(LogTags.AUTH, "Using Web Client ID: ${googleWebClientId.take(20)}...")
        Logger.d(LogTags.AUTH, "Package name: ${context.packageName}")
        Logger.d(LogTags.AUTH, "Activity context: ${activityContext.javaClass.simpleName}")
        Logger.d(LogTags.AUTH, "Debug SHA-1 should be: 98:1F:ED:CF:28:31:A0:10:7C:03:1B:A2:F2:4F:7C:88:06:99:20:D9")

        // CRITICAL: Create GoogleIdOption with explicit email request
        val googleIdOption: GetGoogleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)  // WICHTIG: Alle Konten anzeigen
            .setServerClientId(googleWebClientId)
            .setAutoSelectEnabled(false)  // Benutzer soll wählen können
            .setNonce(null) // Keine Nonce required für Standard-Flow
            // NOTE: Email is included by default in the ID token when using proper Web Client ID
            // If email is still missing, the issue is in Google Cloud Console configuration
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

    suspend fun extractUserInfo(response: GetCredentialResponse?, activityContext: Context): Triple<String?, String?, String?> {
        val credential = response?.credential
        
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                
                val userId = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                
                Logger.d(LogTags.AUTH, "🔍 EXTRACT-START: Raw userId=$userId, displayName=$displayName")
                
                // HYBRID-FLOW: Try to extract email using Google Sign-In silentSignIn
                // This should work because Credential Manager just authenticated the user
                var email: String? = getEmailWithHybridFlow(activityContext)
                
                if (!email.isNullOrEmpty()) {
                    Logger.business(LogTags.AUTH, "✅ HYBRID-SUCCESS: Email extracted successfully: $email")
                    return Triple(userId, displayName, email)
                }
                
                // FALLBACK: Traditional methods if Hybrid Flow fails
                Logger.w(LogTags.AUTH, "⚠️ HYBRID-FAILED: Falling back to traditional methods")
                email = getEmailWithFallback(activityContext, null)
                
                if (!email.isNullOrEmpty()) {
                    Logger.business(LogTags.AUTH, "✅ FALLBACK-SUCCESS: Email extracted via fallback: $email")
                    return Triple(userId, displayName, email)
                }
                
                // If all methods fail, this indicates a configuration problem
                Logger.e(LogTags.AUTH, "❌ EMAIL-FAILED: No email found despite One Tap UI showing it")
                Logger.e(LogTags.AUTH, "💡 This indicates Google Cloud Console configuration issue or device setup problem")
                
                return Triple(userId, displayName, null)
                
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ EXTRACT-FATAL: Critical error parsing credential", e)
            }
        }
        
        Logger.w(LogTags.AUTH, "❌ EXTRACT-TYPE: Credential is not GoogleIdTokenCredential type")
        return Triple(null, null, null)
    }

    /**
     * HYBRID-FLOW EMAIL EXTRACTION: Uses Google Sign-In silentSignIn after Credential Manager success
     * This is Gemini's recommended approach: Modern UI + Reliable data retrieval
     */
    @Suppress("DEPRECATION") // GoogleSignIn API: Needed for reliable email extraction
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
            
            // Use silentSignIn() - this should work after successful Credential Manager flow
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
    
    /**
     * FALLBACK EMAIL EXTRACTION: Traditional methods for when Hybrid Flow fails
     */
    suspend fun getEmailWithFallback(activityContext: Context, userEmail: String?): String? {
        Logger.d(LogTags.AUTH, "🔍 EMAIL-FALLBACK: Starting traditional email extraction...")
        
        // Method 1: Use provided userEmail if valid
        if (!userEmail.isNullOrEmpty() && userEmail.contains("@") && !userEmail.contains("user.needs.to.enter")) {
            Logger.business(LogTags.AUTH, "✅ EMAIL-PROVIDED: Using valid provided email: $userEmail")
            return userEmail
        }
        
        // Method 2: Try AccountManager 
        try {
            Logger.d(LogTags.AUTH, "🔄 EMAIL-ACCOUNT: Trying AccountManager...")
            val accountManager = activityContext.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
            val googleAccounts = accountManager.getAccountsByType("com.google")
            
            if (googleAccounts.isNotEmpty()) {
                val email = googleAccounts[0].name
                Logger.business(LogTags.AUTH, "✅ EMAIL-ACCOUNT: Found email via AccountManager: $email")
                return email
            }
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "⚠️ EMAIL-ACCOUNT: AccountManager failed", e)
        }
        
        // Method 3: Check SharedPreferences for previously stored email
        try {
            val prefs = activityContext.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val storedEmail = prefs.getString("user_email", null)
            
            if (!storedEmail.isNullOrEmpty() && storedEmail.contains("@") && !storedEmail.contains("user.needs.to.enter")) {
                Logger.business(LogTags.AUTH, "✅ EMAIL-STORED: Found valid stored email: $storedEmail")
                return storedEmail
            }
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "⚠️ EMAIL-STORED: SharedPreferences check failed", e)
        }
        
        Logger.e(LogTags.AUTH, "❌ EMAIL-FALLBACK: All traditional email extraction methods failed")
        return null
    }
    fun diagnoseEmailExtraction(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== EMAIL EXTRACTION DIAGNOSTIC ===\n")
        
        // Check SharedPreferences
        try {
            val authPrefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
            val cfPrefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            
            val authEmail = authPrefs.getString("user_email", "NOT_FOUND")
            val cfEmail = cfPrefs.getString("current_user_email", "NOT_FOUND")
            
            sb.append("auth_prefs.user_email: $authEmail\n")
            sb.append("cf_alarm_auth.current_user_email: $cfEmail\n")
            
        } catch (e: Exception) {
            sb.append("SharedPreferences check failed: ${e.message}\n")
        }
        
        // Check Android Accounts
        try {
            val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
            val accounts = accountManager.getAccountsByType("com.google")
            
            sb.append("Google Accounts on device: ${accounts.size}\n")
            accounts.forEachIndexed { index, account ->
                sb.append("  Account $index: ${account.name}\n")
            }
            
        } catch (e: SecurityException) {
            sb.append("GET_ACCOUNTS permission denied\n")
        } catch (e: Exception) {
            sb.append("Account check failed: ${e.message}\n")
        }
        
        sb.append("=== END DIAGNOSTIC ===")
        return sb.toString()
    }
}