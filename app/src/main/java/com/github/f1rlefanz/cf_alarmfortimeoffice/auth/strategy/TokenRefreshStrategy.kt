package com.github.f1rlefanz.cf_alarmfortimeoffice.auth.strategy

import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.data.TokenData
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.TokenException
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Smart Retry Strategy für Token-Refresh
 * 
 * Features:
 * - ✅ Exponential Backoff
 * - ✅ Jitter (Thundering Herd Prevention)
 * - ✅ Selektive Retry-Logic basierend auf Exception-Type
 * - ✅ CancellationException Propagation
 */
class TokenRefreshStrategy(
    private val oauth2Manager: OAuth2TokenManager
) {
    
    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 30000L
    }
    
    /**
     * Refresht Token mit intelligenter Retry-Logic
     */
    suspend fun refreshWithRetry(
        token: TokenData,
        maxAttempts: Int = MAX_ATTEMPTS
    ): Result<TokenData> {
        var attempt = 0
        var lastException: Exception? = null
        
        while (attempt < maxAttempts) {
            try {
                attempt++
                Logger.d(LogTags.TOKEN, "Token refresh attempt $attempt/$maxAttempts")
                
                // Attempt refresh
                val result = oauth2Manager.refresh(token)
                
                if (result.isSuccess) {
                    Logger.i(LogTags.TOKEN, "✅ Token refresh successful on attempt $attempt")
                    return result
                }
                
                // Analyze failure
                lastException = result.exceptionOrNull() as? Exception
                
                // Determine if retry makes sense
                val shouldRetry = shouldRetry(lastException, attempt, maxAttempts)
                
                if (!shouldRetry) {
                    Logger.w(LogTags.TOKEN, "Token refresh not retryable: ${lastException?.message}")
                    break
                }
                
                // Exponential backoff with jitter
                val delayMs = calculateBackoff(attempt)
                Logger.d(LogTags.TOKEN, "Waiting ${delayMs}ms before retry...")
                delay(delayMs)
                
            } catch (e: CancellationException) {
                // KRITISCH: Sofort propagieren!
                throw e
            } catch (e: Exception) {
                lastException = e
                Logger.w(LogTags.TOKEN, "Token refresh attempt $attempt failed", e)
            }
        }
        
        // All attempts failed
        Logger.e(LogTags.TOKEN, "❌ Token refresh failed after $maxAttempts attempts")
        return Result.failure(
            lastException ?: TokenException.RefreshFailed("Max retries exceeded")
        )
    }
    
    /**
     * Bestimmt ob Retry sinnvoll ist
     */
    private fun shouldRetry(exception: Exception?, attempt: Int, maxAttempts: Int): Boolean {
        if (attempt >= maxAttempts) return false
        
        return when (exception) {
            // Network Errors: Retry
            is TokenException.NetworkError -> true
            
            // Transient Errors: Retry
            is TokenException.TransientError -> true
            
            // Storage Failures: Retry (könnte temporary sein)
            is TokenException.StorageFailed -> true
            
            // Authorization Expired: No Retry (User-Action required)
            is TokenException.AuthorizationExpired -> false
            
            // Security Violations: No Retry (Critical)
            is TokenException.SecurityViolation -> false
            
            // Unknown: Retry on first few attempts
            else -> attempt < 2
        }
    }
    
    /**
     * Berechnet Backoff mit Exponential + Jitter
     */
    private fun calculateBackoff(attempt: Int): Long {
        // Exponential: 1s, 2s, 4s, 8s, ...
        val exponentialDelay = (BASE_DELAY_MS * (1 shl (attempt - 1)))
            .coerceAtMost(MAX_DELAY_MS)
        
        // Jitter: ±20% to prevent thundering herd
        val jitterRange = (exponentialDelay * 0.2).toLong()
        val jitter = Random.nextLong(-jitterRange, jitterRange)
        
        return (exponentialDelay + jitter).coerceAtLeast(0)
    }
}
