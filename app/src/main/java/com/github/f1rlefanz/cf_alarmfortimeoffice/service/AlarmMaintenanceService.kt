package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.Date
import javax.inject.Inject

/**
 * AlarmMaintenanceService - Short-Lived Foreground Service
 * 
 * ARCHITECTURE (Briefing 4.0):
 * - Runs as Foreground Service (visible notification for 10-30 seconds)
 * - Triggered by Exact Alarm every 6 hours
 * - Performs: Token Refresh → Health Check → Event Loading → Alarm Creation
 * - Self-Healing: Schedules next run before stopping
 * 
 * HEALTH CHECK (v3.0 - Time-based):
 * - Checks last scheduled alarm's trigger time
 * - Loads events only if buffer < 7 days
 * - Adaptive to shift frequency
 * 
 * LIFECYCLE:
 * 1. BroadcastReceiver starts service
 * 2. startForeground() - notification appears
 * 3. performMaintenance() in coroutine
 * 4. stopSelf() - notification disappears
 * 5. Service returns START_NOT_STICKY
 */
@AndroidEntryPoint
class AlarmMaintenanceService : Service() {
    
    @Inject lateinit var tokenManager: OAuth2TokenManager
    @Inject lateinit var alarmUseCase: IAlarmUseCase
    @Inject lateinit var calendarUseCase: ICalendarUseCase
    @Inject lateinit var shiftUseCase: IShiftUseCase
    @Inject lateinit var calendarSelectionRepository: CalendarSelectionRepository
    @Inject lateinit var shiftRecognitionEngine: ShiftRecognitionEngine
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "alarm_maintenance"
        private const val NOTIFICATION_ID = 1001
        private const val MAINTENANCE_ALARM_REQUEST_CODE = 9999
        private const val MAINTENANCE_INTERVAL_HOURS = 6L
        private const val MIN_BUFFER_DAYS = 7
        // PHASE 2 CLEANUP: Using global constant from CalendarConstants
        
        private const val PREFS_NAME = "cf_alarm_prefs"
        private const val KEY_LAST_MAINTENANCE = "last_maintenance_time"
        
        /**
         * Gets the timestamp of the last successful maintenance
         */
        fun getLastMaintenanceTime(context: Context): Long {
            return context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getLong(KEY_LAST_MAINTENANCE, 0L)
        }
        
