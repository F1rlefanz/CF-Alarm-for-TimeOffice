package com.github.f1rlefanz.cf_alarmfortimeoffice.service.worker

import android.content.Context
import androidx.core.content.edit
import androidx.work.*
import androidx.work.ListenableWorker.Result as WorkerResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.CFAlarmApplication
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenRefreshUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.AppContainer
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.abs
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

        // Retry configuration
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_INITIAL_DELAY_MINUTES = 15L

        /**
         * Schedules background token refresh with intelligent intervals
         * @param intervalHours Custom interval in hours (1, 3, 6, 12), defaults to 6h
         */
        fun scheduleTokenRefresh(context: Context, intervalHours: Long = DEFAULT_REFRESH_INTERVAL_HOURS) {
            val workManager = WorkManager.getInstance(context)

            // Cancel any existing work
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)

            val refreshRequest = PeriodicWorkRequestBuilder<BackgroundTokenRefreshWorker>(
                intervalHours, TimeUnit.HOURS,
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
                "Interval: ${intervalHours}h"
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

    private val appContainer: AppContainer by lazy {
        (applicationContext as CFAlarmApplication).appContainer
    }

    private val tokenRefreshUseCase: TokenRefreshUseCase by lazy {
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
        data class Failure(
            val error: Throwable,
            val isRetryable: Boolean,
            val needsReauth: Boolean = false // 🔧 STUFE 3: Track if re-authorization is needed
        ) : TokenRefreshResult()
    }

    /**
     * 🔄 Smart Maintenance Chain Level 2 Result wrapper
     */
    private sealed class AlarmMaintenanceResult {
        data class Success(val alarmsCreated: Int, val existingAlarms: Int) :
            AlarmMaintenanceResult()

        data class Skipped(val reason: String) : AlarmMaintenanceResult()
        data class Failure(val error: Throwable) : AlarmMaintenanceResult()
    }

    /**
     * 🔧 PHASE 1 FIX: Proactive Health Check for Token Refresh Prerequisites
     *
     * Validates that all required components for token refresh are available:
     * - User email (from SharedPreferences, TokenData, or JWT)
     * - Token storage accessibility
     * - Network connectivity (checked by WorkManager constraints)
     *
     * @return Result indicating health status with detailed error message
     */
    private fun performTokenHealthCheck(): KotlinResult<String> {
        return try {
            Logger.d(LogTags.TOKEN, "🔍 PHASE-1: Starting token health check")

            // Check 1: Verify token storage is accessible
            try {
                appContainer.tokenStorageRepository.getCurrentToken()
            } catch (e: Exception) {
                Logger.e(LogTags.TOKEN, "❌ PHASE-1: Token storage not accessible", e)
                return KotlinResult.failure(Exception("Token storage corrupted or inaccessible"))
            }

            Logger.d(LogTags.TOKEN, "✅ PHASE-1: Token storage accessible")

            // Check 2: Verify user email is available (critical for refresh)
            val prefs = applicationContext.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            val emailFromPrefs = prefs.getString("current_user_email", null)
            val isSignedIn = prefs.getBoolean("user_signed_in", false)

            // Primary source: SharedPreferences
            if (!emailFromPrefs.isNullOrBlank() && emailFromPrefs.contains("@") && isSignedIn) {
                Logger.business(LogTags.TOKEN, "✅ PHASE-1 HEALTH: Email available from SharedPreferences: $emailFromPrefs")
                return KotlinResult.success("Health check passed")
            }

            Logger.w(LogTags.TOKEN, "⚠️ PHASE-1 HEALTH: Email not in SharedPreferences, checking fallback sources")

            // Fallback: Check TokenData
            val currentToken = appContainer.tokenStorageRepository.getCurrentToken()
            if (currentToken?.userEmail?.isNotBlank() == true) {
                Logger.business(LogTags.TOKEN, "✅ PHASE-1 HEALTH: Email available from TokenData: ${currentToken.userEmail}")
                // Cache back to SharedPreferences for future
                prefs.edit {
                    putString("current_user_email", currentToken.userEmail)
                }
                return KotlinResult.success("Health check passed (recovered from TokenData)")
            }

            // Last resort: Extract from JWT token
            if (currentToken?.accessToken?.isNotBlank() == true) {
                val emailFromJWT = extractEmailFromJWT(currentToken.accessToken)
                if (!emailFromJWT.isNullOrBlank()) {
                    Logger.business(LogTags.TOKEN, "✅ PHASE-1 HEALTH: Email extracted from JWT: $emailFromJWT")
                    // Cache for future
                    prefs.edit {
                        putString("current_user_email", emailFromJWT)
                    }
                    return KotlinResult.success("Health check passed (recovered from JWT)")
                }
            }

            // No email found in any source - critical failure
            Logger.e(LogTags.TOKEN, "❌ PHASE-1 HEALTH: NO EMAIL AVAILABLE from any source!")
            Logger.w(LogTags.TOKEN, "💡 PHASE-1 HEALTH: User needs to re-authenticate")

            KotlinResult.failure(Exception("No user email available - re-authentication required"))

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ PHASE-1 HEALTH: Health check failed", e)
            KotlinResult.failure(e)
        }
    }

    /**
     * 🔧 PHASE 1 FIX: Extract email from JWT access token
     */
    private fun extractEmailFromJWT(accessToken: String): String? {
        return try {
            val segments = accessToken.split(".")
            if (segments.size < 2) return null

            val payloadBytes = android.util.Base64.decode(
                segments[1],
                android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING
            )
            val payloadJson = org.json.JSONObject(String(payloadBytes, Charsets.UTF_8))
            payloadJson.optString("email").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to extract email from JWT", e)
            null
        }
    }

    /**
     * 🔧 PHASE 1 FIX: Send User Notification for Re-Authentication
     *
     * Notifies the user when token refresh fails due to missing credentials.
     * Provides clear action: "Sign in again to restore Calendar access"
     */
    private fun sendReAuthNotification(reason: String) {
        try {
            Logger.business(LogTags.TOKEN, "🔔 PHASE-1: Sending re-authentication notification")

            // Check if we have notification permission (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (androidx.core.app.ActivityCompat.checkSelfPermission(
                        applicationContext,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Logger.w(LogTags.TOKEN, "⚠️ PHASE-1: No notification permission - cannot notify user")
                    return
                }
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) 
                as android.app.NotificationManager

            // Create notification channel
            val channel = android.app.NotificationChannel(
                "token_reauth",
                "Calendar Authorization",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications when Calendar access needs to be restored"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)

            // Create intent to open app
            val openAppIntent = applicationContext.packageManager
                .getLaunchIntentForPackage(applicationContext.packageName)
                ?.apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or 
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                }

            val pendingIntent = android.app.PendingIntent.getActivity(
                applicationContext,
                0,
                openAppIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or 
                android.app.PendingIntent.FLAG_IMMUTABLE
            )

            // Build notification
            val notification = androidx.core.app.NotificationCompat.Builder(
                applicationContext,
                "token_reauth"
            )
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Calendar Access Lost")
                .setContentText("Tap to sign in again and restore alarm scheduling")
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText("Your Calendar authorization has expired. Please open CF Alarm and sign in again to restore automatic alarm scheduling.\n\nReason: $reason")
                )
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            notificationManager.notify(1001, notification)
            Logger.business(LogTags.TOKEN, "✅ PHASE-1: Re-authentication notification sent successfully")

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ PHASE-1: Failed to send re-authentication notification", e)
        }
    }

    override suspend fun doWork(): WorkerResult = withContext(Dispatchers.IO) {
        Logger.business(
            LogTags.MAINTENANCE_L2,
            "🔄 LEVEL 2: Enhanced background worker started (Token + Alarm maintenance)"
        )

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
                            WorkerResult.success(
                                createCombinedSuccessData(
                                    tokenResult,
                                    alarmResult,
                                    sessionDuration
                                )
                            )
                        }

                        is AlarmMaintenanceResult.Skipped -> {
                            Logger.d(
                                LogTags.MAINTENANCE_L2,
                                "✅ LEVEL 2 PARTIAL: Token success, alarm maintenance skipped - ${alarmResult.reason}"
                            )

                            scheduleNextRefresh(true)
                            WorkerResult.success(
                                createTokenOnlySuccessData(
                                    tokenResult,
                                    sessionDuration
                                )
                            )
                        }

                        is AlarmMaintenanceResult.Failure -> {
                            Logger.w(
                                LogTags.MAINTENANCE_L2,
                                "⚠️ LEVEL 2 MIXED: Token success, alarm maintenance failed",
                                alarmResult.error
                            )

                            // Token success is more critical - continue with success
                            scheduleNextRefresh(true)
                            WorkerResult.success(
                                createMixedResultData(
                                    tokenResult,
                                    alarmResult,
                                    sessionDuration
                                )
                            )
                        }
                    }
                }

                is TokenRefreshResult.Failure -> {
                    Logger.e(
                        LogTags.MAINTENANCE_L2,
                        "❌ LEVEL 2: Token refresh failed - impact on alarm maintenance",
                        tokenResult.error
                    )

                    // 🔧 STUFE 3: Handle token revocation specially
                    if (tokenResult.needsReauth) {
                        Logger.e(
                            LogTags.MAINTENANCE_L2,
                            "🔴 STUFE-3: Token revoked - user re-authorization required, no retry"
                        )

                        // Don't schedule next refresh - wait for user to re-authorize
                        Logger.business(LogTags.MAINTENANCE_L2, "⏸️ STUFE-3: Background refresh suspended until re-authorization")

                        return@withContext WorkerResult.failure(
                            Data.Builder()
                                .putAll(createCombinedFailureData(
                                    tokenResult,
                                    alarmResult,
                                    sessionDuration
                                ))
                                .putBoolean("token_revoked", true)
                                .putString("action_required", "user_reauth")
                                .build()
                        )
                    }

                    // Schedule next refresh with failure handling
                    scheduleNextRefresh(false)

                    // Determine if we should retry
                    if (tokenResult.isRetryable && runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Logger.w(
                            LogTags.MAINTENANCE_L2,
                            "🔄 LEVEL 2: Will retry with backoff (attempt ${runAttemptCount + 1})"
                        )
                        WorkerResult.retry()
                    } else {
                        Logger.e(
                            LogTags.MAINTENANCE_L2,
                            "💥 LEVEL 2: Failed permanently or max retries reached"
                        )
                        WorkerResult.failure(
                            createCombinedFailureData(
                                tokenResult,
                                alarmResult,
                                sessionDuration
                            )
                        )
                    }
                }
            }

        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE_L2,
                "💥 LEVEL 2: Unexpected error in enhanced background worker",
                e
            )
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
            Logger.business(
                LogTags.MAINTENANCE_L2,
                "🔍 LEVEL 2: Starting scheduled alarm maintenance"
            )

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
                Logger.d(
                    LogTags.MAINTENANCE_L2,
                    "✅ LEVEL 2: Sufficient alarms (${futureAlarms.size} >= $MINIMUM_FUTURE_ALARMS_L2)"
                )
                return AlarmMaintenanceResult.Success(0, futureAlarms.size)
            }

            Logger.business(
                LogTags.MAINTENANCE_L2,
                "🔄 LEVEL 2: Maintenance needed! Found ${futureAlarms.size}, need $MINIMUM_FUTURE_ALARMS_L2"
            )

            // 3. Get selected calendars
            val selectedCalendarIds = calendarSelectionRepository.selectedCalendarIds.first()
            if (selectedCalendarIds.isEmpty()) {
                Logger.w(
                    LogTags.MAINTENANCE_L2,
                    "⚠️ LEVEL 2: No calendars selected, skipping maintenance"
                )
                return AlarmMaintenanceResult.Skipped("No calendars selected")
            }

            // 4. Extended calendar scan (4 weeks vs 3 weeks in Level 1)
            Logger.d(
                LogTags.MAINTENANCE_L2,
                "🔍 LEVEL 2 EXTENDED SCAN: $EXTENDED_LOOKAHEAD_DAYS_L2 days ahead for ${selectedCalendarIds.size} calendars"
            )

            val extendedEventsResult = calendarUseCase.getCalendarEventsWithCache(
                calendarIds = selectedCalendarIds,
                daysAhead = EXTENDED_LOOKAHEAD_DAYS_L2.toInt(),
                forceRefresh = false // Use cache for battery optimization
            )

            if (extendedEventsResult.isFailure) {
                Logger.w(
                    LogTags.MAINTENANCE_L2,
                    "❌ LEVEL 2: Failed to get extended calendar events",
                    extendedEventsResult.exceptionOrNull()
                )
                return AlarmMaintenanceResult.Failure(
                    extendedEventsResult.exceptionOrNull() ?: Exception("Unknown calendar error")
                )
            }

            val extendedEvents = extendedEventsResult.getOrNull() ?: emptyList()
            Logger.business(
                LogTags.MAINTENANCE_L2,
                "📅 LEVEL 2 SCAN: Found ${extendedEvents.size} events in ${EXTENDED_LOOKAHEAD_DAYS_L2}-day range"
            )

            // 5. Recognize shifts and create new alarms
            val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(extendedEvents)
            val newShiftMatches = shiftMatches.filter { shiftMatch ->
                // Only future shifts that don't already have alarms
                val alarmTime =
                    shiftMatch.calculatedAlarmTime.atZone(ZoneId.systemDefault()).toInstant()
                        .toEpochMilli()
                alarmTime > System.currentTimeMillis() &&
                        !futureAlarms.any { existingAlarm ->
                            abs(existingAlarm.triggerTime - alarmTime) < 60 * 1000 // 1 minute tolerance
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
                Logger.d(
                    LogTags.MAINTENANCE_L2,
                    "⚠️ LEVEL 2: Auto-alarm disabled, skipping alarm creation"
                )
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
                        Logger.d(
                            LogTags.MAINTENANCE_L2,
                            "✅ LEVEL 2: System alarm scheduled for: ${newAlarm.shiftName}"
                        )
                    } catch (e: Exception) {
                        Logger.e(
                            LogTags.MAINTENANCE_L2,
                            "❌ LEVEL 2: Failed to schedule system alarm for: ${newAlarm.shiftName}",
                            e
                        )
                    }
                }

                Logger.business(
                    LogTags.MAINTENANCE_L2,
                    "✅ LEVEL 2 SUCCESS: Created $successfulSystemAlarms new alarms automatically!",
                    "Total future alarms: ${futureAlarms.size + successfulSystemAlarms}"
                )

                AlarmMaintenanceResult.Success(successfulSystemAlarms, futureAlarms.size)
            } else {
                Logger.w(
                    LogTags.MAINTENANCE_L2,
                    "❌ LEVEL 2: Failed to create alarms",
                    createResult.exceptionOrNull()
                )
                AlarmMaintenanceResult.Failure(
                    createResult.exceptionOrNull() ?: Exception("Unknown alarm creation error")
                )
            }

        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE_L2,
                "❌ LEVEL 2: Critical error during scheduled maintenance",
                e
            )
            AlarmMaintenanceResult.Failure(e)
        }
    }

    /**
     * Performs the actual token refresh with comprehensive error handling
     * Returns internal TokenRefreshResult for proper type handling
     * 
     * 🔧 PHASE 1 ENHANCED: Now includes proactive health check before refresh attempt
     * 🔧 STUFE 3 ENHANCED: Detects token revocation and triggers UI state update
     */
    private suspend fun performTokenRefresh(): TokenRefreshResult {
        return try {
            Logger.d(LogTags.TOKEN, "🔍 Starting token validation and refresh")

            val refreshStartTime = System.currentTimeMillis()

            // 🔧 PHASE 1: Proactive health check BEFORE attempting refresh
            val healthCheckResult = performTokenHealthCheck()
            if (healthCheckResult.isFailure) {
                val healthError = healthCheckResult.exceptionOrNull() 
                    ?: Exception("Health check failed")
                Logger.e(LogTags.TOKEN, "❌ PHASE-1: Token health check failed - cannot refresh", healthError)
                
                // Send notification to user about re-auth requirement
                sendReAuthNotification(healthError.message ?: "Unknown reason")
                
                // 🔧 STUFE 3: Mark that re-authorization is needed
                return TokenRefreshResult.Failure(
                    error = healthError,
                    isRetryable = false, // No point retrying without email
                    needsReauth = true
                )
            }
            
            Logger.business(LogTags.TOKEN, "✅ PHASE-1: Token health check passed - proceeding with refresh")

            // Get current token status first
            val tokenStatus = tokenRefreshUseCase.getTokenStatus()
            Logger.d(LogTags.TOKEN, "📊 Current token status: $tokenStatus")

            val kotlinResult: KotlinResult<String> = when (tokenStatus) {
                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.NoToken -> {
                    Logger.w(LogTags.TOKEN, "❌ No token available - user needs to re-authenticate")
                    
                    // 🔧 STUFE 3: Trigger UI state update for no token
                    markTokenAsInvalid("No token available")
                    
                    KotlinResult.failure(Exception("No authentication token available"))
                }

                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.ExpiredNotRefreshable -> {
                    Logger.w(
                        LogTags.TOKEN,
                        "❌ Token expired and not refreshable - user re-auth required"
                    )
                    
                    // 🔧 STUFE 3: Trigger UI state update for expired token
                    markTokenAsInvalid("Token expired and not refreshable")
                    
                    KotlinResult.failure(Exception("Token expired - re-authentication required"))
                }

                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.Valid -> {
                    if (tokenStatus.remainingMinutes < TOKEN_EXPIRY_BUFFER_MINUTES) {
                        Logger.d(
                            LogTags.TOKEN,
                            "⏰ Token expiring soon (${tokenStatus.remainingMinutes}min) - refreshing"
                        )
                        tokenRefreshUseCase.forceRefresh()
                    } else {
                        Logger.d(
                            LogTags.TOKEN,
                            "✅ Token still valid (${tokenStatus.remainingMinutes}min remaining)"
                        )
                        tokenRefreshUseCase.ensureValidToken()
                    }
                }

                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.ExpiredButRefreshable -> {
                    Logger.d(LogTags.TOKEN, "🔄 Token expired but refreshable - attempting refresh")
                    val refreshResult =
                        tokenRefreshUseCase.refreshIfExpiringSoon(0) // Force refresh
                    if (refreshResult.isSuccess) {
                        tokenRefreshUseCase.ensureValidToken()
                    } else {
                        // 🔧 STUFE 3: Check if refresh failed due to revocation
                        val refreshError = refreshResult.exceptionOrNull()
                        if (isTokenRevocationError(refreshError)) {
                            Logger.e(LogTags.TOKEN, "🔴 STUFE-3: Token refresh failed - token revoked", refreshError)
                            markTokenAsInvalid("Token was revoked")
                            sendReAuthNotification("Your Calendar authorization was revoked by Google")
                        }
                        
                        refreshResult.map { "Token refreshed successfully" }
                    }
                }

                is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.usecase.TokenStatus.Error -> {
                    Logger.e(LogTags.TOKEN, "❌ Token status error", tokenStatus.exception)
                    
                    // 🔧 STUFE 3: Check if error indicates token revocation
                    if (isTokenRevocationError(tokenStatus.exception)) {
                        Logger.e(LogTags.TOKEN, "🔴 STUFE-3: Token status error due to revocation")
                        markTokenAsInvalid("Token error - possibly revoked")
                        sendReAuthNotification("Calendar access needs to be re-authorized")
                    }
                    
                    // Try to get a valid token anyway
                    tokenRefreshUseCase.ensureValidToken()
                }
            }

            val refreshDuration = System.currentTimeMillis() - refreshStartTime

            // Convert Kotlin Result to internal TokenRefreshResult
            if (kotlinResult.isSuccess) {
                TokenRefreshResult.Success(refreshDuration)
            } else {
                val error =
                    kotlinResult.exceptionOrNull() ?: Exception("Unknown token refresh error")
                
                // 🔧 STUFE 3: Detect if this is a revocation error
                val needsReauth = isTokenRevocationError(error)
                
                if (needsReauth) {
                    Logger.e(LogTags.TOKEN, "🔴 STUFE-3: Token refresh failed permanently - revocation detected", error)
                    markTokenAsInvalid("Token refresh failed - revocation")
                    sendReAuthNotification("Calendar authorization lost - please re-authorize")
                }
                
                val isRetryable = shouldRetryError(error) && !needsReauth
                TokenRefreshResult.Failure(error, isRetryable, needsReauth)
            }

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error during token refresh", e)
            
            // 🔧 STUFE 3: Check for revocation in exception
            val needsReauth = isTokenRevocationError(e)
            if (needsReauth) {
                markTokenAsInvalid("Unexpected error - possible revocation")
                sendReAuthNotification("Calendar access error - re-authorization needed")
            }
            
            val isRetryable = shouldRetryError(e) && !needsReauth
            TokenRefreshResult.Failure(e, isRetryable, needsReauth)
        }
    }

    /**
     * Schedules the next refresh based on current result
     */
    private fun scheduleNextRefresh(wasSuccessful: Boolean) {
        if (!wasSuccessful) {
            // Schedule more frequent checks when refresh fails
            Logger.w(
                LogTags.MAINTENANCE_L2,
                "📅 LEVEL 2: Scheduling frequent checks due to recent failure"
            )
            scheduleUrgentTokenRefresh(applicationContext)
        } else {
            Logger.d(
                LogTags.MAINTENANCE_L2,
                "📅 LEVEL 2: Session successful - maintaining normal 6h schedule"
            )
            // Normal scheduling will continue with the periodic work
        }
    }

    /**
     * 🔧 STUFE 3: Detects if an error indicates token revocation
     * 
     * Checks for specific error messages that indicate the token was revoked:
     * - "invalid_grant" from Google OAuth
     * - "authorization expired" or "authorization was revoked"
     * - "Token has been expired or revoked"
     * 
     * @return true if error indicates token revocation, false otherwise
     */
    private fun isTokenRevocationError(error: Throwable?): Boolean {
        if (error == null) return false
        
        val errorMessage = error.message?.lowercase() ?: return false
        
        return errorMessage.contains("invalid_grant") ||
               errorMessage.contains("authorization expired") ||
               errorMessage.contains("authorization was revoked") ||
               errorMessage.contains("token has been expired or revoked") ||
               errorMessage.contains("token was revoked") ||
               error is com.github.f1rlefanz.cf_alarmfortimeoffice.auth.TokenException.AuthorizationExpired
    }
    
    /**
     * 🔧 STUFE 3: Marks token as invalid in UI state by updating AuthDataStore
     * 
     * This triggers the UI to show the re-authorization card to the user.
     * The CalendarOperationState.hasValidToken will be set to false, which
     * activates the needsTokenReauthorization flag.
     * 
     * @param reason Human-readable reason for marking token as invalid
     */
    private suspend fun markTokenAsInvalid(reason: String) {
        try {
            Logger.business(LogTags.TOKEN, "🔧 STUFE-3: Marking token as invalid in UI state - Reason: $reason")
            
            // Clear access token in AuthDataStore to trigger hasValidToken = false
            val currentAuthData = appContainer.authDataStoreRepository.getCurrentAuthData().getOrNull()
            
            if (currentAuthData != null) {
                val updatedAuthData = currentAuthData.copy(
                    accessToken = null // Clear token to trigger hasValidToken = false
                )
                
                appContainer.authDataStoreRepository.updateAuthData(updatedAuthData)
                
                Logger.business(LogTags.TOKEN, "✅ STUFE-3: UI state updated - hasValidToken=false, user will see re-auth card")
            } else {
                Logger.w(LogTags.TOKEN, "⚠️ STUFE-3: No auth data found to update")
            }
            
            // Also update SharedPreferences to be safe
            val prefs = applicationContext.getSharedPreferences("cf_alarm_auth", Context.MODE_PRIVATE)
            prefs.edit {
                putBoolean("token_valid", false)
                putString("token_invalid_reason", reason)
                putLong("token_invalid_timestamp", System.currentTimeMillis())
            }
            
            Logger.business(LogTags.TOKEN, "✅ STUFE-3: Token marked as invalid in both AuthDataStore and SharedPreferences")
            
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ STUFE-3: Failed to mark token as invalid", e)
        }
    }
    
    /**
     * Determines if an error should trigger a retry
     */
    private fun shouldRetryError(error: Throwable?): Boolean {
        return when {
            error == null -> false
            
            // 🔧 STUFE 3: Never retry token revocation errors
            isTokenRevocationError(error) -> false

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
            .putBoolean(
                "is_oneplus_device",
                android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            )
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
            .putBoolean(
                "is_oneplus_device",
                android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            )
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
            .putBoolean(
                "is_oneplus_device",
                android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            )
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
            .putBoolean(
                "is_oneplus_device",
                android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            )
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
            .putBoolean(
                "is_oneplus_device",
                android.os.Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
            )
            .build()
    }
}
