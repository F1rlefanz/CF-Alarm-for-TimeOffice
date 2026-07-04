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
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.R
import com.github.f1rlefanz.cf_alarmfortimeoffice.auth.manager.OAuth2TokenManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Hilt EntryPoint that lets [AlarmMaintenanceService.getLastMaintenanceTime] (a static/companion
 * helper called from Compose UI, e.g. SettingsTabContent, without an AlarmMaintenanceService
 * instance) reach the Hilt-managed [MainDataStore]. Same pattern as HueSmartSchedulerEntryPoint /
 * HueBridgeConnectionManagerEntryPoint.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AlarmMaintenanceEntryPoint {
    @MainDataStore
    fun mainDataStore(): DataStore<Preferences>
}

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
    @Inject @MainDataStore lateinit var mainDataStore: DataStore<Preferences>

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "alarm_maintenance"
        private const val NOTIFICATION_ID = 1001
        private const val MAINTENANCE_ALARM_REQUEST_CODE = 9999
        private const val MAINTENANCE_INTERVAL_HOURS = 6L
        private const val MIN_BUFFER_DAYS = 7

        // CONSOLIDATION: moved off the standalone "cf_alarm_prefs" SharedPreferences file
        // and into the existing Hilt @MainDataStore.
        // NO MIGRATION: only the developer uses the app right now, so the old SharedPreferences
        // value is intentionally left behind - losing the last-maintenance timestamp once is harmless.
        private val KEY_LAST_MAINTENANCE = longPreferencesKey("last_maintenance_time")

        /**
         * Gets the timestamp of the last successful maintenance.
         *
         * Suspends to read the @MainDataStore (resolved via Hilt EntryPoint, since this is a
         * static helper called without a Service instance - e.g. from SettingsTabContent's
         * LaunchedEffect). Callers must invoke this from a coroutine.
         */
        suspend fun getLastMaintenanceTime(context: Context): Long {
            val dataStore = EntryPointAccessors
                .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                .mainDataStore()
            return dataStore.data.first()[KEY_LAST_MAINTENANCE] ?: 0L
        }

        /**
         * Stamps "now" as the last sync time.
         *
         * Wird zusätzlich aus dem VORDERGRUND-Sync (CalendarViewModel → syncAlarms) aufgerufen,
         * damit "Letzter Sync" den tatsächlich letzten Sync widerspiegelt und nicht nur den
         * 6h-Hintergrundlauf. Ein alter Wert bleibt damit das ehrliche Signal "seit X nichts
         * synchronisiert" (weder Vordergrund noch Hintergrund).
         */
        suspend fun recordSyncTime(context: Context) {
            val dataStore = EntryPointAccessors
                .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                .mainDataStore()
            dataStore.edit { it[KEY_LAST_MAINTENANCE] = System.currentTimeMillis() }
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
                "⏰ Next maintenance scheduled for ${Date(triggerTime)}"
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
     * Saves the current time as last maintenance timestamp in @MainDataStore.
     * Instance method (not companion) since the service already has the injected
     * mainDataStore available - no need to re-resolve it via EntryPoint.
     */
    private suspend fun saveMaintenanceTime() {
        mainDataStore.edit { prefs ->
            prefs[KEY_LAST_MAINTENANCE] = System.currentTimeMillis()
        }
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
            saveMaintenanceTime()
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
            saveMaintenanceTime()
            return
        }
        
        // STEP 5: ALARM CREATION
        Logger.d(LogTags.MAINTENANCE, "Step 5: Alarm creation")
        
        val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
        
        if (shiftConfig?.autoAlarmEnabled != true) {
            Logger.d(LogTags.MAINTENANCE, "Auto-alarm disabled, skipping")
            return
        }
        
        // Delta-Sync erwartet den VOLLSTAENDIGEN Soll-Zustand. Nur die neu erkannten Schichten
        // zu uebergeben wuerde alle bereits geplanten Wecker loeschen, deren Event nicht in der
        // Teilliste steht (syncAlarms entfernt Alarme ohne passendes Event). Die
        // newShifts-Pruefung oben dient nur der Entscheidung, OB ueberhaupt synchronisiert wird.
        // Orchestrator: syncAlarms setzt die System-Alarme INTERN (Delta-Sync + idempotentes
        // Re-Arming). Kein separates scheduleSystemAlarm mehr noetig (frueher: Doppel-Scheduling).
        val syncResult = alarmUseCase.syncAlarms(events, shiftConfig)

        if (syncResult.isSuccess) {
            val syncedAlarms = syncResult.getOrThrow()
            val duration = System.currentTimeMillis() - startTime
            Logger.business(
                LogTags.MAINTENANCE,
                "✅ Maintenance completed: ${syncedAlarms.size} alarms in sync in ${duration}ms"
            )
            saveMaintenanceTime()
            scheduleNextAlarm()
        } else {
            Logger.e(LogTags.MAINTENANCE, "Alarm sync failed", syncResult.exceptionOrNull())
            scheduleNextAlarm()
        }
    }
    
    /**
     * Schedules the next maintenance run in 6 hours
     */
    private fun scheduleNextAlarm() {
        val context = applicationContext
        val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
        
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