        /**
         * Saves the current time as last maintenance timestamp
         */
        private fun saveMaintenanceTime(context: Context) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit {
                putLong(KEY_LAST_MAINTENANCE, System.currentTimeMillis())
            }
        }
        
        /**
         * Starts the maintenance service
         */
        fun start(context: Context) {
            val intent = Intent(context, AlarmMaintenanceService::class.java)
            context.startForegroundService(intent)
        }
        
        /**
         * Schedules next maintenance run via AlarmManager
         */
        fun scheduleNext(context: Context) {
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            
            val triggerTime = System.currentTimeMillis() + 
                TimeUnit.HOURS.toMillis(MAINTENANCE_INTERVAL_HOURS)
            
            val intent = Intent(context, AlarmMaintenanceBroadcastReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            // Use setExactAndAllowWhileIdle for guaranteed execution
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
            
            Logger.business(
                LogTags.MAINTENANCE,
                "⏰ Next maintenance scheduled for ${java.util.Date(triggerTime)}"
            )
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Logger.d(LogTags.MAINTENANCE, "AlarmMaintenanceService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.business(LogTags.MAINTENANCE, "🔧 Maintenance service started")
        
        // Create notification and start foreground
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)
        
        // Perform maintenance in background
        serviceScope.launch {
            try {
                performMaintenance()
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE, "Maintenance failed with exception", e)
            } finally {
                // Always schedule next run and stop service
                scheduleNext(applicationContext)
                stopSelf()
            }
        }
        
        // Don't restart if killed by system
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Logger.d(LogTags.MAINTENANCE, "AlarmMaintenanceService destroyed")
    }
    
    /**
     * Main maintenance logic
     * 
     * STEPS:
     * 1. Token Refresh (2-5s)
     * 2. Health Check - Time-based (1s)
     * 3. Event Loading if needed (5-10s)
     * 4. Shift Recognition (1-2s)
     * 5. Alarm Creation (1-2s)
     */
    private suspend fun performMaintenance() {
        val startTime = System.currentTimeMillis()
        Logger.business(LogTags.MAINTENANCE, "🔧 Starting maintenance cycle")
        
        // STEP 1: TOKEN REFRESH
        Logger.d(LogTags.MAINTENANCE, "Step 1: Token refresh")
        val tokenResult = tokenManager.getValidToken()
        
        if (tokenResult.isFailure) {
            Logger.e(LogTags.MAINTENANCE, "Token refresh failed, aborting maintenance")
            return
        }
        
        Logger.d(LogTags.MAINTENANCE, "✅ Token valid")
        
        // STEP 2: HEALTH CHECK (Time-based v3.0)
        Logger.d(LogTags.MAINTENANCE, "Step 2: Health check (time-based)")
        
        val allAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
        val now = System.currentTimeMillis()
        val futureAlarms = allAlarms.filter { it.triggerTime > now }
        
        Logger.d(LogTags.MAINTENANCE, "Found ${futureAlarms.size} future alarms")
        
        val needsLoad = if (futureAlarms.isEmpty()) {
            Logger.d(LogTags.MAINTENANCE, "No future alarms, needs load")
            true
        } else {
            val lastAlarmTime = futureAlarms.maxOf { it.triggerTime }
            val bufferMillis = lastAlarmTime - now
            val bufferDays = TimeUnit.MILLISECONDS.toDays(bufferMillis)
            
            Logger.d(LogTags.MAINTENANCE, "Last alarm in $bufferDays days")
            
            if (bufferDays < MIN_BUFFER_DAYS) {
                Logger.d(LogTags.MAINTENANCE, "Buffer < $MIN_BUFFER_DAYS days, needs load")
                true
            } else {
                Logger.d(LogTags.MAINTENANCE, "Buffer sufficient ($bufferDays days), skipping")
                false
            }
        }
        
        if (!needsLoad) {
            val duration = System.currentTimeMillis() - startTime
            Logger.business(
                LogTags.MAINTENANCE,
                "✅ Maintenance completed (skipped, buffer sufficient) in ${duration}ms"
            )
            saveMaintenanceTime(applicationContext)
            return
        }
        
        // STEP 3: EVENT LOADING
        Logger.d(LogTags.MAINTENANCE, "Step 3: Loading events")
        
        val selectedCalendars = calendarSelectionRepository.getCurrentSelectedCalendarIds()
            .getOrNull() ?: emptySet()
        
        if (selectedCalendars.isEmpty()) {
            Logger.w(LogTags.MAINTENANCE, "No calendars selected, skipping")
            return
        }
        
        // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
        val eventsResult = calendarUseCase.getCalendarEventsWithCache(
            calendarIds = selectedCalendars,
            forceRefresh = false
        )
        
        if (eventsResult.isFailure) {
            Logger.e(LogTags.MAINTENANCE, "Event loading failed", eventsResult.exceptionOrNull())
            scheduleNextAlarm()
            return
        }
        
        val events = eventsResult.getOrThrow()
        Logger.d(LogTags.MAINTENANCE, "Loaded ${events.size} events")
        
        // STEP 4: SHIFT RECOGNITION
        Logger.d(LogTags.MAINTENANCE, "Step 4: Shift recognition")
        
        val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(events)
        
        val newShifts = shiftMatches.filter { match ->
            // Use pre-calculated alarm time from ShiftMatch
            val alarmTimeMillis = match.calculatedAlarmTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            
            alarmTimeMillis > now && futureAlarms.none { alarm ->
                // Check if alarm already exists for this event
                alarm.eventId == match.calendarEvent.id
            }
        }
        
        Logger.d(LogTags.MAINTENANCE, "Found ${newShifts.size} new shifts to schedule")
        
        if (newShifts.isEmpty()) {
            val duration = System.currentTimeMillis() - startTime
            Logger.business(
                LogTags.MAINTENANCE,
                "✅ Maintenance completed (no new shifts) in ${duration}ms"
            )
            saveMaintenanceTime(applicationContext)
            return
        }
        
        // STEP 5: ALARM CREATION
        Logger.d(LogTags.MAINTENANCE, "Step 5: Alarm creation")
        
        val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
        
        if (shiftConfig?.autoAlarmEnabled != true) {
            Logger.d(LogTags.MAINTENANCE, "Auto-alarm disabled, skipping")
            return
        }
        
        val newEvents = newShifts.map { it.calendarEvent }
        val createResult = alarmUseCase.createAlarmsFromEvents(newEvents, shiftConfig)
        
        if (createResult.isSuccess) {
            val newAlarms = createResult.getOrThrow()
            
            var successCount = 0
            var failCount = 0
            
            newAlarms.forEach { alarm ->
                try {
                    alarmUseCase.scheduleSystemAlarm(alarm)
                    successCount++
                    Logger.d(LogTags.MAINTENANCE, "✅ Alarm scheduled: ${alarm.shiftName}")
                } catch (e: Exception) {
                    failCount++
                    Logger.e(LogTags.MAINTENANCE, "Failed to schedule: ${alarm.shiftName}", e)
                }
            }
            
            val duration = System.currentTimeMillis() - startTime
            Logger.business(
                LogTags.MAINTENANCE,
                "✅ Maintenance completed: $successCount alarms scheduled, $failCount failed in ${duration}ms"
            )
            saveMaintenanceTime(applicationContext)
            scheduleNextAlarm()
        } else {
            Logger.e(LogTags.MAINTENANCE, "Alarm creation failed", createResult.exceptionOrNull())
            scheduleNextAlarm()
        }
    }
    
    /**
     * Schedules the next maintenance run in 6 hours
     */
    private fun scheduleNextAlarm() {
        val context = applicationContext
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        val triggerTime = System.currentTimeMillis() + (6 * 60 * 60 * 1000L) // 6 hours
        
        val intent = Intent(context, AlarmMaintenanceBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            MAINTENANCE_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
        
        Logger.d(LogTags.MAINTENANCE, "Next maintenance scheduled for ${Date(triggerTime)}")
    }
    
    /**
     * Creates notification for foreground service
     */
    private fun createNotification(): Notification {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Alarm-Wartung",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Hintergrund-Synchronisation der Wecker"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("CF Alarm Wartung")
            .setContentText("Wecker werden aktualisiert...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSound(null)
            .setVibrate(null)
            .build()
    }
}
