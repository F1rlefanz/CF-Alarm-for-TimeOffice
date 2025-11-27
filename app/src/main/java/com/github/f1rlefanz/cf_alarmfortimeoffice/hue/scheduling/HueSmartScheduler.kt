package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.DailySchedulePlanningWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.GenericHealthCheckWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers.PreAlarmHealthCheckWorker
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration
import java.time.Duration as JavaDuration

/**
 * PHASE 2: Smart Scheduler for Hue Bridge Health Checks
 * 
 * EFFICIENCY GOALS:
 * - Health checks only before actual alarm times
 * - Integration with calendar/shift schedules  
 * - WorkManager for reliable background execution
 * - Further reduction: ~10-20 calls/day → ~5-15 calls/day
 * 
 * FEATURES:
 * - Pre-alarm health checks (10 minutes before)
 * - Calendar integration for next alarm prediction
 * - Adaptive scheduling based on user patterns
 * - Battery-optimized background tasks
 */
class HueSmartScheduler private constructor() {
    companion object {
        @Volatile
        private var INSTANCE: HueSmartScheduler? = null
        
        fun getInstance(context: Context): HueSmartScheduler {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HueSmartScheduler().also { 
                    INSTANCE = it
                    it.initialize(context.applicationContext)
                }
            }
        }
        
