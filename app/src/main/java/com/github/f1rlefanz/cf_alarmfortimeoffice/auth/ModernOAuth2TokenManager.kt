package com.github.f1rlefanz.cf_alarmfortimeoffice.auth

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenStorageRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Modern OAuth2TokenManager using Google's recommended 2024/2025 approach:
 * - Works with Credential Manager authentication
 * - Uses Account-based token management for Google APIs
 * - Replaces deprecated GoogleSignInClient
 * 
 * ARCHITECTURE:
 * - Single Responsibility: OAuth2 token management for Google APIs
 * - Loose Coupling: Uses TokenStorageRepository for persistence
 * - Modern APIs: Compatible with Credential Manager authentication flow
 */
class ModernOAuth2TokenManager(
    private val context: Context,
    private val tokenStorage: TokenStorageRepository
) {
    
    /**
     * Gets valid access token for Google Calendar API.
     * This is the main method for API access - automatically handles refresh.
     * 
     * MODERNIZED: Supports both legacy tokens and modern GoogleAuthUtil authentication
     * CRITICAL DIAGNOSTIC: Enhanced logging for token troubleshooting
     * CRITICAL FIX: Improved token refresh logic for better reliability
     */
    suspend fun getValidCalendarToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val currentToken = tokenStorage.getCurrentToken()
            
            when {
                currentToken == null -> {
                    Logger.w(LogTags.TOKEN, "❌ TOKEN-DIAGNOSTIC: No Calendar token available - authorization required")
                    Logger.d(LogTags.TOKEN, "💡 TOKEN-DIAGNOSTIC: User needs to complete Calendar authorization flow")
                    Result.failure(TokenException.NoTokenAvailable("No Calendar API authorization - please authorize Calendar access"))
                }
                
                currentToken.isValid() -> {
                    Logger.business(LogTags.TOKEN, "✅ TOKEN-DIAGNOSTIC: Using valid Calendar access token (${currentToken.getRemainingLifetimeMinutes()}min remaining)")
                    Result.success(currentToken.accessToken)
                }
                
                currentToken.canRefresh() && !currentToken.accessToken.isBlank() -> {
                    Logger.business(LogTags.TOKEN, "🔄 TOKEN-DIAGNOSTIC: Calendar token expired (${-currentToken.getRemainingLifetimeMinutes()}min ago), attempting refresh")
                    
                    // CRITICAL FIX: Improved token refresh with better error handling
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
     * Authorize Calendar API access for an authenticated user.
     * Uses GoogleAuthUtil with GET_ACCOUNTS permission to get Calendar API token.
     * 
     * This is the working solution that doesn't require a backend server.
     */
    suspend fun authorizeCalendarAccess(userEmail: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            Logger.business(LogTags.OAUTH, "🔐 AUTH: Authorizing Calendar access for user: $userEmail")
            
            // Create Google Account for token request
            val googleAccount = android.accounts.Account(userEmail, "com.google")
            
            // Request Calendar API access token using GoogleAuthUtil
            val calendarToken = try {
                GoogleAuthUtil.getToken(
                    context,
                    googleAccount,
                    "oauth2:${CalendarScopes.CALENDAR_READONLY}"
                )
            } catch (e: Exception) {
                Logger.e(LogTags.OAUTH, "❌ Failed to get Calendar token", e)
                return@withContext AuthResult.Failure("Calendar authorization failed: ${e.localizedMessage}")
            }
            
            if (calendarToken.isNullOrEmpty()) {
                Logger.e(LogTags.OAUTH, "❌ Empty Calendar API token received")
                return@withContext AuthResult.Failure("Failed to obtain Calendar API token")
            }
            
            Logger.business(LogTags.OAUTH, "✅ Successfully obtained Calendar API token")
            
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
                Logger.e(LogTags.TOKEN, "❌ Failed to store Calendar token: ${storeResult.exceptionOrNull()}")
                return@withContext AuthResult.Failure("Failed to store Calendar authorization")
            }
            
            Logger.business(LogTags.TOKEN, "✅ Calendar token stored successfully")
            AuthResult.Success(tokenData)
            
        } catch (e: Exception) {
            Logger.e(LogTags.OAUTH, "❌ Calendar authorization failed", e)
            AuthResult.Failure("Calendar authorization failed: ${e.localizedMessage}")
        }
    }
    
    /**
     * CRITICAL FIX: Improved Calendar token refresh with enhanced error handling
     * Replaces the legacy refresh method with better diagnostics and fallback strategies
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
     * Gets user email from SharedPreferences (where AuthViewModel stores it)
     * Falls back to Android Accounts if available (requires GET_ACCOUNTS permission)
     */
    private fun getUserEmailFromAccounts(): String? {
        return try {
            Logger.d(LogTags.AUTH, "🔍 EMAIL-LOOKUP: Retrieving user email...")
            
            // First try: Read from SharedPreferences where AuthViewModel stores it
            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            val email = prefs.getString("current_user_email", null)
            
            if (email != null) {
                Logger.business(LogTags.AUTH, "✅ EMAIL-FOUND: User email retrieved from SharedPreferences: $email")
                return email
            }
            
            Logger.w(LogTags.AUTH, "⚠️ EMAIL-MISSING: No user email in SharedPreferences, trying Android Accounts")
            
            // Fallback: Try Android Accounts (requires GET_ACCOUNTS permission)
            try {
                val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
                val accounts = accountManager.getAccountsByType("com.google")
                
                if (accounts.isNotEmpty()) {
                    val fallbackEmail = accounts.first().name
                    Logger.business(LogTags.AUTH, "✅ EMAIL-FALLBACK: Found email via Android Accounts: $fallbackEmail")
                    
                    // Save to SharedPreferences for next time
                    prefs.edit().putString("current_user_email", fallbackEmail).apply()
                    return fallbackEmail
                }
            } catch (e: SecurityException) {
                Logger.w(LogTags.AUTH, "No GET_ACCOUNTS permission, cannot use fallback")
            }
            
            Logger.e(LogTags.AUTH, "❌ EMAIL-ERROR: No user email found - user needs to sign in")
            null
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ EMAIL-EXCEPTION: Error getting user email", e)
            null
        }
    }
    
    /**
     * Checks if user has authorized Calendar API access.
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
 * Result of authorization operations
 */
sealed class AuthResult {
    data class Success(val tokenData: TokenData) : AuthResult()
    data class Failure(val error: String) : AuthResult()
}

/**
 * User information from authentication
 */
data class UserInfo(
    val email: String,
    val displayName: String,
    val id: String
)
