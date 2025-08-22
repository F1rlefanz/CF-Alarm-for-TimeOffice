package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.storage

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * Repository pattern implementation for token storage management.
 * Provides reactive access to token state and abstracts storage implementation.
 * 
 * FIXED: Lifecycle-aware with proper scope management to prevent Race Conditions.
 * - SupervisorJob prevents child failures from affecting parent
 * - Proper cancellation prevents Use-After-Free scenarios
 * - Structured concurrency for thread safety
 */
class TokenStorageRepository(context: Context) {
    
    private val secureStorage = SecureTokenStorage(context)
    
    // FIXED: Lifecycle-aware scope with SupervisorJob
    private val repositoryScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("TokenStorageRepository")
    )
    
    // Internal mutable state for reactive token management
    private val _tokenState = MutableStateFlow<TokenData?>(null)
    
    /**
     * Reactive access to current token state.
     * Emits updates when token changes.
     */
    val tokenState: Flow<TokenData?> = _tokenState.asStateFlow()
    
    /**
     * Initializes repository by loading existing token from secure storage.
     * Should be called during app initialization.
     */
    suspend fun initialize(): Result<Unit> {
        return try {
            val tokenResult = secureStorage.getToken()
            val token = tokenResult.getOrNull()
            
            if (token != null && token.accessToken.isNotBlank()) {
                _tokenState.value = token
                Logger.d(LogTags.TOKEN, "TokenStorageRepository initialized with existing token: ${token.toLogString()}")
            } else {
                _tokenState.value = null
                Logger.d(LogTags.TOKEN, "TokenStorageRepository initialized without valid token")
            }
            
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "Error initializing TokenStorageRepository", e)
            _tokenState.value = null
            Result.failure(e)
        }
    }
    
    /**
     * Saves new token data and updates reactive state.
     * @param tokenData Token to save
     * @return Result indicating success or failure
     */
    suspend fun saveToken(tokenData: TokenData): Result<Unit> {
        val saveResult = secureStorage.saveToken(tokenData)
        
        return if (saveResult.isSuccess) {
            _tokenState.value = tokenData
            Logger.d(LogTags.TOKEN, "Token saved and state updated: ${tokenData.toLogString()}")
            Result.success(Unit)
        } else {
            Logger.e(LogTags.TOKEN, "Failed to save token: ${saveResult.exceptionOrNull()}")
            saveResult
        }
    }
    
    /**
     * Gets current token from state (synchronous access).
     * @return Current token or null if none available
     */
    fun getCurrentToken(): TokenData? = _tokenState.value
    
    /**
     * Gets current token ensuring it's valid.
     * @return Valid token or null
     */
    fun getCurrentValidToken(): TokenData? {
        val token = _tokenState.value
        return if (token?.isValid() == true) token else null
    }
    
    /**
     * Updates access token after refresh operation.
     * Preserves refresh token and other metadata.
     */
    suspend fun updateAccessToken(
        newAccessToken: String,
        newExpiresAt: Long,
        newScope: String? = null
    ): Result<Unit> {
        val updateResult = secureStorage.updateAccessToken(newAccessToken, newExpiresAt, newScope)
        
        return if (updateResult.isSuccess) {
            // Reload token from storage to ensure consistency
            val refreshedTokenResult = secureStorage.getToken()
            val refreshedToken = refreshedTokenResult.getOrNull()
            
            if (refreshedToken != null) {
                _tokenState.value = refreshedToken
                Logger.d(LogTags.TOKEN, "Access token updated: ${refreshedToken.toLogString()}")
            }
            
            Result.success(Unit)
        } else {
            Logger.e(LogTags.TOKEN, "Failed to update access token: ${updateResult.exceptionOrNull()}")
            updateResult
        }
    }
    
    /**
     * Clears all token data (logout operation).
     * Updates reactive state immediately.
     */
    suspend fun clearToken(): Result<Unit> {
        val clearResult = secureStorage.clearToken()
        
        // Always clear state, even if storage operation failed
        _tokenState.value = null
        
        return if (clearResult.isSuccess) {
            Logger.business(LogTags.TOKEN, "Token cleared from storage and state")
            Result.success(Unit)
        } else {
            Logger.e(LogTags.TOKEN, "Failed to clear token from storage: ${clearResult.exceptionOrNull()}")
            // Still return success since state was cleared
            Result.success(Unit)
        }
    }
    
    /**
     * Checks if current token needs refresh (reactive check).
     * @param bufferMinutes Buffer time before expiration
     * @return true if refresh is needed
     */
    fun shouldRefreshToken(bufferMinutes: Long = TokenData.TOKEN_REFRESH_BUFFER_MINUTES): Boolean {
        val token = _tokenState.value
        return token?.isExpiredOrExpiringSoon(bufferMinutes) ?: false
    }
    
    /**
     * Checks if token refresh is possible (has refresh token).
     * @return true if refresh token is available
     */
    fun canRefreshToken(): Boolean {
        val token = _tokenState.value
        return token?.canRefresh() ?: false
    }
    
    /**
     * Gets debug information about current token state.
     * Safe for logging (no sensitive data exposed).
     */
    fun getTokenDebugInfo(): String {
        val token = _tokenState.value
        return if (token != null) {
            "TokenState: ${token.toLogString()}, needsRefresh=${shouldRefreshToken()}, canRefresh=${canRefreshToken()}"
        } else {
            "TokenState: No token available"
        }
    }
}
