package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenStorageRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Modern OAuth2TokenManager - OAuth2 Modernized
 * 
 * ✅ MODERNIZED: Removed AccountManager fallback (no longer needs GET_ACCOUNTS permission)
 * ✅ CLEAN IMPLEMENTATION: Relies solely on SharedPreferences for email storage
 * 
 * ENHANCED FEATURES:
 * 1. Added Activity context parameter for launching permission intents
 * 2. Proper UserRecoverableAuthException handling with intent launch
 * 3. Added callback mechanism for permission results
 * 4. Enhanced error messages for better debugging
 * 5. Removed deprecated AccountManager fallback
 */
class ModernOAuth2TokenManager(
    private val context: Context,
    private val tokenStorage: TokenStorageRepository
) {
    
    // CRITICAL FIX: Add request code for permission activity result
    companion object {
        const val REQUEST_CODE_CALENDAR_AUTHORIZATION = 1001
    }
    
    private var isInitialized = false
    private var lastUserEmail: String? = null
    
    // CRITICAL FIX: Callback for permission request results
    private var pendingAuthCallback: ((Boolean) -> Unit)? = null
    
    /**
     * Initialize token manager with proper validation
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.business(LogTags.TOKEN, "🔧 TOKEN-INIT: Initializing ModernOAuth2TokenManager")
            
            // Validate storage availability
            tokenStorage.getCurrentToken()
            Logger.d(LogTags.TOKEN, "✅ TOKEN-INIT: Token storage validated successfully")
            
            // Cache user email for faster lookups
            lastUserEmail = getUserEmailFromAccounts()
            if (lastUserEmail != null) {
                Logger.business(LogTags.TOKEN, "✅ TOKEN-INIT: User email cached: $lastUserEmail")
            } else {
                Logger.w(LogTags.TOKEN, "⚠️ TOKEN-INIT: No user email available - user needs to sign in")
            }
            
            isInitialized = true
            Logger.business(LogTags.TOKEN, "✅ TOKEN-INIT: ModernOAuth2TokenManager initialized successfully")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ TOKEN-INIT: Failed to initialize ModernOAuth2TokenManager", e)
            Result.failure(e)
        }
    }
    
    /**
     * CRITICAL FIX: Enhanced token retrieval with initialization check and retry logic
     * 
     * 🔧 OPTION 4 FIX: Added token health check before returning
     */
    suspend fun getValidCalendarToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Ensure manager is initialized
            if (!isInitialized) {
                Logger.w(LogTags.TOKEN, "⚠️ TOKEN-DIAGNOSTIC: Manager not initialized, initializing now...")
                val initResult = initialize()
                if (initResult.isFailure) {
                    Logger.e(LogTags.TOKEN, "❌ TOKEN-ERROR: Failed to initialize token manager")
                    return@withContext Result.failure(TokenException.NoTokenAvailable("Token manager initialization failed"))
                }
            }
            
            // 🔧 OPTION 4 FIX: Read token with health check
            val currentToken = tokenStorage.getCurrentToken()
            
            // 🔧 OPTION 4 FIX: Defensive null check with detailed logging
            if (currentToken == null) {
                Logger.e(LogTags.TOKEN, "❌ OPTION-4-DEFENSIVE: Token storage returned NULL - no token available")
                Logger.business(LogTags.TOKEN, "💡 OPTION-4-DEFENSIVE: User must authorize Calendar access")
                
                // Check if user email exists (signed in but no Calendar token)
                if (lastUserEmail != null) {
                    Logger.w(LogTags.TOKEN, "⚠️ OPTION-4-DEFENSIVE: User is signed in ($lastUserEmail) but Calendar token is missing")
                    return@withContext Result.failure(TokenException.NoTokenAvailable("Calendar authorization required - user is signed in but needs to authorize Calendar access"))
                }
                
                return@withContext Result.failure(TokenException.NoTokenAvailable("No Calendar API authorization - please sign in and authorize Calendar access"))
            }
            
            when {
                currentToken.isValid() -> {
                    Logger.business(LogTags.TOKEN, "✅ TOKEN-DIAGNOSTIC: Using valid Calendar access token (${currentToken.getRemainingLifetimeMinutes()}min remaining)")
                    Result.success(currentToken.accessToken)
                }
                
                currentToken.canRefresh() && !currentToken.accessToken.isBlank() -> {
                    Logger.business(LogTags.TOKEN, "🔄 TOKEN-DIAGNOSTIC: Calendar token expired (${-currentToken.getRemainingLifetimeMinutes()}min ago), attempting refresh")
                    
                    val refreshResult = refreshCalendarTokenImproved(currentToken.refreshToken)
                    
                    if (refreshResult.isSuccess) {
                        Logger.business(LogTags.TOKEN, "✅ TOKEN-REFRESH: Calendar token refreshed successfully")
                        refreshResult
                    } else {
                        Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Failed to refresh Calendar token", refreshResult.exceptionOrNull())
                        Logger.w(LogTags.TOKEN, "💡 TOKEN-DIAGNOSTIC: Token refresh failed - user needs to re-authorize Calendar access")
                        Result.failure(TokenException.AuthorizationExpired("Calendar token refresh failed - re-authorization required"))
                    }
                }
                
                else -> {
                    Logger.w(LogTags.TOKEN, "❌ TOKEN-DIAGNOSTIC: Calendar token expired and cannot be refreshed - re-authorization required")
                    Logger.d(LogTags.TOKEN, "💡 TOKEN-DIAGNOSTIC: User needs to re-authorize Calendar access")
                    Result.failure(TokenException.AuthorizationExpired("Calendar authorization expired - re-authorization required"))
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ TOKEN-DIAGNOSTIC: Error getting valid Calendar token", e)
            Result.failure(e)
        }
    }
    
    /**
     * CRITICAL FIX: Authorize Calendar API access with proper UserRecoverableAuthException handling
     * 
     * This method now properly launches the permission request intent when user permission
     * is needed, instead of just returning a failure.
     * 
     * @param userEmail The Google account email to authorize
     * @param activity The Activity context required to launch permission request (optional for backward compatibility)
     * @param onPermissionResult Callback for permission request result (optional, for non-blocking flow)
     * @return AuthResult with token data or failure reason
     */
    suspend fun authorizeCalendarAccess(
        userEmail: String,
        activity: Activity? = null,
        onPermissionResult: ((Boolean) -> Unit)? = null
    ): AuthResult = withContext(Dispatchers.IO) {
        try {
            Logger.business(LogTags.OAUTH, "🔐 AUTH-FIXED: Authorizing Calendar access for user: $userEmail")
            
            // Create Google Account for token request
            val googleAccount = android.accounts.Account(userEmail, "com.google")
            
            // CRITICAL FIX: Request Calendar API access token with proper exception handling
            val calendarToken = try {
                GoogleAuthUtil.getToken(
                    context,
                    googleAccount,
                    "oauth2:${CalendarScopes.CALENDAR_READONLY}"
                )
            } catch (e: UserRecoverableAuthException) {
                // CRITICAL FIX: Launch permission request intent instead of just logging
                Logger.w(LogTags.OAUTH, "⚠️ AUTH-FIXED: Calendar authorization requires user permission")
                
                if (activity != null) {
                    Logger.business(LogTags.OAUTH, "🚀 AUTH-FIXED: Launching Calendar permission request")
                    
                    // Store callback for result handling
                    pendingAuthCallback = onPermissionResult
                    
                    // Launch the permission request intent
                    try {
                        activity.startActivityForResult(
                            e.intent,
                            REQUEST_CODE_CALENDAR_AUTHORIZATION
                        )
                        
                        // Return pending status - result will come through onActivityResult
                        return@withContext AuthResult.Pending("Calendar permission request launched - waiting for user response")
                    } catch (launchException: Exception) {
                        Logger.e(LogTags.OAUTH, "❌ AUTH-FIXED: Failed to launch permission intent", launchException)
                        return@withContext AuthResult.Failure("Failed to launch Calendar permission request: ${launchException.message}")
                    }
                } else {
                    // No activity context - return detailed error with intent
                    Logger.e(LogTags.OAUTH, "❌ AUTH-FIXED: No Activity context to launch permission request")
                    Logger.d(LogTags.OAUTH, "💡 AUTH-FIXED: Intent details: ${e.intent}")
                    return@withContext AuthResult.Failure(
                        "NEEDS_USER_PERMISSION",
                        permissionIntent = e.intent
                    )
                }
            } catch (e: Exception) {
                Logger.e(LogTags.OAUTH, "❌ AUTH-FIXED: Failed to get Calendar token", e)
                val errorMessage = when {
                    e.message?.contains("NetworkError") == true -> "Network error during Calendar authorization"
                    e.message?.contains("Account not found") == true -> "Google account not found on device"
                    e.message?.contains("ServiceDisabled") == true -> "Calendar API service disabled"
                    e.message?.contains("BAD_AUTHENTICATION") == true -> "Authentication failed - invalid credentials"
                    else -> "Calendar authorization failed: ${e.localizedMessage}"
                }
                return@withContext AuthResult.Failure(errorMessage)
            }
            
            if (calendarToken.isEmpty()) {
                Logger.e(LogTags.OAUTH, "❌ AUTH-FIXED: Empty Calendar API token received")
                return@withContext AuthResult.Failure("Failed to obtain Calendar API token")
            }
            
            Logger.business(LogTags.OAUTH, "✅ AUTH-FIXED: Successfully obtained Calendar API token")
            
            // 🔧 OPTION 2 FIX: Store token WITH user email for refresh capability
            val tokenData = TokenData.fromOAuthResponse(
                accessToken = calendarToken,
                refreshToken = calendarToken, // ✅ FIXED: Use actual token for refresh capability
                expiresInSeconds = 3600L, // 1 hour
                scope = CalendarScopes.CALENDAR_READONLY,
                userEmail = userEmail // 🔧 PHASE 1: Store email for reliable refresh
            )
            
            Logger.d(LogTags.TOKEN, "🔐 PHASE-1-FIX: Token stored with email and refresh capability")
            
            // Store token
            val storeResult = tokenStorage.saveToken(tokenData)
            if (storeResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ AUTH-FIXED: Failed to store Calendar token: ${storeResult.exceptionOrNull()}")
                return@withContext AuthResult.Failure("Failed to store Calendar authorization")
            }
            
            Logger.business(LogTags.TOKEN, "✅ AUTH-FIXED: Calendar token stored successfully")
            
            // Notify callback of success
            onPermissionResult?.invoke(true)
            
            AuthResult.Success(tokenData)
            
        } catch (e: Exception) {
            Logger.e(LogTags.OAUTH, "❌ AUTH-FIXED: Calendar authorization failed", e)
            onPermissionResult?.invoke(false)
            AuthResult.Failure("Calendar authorization failed: ${e.localizedMessage}")
        }
    }
    
    /**
     * CRITICAL FIX: Handle permission request result
     * Call this from Activity.onActivityResult()
     */
    suspend fun handlePermissionResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE_CALENDAR_AUTHORIZATION) {
            return false
        }
        
        val success = resultCode == Activity.RESULT_OK
        Logger.business(LogTags.OAUTH, "🎯 AUTH-FIXED: Calendar permission result: ${if (success) "GRANTED" else "DENIED"}")
        
        // Notify pending callback
        pendingAuthCallback?.invoke(success)
        pendingAuthCallback = null
        
        // If permission granted, retry token fetch
        if (success && lastUserEmail != null) {
            Logger.business(LogTags.OAUTH, "🔄 AUTH-FIXED: Permission granted, retrying token fetch")
            val retryResult = authorizeCalendarAccess(lastUserEmail!!)
            return retryResult is AuthResult.Success
        }
        
        return success
    }
    
    /**
     * Check if Calendar authorization is available
     */
    suspend fun hasCalendarAuthorization(): Boolean = withContext(Dispatchers.IO) {
        try {
            val currentToken = tokenStorage.getCurrentToken()
            val hasToken = currentToken?.isValid() ?: false
            Logger.dThrottled(LogTags.AUTH, "Calendar authorization check: $hasToken")
            hasToken
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "Error checking Calendar authorization", e)
            false
        }
    }
    
    /**
     * ✅ PHASE 1 FIX: Multi-Source Email Retrieval with JWT Fallback
     * 
     * Retrieval Strategy (in order):
     * 1. SharedPreferences (fastest)
     * 2. TokenData userEmail field (stored with token)
     * 3. JWT Token extraction (from access token)
     * 
     * This redundancy prevents token refresh failures due to data loss.
     */
    private suspend fun getUserEmailFromAccounts(): String? {
        return try {
            Logger.d(LogTags.AUTH, "🔍 PHASE-1: Multi-source email retrieval started")
            
            // SOURCE 1: SharedPreferences (fastest)
            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            val emailFromPrefs = prefs.getString("current_user_email", null)
            val isSignedIn = prefs.getBoolean("user_signed_in", false)
            
            if (!emailFromPrefs.isNullOrBlank() && emailFromPrefs.contains("@") && isSignedIn) {
                Logger.business(LogTags.AUTH, "✅ PHASE-1: Email from SharedPreferences: $emailFromPrefs")
                return emailFromPrefs
            }
            
            Logger.d(LogTags.AUTH, "⚠️ PHASE-1: No email in SharedPreferences, trying TokenData")
            
            // SOURCE 2: TokenData userEmail field
            val currentToken = tokenStorage.getCurrentToken()
            if (currentToken?.userEmail?.isNotBlank() == true) {
                Logger.business(LogTags.AUTH, "✅ PHASE-1: Email from TokenData: ${currentToken.userEmail}")
                
                // Cache back to SharedPreferences for next time
                saveUserEmailToPreferences(currentToken.userEmail)
                
                return currentToken.userEmail
            }
            
            Logger.d(LogTags.AUTH, "⚠️ PHASE-1: No email in TokenData, trying JWT extraction")
            
            // SOURCE 3: JWT Token extraction (last resort)
            if (currentToken?.accessToken?.isNotBlank() == true) {
                val emailFromJWT = extractEmailFromJWT(currentToken.accessToken)
                
                if (!emailFromJWT.isNullOrBlank()) {
                    Logger.business(LogTags.AUTH, "✅ PHASE-1: Email extracted from JWT: $emailFromJWT")
                    
                    // Cache to both SharedPreferences and TokenData for future
                    saveUserEmailToPreferences(emailFromJWT)
                    
                    // Update token with email
                    val updatedToken = currentToken.copy(userEmail = emailFromJWT)
                    tokenStorage.saveToken(updatedToken)
                    
                    return emailFromJWT
                }
            }
            
            // All sources failed
            Logger.w(LogTags.AUTH, "❌ PHASE-1: No user email available from any source - user needs to sign in")
            null
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ PHASE-1: Error during multi-source email retrieval", e)
            null
        }
    }
    
    /**
     * PHASE 1 FIX: Extract email from JWT access token
     * 
     * JWT Structure: Header.Payload.Signature
     * Payload contains user claims including email.
     */
    private fun extractEmailFromJWT(accessToken: String): String? {
        return try {
            Logger.d(LogTags.AUTH, "🔍 JWT-EXTRACT: Starting email extraction from access token")
            
            val segments = accessToken.split(".")
            if (segments.size < 2) {
                Logger.e(LogTags.AUTH, "❌ JWT-EXTRACT: Invalid JWT format (${segments.size} segments)")
                return null
            }
            
            // Decode Payload (second segment)
            val payloadBytes = android.util.Base64.decode(
                segments[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val payloadJson = org.json.JSONObject(String(payloadBytes, Charsets.UTF_8))
            
            val email = payloadJson.optString("email", "")
            
            if (email.isNotBlank()) {
                Logger.business(LogTags.AUTH, "✅ JWT-EXTRACT: Email found: $email")
                return email
            }
            
            Logger.e(LogTags.AUTH, "❌ JWT-EXTRACT: No email in JWT payload")
            null
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ JWT-EXTRACT: Exception during extraction", e)
            null
        }
    }
    
    /**
     * Helper method to save user email to SharedPreferences
     */
    private fun saveUserEmailToPreferences(email: String) {
        try {
            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            prefs.edit {
                putString("current_user_email", email)
            }
            Logger.d(LogTags.AUTH, "✅ EMAIL-SAVE: User email saved to SharedPreferences: $email")
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Failed to save user email to SharedPreferences", e)
        }
    }
    
    /**
     * Improved Calendar token refresh
     * 
     * 🔧 PHASE 1 FIX: Uses multi-source email retrieval for reliability
     */
    private suspend fun refreshCalendarTokenImproved(refreshToken: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            Logger.business(LogTags.TOKEN, "🔄 TOKEN-REFRESH: Starting improved Calendar token refresh")
            
            if (refreshToken.isNullOrBlank()) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Cannot refresh - no refresh token available")
                return@withContext Result.failure(TokenException.RefreshFailed("No refresh token available"))
            }
            
            // 🔧 OPTION 2 FIX: Clear the old access token (which we stored as refresh token)
            // This forces GoogleAuthUtil to fetch a fresh token from Google servers
            GoogleAuthUtil.clearToken(context, refreshToken)
            Logger.d(LogTags.TOKEN, "🧹 OPTION-2-FIX: Cleared cached token to force refresh")
            
            // 🔧 PHASE 1 FIX: Get user email with multi-source fallback
            val userEmail = getUserEmailFromAccounts()
            
            if (userEmail == null) {
                Logger.e(LogTags.TOKEN, "❌ PHASE-1: Cannot refresh - no user email from any source!")
                Logger.w(LogTags.TOKEN, "💡 PHASE-1: User needs to re-authenticate to restore Calendar access")
                return@withContext Result.failure(TokenException.RefreshFailed("No user account available - re-authentication required"))
            }
            
            Logger.business(LogTags.TOKEN, "📧 PHASE-1: Using email for refresh: $userEmail")
            
            val googleAccount = android.accounts.Account(userEmail, "com.google")
            
            // Get fresh access token with proper error handling
            val newAccessToken = try {
                GoogleAuthUtil.getToken(
                    context,
                    googleAccount,
                    "oauth2:${CalendarScopes.CALENDAR_READONLY}"
                )
            } catch (e: Exception) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: GoogleAuthUtil.getToken failed", e)
                
                val errorMessage = when {
                    e.message?.contains("NetworkError") == true -> "Network error during token refresh"
                    e.message?.contains("ServiceDisabled") == true -> "Google Calendar API service disabled"
                    e.message?.contains("UserRecoverableAuth") == true -> "User interaction required for token refresh"
                    e.message?.contains("Account not found") == true -> "Google account not found on device"
                    else -> "Unknown error during token refresh: ${e.localizedMessage}"
                }
                
                return@withContext Result.failure(TokenException.RefreshFailed(errorMessage))
            }
            
            if (newAccessToken.isEmpty()) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Empty token received from GoogleAuthUtil")
                return@withContext Result.failure(TokenException.RefreshFailed("Empty access token received"))
            }
            
            Logger.business(LogTags.TOKEN, "✅ TOKEN-REFRESH: New Calendar token obtained (${newAccessToken.take(20)}...)")
            
            val newExpiresAt = System.currentTimeMillis() + (3600L * 1000) // 1 hour
            
            // 🔧 OPTION 2 FIX: Store new token with itself as refresh token (Google-managed)
            val updateResult = tokenStorage.updateAccessToken(
                newAccessToken = newAccessToken,
                newExpiresAt = newExpiresAt
            )
            
            if (updateResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Failed to update stored Calendar token", updateResult.exceptionOrNull())
                return@withContext Result.failure(TokenException.RefreshFailed("Failed to update stored Calendar token"))
            }
            
            // 🔧 OPTION 1 FIX: Verify token was actually saved
            val verifyToken = tokenStorage.getCurrentToken()
            if (verifyToken?.accessToken != newAccessToken) {
                Logger.e(LogTags.TOKEN, "❌ OPTION-1-VERIFY: Token refresh succeeded but verification failed!")
                return@withContext Result.failure(TokenException.RefreshFailed("Token verification failed after refresh"))
            }
            
            Logger.business(LogTags.TOKEN, "✅ TOKEN-REFRESH: Calendar access token refreshed and verified successfully")
            Result.success(newAccessToken)
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Unexpected error during token refresh", e)
            Result.failure(TokenException.RefreshFailed("Unexpected error: ${e.localizedMessage}"))
        }
    }
    
    /**
     * Revokes Calendar API authorization and clears stored tokens.
     */
    suspend fun revokeCalendarAuthorization(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.d(LogTags.AUTH, "Revoking Calendar authorization")
            
            // Clear tokens from storage
            val clearResult = tokenStorage.clearToken()
            
            if (clearResult.isSuccess) {
                Logger.business(LogTags.AUTH, "Calendar authorization revoked successfully")
                Result.success(Unit)
            } else {
                Logger.e(LogTags.AUTH, "Failed to clear Calendar tokens: ${clearResult.exceptionOrNull()}")
                Result.failure(Exception("Failed to clear Calendar authorization"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "Error revoking Calendar authorization", e)
            Result.failure(e)
        }
    }
    
    /**
     * Gets current authorization status with details.
     */
    suspend fun getAuthorizationStatus(): AuthorizationStatus = withContext(Dispatchers.IO) {
        try {
            val currentToken = tokenStorage.getCurrentToken()
            
            when {
                currentToken == null -> AuthorizationStatus.NotAuthorized
                
                currentToken.isValid() -> AuthorizationStatus.Authorized(
                    remainingMinutes = currentToken.getRemainingLifetimeMinutes()
                )
                
                currentToken.canRefresh() -> AuthorizationStatus.ExpiredButRefreshable(
                    expiredMinutesAgo = -currentToken.getRemainingLifetimeMinutes()
                )
                
                else -> AuthorizationStatus.ExpiredNotRefreshable
            }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error getting authorization status", e)
            AuthorizationStatus.Error(e)
        }
    }
}

/**
 * Specific token-related exceptions for better error handling
 */
sealed class TokenException(message: String) : Exception(message) {
    class NoTokenAvailable(message: String) : TokenException(message)
    class AuthorizationExpired(message: String) : TokenException(message)
    class RefreshFailed(message: String) : TokenException(message)
}

/**
 * Authorization status information
 */
sealed class AuthorizationStatus {
    object NotAuthorized : AuthorizationStatus()
    data class Authorized(val remainingMinutes: Long) : AuthorizationStatus()
    data class ExpiredButRefreshable(val expiredMinutesAgo: Long) : AuthorizationStatus()
    object ExpiredNotRefreshable : AuthorizationStatus()
    data class Error(val exception: Throwable) : AuthorizationStatus()
}

/**
 * CRITICAL FIX: Enhanced AuthResult with Pending state and permissionIntent
 */
sealed class AuthResult {
    data class Success(val tokenData: TokenData) : AuthResult()
    data class Failure(
        val error: String,
        val permissionIntent: Intent? = null
    ) : AuthResult()
    data class Pending(val message: String) : AuthResult()
}
