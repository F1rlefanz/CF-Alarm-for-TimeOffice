package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AuthData
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAuthUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.ModernOAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.AuthResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * UseCase für alle Authentication-bezogenen Operationen - implementiert IAuthUseCase
 * 
 * REFACTORED:
 * ✅ Implementiert IAuthUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interface statt konkrete Implementierung
 * ✅ Kapselt Business Logic von Infrastructure
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Clean Architecture Compliance
 * ✅ MODERN: Integriert ModernOAuth2TokenManager für Calendar-Autorisierung
 * 
 * AUTHENTICATION FLOW 2024/2025:
 * 1. Credential Manager für Benutzer-Authentifizierung (wer bist du?)
 * 2. ModernOAuth2TokenManager für API-Autorisierung (was darfst du?)
 */
class AuthUseCase(
    private val authDataStoreRepository: IAuthDataStoreRepository,
    private val modernOAuth2TokenManager: ModernOAuth2TokenManager? = null
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
            
            modernOAuth2TokenManager?.let { tokenManager ->
                Logger.business(LogTags.AUTH, "🔐 MODERN-TOKEN: Requesting Calendar authorization for user: $emailToUse")
                
                val calendarAuthResult = tokenManager.authorizeCalendarAccess(emailToUse)
                when (calendarAuthResult) {
                    is AuthResult.Success -> {
                        Logger.business(LogTags.AUTH, "✅ MODERN-TOKEN: Calendar authorization successful - real OAuth2 token obtained", emailToUse)
                        Logger.d(LogTags.AUTH, "📊 Token details: accessToken=${calendarAuthResult.tokenData.accessToken.take(20)}..., expires=${calendarAuthResult.tokenData.getRemainingLifetimeMinutes()}min")
                        true
                    }
                    is AuthResult.Failure -> {
                        Logger.e(LogTags.AUTH, "❌ MODERN-TOKEN: Calendar authorization failed: ${calendarAuthResult.error}")
                        throw Exception("Calendar authorization failed: ${calendarAuthResult.error}")
                    }
                }
            } ?: run {
                Logger.e(LogTags.AUTH, "❌ CRITICAL: ModernOAuth2TokenManager not available - token system broken!")
                throw Exception("Calendar authorization system not available - dependency injection issue")
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
            modernOAuth2TokenManager?.hasCalendarAuthorization() ?: false
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
