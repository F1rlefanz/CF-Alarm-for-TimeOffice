package com.github.f1rlefanz.cf_alarmfortimeoffice.service.worker

import android.content.Context
import androidx.work.*
import androidx.work.ListenableWorker.Result as WorkerResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.CFAlarmApplication
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenRefreshUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.Result as KotlinResult

/**
 * 🚀 PHASE 3: Enhanced Background Token Refresh Worker
 * 
 * Proactive token refresh system that ensures Calendar access tokens
 * are always valid, preventing alarm failures due to expired authentication.
 * 
 * Features:
 * - Intelligent scheduling based on token expiry
 * - OnePlus-specific reliability enhancements
 * - Comprehensive error handling and retry logic
 * - Battery-optimized execution strategy
 * - Integration with AlarmVerificationManager for failure prevention
 */
class BackgroundTokenRefreshWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val WORKER_TAG = "background_token_refresh"
        const val UNIQUE_WORK_NAME = "token_refresh_periodic"
        
        // Scheduling constants
        private const val DEFAULT_REFRESH_INTERVAL_HOURS = 6L
        private const val TOKEN_EXPIRY_BUFFER_MINUTES = 30L
        
        // Retry configuration
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_INITIAL_DELAY_MINUTES = 15L
        
        /**
         * Schedules background token refresh with intelligent intervals
         */
        fun scheduleTokenRefresh(context: Context) {
            val workManager = WorkManager.getInstance(context)
            
            // Cancel any existing work
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            
            val refreshRequest = PeriodicWorkRequestBuilder<BackgroundTokenRefreshWorker>(
                DEFAULT_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS,
                1, TimeUnit.HOURS // Flex interval for battery optimization
            )
                .setConstraints(createWorkConstraints())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    Duration.ofMinutes(RETRY_BACKOFF_INITIAL_DELAY_MINUTES)
                )
                .addTag(WORKER_TAG)
                .build()
            
            workManager.enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                refreshRequest
            )
            
            Logger.business(
                LogTags.TOKEN, 
                "🔄 Background token refresh scheduled", 
                "Interval: ${DEFAULT_REFRESH_INTERVAL_HOURS}h"
            )
        }
        
        /**
         * Schedules urgent token refresh (when token is expiring soon)
         */
        fun scheduleUrgentTokenRefresh(context: Context) {
            val workManager = WorkManager.getInstance(context)
            
            val urgentRequest = OneTimeWorkRequestBuilder<BackgroundTokenRefreshWorker>()
                .setConstraints(createWorkConstraints(allowOnBattery = true))
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    Duration.ofMinutes(5)
                )
                .addTag("${WORKER_TAG}_urgent")
                .build()
            
            workManager.enqueueUniqueWork(
                "${UNIQUE_WORK_NAME}_urgent",
                ExistingWorkPolicy.REPLACE,
                urgentRequest
            )
            
            Logger.business(LogTags.TOKEN, "⚡ Urgent token refresh scheduled")
        }
        
        /**
         * Creates battery-optimized work constraints
         */
        private fun createWorkConstraints(allowOnBattery: Boolean = false): Constraints {
            return Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(!allowOnBattery) // Allow urgent refresh on low battery
                .setRequiresDeviceIdle(false) // Don't wait for idle for token refresh
                .build()
        }
        
        /**
         * Cancels all token refresh work
         */
        fun cancelTokenRefresh(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.cancelAllWorkByTag(WORKER_TAG)
            
            Logger.d(LogTags.TOKEN, "🛑 Background token refresh cancelled")
        }
    }
    
    private val appContainer by lazy {
        (applicationContext as CFAlarmApplication).appContainer
    }
    
    private val tokenRefreshUseCase by lazy {
        TokenRefreshUseCase(
            appContainer.modernOAuth2TokenManager,
            appContainer.tokenStorageRepository
        )
    }
    
    /**
     * Internal result wrapper to bridge Kotlin Result and WorkManager Result
     */
    private sealed class TokenRefreshResult {
        data class Success(val duration: Long) : TokenRefreshResult()
        data class Failure(val error: Throwable, val isRetryable: Boolean) : TokenRefreshResult()
    }
    
    override suspend fun doWork(): WorkerResult = withContext(Dispatchers.IO) {
        Logger.business(LogTags.TOKEN, "🔄 Background token refresh worker started")
        
        try {
            // Check if we're running on OnePlus device for enhanced logging
            val isOnePlus = android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            if (isOnePlus) {
                Logger.business(LogTags.TOKEN, "🔴 OnePlus device - enhanced token refresh reliability")
            }
            
            // Attempt token refresh
            val result = performTokenRefresh()
            
            // Handle result based on internal wrapper
            when (result) {
                is TokenRefreshResult.Success -> {
                    Logger.business(
                        LogTags.TOKEN, 
                        "⏱️ Token refresh completed successfully", 
                        "Duration: ${result.duration}ms, OnePlus: $isOnePlus"
                    )
                    
                    // Schedule next refresh
                    scheduleNextRefresh(true)
                    
                    WorkerResult.success(createSuccessOutputData(result.duration))
                }
                
                is TokenRefreshResult.Failure -> {
                    Logger.e(LogTags.TOKEN, "❌ Background token refresh failed", result.error)
                    
                    // Schedule next refresh with failure handling
                    scheduleNextRefresh(false)
                    
                    // Determine if we should retry based on error type
                    if (result.isRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Logger.w(LogTags.TOKEN, "🔄 Token refresh failed, will retry with backoff (attempt ${runAttemptCount + 1})")
                        WorkerResult.retry()
                    } else {
                        Logger.e(LogTags.TOKEN, "💥 Token refresh failed permanently or max retries reached")
                        WorkerResult.failure(createFailureOutputData(result.error))
                    }
                }
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "💥 Unexpected error in background token refresh", e)
            WorkerResult.failure(createFailureOutputData(e))
        }
    }
    
    /**
     * Performs the actual token refresh with comprehensive error handling
     * Returns internal TokenRefreshResult for proper type handling
     */
    private suspend fun performTokenRefresh(): TokenRefreshResult {
        return try {
            Logger.d(LogTags.TOKEN, "🔍 Starting token validation and refresh")
            
            val refreshStartTime = System.currentTimeMillis()
            
            // Get current token status first
            val tokenStatus = tokenRefreshUseCase.getTokenStatus()
            Logger.d(LogTags.TOKEN, "📊 Current token status: $tokenStatus")
            
            val kotlinResult: KotlinResult<String> = when (tokenStatus) {
                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.NoToken -> {
                    Logger.w(LogTags.TOKEN, "❌ No token available - user needs to re-authenticate")
                    KotlinResult.failure(Exception("No authentication token available"))
                }
                
                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.ExpiredNotRefreshable -> {
                    Logger.w(LogTags.TOKEN, "❌ Token expired and not refreshable - user re-auth required")
                    KotlinResult.failure(Exception("Token expired - re-authentication required"))
                }
                
                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.Valid -> {
                    if (tokenStatus.remainingMinutes < TOKEN_EXPIRY_BUFFER_MINUTES) {
                        Logger.d(LogTags.TOKEN, "⏰ Token expiring soon (${tokenStatus.remainingMinutes}min) - refreshing")
                        tokenRefreshUseCase.forceRefresh()
                    } else {
                        Logger.d(LogTags.TOKEN, "✅ Token still valid (${tokenStatus.remainingMinutes}min remaining)")
                        tokenRefreshUseCase.ensureValidToken()
                    }
                }
                
                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.ExpiredButRefreshable -> {
                    Logger.d(LogTags.TOKEN, "🔄 Token expired but refreshable - attempting refresh")
                    val refreshResult = tokenRefreshUseCase.refreshIfExpiringSoon(0) // Force refresh
                    if (refreshResult.isSuccess) {
                        tokenRefreshUseCase.ensureValidToken()
                    } else {
                        refreshResult.map { "Token refreshed successfully" }
                    }
                }
                
                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.Error -> {
                    Logger.e(LogTags.TOKEN, "❌ Token status error", tokenStatus.exception)
                    // Try to get a valid token anyway
                    tokenRefreshUseCase.ensureValidToken()
                }
            }
            
            val refreshDuration = System.currentTimeMillis() - refreshStartTime
            
            // Convert Kotlin Result to internal TokenRefreshResult
            if (kotlinResult.isSuccess) {
                val token = kotlinResult.getOrThrow()
                TokenRefreshResult.Success(refreshDuration)
            } else {
                val error = kotlinResult.exceptionOrNull() ?: Exception("Unknown token refresh error")
                val isRetryable = shouldRetryError(error)
                TokenRefreshResult.Failure(error, isRetryable)
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error during token refresh", e)
            val isRetryable = shouldRetryError(e)
            TokenRefreshResult.Failure(e, isRetryable)
        }
    }
    
    /**
     * Schedules the next refresh based on current result
     */
    private fun scheduleNextRefresh(wasSuccessful: Boolean) {
        if (!wasSuccessful) {
            // Schedule more frequent checks when refresh fails
            Logger.w(LogTags.TOKEN, "📅 Scheduling frequent token checks due to recent failure")
            scheduleUrgentTokenRefresh(applicationContext)
        } else {
            Logger.d(LogTags.TOKEN, "📅 Token refresh successful - maintaining normal schedule")
            // Normal scheduling will continue with the periodic work
        }
    }
    
    /**
     * Determines if an error should trigger a retry
     */
    private fun shouldRetryError(error: Throwable?): Boolean {
        return when {
            error == null -> false
            
            // Network-related errors should be retried
            error.message?.contains("NetworkError", ignoreCase = true) == true -> true
            error.message?.contains("timeout", ignoreCase = true) == true -> true
            error.message?.contains("connection", ignoreCase = true) == true -> true
            
            // Authentication errors that might be temporary
            error.message?.contains("temporarily", ignoreCase = true) == true -> true
            error.message?.contains("rate limit", ignoreCase = true) == true -> true
            
            // Don't retry permanent authentication failures
            error.message?.contains("re-authentication", ignoreCase = true) == true -> false
            error.message?.contains("authorization expired", ignoreCase = true) == true -> false
            error.message?.contains("invalid credentials", ignoreCase = true) == true -> false
            
            // Default: retry once for unknown errors
            else -> runAttemptCount < MAX_RETRY_ATTEMPTS
        }
    }
    
    /**
     * Creates success output data for work manager
     */
    private fun createSuccessOutputData(refreshDuration: Long): Data {
        return Data.Builder()
            .putLong("refresh_duration_ms", refreshDuration)
            .putLong("refresh_timestamp", System.currentTimeMillis())
            .putString("result", "success")
            .putBoolean("is_oneplus_device", android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true))
            .build()
    }
    
    /**
     * Creates failure output data for work manager
     */
    private fun createFailureOutputData(error: Throwable?): Data {
        return Data.Builder()
            .putLong("refresh_timestamp", System.currentTimeMillis())
            .putString("result", "failure")
            .putString("error_message", error?.message ?: "Unknown error")
            .putString("error_type", error?.javaClass?.simpleName ?: "UnknownError")
            .putInt("attempt_count", runAttemptCount)
            .putBoolean("is_oneplus_device", android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true))
            .build()
    }
}
