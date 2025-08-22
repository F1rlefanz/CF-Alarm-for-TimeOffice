package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.ModernOAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.AuthorizationStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenStorageRepository
import kotlinx.coroutines.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * UseCase for automatic token refresh and validation.
 * 
 * MODERNIZED + OPTIMIZED: Uses ModernOAuth2TokenManager for 2024/2025 Google APIs approach.
 * - Thread-safe implementation with proper cancellation support
 * - Uses structured concurrency
 * - Compatible with Credential Manager authentication
 * - Prevents Race Conditions through proper scope management
 * - Cancellation-aware for lifecycle safety
 * - PERFORMANCE: Smart caching to reduce redundant token validations
 */
class TokenRefreshUseCase(
    private val modernOAuth2TokenManager: ModernOAuth2TokenManager,
    private val tokenStorage: TokenStorageRepository
) {
    
    // PERFORMANCE: Smart token validation caching
    @Volatile
    private var lastTokenValidation: String? = null
    @Volatile
    private var lastValidationTime: Long = 0L
    @Volatile
    private var validationInProgress: Boolean = false
    
    private companion object {
        const val TOKEN_VALIDATION_CACHE_MS = 30000L // 30 seconds cache for token validation
        const val MAX_VALIDATION_WAIT_MS = 200L      // Max wait for concurrent validations
    }
    
    /**
     * Ensures a valid access token is available for API calls.
     * Automatically refreshes if needed.
     * 
     * MODERNIZED + OPTIMIZED: Uses ModernOAuth2TokenManager with smart caching
     * HYBRID-SUPPORT: Handles both legacy tokens and modern Credential Manager tokens
     * CRITICAL FIX: Enhanced error handling and token validation
     */
    suspend fun ensureValidToken(): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Check for cancellation before proceeding
            ensureActive()
            
            // PERFORMANCE: Check validation cache first
            val currentTime = System.currentTimeMillis()
            lastTokenValidation?.let { cachedToken ->
                val cacheAge = currentTime - lastValidationTime
                if (cacheAge < TOKEN_VALIDATION_CACHE_MS) {
                    Logger.d(LogTags.TOKEN, "✅ TOKEN-DIAGNOSTIC: Using valid Calendar access token (${(TOKEN_VALIDATION_CACHE_MS - cacheAge) / 1000}s cache remaining)")
                    return@withContext Result.success(cachedToken)
                } else {
                    Logger.d(LogTags.TOKEN, "⏰ TOKEN-CACHE: Cache expired (${cacheAge / 1000}s old), validating token")
                }
            }
            
            // PERFORMANCE: Handle concurrent validation attempts
            if (validationInProgress) {
                Logger.d(LogTags.TOKEN, "🔄 TOKEN-WAIT: Validation in progress, waiting smartly...")
                
                val startWait = System.currentTimeMillis()
                while (validationInProgress && (System.currentTimeMillis() - startWait) < MAX_VALIDATION_WAIT_MS) {
                    delay(25)
                }
                
                // Check if concurrent validation completed successfully
                lastTokenValidation?.let { freshToken ->
                    val cacheAge = System.currentTimeMillis() - lastValidationTime
                    if (cacheAge < TOKEN_VALIDATION_CACHE_MS) {
                        Logger.d(LogTags.TOKEN, "✅ TOKEN-CONCURRENT-SUCCESS: Using fresh token from concurrent validation")
                        return@withContext Result.success(freshToken)
                    }
                }
            }
            
            validationInProgress = true
            
            try {
                Logger.business(LogTags.TOKEN, "🔍 TOKEN-VALIDATION: Starting comprehensive token validation")
                
                // Use modern token manager directly with enhanced error handling
                val result = modernOAuth2TokenManager.getValidCalendarToken()
                
                // PERFORMANCE: Cache successful token validation with enhanced diagnostics
                result.onSuccess { token ->
                    lastTokenValidation = token
                    lastValidationTime = currentTime
                    
                    when {
                        token == "credential_manager_auth_pending" -> {
                            Logger.d(LogTags.TOKEN, "🔐 MODERN-AUTH: Using Credential Manager authentication flow")
                            // This is valid - the token will trigger hybrid authentication in CalendarRepository
                        }
                        token.startsWith("ya29.") -> {
                            Logger.business(LogTags.TOKEN, "✅ TOKEN-OK: Real Google OAuth2 access token validated (ya29.)")
                        }
                        token.length < 10 -> {
                            Logger.w(LogTags.TOKEN, "⚠️ TOKEN-SUSPICIOUS: Token seems too short (${token.length} chars)")
                        }
                        else -> {
                            Logger.business(LogTags.TOKEN, "✅ TOKEN-VALID: Using validated Calendar access token (${token.take(20)}...)")
                        }
                    }
                }.onFailure { error ->
                    // Clear cache on failure
                    lastTokenValidation = null
                    lastValidationTime = 0L
                    
                    // Enhanced error handling based on error type
                    when (error) {
                        is TokenException.NoTokenAvailable -> {
                            Logger.e(LogTags.TOKEN, "❌ AUTH-REQUIRED: No Calendar token - user needs to authorize Calendar access")
                        }
                        is TokenException.AuthorizationExpired -> {
                            Logger.e(LogTags.TOKEN, "❌ REAUTH-REQUIRED: Calendar authorization expired - re-authorization needed")
                        }
                        else -> {
                            Logger.e(LogTags.TOKEN, "❌ TOKEN-ERROR: Calendar token validation failed", error)
                        }
                    }
                }
                
                result
            } finally {
                validationInProgress = false
            }
            
        } catch (e: CancellationException) {
            Logger.d(LogTags.TOKEN, "Operation cancelled")
            throw e // Re-throw cancellation
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error ensuring valid token", e)
            
            // Enhanced error classification
            val tokenException = when {
                e.message?.contains("re-authentication") == true -> 
                    TokenException.AuthorizationExpired("Re-authentication required")
                e.message?.contains("No user account") == true -> 
                    TokenException.NoTokenAvailable
                e.message?.contains("NetworkError") == true -> 
                    TokenException.NetworkError("Network error occurred")
                else -> 
                    TokenException.UnknownError(e)
            }
            
            Result.failure(tokenException)
        }
    }
    
    /**
     * Proactively refreshes token if it's expiring soon.
     * Useful for background refresh operations.
     * 
     * MODERNIZED: Simplified approach using ModernOAuth2TokenManager
     */
    suspend fun refreshIfExpiringSoon(bufferMinutes: Long = 15): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val currentToken = tokenStorage.getCurrentToken()
            
            if (currentToken == null) {
                Logger.d(LogTags.TOKEN, "No token to refresh")
                return@withContext Result.success(false)
            }
            
            if (!currentToken.isExpiredOrExpiringSoon(bufferMinutes)) {
                Logger.d(LogTags.TOKEN, "Token not expiring soon, no refresh needed")
                return@withContext Result.success(false)
            }
            
            Logger.d(LogTags.TOKEN, "Token expiring in ${currentToken.getRemainingLifetimeMinutes()} minutes, refreshing")
            
            // Use modern token manager for refresh
            val refreshResult = modernOAuth2TokenManager.getValidCalendarToken()
            
            if (refreshResult.isSuccess) {
                Logger.business(LogTags.TOKEN, "Proactive token refresh successful")
                Result.success(true)
            } else {
                Logger.e(LogTags.TOKEN, "Proactive token refresh failed: ${refreshResult.exceptionOrNull()}")
                Result.failure(TokenException.RefreshFailed(refreshResult.exceptionOrNull()))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error in proactive token refresh", e)
            Result.failure(TokenException.UnknownError(e))
        }
    }
    
    /**
     * Validates current token and returns detailed status.
     * Useful for debugging and status checks.
     * 
     * MODERNIZED: Uses ModernOAuth2TokenManager authorization status
     */
    suspend fun getTokenStatus(): TokenStatus = withContext(Dispatchers.IO) {
        try {
            val authStatus = modernOAuth2TokenManager.getAuthorizationStatus()
            
            return@withContext when (authStatus) {
                is AuthorizationStatus.NotAuthorized -> {
                    TokenStatus.NoToken
                }
                is AuthorizationStatus.Authorized -> {
                    TokenStatus.Valid(
                        remainingMinutes = authStatus.remainingMinutes
                        // Modern system handles refresh automatically
                    )
                }
                is AuthorizationStatus.ExpiredButRefreshable -> {
                    TokenStatus.ExpiredButRefreshable
                }
                is AuthorizationStatus.ExpiredNotRefreshable -> {
                    TokenStatus.ExpiredNotRefreshable
                }
                is AuthorizationStatus.Error -> {
                    TokenStatus.Error(authStatus.exception)
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error getting token status", e)
            return@withContext TokenStatus.Error(e)
        }
    }
    
    /**
     * Forces a token refresh regardless of expiration status.
     * Useful for testing or when token is suspected to be invalid.
     * 
     * MODERNIZED: Uses ModernOAuth2TokenManager for forced refresh
     */
    suspend fun forceRefresh(): Result<String> = withContext(Dispatchers.IO) {
        try {
            Logger.d(LogTags.TOKEN, "Forcing token refresh")
            
            // Modern token manager handles the refresh logic
            modernOAuth2TokenManager.getValidCalendarToken()
                .onSuccess { Logger.business(LogTags.TOKEN, "Force refresh successful") }
                .onFailure { Logger.e(LogTags.TOKEN, "Force refresh failed: ${it.message}") }
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error in force refresh", e)
            Result.failure(TokenException.UnknownError(e))
        }
    }
    
    /**
     * Clears invalid tokens from storage.
     * Useful for cleanup when tokens are permanently invalid.
     * 
     * MODERNIZED: Uses ModernOAuth2TokenManager for token management
     */
    suspend fun clearInvalidTokens(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.d(LogTags.TOKEN, "Clearing invalid tokens")
            modernOAuth2TokenManager.revokeCalendarAuthorization()
                .onSuccess { Logger.d(LogTags.TOKEN, "Invalid tokens cleared") }
                .onFailure { Logger.e(LogTags.TOKEN, "Failed to clear invalid tokens: ${it.message}") }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error clearing invalid tokens", e)
            Result.failure(TokenException.UnknownError(e))
        }
    }
}

/**
 * Token status information for debugging and UI updates
 */
sealed class TokenStatus {
    object NoToken : TokenStatus()
    data class Valid(val remainingMinutes: Long) : TokenStatus()
    object ExpiredButRefreshable : TokenStatus()
    object ExpiredNotRefreshable : TokenStatus()
    data class Error(val exception: Throwable) : TokenStatus()
}

/**
 * Typed exceptions for token operations
 */
sealed class TokenException : Exception() {
    object NoTokenAvailable : TokenException() {
        override val message = "No authentication token available"
    }
    
    data class AuthorizationExpired(val details: String = "Authorization expired") : TokenException()
    
    data class NetworkError(val details: String = "Network error occurred") : TokenException()
    
    object RefreshNotPossible : TokenException() {
        override val message = "Token cannot be refreshed - re-authentication required"
    }
    
    data class RefreshFailed(val error: Throwable?) : TokenException() {
        override val message = "Token refresh failed: ${error?.message}"
        override val cause = error
    }
    
    data class UnknownError(val error: Throwable) : TokenException() {
        override val message = "Unknown token error: ${error.message}"
        override val cause = error
    }
}
