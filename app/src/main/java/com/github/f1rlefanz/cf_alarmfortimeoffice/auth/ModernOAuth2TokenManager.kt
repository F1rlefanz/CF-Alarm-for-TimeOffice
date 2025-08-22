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
     * Use this after successful Credential Manager sign-in.
     * 
     * MODERNIZED: Uses GoogleAuthUtil to get actual Calendar API token
     * CRITICAL DIAGNOSTIC: Enhanced error reporting and token validation
     */
    suspend fun authorizeCalendarAccess(userEmail: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            Logger.business(LogTags.OAUTH, "🔐 MODERN-AUTH: Authorizing Calendar access for user: $userEmail")
            
            // CRITICAL FIX: Get a real Calendar API token instead of placeholder
            Logger.business(LogTags.OAUTH, "🔗 MODERN-AUTH: Requesting Calendar API token from Google for authenticated user")
            
            // Create Google Account for token request
            val googleAccount = android.accounts.Account(userEmail, "com.google")
            
            // Request actual Calendar API access token
            val calendarToken = GoogleAuthUtil.getToken(
                context,
                googleAccount,
                "oauth2:${CalendarScopes.CALENDAR_READONLY}"
            )
            
            if (calendarToken.isNullOrEmpty()) {
                Logger.e(LogTags.OAUTH, "❌ MODERN-AUTH: Empty Calendar API token received from GoogleAuthUtil")
                return@withContext AuthResult.Failure("Failed to obtain Calendar API token")
            }
            
            Logger.business(LogTags.OAUTH, "✅ MODERN-AUTH: Successfully obtained Calendar API token (${calendarToken.take(20)}...)")
            Logger.d(LogTags.OAUTH, "📊 TOKEN-INFO: Token length=${calendarToken.length}, scope=${CalendarScopes.CALENDAR_READONLY}")
            
            // Calculate expiration (Google tokens typically valid for 1 hour)
            val expiresInSeconds = 3600L // 1 hour
            
            // Create token data with real token
            val tokenData = TokenData.fromOAuthResponse(
                accessToken = calendarToken,
                refreshToken = "google_managed_credentials", 
                expiresInSeconds = expiresInSeconds,
                scope = CalendarScopes.CALENDAR_READONLY
            )
            
            // Store token
            val storeResult = tokenStorage.saveToken(tokenData)
            if (storeResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ MODERN-AUTH: Failed to store Calendar token: ${storeResult.exceptionOrNull()}")
                return@withContext AuthResult.Failure("Failed to store Calendar authorization")
            }
            
            Logger.business(LogTags.TOKEN, "✅ MODERN-AUTH: Calendar token stored successfully")
            
            // Create user info
            val userInfo = UserInfo(
                email = userEmail,
                displayName = "", // We don't have display name from email alone
                id = userEmail
            )
            
            Logger.business(LogTags.OAUTH, "✅ MODERN-AUTH: Calendar authorization completed successfully for $userEmail")
            Logger.business(LogTags.OAUTH, "📊 READY: Calendar API calls will now use real OAuth2 token")
            
            AuthResult.Success(tokenData)
            
        } catch (e: Exception) {
            Logger.e(LogTags.OAUTH, "❌ MODERN-AUTH: Calendar authorization failed for $userEmail", e)
            
            // Enhanced error reporting
            val errorMessage = when {
                e.message?.contains("NetworkError") == true -> "Calendar authorization failed: No internet connection"
                e.message?.contains("ServiceDisabled") == true -> "Calendar authorization failed: Google Calendar API is disabled"
                e.message?.contains("UserRecoverableAuth") == true -> "Calendar authorization failed: User interaction required"
                e.message?.contains("GoogleAuthException") == true -> "Calendar authorization failed: Google authentication error"
                e.message?.contains("Account not found") == true -> "Calendar authorization failed: Google account not found on device"
                else -> "Calendar authorization failed: ${e.localizedMessage}"
            }
            
            Logger.e(LogTags.OAUTH, "💡 ERROR-HELP: $errorMessage")
            AuthResult.Failure(errorMessage)
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
     * CRITICAL FIX: Enhanced user email retrieval with better diagnostics and fallbacks
     * Gets user email from SharedPreferences (consistent with AuthViewModel) with Android Accounts fallback
     */
    private fun getUserEmailFromAccounts(): String? {
        return try {
            Logger.d(LogTags.AUTH, "🔍 EMAIL-LOOKUP: Searching for user email...")
            
            // CRITICAL FIX: Read from SharedPreferences where AuthViewModel stores it
            val prefs = context.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            val email = prefs.getString("current_user_email", null)
            
            if (email != null) {
                Logger.business(LogTags.AUTH, "✅ EMAIL-FOUND: User email retrieved from SharedPreferences: $email")
                return email
            }
            
            Logger.w(LogTags.AUTH, "⚠️ EMAIL-MISSING: No user email found in SharedPreferences, trying Android Accounts fallback")
            
            // FALLBACK: Try Android Accounts system if SharedPreferences is empty
            val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as android.accounts.AccountManager
            val accounts = accountManager.getAccountsByType("com.google")
            
            Logger.d(LogTags.AUTH, "📱 ANDROID-ACCOUNTS: Found ${accounts.size} Google accounts on device")
            
            if (accounts.isEmpty()) {
                Logger.e(LogTags.AUTH, "❌ NO-ACCOUNTS: No Google accounts found on device")
                Logger.e(LogTags.AUTH, "💡 ACCOUNT-HELP: User needs to add Google account to device in Settings")
                return null
            }
            
            val fallbackEmail = accounts.firstOrNull()?.name
            
            if (fallbackEmail != null) {
                Logger.business(LogTags.AUTH, "✅ EMAIL-FALLBACK: User email retrieved from Android Accounts: $fallbackEmail")
                
                // Save to SharedPreferences for future use
                prefs.edit().putString("current_user_email", fallbackEmail).apply()
                Logger.d(LogTags.AUTH, "💾 EMAIL-SAVED: Email saved to SharedPreferences for future use")
                
                return fallbackEmail
            } else {
                Logger.e(LogTags.AUTH, "❌ EMAIL-ERROR: Found Google accounts but no email addresses")
                return null
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.AUTH, "❌ EMAIL-EXCEPTION: Error getting user email", e)
            
            // Enhanced error reporting
            when {
                e.message?.contains("SecurityException") == true -> {
                    Logger.e(LogTags.AUTH, "💡 PERMISSION-HELP: App doesn't have permission to access accounts")
                }
                e.message?.contains("AccountManager") == true -> {
                    Logger.e(LogTags.AUTH, "💡 ACCOUNT-HELP: AccountManager service not available")
                }
            }
            
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
