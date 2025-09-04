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
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.Result as KotlinResult

/**
 * 🚀 ENHANCED: Background Token Refresh Worker with Smart Maintenance Chain Level 2
 * 
 * ORIGINAL FEATURES:
 * - Proactive token refresh system for Calendar access tokens
 * - OnePlus-specific reliability enhancements  
 * - Comprehensive error handling and retry logic
 * - Battery-optimized execution strategy
 * 
 * 🔄 NEW: Smart Maintenance Chain Level 2 - Scheduled Background Maintenance
 * - Combines Token-Refresh + Alarm-Maintenance in single 6h session
 * - Backup system for cases where Level 1 opportunistic checks aren't sufficient
 * - Periodic health checks ensuring alarm continuity during extended app inactivity
 * - Extended 21-day calendar scanning for comprehensive alarm planning
 * - Intelligent failure detection and automatic alarm recovery
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
        
        // 🔄 Smart Maintenance Chain Level 2 Configuration
        private const val MINIMUM_FUTURE_ALARMS_L2 = 5  // Higher threshold for scheduled checks
        private const val EXTENDED_LOOKAHEAD_DAYS_L2 = 28L  // 4 weeks for background scanning
        private const val ALARM_HEALTH_CHECK_ENABLED = true
        
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
                "🔄 Enhanced background worker scheduled (Token + Alarm maintenance)", 
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
    
    /**
     * 🔄 Smart Maintenance Chain Level 2 Result wrapper
     */
    private sealed class AlarmMaintenanceResult {
        data class Success(val alarmsCreated: Int, val existingAlarms: Int) : AlarmMaintenanceResult()
        data class Skipped(val reason: String) : AlarmMaintenanceResult()
        data class Failure(val error: Throwable) : AlarmMaintenanceResult()
    }
    
    override suspend fun doWork(): WorkerResult = withContext(Dispatchers.IO) {
        Logger.business(LogTags.MAINTENANCE_L2, "🔄 LEVEL 2: Enhanced background worker started (Token + Alarm maintenance)")
        
        try {
            // Check if we're running on OnePlus device for enhanced logging
            val isOnePlus = android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            if (isOnePlus) {
                Logger.business(LogTags.TOKEN, "🔴 OnePlus device - enhanced reliability mode")
            }
            
            val sessionStartTime = System.currentTimeMillis()
            
            // 🔄 LEVEL 2: Combined Token + Alarm maintenance session
            val tokenResult = performTokenRefresh()
            val alarmResult = performScheduledAlarmMaintenance()
            
            val sessionDuration = System.currentTimeMillis() - sessionStartTime
            
            // Evaluate combined results
            when (tokenResult) {
                is TokenRefreshResult.Success -> {
                    Logger.business(
                        LogTags.MAINTENANCE_L2, 
                        "⏱️ LEVEL 2 SESSION: Token refresh successful", 
                        "Duration: ${tokenResult.duration}ms"
                    )
                    
                    // Evaluate alarm maintenance result
                    when (alarmResult) {
                        is AlarmMaintenanceResult.Success -> {
                            Logger.business(
                                LogTags.MAINTENANCE_L2,
                                "✅ LEVEL 2 SUCCESS: Complete maintenance session successful",
                                "Created ${alarmResult.alarmsCreated} alarms, ${alarmResult.existingAlarms} existing, Session: ${sessionDuration}ms"
                            )
                            
                            scheduleNextRefresh(true)
                            WorkerResult.success(createCombinedSuccessData(tokenResult, alarmResult, sessionDuration))
                        }
                        
                        is AlarmMaintenanceResult.Skipped -> {
                            Logger.d(
                                LogTags.MAINTENANCE_L2,
                                "✅ LEVEL 2 PARTIAL: Token success, alarm maintenance skipped",
                                alarmResult.reason
                            )
                            
                            scheduleNextRefresh(true)
                            WorkerResult.success(createTokenOnlySuccessData(tokenResult, sessionDuration))
                        }
                        
                        is AlarmMaintenanceResult.Failure -> {
                            Logger.w(
                                LogTags.MAINTENANCE_L2,
                                "⚠️ LEVEL 2 MIXED: Token success, alarm maintenance failed",
                                alarmResult.error
                            )
                            
                            // Token success is more critical - continue with success
                            scheduleNextRefresh(true)
                            WorkerResult.success(createMixedResultData(tokenResult, alarmResult, sessionDuration))
                        }
                    }
                }
                
                is TokenRefreshResult.Failure -> {
                    Logger.e(LogTags.MAINTENANCE_L2, "❌ LEVEL 2: Token refresh failed - impact on alarm maintenance", tokenResult.error)
                    
                    // Schedule next refresh with failure handling
                    scheduleNextRefresh(false)
                    
                    // Determine if we should retry
                    if (tokenResult.isRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Logger.w(LogTags.MAINTENANCE_L2, "🔄 LEVEL 2: Will retry with backoff (attempt ${runAttemptCount + 1})")
                        WorkerResult.retry()
                    } else {
                        Logger.e(LogTags.MAINTENANCE_L2, "💥 LEVEL 2: Failed permanently or max retries reached")
                        WorkerResult.failure(createCombinedFailureData(tokenResult, alarmResult, sessionDuration))
                    }
                }
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L2, "💥 LEVEL 2: Unexpected error in enhanced background worker", e)
            WorkerResult.failure(createFailureOutputData(e))
        }
    }
    
    /**
     * 🔄 SMART MAINTENANCE CHAIN Level 2: Scheduled Alarm Maintenance
     * 
     * Runs every 6 hours as backup to Level 1 opportunistic checks.
     * More comprehensive than Level 1 with extended 4-week scanning.
     * 
     * BATTERY OPTIMIZED: Only runs when network available and battery not low
     * COMPREHENSIVE: 28-day lookahead vs 21-day in Level 1
     * BACKUP SYSTEM: Ensures alarm continuity even if Level 1 misses cases
     */
    private suspend fun performScheduledAlarmMaintenance(): AlarmMaintenanceResult {
        return try {
            Logger.business(LogTags.MAINTENANCE_L2, "🔍 LEVEL 2: Starting scheduled alarm maintenance")
            
            if (!ALARM_HEALTH_CHECK_ENABLED) {
                Logger.d(LogTags.MAINTENANCE_L2, "⚠️ LEVEL 2: Alarm health checks disabled")
                return AlarmMaintenanceResult.Skipped("Alarm health checks disabled")
            }
            
            val alarmUseCase = appContainer.alarmUseCase
            val calendarUseCase = appContainer.calendarUseCase
            val shiftUseCase = appContainer.shiftUseCase
            val calendarSelectionRepository = appContainer.calendarSelectionRepository
            val shiftRecognitionEngine = appContainer.shiftRecognitionEngine
            
            // 1. Analyze current alarm situation
            val currentAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
            val futureAlarms = currentAlarms.filter { 
                it.triggerTime > System.currentTimeMillis() 
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L2,
                "📊 LEVEL 2 ANALYSIS: ${futureAlarms.size} future alarms found"
            )
            
            // 2. Check if maintenance is needed (higher threshold than Level 1)
            if (futureAlarms.size >= MINIMUM_FUTURE_ALARMS_L2) {
                Logger.d(LogTags.MAINTENANCE_L2, "✅ LEVEL 2: Sufficient alarms (${futureAlarms.size} >= $MINIMUM_FUTURE_ALARMS_L2)")
                return AlarmMaintenanceResult.Success(0, futureAlarms.size)
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L2,
                "🔄 LEVEL 2: Maintenance needed! Found ${futureAlarms.size}, need $MINIMUM_FUTURE_ALARMS_L2"
            )
            
            // 3. Get selected calendars
            val selectedCalendarIds = calendarSelectionRepository.selectedCalendarIds.first()
            if (selectedCalendarIds.isEmpty()) {
                Logger.w(LogTags.MAINTENANCE_L2, "⚠️ LEVEL 2: No calendars selected, skipping maintenance")
                return AlarmMaintenanceResult.Skipped("No calendars selected")
            }
            
            // 4. Extended calendar scan (4 weeks vs 3 weeks in Level 1)
            Logger.d(LogTags.MAINTENANCE_L2, "🔍 LEVEL 2 EXTENDED SCAN: ${EXTENDED_LOOKAHEAD_DAYS_L2} days ahead for ${selectedCalendarIds.size} calendars")
            
            val extendedEventsResult = calendarUseCase.getCalendarEventsWithCache(
                calendarIds = selectedCalendarIds,
                daysAhead = EXTENDED_LOOKAHEAD_DAYS_L2.toInt(),
                forceRefresh = false // Use cache for battery optimization
            )
            
            if (extendedEventsResult.isFailure) {
                Logger.w(LogTags.MAINTENANCE_L2, "❌ LEVEL 2: Failed to get extended calendar events", extendedEventsResult.exceptionOrNull())
                return AlarmMaintenanceResult.Failure(extendedEventsResult.exceptionOrNull() ?: Exception("Unknown calendar error"))
            }
            
            val extendedEvents = extendedEventsResult.getOrNull() ?: emptyList()
            Logger.business(LogTags.MAINTENANCE_L2, "📅 LEVEL 2 SCAN: Found ${extendedEvents.size} events in ${EXTENDED_LOOKAHEAD_DAYS_L2}-day range")
            
            // 5. Recognize shifts and create new alarms
            val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(extendedEvents)
            val newShiftMatches = shiftMatches.filter { shiftMatch ->
                // Only future shifts that don't already have alarms
                val alarmTime = shiftMatch.calculatedAlarmTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                alarmTime > System.currentTimeMillis() && 
                !futureAlarms.any { existingAlarm -> 
                    Math.abs(existingAlarm.triggerTime - alarmTime) < 60 * 1000 // 1 minute tolerance
                }
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L2,
                "🆕 LEVEL 2: Found ${newShiftMatches.size} new shifts to schedule"
            )
            
            if (newShiftMatches.isEmpty()) {
                Logger.d(LogTags.MAINTENANCE_L2, "💡 LEVEL 2: No new shifts found - system healthy")
                return AlarmMaintenanceResult.Success(0, futureAlarms.size)
            }
            
            // 6. Check if auto-alarm is enabled
            val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
            if (shiftConfig == null || !shiftConfig.autoAlarmEnabled) {
                Logger.d(LogTags.MAINTENANCE_L2, "⚠️ LEVEL 2: Auto-alarm disabled, skipping alarm creation")
                return AlarmMaintenanceResult.Skipped("Auto-alarm disabled")
            }
            
            // 7. Create new alarms
            val newEvents = newShiftMatches.map { it.calendarEvent }
            val createResult = alarmUseCase.createAlarmsFromEvents(newEvents, shiftConfig)
            
            if (createResult.isSuccess) {
                val createdAlarms = createResult.getOrNull() ?: emptyList()
                
                // 8. Schedule system alarms
                var successfulSystemAlarms = 0
                for (newAlarm in createdAlarms) {
                    try {
                        alarmUseCase.scheduleSystemAlarm(newAlarm)
                        successfulSystemAlarms++
                        Logger.d(LogTags.MAINTENANCE_L2, "✅ LEVEL 2: System alarm scheduled for: ${newAlarm.shiftName}")
                    } catch (e: Exception) {
                        Logger.e(LogTags.MAINTENANCE_L2, "❌ LEVEL 2: Failed to schedule system alarm for: ${newAlarm.shiftName}", e)
                    }
                }
                
                Logger.business(
                    LogTags.MAINTENANCE_L2,
                    "✅ LEVEL 2 SUCCESS: Created $successfulSystemAlarms new alarms automatically!",
                    "Total future alarms: ${futureAlarms.size + successfulSystemAlarms}"
                )
                
                AlarmMaintenanceResult.Success(successfulSystemAlarms, futureAlarms.size)
            } else {
                Logger.w(LogTags.MAINTENANCE_L2, "❌ LEVEL 2: Failed to create alarms", createResult.exceptionOrNull())
                AlarmMaintenanceResult.Failure(createResult.exceptionOrNull() ?: Exception("Unknown alarm creation error"))
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L2, "❌ LEVEL 2: Critical error during scheduled maintenance", e)
            AlarmMaintenanceResult.Failure(e)
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
            Logger.w(LogTags.MAINTENANCE_L2, "📅 LEVEL 2: Scheduling frequent checks due to recent failure")
            scheduleUrgentTokenRefresh(applicationContext)
        } else {
            Logger.d(LogTags.MAINTENANCE_L2, "📅 LEVEL 2: Session successful - maintaining normal 6h schedule")
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
     * 🔄 LEVEL 2: Combined success data for WorkManager
     */
    private fun createCombinedSuccessData(
        tokenResult: TokenRefreshResult.Success, 
        alarmResult: AlarmMaintenanceResult.Success,
        sessionDuration: Long
    ): Data {
        return Data.Builder()
            .putLong("session_duration_ms", sessionDuration)
            .putLong("token_refresh_duration_ms", tokenResult.duration)
            .putLong("refresh_timestamp", System.currentTimeMillis())
            .putString("result", "combined_success")
            .putString("level", "L2_COMBINED")
            .putInt("alarms_created", alarmResult.alarmsCreated)
            .putInt("existing_alarms", alarmResult.existingAlarms)
            .putBoolean("is_oneplus_device", android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true))
            .build()
    }
    
    /**
     * Token-only success data when alarm maintenance was skipped
     */
    private fun createTokenOnlySuccessData(
        tokenResult: TokenRefreshResult.Success,
        sessionDuration: Long
    ): Data {
        return Data.Builder()
            .putLong("session_duration_ms", sessionDuration)
            .putLong("token_refresh_duration_ms", tokenResult.duration)
            .putLong("refresh_timestamp", System.currentTimeMillis())
            .putString("result", "token_only_success")
            .putString("level", "L2_TOKEN_ONLY")
            .putBoolean("is_oneplus_device", android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true))
            .build()
    }
    
    /**
     * Mixed result data when token succeeded but alarm maintenance failed
     */
    private fun createMixedResultData(
        tokenResult: TokenRefreshResult.Success,
        alarmResult: AlarmMaintenanceResult.Failure,
        sessionDuration: Long
    ): Data {
        return Data.Builder()
            .putLong("session_duration_ms", sessionDuration)
            .putLong("token_refresh_duration_ms", tokenResult.duration)
            .putLong("refresh_timestamp", System.currentTimeMillis())
            .putString("result", "mixed_result")
            .putString("level", "L2_MIXED")
            .putString("alarm_error", alarmResult.error.message ?: "Unknown alarm error")
            .putBoolean("is_oneplus_device", android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true))
            .build()
    }
    
    /**
     * Combined failure data for WorkManager
     */
    private fun createCombinedFailureData(
        tokenResult: TokenRefreshResult.Failure,
        alarmResult: AlarmMaintenanceResult?,
        sessionDuration: Long
    ): Data {
        return Data.Builder()
            .putLong("session_duration_ms", sessionDuration)
            .putLong("refresh_timestamp", System.currentTimeMillis())
            .putString("result", "combined_failure")
            .putString("level", "L2_COMBINED_FAILURE")
            .putString("token_error", tokenResult.error.message ?: "Unknown token error")
            .putString("token_error_type", tokenResult.error.javaClass.simpleName)
            .putString("alarm_result", alarmResult?.javaClass?.simpleName ?: "NotExecuted")
            .putInt("attempt_count", runAttemptCount)
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
            .putString("level", "L2_CRITICAL_FAILURE")
            .putString("error_message", error?.message ?: "Unknown error")
            .putString("error_type", error?.javaClass?.simpleName ?: "UnknownError")
            .putInt("attempt_count", runAttemptCount)
            .putBoolean("is_oneplus_device", android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true))
            .build()
    }
}