        // Smart scheduling constants
        private const val PRE_ALARM_HEALTH_CHECK_WORK = "pre_alarm_health_check"
        private const val DAILY_SCHEDULE_WORK = "daily_schedule_planning"
        private val PRE_ALARM_CHECK_WINDOW = 10.minutes
        private const val MAX_LOOKAHEAD_DAYS = 7
    }
    
    private lateinit var workManager: WorkManager
    
    /**
     * Initialize with application context (called once by getInstance)
     */
    private fun initialize(context: Context) {
        workManager = WorkManager.getInstance(context)
    }
    
    /**
     * MAIN API: Initialize smart scheduling system
     */
    fun initializeSmartScheduling() {
        if (!::workManager.isInitialized) {
            Logger.w(LogTags.HUE_BRIDGE, "⚠️ SMART-SCHEDULER: WorkManager not initialized, scheduler not ready")
            return
        }
        
        Logger.i(LogTags.HUE_BRIDGE, "🧠 SMART-SCHEDULER: Initializing intelligent health check scheduling")
        
        // Schedule daily planning work
        scheduleDailyPlanning()
        
        // Initial schedule calculation
        CoroutineScope(Dispatchers.IO).launch {
            try {
                calculateAndScheduleNextHealthChecks()
            } catch (e: Exception) {
                Logger.e(LogTags.HUE_BRIDGE, "Failed to initialize smart scheduling", e)
            }
        }
        
        Logger.i(LogTags.HUE_BRIDGE, "✅ SMART-SCHEDULER: Smart scheduling initialized")
    }
    
    /**
     * OPTIMIZATION: Calculate next alarm times and schedule health checks accordingly
     */
    suspend fun calculateAndScheduleNextHealthChecks() = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_BRIDGE, "🔮 SMART-SCHEDULER: Calculating next alarm times for health check scheduling")
        
        try {
            // Get next alarm times from various sources
            val nextAlarmTimes = getNextAlarmTimes()
            
            if (nextAlarmTimes.isEmpty()) {
                Logger.d(LogTags.HUE_BRIDGE, "📅 SMART-SCHEDULER: No upcoming alarms found, using fallback schedule")
                scheduleGenericHealthChecks()
                return@withContext
            }
            
            // Cancel existing scheduled health checks
            workManager.cancelUniqueWork(PRE_ALARM_HEALTH_CHECK_WORK)
            
            // Schedule health checks for each upcoming alarm
            nextAlarmTimes.forEachIndexed { index, alarmTime ->
                schedulePreAlarmHealthCheck(alarmTime, index)
            }
            
            Logger.i(LogTags.HUE_BRIDGE, "✅ SMART-SCHEDULER: Scheduled ${nextAlarmTimes.size} pre-alarm health checks")
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ SMART-SCHEDULER: Failed to calculate alarm schedule", e)
            scheduleGenericHealthChecks() // Fallback
        }
    }
    
    /**
     * CORE FEATURE: Get next alarm times from multiple sources
     */
    private suspend fun getNextAlarmTimes(): List<LocalDateTime> = withContext(Dispatchers.IO) {
        val alarmTimes = mutableListOf<LocalDateTime>()
        
        try {
            // Source 1: Android System Alarms (actual set alarms - requires permission)
            val systemAlarms = getSystemAlarmTimes()
            alarmTimes.addAll(systemAlarms)
            
            // Source 2: Shift-based predictions from ShiftViewModel (fallback)
            val shiftAlarms = getShiftBasedAlarmTimes()
            alarmTimes.addAll(shiftAlarms)
            
            // Filter and sort
            val now = LocalDateTime.now()
            val maxTime = now.plusDays(MAX_LOOKAHEAD_DAYS.toLong())
            
            return@withContext alarmTimes
                .filter { it.isAfter(now) && it.isBefore(maxTime) }
                .distinct()
                .sorted()
                .take(10) // Limit to next 10 alarms
                
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to get alarm times", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * DATA SOURCE: Android system alarms (CRITICAL: should use actual set alarms)
     * TODO: Integrate with Android AlarmManager to read actual scheduled alarms
     * Currently returns empty - using shift-based fallback
     */
    private suspend fun getSystemAlarmTimes(): List<LocalDateTime> = withContext(Dispatchers.IO) {
        return@withContext emptyList()
    }
    
    /**
     * DATA SOURCE: Shift-based alarm predictions (FALLBACK: uses hardcoded patterns)
     * TODO: Integrate with ShiftViewModel to read user's actual shift configuration
     * Currently uses hardcoded times as fallback
     */
    private suspend fun getShiftBasedAlarmTimes(): List<LocalDateTime> = withContext(Dispatchers.IO) {
        try {
            // Fallback: hardcoded shift patterns (temporary during development)
            val alarms = mutableListOf<LocalDateTime>()
            val now = LocalDateTime.now()
            
            // For now, implement basic shift pattern prediction
            // Common shift patterns: Early (5:00-6:30), Day (7:00-8:30), Late (14:00-15:30)
            val shiftStartTimes = listOf(
                5 to 0,   // 5:00 AM
                5 to 30,  // 5:30 AM  
                6 to 0,   // 6:00 AM
                6 to 30,  // 6:30 AM
                7 to 0,   // 7:00 AM
                7 to 30   // 7:30 AM
            )
            
            // Generate alarm times for next 7 days
            for (day in 1..7) {
                val baseDay = now.plusDays(day.toLong())
                
                // Only add early morning shifts (likely to use Hue lights)
                shiftStartTimes.filter { it.first <= 7 }.forEach { (hour, minute) ->
                    // Wake up 30-60 minutes before shift start
                    val wakeUpTime = baseDay
                        .withHour(hour)
                        .withMinute(minute)
                        .withSecond(0)
                        .withNano(0)
                        .minusMinutes(45) // 45 minutes before shift start
                    
                    if (wakeUpTime.isAfter(now)) {
                        alarms.add(wakeUpTime)
                    }
                }
            }
            
            Logger.d(LogTags.HUE_BRIDGE, "📋 SMART-SCHEDULER: Generated ${alarms.size} shift-based alarm predictions")
            return@withContext alarms.take(5) // Limit to next 5
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "Failed to get shift-based alarms", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * WORKMANAGER: Schedule pre-alarm health check
     */
    private fun schedulePreAlarmHealthCheck(alarmTime: LocalDateTime, index: Int) {
        val checkTime = alarmTime.minus(PRE_ALARM_CHECK_WINDOW.toJavaDuration())
        val now = LocalDateTime.now()
        
        if (checkTime.isBefore(now)) {
            Logger.d(LogTags.HUE_BRIDGE, "⏰ SMART-SCHEDULER: Skipping past alarm time: $alarmTime")
            return
        }
        
        val delayMillis = JavaDuration.between(now, checkTime).toMillis()
        
        val workRequest = OneTimeWorkRequestBuilder<PreAlarmHealthCheckWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(
                "alarm_time" to alarmTime.toString(),
                "check_index" to index
            ))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        
        workManager.enqueueUniqueWork(
            "${PRE_ALARM_HEALTH_CHECK_WORK}_$index",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        Logger.d(LogTags.HUE_BRIDGE, "⏰ SMART-SCHEDULER: Scheduled health check for $checkTime (${delayMillis/1000/60} minutes from now)")
    }
    
    /**
     * FALLBACK: Generic health checks when no alarms are found
     */
    private fun scheduleGenericHealthChecks() {
        Logger.d(LogTags.HUE_BRIDGE, "🔄 SMART-SCHEDULER: Using fallback generic health check schedule")
        
        // Schedule a health check every 6 hours as fallback
        val workRequest = PeriodicWorkRequestBuilder<GenericHealthCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            "generic_health_checks",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )
    }
    
    /**
     * DAILY PLANNING: Schedule daily recalculation of health checks
     */
    private fun scheduleDailyPlanning() {
        val dailyWork = PeriodicWorkRequestBuilder<DailySchedulePlanningWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            DAILY_SCHEDULE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWork
        )
        
        Logger.d(LogTags.HUE_BRIDGE, "📅 SMART-SCHEDULER: Daily planning work scheduled")
    }
    
    /**
     * PUBLIC API: Manual trigger for schedule recalculation
     */
    fun recalculateSchedule() {
        Logger.i(LogTags.HUE_BRIDGE, "🔄 SMART-SCHEDULER: Manual schedule recalculation triggered")
        
        CoroutineScope(Dispatchers.IO).launch {
            calculateAndScheduleNextHealthChecks()
        }
    }
    
    /**
     * CLEANUP: Cancel all scheduled work
     */
    fun cleanup() {
        if (::workManager.isInitialized) {
            workManager.cancelUniqueWork(PRE_ALARM_HEALTH_CHECK_WORK)
            workManager.cancelUniqueWork(DAILY_SCHEDULE_WORK)
            Logger.d(LogTags.HUE_BRIDGE, "🧹 SMART-SCHEDULER: Cleanup completed")
        }
    }
}
