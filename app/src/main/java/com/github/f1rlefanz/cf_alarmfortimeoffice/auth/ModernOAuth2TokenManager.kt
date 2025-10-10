package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
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
            val storageTest = tokenStorage.getCurrentToken()
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
            
            val currentToken = tokenStorage.getCurrentToken()
            
            when {
                currentToken == null -> {
                    Logger.w(LogTags.TOKEN, "❌ TOKEN-DIAGNOSTIC: No Calendar token available - authorization required")
                    Logger.d(LogTags.TOKEN, "💡 TOKEN-DIAGNOSTIC: User needs to complete Calendar authorization flow")
                    
                    // Check if user is signed in but token missing
                    if (lastUserEmail != null) {
                        Logger.business(LogTags.TOKEN, "🔄 TOKEN-RECOVERY: User signed in but no Calendar token - user interaction required")
                    }
                    
                    Result.failure(TokenException.NoTokenAvailable("No Calendar API authorization - please authorize Calendar access"))
                }
                
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
            
            if (calendarToken.isNullOrEmpty()) {
                Logger.e(LogTags.OAUTH, "❌ AUTH-FIXED: Empty Calendar API token received")
                return@withContext AuthResult.Failure("Failed to obtain Calendar API token")
            }
            
            Logger.business(LogTags.OAUTH, "✅ AUTH-FIXED: Successfully obtained Calendar API token")
            
            // Create token data with real token
            val tokenData = TokenData.fromOAuthResponse(
                accessToken = calendarToken,
                refreshToken = "google_managed", 
                expiresInSeconds = 3600L, // 1 hour
                scope = CalendarScopes.CALENDAR_READONLY
            )
            
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
    suspend fun handlePermissionResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
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
     * ✅ MODERNIZED: Get user email from SharedPreferences only
     * 
     * NO FALLBACK TO AccountManager: Since we removed GET_ACCOUNTS permission,
     * we rely solely on SharedPreferences where AuthViewModel stores the email
     * after successful OAuth2 sign-in.
     * 
     * If email is not in SharedPreferences, user must sign in again.
     */
    private fun getUserEmailFromAccounts(): String? {
        return try {
            Logger.d(LogTags.AUTH, "🔍 EMAIL-LOOKUP: Retrieving user email from SharedPreferences")
            
            // Read from SharedPreferences where AuthViewModel stores it after OAuth2 sign-in
            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            val email = prefs.getString("current_user_email", null)
            val isSignedIn = prefs.getBoolean("user_signed_in", false)
            
            if (email != null && email.contains("@") && isSignedIn) {
                Logger.business(LogTags.AUTH, "✅ EMAIL-FOUND: User email retrieved from SharedPreferences: $email")
                return email
            }
            
            // ✅ NO FALLBACK: User must sign in again if email not in SharedPreferences
            Logger.w(LogTags.AUTH, "⚠️ EMAIL-MISSING: No valid user email in SharedPreferences - user needs to sign in")
            Logger.d(LogTags.AUTH, "💡 EMAIL-INFO: email=$email, signedIn=$isSignedIn")
            null
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ EMAIL-EXCEPTION: Error getting user email from SharedPreferences", e)
            null
        }
    }
    
    /**
     * Helper method to save user email to SharedPreferences
     */
    private fun saveUserEmailToPreferences(email: String) {
        try {
            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            prefs.edit().putString("current_user_email", email).apply()
            Logger.d(LogTags.AUTH, "✅ EMAIL-SAVE: User email saved to SharedPreferences: $email")
        } catch (e: Exception) {
            Logger.w(LogTags.AUTH, "Failed to save user email to SharedPreferences", e)
        }
    }
    
    /**
     * Improved Calendar token refresh
     */
    private suspend fun refreshCalendarTokenImproved(refreshToken: String?): Result<String> = withContext(Dispatchers.IO) {
        try {
            Logger.business(LogTags.TOKEN, "🔄 TOKEN-REFRESH: Starting improved Calendar token refresh")
            
            if (refreshToken.isNullOrBlank()) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Cannot refresh - no refresh token available")
                return@withContext Result.failure(TokenException.RefreshFailed("No refresh token available"))
            }
            
            // Clear any cached tokens to force fresh token request
            GoogleAuthUtil.clearToken(context, refreshToken)
            
            // Get current user account (we need this for refresh)
            val userEmail = getUserEmailFromAccounts()
            
            if (userEmail == null) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Cannot refresh Calendar token - no user account found")
                return@withContext Result.failure(TokenException.RefreshFailed("No user account available for token refresh"))
            }
            
            Logger.d(LogTags.TOKEN, "📧 TOKEN-REFRESH: Using user account: $userEmail")
            
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
            
            if (newAccessToken.isNullOrEmpty()) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Empty token received from GoogleAuthUtil")
                return@withContext Result.failure(TokenException.RefreshFailed("Empty access token received"))
            }
            
            Logger.business(LogTags.TOKEN, "✅ TOKEN-REFRESH: New Calendar token obtained (${newAccessToken.take(20)}...)")
            
            val newExpiresAt = System.currentTimeMillis() + (3600L * 1000) // 1 hour
            
            // Update stored token
            val updateResult = tokenStorage.updateAccessToken(
                newAccessToken = newAccessToken,
                newExpiresAt = newExpiresAt
            )
            
            if (updateResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ TOKEN-REFRESH: Failed to update stored Calendar token", updateResult.exceptionOrNull())
                return@withContext Result.failure(TokenException.RefreshFailed("Failed to update stored Calendar token"))
            }
            
            Logger.business(LogTags.TOKEN, "✅ TOKEN-REFRESH: Calendar access token refreshed successfully")
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
    class NetworkError(message: String) : TokenException(message)
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

/**
 * User information from authentication
 */
data class UserInfo(
    val email: String,
    val displayName: String,
    val id: String
)
