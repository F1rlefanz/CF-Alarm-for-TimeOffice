package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import android.app.Activity
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * UseCase für alle Authentication-bezogenen Operationen - implementiert IAuthUseCase
 * 
 * ✅ PHASE 4 MODERNIZED (2025):
 * - Verwendet OAuth2TokenManager statt deprecated ModernOAuth2TokenManager
 * - Smart Retry Logic mit TokenRefreshStrategy (via OAuth2TokenManager)
 * - Token Rotation Support
 * - Improved Exception Handling mit TokenException hierarchy
 * 
 * REFACTORED:
 * ✅ Implementiert IAuthUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interface statt konkrete Implementierung
 * ✅ Kapselt Business Logic von Infrastructure
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Clean Architecture Compliance
 * ✅ MODERN: Integriert OAuth2TokenManager für Calendar-Autorisierung
 * ✅ FIXED: Added Activity-based authorization method for permission flow
 * 
 * AUTHENTICATION FLOW 2024/2025:
 * 1. Credential Manager für Benutzer-Authentifizierung (wer bist du?)
 * 2. OAuth2TokenManager für API-Autorisierung (was darfst du?)
 */
class AuthUseCase(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val oauth2TokenManager: OAuth2TokenManager? = null
) : IAuthUseCase {
    
    override val authData: Flow<AuthData> = authDataStoreRepository.authData
    
    override suspend fun updateAuthData(authData: AuthData): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.updateAuthData") {
            authDataStoreRepository.updateAuthData(authData).getOrThrow()
            Logger.business(LogTags.AUTH, "✅ AUTH-UPDATE: Auth data updated successfully for ${authData.email}")
            
            // Note: Calendar authorization is now handled by AuthViewModel.requestCalendarAuthorization()
            // to prevent duplicate authorization attempts
        }
    }
    
    /**
     * MODERN: Requests Calendar API authorization for signed-in user
     * 
     * @param userEmail Optional email address (uses current user if null)
     * @return Result with Boolean (true if authorized) or error
     */
    override suspend fun requestCalendarAuthorization(userEmail: String?): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.requestCalendarAuthorization") {
            val emailToUse = userEmail ?: run {
                val currentAuth = authDataStoreRepository.getCurrentAuthData().getOrNull()
                currentAuth?.email ?: throw Exception("No user email available for Calendar authorization")
            }
            
            oauth2TokenManager?.let { tokenManager ->
                Logger.business(LogTags.AUTH, "🔐 MODERN-TOKEN: Requesting Calendar authorization for user: $emailToUse")
                
                val calendarAuthResult = tokenManager.authorize(emailToUse)
                if (calendarAuthResult.isSuccess) {
                    val tokenData = calendarAuthResult.getOrThrow()
                    Logger.business(LogTags.AUTH, "✅ MODERN-TOKEN: Calendar authorization successful - real OAuth2 token obtained")
                    Logger.d(LogTags.AUTH, "📊 Token details: accessToken=${tokenData.accessToken.take(20)}..., expires=${tokenData.getRemainingLifetimeMinutes()}min")
                    true
                } else {
                    val error = calendarAuthResult.exceptionOrNull()
                    when (error) {
                        is TokenException.PendingAuthorization -> {
                            Logger.business(LogTags.AUTH, "⏳ MODERN-TOKEN: Calendar authorization pending - ${error.message}")
                            // Pending state means permission dialog was launched
                            // Return false for now - callback will handle success
                            false
                        }
                        else -> {
                            Logger.e(LogTags.AUTH, "❌ MODERN-TOKEN: Calendar authorization failed", error)
                            throw Exception("Calendar authorization failed: ${error?.message}")
                        }
                    }
                }
            } ?: run {
                Logger.e(LogTags.AUTH, "❌ CRITICAL: OAuth2TokenManager not available - token system broken!")
                throw Exception("Calendar authorization system not available - dependency injection issue")
            }
        }
    }
    
    /**
     * CRITICAL FIX: Request Calendar authorization with Activity context for permission flow
     * 
     * This method properly handles the UserRecoverableAuthException by launching the
     * permission intent when needed.
     * 
     * @param userEmail Email address to authorize
     * @param activity Activity context for launching permission dialog
     * @param onResult Callback for authorization result
     * @return Result indicating if authorization was initiated
     */
    suspend fun requestCalendarAuthorizationWithActivity(
        userEmail: String,
        activity: Activity,
        onResult: (Boolean) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.requestCalendarAuthorizationWithActivity") {
            oauth2TokenManager?.let { tokenManager ->
                Logger.business(LogTags.AUTH, "🔐 FIXED-TOKEN: Requesting Calendar authorization with activity context")
                
                val authResult = tokenManager.authorize(
                    userEmail,
                    activity,
                    onResult
                )
                
                if (authResult.isSuccess) {
                    Logger.business(LogTags.AUTH, "✅ FIXED-TOKEN: Calendar authorization successful")
                    onResult(true)
                } else {
                    val error = authResult.exceptionOrNull()
                    when (error) {
                        is TokenException.PendingAuthorization -> {
                            Logger.business(LogTags.AUTH, "⏳ FIXED-TOKEN: Calendar authorization pending user permission")
                            // Callback will be triggered by onActivityResult
                        }
                        else -> {
                            Logger.e(LogTags.AUTH, "❌ FIXED-TOKEN: Calendar authorization failed", error)
                            onResult(false)
                            throw Exception(error?.message ?: "Authorization failed")
                        }
                    }
                }
            } ?: run {
                Logger.e(LogTags.AUTH, "❌ CRITICAL: OAuth2TokenManager not available")
                onResult(false)
                throw Exception("Calendar authorization system not available")
            }
        }
    }
    
    /**
     * MODERN: Checks if Calendar authorization is available
     * 
     * @return Result with Boolean (true if calendar access authorized) or error
     */
    override suspend fun hasCalendarAuthorization(): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.hasCalendarAuthorization") {
            oauth2TokenManager?.let { tokenManager ->
                val tokenResult = tokenManager.getValidToken()
                tokenResult.isSuccess
            } ?: false
        }
    }
    
    override suspend fun clearAuthData(): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.clearAuthData") {
            authDataStoreRepository.clearAuthData().getOrThrow()
            Logger.business(LogTags.AUTH, "Auth data cleared (logout)")
        }
    }
    
    override suspend fun isAuthenticated(): Result<Boolean> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.isAuthenticated") {
            authDataStoreRepository.isAuthenticated().getOrThrow()
        }
    }
    
    override suspend fun getCurrentAuthData(): Result<AuthData> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.getCurrentAuthData") {
            authDataStoreRepository.getCurrentAuthData().getOrThrow()
        }
    }
    
    override suspend fun migrateTokenExpiryIfNeeded(): Result<Unit> = withContext(Dispatchers.IO) {
        SafeExecutor.safeExecute("AuthUseCase.migrateTokenExpiryIfNeeded") {
            authDataStoreRepository.migrateTokenExpiryIfNeeded().getOrThrow()
            Logger.d(LogTags.DATASTORE, "Token expiry migration completed")
        }
    }
}
