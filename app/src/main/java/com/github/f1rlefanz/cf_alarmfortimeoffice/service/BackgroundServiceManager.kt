package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.os.Build
import androidx.work.*
import androidx.work.CoroutineWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.worker.BackgroundTokenRefreshWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background Service Manager - MEMORY LEAK FIXED VERSION (v2.1)
 *
 * FIXED CRITICAL MEMORY LEAK:
 * ✅ Replaced problematic Singleton pattern with Hilt Dependency Injection
 * ✅ Eliminated static Context references that could cause memory leaks
 * ✅ Proper lifecycle management through Hilt SingletonComponent
 * ✅ Thread-safe without volatile variables or manual synchronization
 *
 * ARCHITECTURE:
 * - Focus on reliable core functionality
 * - Background token refresh worker management
 * - Clean service lifecycle management
 * - Simple alarm failure handling
 *
 * DEPENDENCY INJECTION:
 * - @ApplicationContext ensures proper Context scoping
 * - @Singleton provides single instance without memory leak risks
 * - Hilt manages lifecycle automatically
 *
 * Philosophy: If the service works (and it does!), keep it simple and secure.
 *
 * @author CF-Alarm Development Team
 * @since Memory Leak Fix v2.1 (August 2025)
 */
@Singleton
class BackgroundServiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val workManager = WorkManager.getInstance(context)
    private val preferences =
        context.getSharedPreferences("background_services", Context.MODE_PRIVATE)

    /**
     * Initializes background services
     */
    fun initializeBackgroundServices() {
        Logger.business(
            LogTags.TOKEN,
            "🚀 Initializing background services (Memory Leak Fixed v2.1)"
        )

        try {
            // Start token refresh service
            BackgroundTokenRefreshWorker.scheduleTokenRefresh(context)

            // Mark services as started
            preferences.edit()
                .putLong("services_started_at", System.currentTimeMillis())
                .putString("device_info", "${Build.MANUFACTURER} ${Build.MODEL}")
                .putString("version", "v2.1-memory-leak-fixed")
                .apply()

            Logger.business(
                LogTags.TOKEN,
                "✅ Background services initialized successfully - Simple, reliable, and memory-safe!"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to initialize background services", e)
        }
    }

    /**
     * Triggers urgent token refresh
     */
    fun triggerUrgentTokenRefresh() {
        try {
            BackgroundTokenRefreshWorker.scheduleUrgentTokenRefresh(context)
            Logger.business(LogTags.TOKEN, "⚡ Urgent token refresh triggered")
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to trigger urgent token refresh", e)
        }
    }

    /**
     * Handles alarm failure events
     */
    fun onAlarmFailureDetected(alarmId: Int, failureReason: String) {
        Logger.business(
            LogTags.ALARM,
            "🚨 Alarm failure detected",
            "ID: $alarmId, Reason: $failureReason"
        )

        // Trigger urgent token refresh in case it was an auth issue
        triggerUrgentTokenRefresh()

        // Log failure for tracking
        preferences.edit()
            .putLong("last_alarm_failure", System.currentTimeMillis())
            .putString("last_failure_reason", failureReason)
            .apply()
    }

    /**
     * Stops all background services
     */
    fun stopAllBackgroundServices() {
        try {
            BackgroundTokenRefreshWorker.cancelTokenRefresh(context)

            Logger.business(LogTags.TOKEN, "🛑 All background services stopped")
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error stopping background services", e)
        }
    }
}
