package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager

import android.app.Activity
import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenProvider
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage.TokenRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simplified OAuth2 Token Manager
 * 
 * Fokussiert auf OAuth2-Logic, delegiert Storage an TokenRepository.
 * 
 * Verbesserungen gegenüber ModernOAuth2TokenManager:
 * - ✅ Single Responsibility: Nur OAuth2-Operations
 * - ✅ Dependency Injection: TokenRepository wird injiziert
 * - ✅ Keine Workarounds: Saubere Implementation
 * - ✅ Proper Exception Handling: CancellationException wird propagiert
 * - ✅ Token Rotation: Security-Feature integriert
 */
class OAuth2TokenManager(
    private val context: Context,
    private val tokenRepository: TokenRepository
) {
    
    companion object {
        const val REQUEST_CODE_CALENDAR_AUTHORIZATION = 1001
    }
    
    private var pendingAuthCallback: ((Boolean) -> Unit)? = null
    
    /**
     * Lädt gültiges Token, refresht automatisch wenn nötig
     */
    suspend fun getValidToken(): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            val currentToken = tokenRepository.get()
            
            when {
                // Kein Token vorhanden
                currentToken == null -> {
                    Logger.w(LogTags.TOKEN, "No token available - authorization required")
                    Result.failure(TokenException.NoTokenAvailable("Authorization required"))
                }
                
                // Token ist noch gültig
                currentToken.isValid() -> {
                    Logger.d(LogTags.TOKEN, "✅ Token is valid (${currentToken.getRemainingLifetimeMinutes()}min remaining)")
                    Result.success(currentToken)
                }
                
                // Token ist abgelaufen aber refresh möglich
                currentToken.canRefresh() -> {
                    Logger.i(LogTags.TOKEN, "🔄 Token expired, attempting refresh...")
                    refresh(currentToken)
                }
                
                // Token abgelaufen und refresh nicht möglich
                else -> {
                    Logger.w(LogTags.TOKEN, "❌ Token expired and cannot be refreshed")
                    Result.failure(TokenException.AuthorizationExpired("Re-authorization required"))
                }
            }
            
        } catch (e: CancellationException) {
            throw e  // ✅ KRITISCH: Propagieren!
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error getting valid token", e)
            Result.failure(e)
        }
    }
    
    /**
     * Autorisiert Calendar-Zugriff
     */
    suspend fun authorize(
        userEmail: String,
        activity: Activity? = null,
        onResult: ((Boolean) -> Unit)? = null
    ): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            Logger.i(LogTags.TOKEN, "🔐 Starting Calendar authorization for: $userEmail")
            
            val account = android.accounts.Account(userEmail, "com.google")
            val scope = "oauth2:${CalendarScopes.CALENDAR_READONLY}"
            
            val accessToken = try {
                GoogleAuthUtil.getToken(context, account, scope)
            } catch (e: UserRecoverableAuthException) {
                // User Permission required
                if (activity != null) {
                    Logger.i(LogTags.TOKEN, "🚀 Launching permission dialog...")
                    pendingAuthCallback = onResult
                    
                    withContext(Dispatchers.Main) {
                        activity.startActivityForResult(
                            e.intent,
                            REQUEST_CODE_CALENDAR_AUTHORIZATION
                        )
                    }
                    
                    return@withContext Result.failure(
                        TokenException.PendingAuthorization("Permission dialog launched")
                    )
                } else {
                    Logger.e(LogTags.TOKEN, "❌ No activity context for permission dialog")
                    return@withContext Result.failure(
                        TokenException.NoActivityContext("Activity required for authorization", e.intent)
                    )
                }
            }
            
            if (accessToken.isBlank()) {
                Logger.e(LogTags.TOKEN, "❌ Empty access token received")
                return@withContext Result.failure(TokenException.AuthorizationFailed("Empty token"))
            }
            
            Logger.i(LogTags.TOKEN, "✅ Access token obtained successfully")
            
            // Create TokenData
            val tokenData = TokenData.fromOAuthResponse(
                accessToken = accessToken,
                refreshToken = null,  // Google Play Services manages this
                expiresInSeconds = 3600,
                scope = CalendarScopes.CALENDAR_READONLY,
                googleAccountEmail = userEmail,
                tokenProvider = TokenProvider.GOOGLE_PLAY_SERVICES
            )
            
            // Save token
            val saveResult = tokenRepository.save(tokenData)
            if (saveResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ Failed to save token", saveResult.exceptionOrNull())
                return@withContext Result.failure(
                    TokenException.StorageFailed("Failed to save token")
                )
            }
            
            Logger.i(LogTags.TOKEN, "✅ Token saved successfully")
            onResult?.invoke(true)
            
            Result.success(tokenData)
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Authorization failed", e)
            onResult?.invoke(false)
            Result.failure(TokenException.AuthorizationFailed(e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Refresht Token mit Rotation-Support
     */
    suspend fun refresh(currentToken: TokenData): Result<TokenData> = withContext(Dispatchers.IO) {
        try {
            Logger.i(LogTags.TOKEN, "🔄 Starting token refresh...")
            
            // Validate rotation chain (Security Check)
            val storedToken = tokenRepository.get()
            if (storedToken != null && !currentToken.validateRotation(storedToken.previousRotationId)) {
                Logger.e(LogTags.TOKEN, "⚠️ Token rotation chain broken - possible theft!")
                tokenRepository.clear()
                return@withContext Result.failure(
                    TokenException.SecurityViolation("Token rotation invalid")
                )
            }
            
            // Refresh based on provider
            val newAccessToken = when (currentToken.tokenProvider) {
                TokenProvider.GOOGLE_PLAY_SERVICES -> refreshViaGooglePlayServices(currentToken)
                TokenProvider.OAUTH2_STANDARD -> refreshViaOAuth2Standard(currentToken)
            }
            
            // Create rotated token
            val rotatedToken = currentToken.rotate(
                newAccessToken = newAccessToken,
                newExpiresAt = System.currentTimeMillis() + 3600000
            )
            
            // Save rotated token
            val saveResult = tokenRepository.save(rotatedToken)
            if (saveResult.isFailure) {
                Logger.e(LogTags.TOKEN, "❌ Failed to save rotated token", saveResult.exceptionOrNull())
                return@withContext Result.failure(
                    TokenException.StorageFailed("Failed to save token")
                )
            }
            
            Logger.i(LogTags.TOKEN, "✅ Token refreshed and rotated (rotation #${rotatedToken.rotationCount})")
            
            Result.success(rotatedToken)
            
        } catch (e: CancellationException) {
            throw e  // ✅ KRITISCH: Propagieren!
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Token refresh failed", e)
            Result.failure(TokenException.RefreshFailed(e.message ?: "Unknown error"))
        }
    }
    
    /**
     * Refresh via Google Play Services
     */
    private suspend fun refreshViaGooglePlayServices(token: TokenData): String {
        require(token.tokenProvider == TokenProvider.GOOGLE_PLAY_SERVICES) {
            "Token provider must be GOOGLE_PLAY_SERVICES"
        }
        require(!token.googleAccountEmail.isNullOrBlank()) {
            "googleAccountEmail is required for Google Play Services refresh"
        }
        
        try {
            // Clear old token
            GoogleAuthUtil.clearToken(context, token.accessToken)
            Logger.d(LogTags.TOKEN, "Old token cleared")
            
            // Get fresh token
            val account = android.accounts.Account(token.googleAccountEmail, "com.google")
            val scope = "oauth2:${CalendarScopes.CALENDAR_READONLY}"
            
            val newToken = GoogleAuthUtil.getToken(context, account, scope)
            
            if (newToken.isBlank()) {
                throw TokenException.RefreshFailed("Empty token received")
            }
            
            return newToken
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Google Play Services refresh failed", e)
            throw TokenException.RefreshFailed("Google refresh failed: ${e.message}")
        }
    }
    
    /**
     * Refresh via standard OAuth2
     */
    private suspend fun refreshViaOAuth2Standard(token: TokenData): String {
        require(token.tokenProvider == TokenProvider.OAUTH2_STANDARD) {
            "Token provider must be OAUTH2_STANDARD"
        }
        require(!token.refreshToken.isNullOrBlank()) {
            "refreshToken is required for OAuth2 standard refresh"
        }
        
        // TODO: Implement if needed
        throw NotImplementedError("OAuth2 standard refresh not yet implemented")
    }
    
    /**
     * Widerruft Token
     */
    suspend fun revoke(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            Logger.i(LogTags.TOKEN, "Revoking token...")
            
            tokenRepository.clear()
                .onSuccess {
                    Logger.i(LogTags.TOKEN, "✅ Token revoked successfully")
                }
                .onFailure { e ->
                    Logger.e(LogTags.TOKEN, "❌ Failed to revoke token", e)
                }
            
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error revoking token", e)
            Result.failure(e)
        }
    }
    
    /**
     * Handles activity result from permission dialog
     */
    suspend fun handlePermissionResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQUEST_CODE_CALENDAR_AUTHORIZATION) {
            return false
        }
        
        val success = resultCode == Activity.RESULT_OK
        Logger.i(LogTags.TOKEN, "Permission result: ${if (success) "GRANTED" else "DENIED"}")
        
        pendingAuthCallback?.invoke(success)
        pendingAuthCallback = null
        
        return success
    }
}
