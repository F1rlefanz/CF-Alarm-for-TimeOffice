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

    fun extractUserInfo(response: GetCredentialResponse?): Triple<String?, String?, String?> {
        val credential = response?.credential
        
        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            try {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                
                val userId = googleIdTokenCredential.id
                val displayName = googleIdTokenCredential.displayName
                
                Logger.d(LogTags.AUTH, "🔍 EXTRACT-START: Raw userId=$userId, displayName=$displayName")
                
                // CRITICAL FIX: Try different methods to get the email
                var email: String? = null
                
                // Method 1: Try to get from data bundle (various possible keys)
                try {
                    val bundleKeys = arrayOf(
                        "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID",
                        "email",
                        "account_name",
                        "user_email"
                    )
                    
                    for (key in bundleKeys) {
                        email = credential.data.getString(key)
                        if (!email.isNullOrEmpty() && email.contains("@")) {
                            Logger.business(LogTags.AUTH, "✅ EMAIL-BUNDLE: Found email via bundle key '$key': $email")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(LogTags.AUTH, "⚠️ EMAIL-BUNDLE: Bundle key extraction failed", e)
                }
                
                // Method 2: Enhanced JWT Token Parsing with better error handling
                if (email.isNullOrEmpty() || !email.contains("@")) {
                    Logger.d(LogTags.AUTH, "🔄 EMAIL-JWT: Attempting JWT token parsing...")
                    
                    try {
                        val idToken = googleIdTokenCredential.idToken
                        Logger.d(LogTags.AUTH, "📝 JWT-DEBUG: Token length: ${idToken.length}")
                        Logger.d(LogTags.AUTH, "📝 JWT-DEBUG: Token start: ${idToken.take(50)}...")
                        
                        val parts = idToken.split(".")
                        if (parts.size == 3) {
                            val header = parts[0]
                            val payload = parts[1]
                            val signature = parts[2]
                            
                            Logger.d(LogTags.AUTH, "📝 JWT-DEBUG: Header length: ${header.length}, Payload length: ${payload.length}")
                            
                            // IMPROVED Base64 decoding with multiple attempts
                            val decodedPayload = try {
                                // First attempt: URL_SAFE with NO_PADDING
                                android.util.Base64.decode(
                                    payload, 
                                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                )
                            } catch (e: Exception) {
                                Logger.w(LogTags.AUTH, "🔄 JWT-DECODE: First decode attempt failed, trying alternative", e)
                                
                                try {
                                    // Second attempt: URL_SAFE without NO_PADDING
                                    android.util.Base64.decode(payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
                                } catch (e2: Exception) {
                                    Logger.w(LogTags.AUTH, "🔄 JWT-DECODE: Second decode attempt failed, trying default", e2)
                                    
                                    // Third attempt: Default Base64
                                    android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                                }
                            }
                            
                            val decodedString = String(decodedPayload, Charsets.UTF_8)
                            Logger.d(LogTags.AUTH, "📝 JWT-PAYLOAD: $decodedString")
                            
                            val jsonObject = org.json.JSONObject(decodedString)
                            
                            // Enhanced email field search
                            val emailFields = arrayOf("email", "upn", "preferred_username", "unique_name")
                            
                            for (field in emailFields) {
                                if (jsonObject.has(field)) {
                                    val foundEmail = jsonObject.getString(field)
                                    if (!foundEmail.isNullOrEmpty() && foundEmail.contains("@")) {
                                        email = foundEmail
                                        Logger.business(LogTags.AUTH, "✅ EMAIL-JWT: Found email via field '$field': $email")
                                        break
                                    }
                                }
                            }
                            
                            if (email.isNullOrEmpty() || !email.contains("@")) {
                                // Log all available fields for debugging
                                val availableFields = jsonObject.keys().asSequence().toList()
                                Logger.w(LogTags.AUTH, "⚠️ EMAIL-JWT: No email found. Available fields: $availableFields")
                            }
                            
                        } else {
                            Logger.e(LogTags.AUTH, "❌ EMAIL-JWT: Invalid JWT format - expected 3 parts, got ${parts.size}")
                        }
                    } catch (e: Exception) {
                        Logger.e(LogTags.AUTH, "❌ EMAIL-JWT: JWT parsing failed", e)
                        Logger.d(LogTags.AUTH, "🔍 JWT-ERROR: Exception type: ${e.javaClass.simpleName}, message: ${e.message}")
                    }
                }
                
                // Method 3: Enhanced Android Account Manager Fallback
                if (email.isNullOrEmpty() || !email.contains("@")) {
                    Logger.d(LogTags.AUTH, "🔄 EMAIL-ACCOUNTS: Attempting AccountManager fallback...")
                    
                    try {
                        val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
                        
                        // CRITICAL FIX: Try different account types
                        val googleAccounts = accountManager.getAccountsByType("com.google")
                        val gmailAccounts = accountManager.getAccountsByType("com.gmail")
                        val allAccounts = accountManager.accounts
                        
                        Logger.d(LogTags.AUTH, "📱 ACCOUNTS-DEBUG: Found ${googleAccounts.size} Google accounts")
                        Logger.d(LogTags.AUTH, "📱 ACCOUNTS-DEBUG: Found ${gmailAccounts.size} Gmail accounts") 
                        Logger.d(LogTags.AUTH, "📱 ACCOUNTS-DEBUG: Found ${allAccounts.size} total accounts")
                        
                        // Try different account sources
                        val candidateEmail = when {
                            googleAccounts.isNotEmpty() -> {
                                Logger.d(LogTags.AUTH, "🔄 Using Google account: ${googleAccounts[0].name}")
                                googleAccounts[0].name
                            }
                            gmailAccounts.isNotEmpty() -> {
                                Logger.d(LogTags.AUTH, "🔄 Using Gmail account: ${gmailAccounts[0].name}")
                                gmailAccounts[0].name
                            }
                            allAccounts.any { it.name.contains("@") } -> {
                                val emailAccount = allAccounts.first { it.name.contains("@") }
                                Logger.d(LogTags.AUTH, "🔄 Using email-like account: ${emailAccount.name} (${emailAccount.type})")
                                emailAccount.name
                            }
                            else -> {
                                Logger.w(LogTags.AUTH, "⚠️ No email-like accounts found")
                                null
                            }
                        }
                        
                        if (!candidateEmail.isNullOrEmpty() && candidateEmail.contains("@")) {
                            email = candidateEmail
                            Logger.business(LogTags.AUTH, "✅ EMAIL-ACCOUNTS: Found email via AccountManager: $email")
                        }
                        
                    } catch (e: SecurityException) {
                        Logger.w(LogTags.AUTH, "❌ EMAIL-ACCOUNTS: SecurityException - GET_ACCOUNTS permission issue", e)
                    } catch (e: Exception) {
                        Logger.e(LogTags.AUTH, "❌ EMAIL-ACCOUNTS: AccountManager failed", e)
                    }
                }
                
                // Method 4: USER INPUT FALLBACK - If we still have no email
                if (email.isNullOrEmpty() || !email.contains("@")) {
                    Logger.e(LogTags.AUTH, "❌ CRITICAL-ERROR: No email extracted after all methods!")
                    Logger.e(LogTags.AUTH, "📊 FINAL-DEBUG: userId=$userId, email=$email, displayName=$displayName")
                    
                    // CRITICAL FIX: Extract email from Google's "sub" field if possible
                    if (userId?.contains("@") == true) {
                        email = userId
                        Logger.business(LogTags.AUTH, "🔄 FALLBACK: Using userId as email (it contains @): $email")
                    } else {
                        Logger.e(LogTags.AUTH, "💥 TOTAL-FAILURE: Calendar API authorization will definitely fail!")
                        Logger.e(LogTags.AUTH, "💡 SOLUTION: Try different Google account or check Google Cloud Console setup")
                        
                        // FINAL FALLBACK: This should prompt user for manual input
                        Logger.w(LogTags.AUTH, "🆘 MANUAL-INPUT-REQUIRED: Email extraction failed completely")
                        Logger.w(LogTags.AUTH, "🆘 MANUAL-INPUT: Showing email input dialog as fallback")
                    }
                }
                
                // Enhanced storage with multiple locations
                if (!email.isNullOrEmpty() && email.contains("@")) {
                    try {
                        // Store in multiple SharedPreferences for reliability
                        val authPrefs = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
                        authPrefs.edit().putString("user_email", email).apply()
                        
                        val cfAlarmPrefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
                        cfAlarmPrefs.edit().putString("current_user_email", email).apply()
                        
                        // Additional fallback storage
                        val mainPrefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
                        mainPrefs.edit().putString("google_user_email", email).apply()
                        
                        Logger.business(LogTags.AUTH, "💾 EMAIL-STORE: Email saved to multiple SharedPreferences")
                        Logger.business(LogTags.AUTH, "🎯 FINAL-SUCCESS: Extracted user info - UserId=$userId, Email=$email, Name=$displayName")
                        
                    } catch (e: Exception) {
                        Logger.e(LogTags.AUTH, "❌ EMAIL-STORE: Failed to store email", e)
                    }
                } else {
                    Logger.e(LogTags.AUTH, "❌ FINAL-FAILURE: No valid email found for Calendar API authorization")
                }
                
                return Triple(userId, displayName, email)
                
            } catch (e: Exception) {
                Logger.e(LogTags.AUTH, "❌ EXTRACT-FATAL: Critical error parsing Google ID Token Credential", e)
                Logger.d(LogTags.AUTH, "🔍 FATAL-DEBUG: Exception: ${e.javaClass.simpleName}, message: ${e.message}")
            }
        }
        
        Logger.w(LogTags.AUTH, "❌ EXTRACT-TYPE: Credential is not of expected GoogleIdTokenCredential type")
        Logger.d(LogTags.AUTH, "🔍 TYPE-DEBUG: Credential type: ${credential?.javaClass?.simpleName}")
        
        return Triple(null, null, null)
    }

    /**
     * ENHANCED EMAIL EXTRACTION: Fallback to Google Sign-In API if Credential Manager fails
     * This provides better compatibility and more reliable email extraction
     */
    @Suppress("DEPRECATION") // GoogleSignIn API: Complex migration, keeping for email reliability
    suspend fun getEmailWithFallback(activityContext: Context, userEmail: String?): String? {
        Logger.d(LogTags.AUTH, "🔍 EMAIL-FALLBACK: Starting enhanced email extraction...")
        
        // Method 1: Use provided userEmail if valid
        if (!userEmail.isNullOrEmpty() && userEmail.contains("@") && !userEmail.contains("user.needs.to.enter")) {
            Logger.business(LogTags.AUTH, "✅ EMAIL-PROVIDED: Using valid provided email: $userEmail")
            return userEmail
        }
        
        // Method 2: Try Google Sign-In API (more reliable for email)
        try {
            Logger.d(LogTags.AUTH, "🔄 EMAIL-GSI: Trying Google Sign-In API for email...")
            
            val gso = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
                com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
            )
                .requestEmail() // Explicitly request email
                .requestProfile()
                .requestIdToken(googleWebClientId)
                .build()
            
            val googleSignInClient = com.google.android.gms.auth.api.signin.GoogleSignIn.getClient(activityContext, gso)
            val lastSignedInAccount = com.google.android.gms.auth.api.signin.GoogleSignIn.getLastSignedInAccount(activityContext)
            
            if (lastSignedInAccount != null && !lastSignedInAccount.email.isNullOrEmpty()) {
                Logger.business(LogTags.AUTH, "✅ EMAIL-GSI: Found email via Google Sign-In: ${lastSignedInAccount.email}")
                return lastSignedInAccount.email
            } else {
                Logger.d(LogTags.AUTH, "ℹ️ EMAIL-GSI: No valid signed-in account found")
            }
            
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "⚠️ EMAIL-GSI: Google Sign-In fallback failed", e)
        }
        
        // Method 3: Try AccountManager (less reliable on modern Android)
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
        
        // Method 4: Check SharedPreferences for previously stored email
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
        
        Logger.e(LogTags.AUTH, "❌ EMAIL-FALLBACK: All email extraction methods failed")
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