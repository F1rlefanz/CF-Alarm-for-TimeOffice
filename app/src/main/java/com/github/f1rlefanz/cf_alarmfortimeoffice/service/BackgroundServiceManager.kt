package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val context: Context
) {

    private val preferences =
        context.getSharedPreferences("background_services", Context.MODE_PRIVATE)

    /**
     * Initializes background services - PHASE 1 MIGRATION
     * BackgroundTokenRefreshWorker removed, replaced by AlarmMaintenanceService
     */
    fun initializeBackgroundServices() {
        Logger.business(
            LogTags.TOKEN,
            "🚀 Initializing background services (Phase 1 Migration - Worker removed)"
        )

        try {
            // Mark services as started
            preferences.edit {
                putLong("services_started_at", System.currentTimeMillis())
                putString("device_info", "${Build.MANUFACTURER} ${Build.MODEL}")
                putString("version", "Phase1-ExactAlarm")
            }

            Logger.business(
                LogTags.TOKEN,
                "✅ Background services initialized (AlarmMaintenanceService via AuthViewModel)"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Failed to initialize background services", e)
        }
    }
    
    /**
     * Initializes AlarmMaintenanceService with first maintenance run
     * Called after successful login/authorization
     */
    fun initializeMaintenanceService() {
        Logger.business(LogTags.MAINTENANCE, "🚀 Initializing AlarmMaintenanceService")
        
        try {
            // Start service which will schedule itself
            AlarmMaintenanceService.start(context)
            
            Logger.business(LogTags.MAINTENANCE, "✅ AlarmMaintenanceService initialized")
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ Failed to initialize maintenance service", e)
        }
    }
    
    /**
     * PHASE 1 MIGRATION: Schedules initial AlarmMaintenanceService after login
     * This replaces the old BackgroundTokenRefreshWorker
     */
    fun scheduleInitialAlarmMaintenance() {
        Logger.business(LogTags.MAINTENANCE, "📅 INITIAL ALARM: Scheduling first maintenance run")
        
        try {
            // Schedule the next maintenance run
            AlarmMaintenanceService.scheduleNext(context)
            
            // Store initial scheduling info
            preferences.edit {
                putLong("initial_alarm_scheduled", System.currentTimeMillis())
                putBoolean("alarm_maintenance_enabled", true)
            }
            
            Logger.business(LogTags.MAINTENANCE, "✅ INITIAL ALARM: First maintenance run scheduled")
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE, "❌ INITIAL ALARM: Failed to schedule maintenance", e)
        }
    }

    /**
     * Handles alarm failure events
     */
    @Suppress("unused") // Public API - may be used in future alarm failure handling
    fun onAlarmFailureDetected(alarmId: Int, failureReason: String) {
        Logger.business(
            LogTags.ALARM,
            "🚨 Alarm failure detected",
            "ID: $alarmId, Reason: $failureReason"
        )

        // Log failure for tracking
        preferences.edit {
            putLong("last_alarm_failure", System.currentTimeMillis())
            putString("last_failure_reason", failureReason)
        }
    }

    /**
     * Stops all background services - PHASE 1 MIGRATION
     * AlarmMaintenanceService stops automatically, no manual cancellation needed
     */
    @Suppress("unused") // Public API - used for cleanup on logout or app reset
    fun stopAllBackgroundServices() {
        try {
            Logger.business(LogTags.TOKEN, "🛑 Background services cleanup (Phase 1)")
        } catch (e: Exception) {
            Logger.e(LogTags.TOKEN, "❌ Error stopping background services", e)
        }
    }
}
