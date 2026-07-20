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
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.SimpleFileTree
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

        /**
         * Request-Code der 6h-Wartungskette. EINE Kette, EIN Slot — siehe [scheduleNext].
         *
         * 0 ist der Wert, den BootReceiver, BackgroundServiceManager und MainScreen schon immer
         * benutzt haben; die zweite Kette lief auf 9999 und ist weg.
         */
        private const val MAINTENANCE_ALARM_REQUEST_CODE = 0
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
         * Stellt den naechsten Wartungslauf (+6h) — der EINZIGE Planer der Kette.
         *
         * Frueher gab es einen zweiten: die Instanzmethode scheduleNextAlarm() stellte denselben
         * Alarm noch einmal, nur mit Request-Code 9999 statt 0. Verschiedene Request-Codes heissen
         * verschiedene PendingIntents, also ZWEI unabhaengige AlarmManager-Eintraege. Da der
         * finally-Block von [onStartCommand] ohnehin immer hier landet, plante jeder erfolgreiche
         * Lauf beide — Ergebnis: dauerhaft zwei Wartungszyklen alle 6h, im Abstand von
         * Millisekunden. Doppelte Google-Kalender-Abfragen, doppelte Arbeit, und genau die zwei
         * ueberlappenden Starts, gegen die stopSelf(startId) haerten musste.
         *
         * Die Pruefung auf canScheduleExactAlarms() stammt aus der geloeschten Methode und bleibt:
         * setExactAndAllowWhileIdle() wirft eine SecurityException, wenn die Berechtigung fehlt.
         * Ab API 33 traegt die App USE_EXACT_ALARM (bei Installation erteilt, nicht entziehbar),
         * auf 31/32 greift nur SCHEDULE_EXACT_ALARM — und das darf der Nutzer abschalten. Vorher
         * stand hier ein ungeprueftes setExactAndAllowWhileIdle().
         */
        fun scheduleNext(context: Context) {
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager

            val triggerTime = System.currentTimeMillis() +
                TimeUnit.HOURS.toMillis(MAINTENANCE_INTERVAL_HOURS)

            val intent = Intent(context, AlarmMaintenanceBroadcastReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                MAINTENANCE_ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Explizite SDK_INT-Verzweigung (nicht als ||-Kurzschluss): minSdk ist 26,
            // canScheduleExactAlarms() gibt es erst ab 31 - diese Form versteht der Lint sicher.
            val canBeExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            }

            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            } else {
                Logger.w(
                    LogTags.MAINTENANCE,
                    "Keine Berechtigung fuer exakte Alarme - Wartung laeuft ungenau (setAndAllowWhileIdle)"
                )
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

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

                // stopSelf(startId) statt stopSelf(): Android stoppt damit nur, wenn seit diesem
                // Start kein weiterer kam. Wird der Service zweimal gestartet (z.B. der 6h-Alarm
                // faellt mit einem Start nach der Autorisierung zusammen), laufen zwei Zyklen auf
                // demselben serviceScope. Das blanke stopSelf() des ERSTEN, der fertig wurde,
                // loeste onDestroy() -> serviceScope.cancel() aus und riss den zweiten mitten in
                // der Arbeit ab (JobCancellationException, Log 14.07. 22:07:30). Fuer eine
                // Wecker-App ist das gefaehrlich: der abgeschnittene Zyklus koennte gerade
                // Alarme angelegt haben. Mit startId raeumt erst der letzte Zyklus den Service ab.
                stopSelf(startId)
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

        // STEP 0: LOG RETENTION - Kaltstart allein ist nicht zuverlaessig genug fuer einen
        // lange laufenden Prozess (SimpleFileTree.init{} feuert dann nie erneut). Eigener
        // try/catch, damit ein Fehler hier niemals die eigentliche Wartung abbricht.
        try {
            applicationContext.getExternalFilesDir(null)?.let { SimpleFileTree.cleanupOldLogs(it) }
        } catch (e: Exception) {
            Logger.w(LogTags.FILE_SYSTEM, "Log-Bereinigung uebersprungen", e)
        }

        // STEP 1: TOKEN REFRESH
        Logger.d(LogTags.MAINTENANCE, "Step 1: Token refresh")
        val tokenResult = tokenManager.getValidToken()
        
        if (tokenResult.isFailure) {
            Logger.e(LogTags.MAINTENANCE, "Token refresh failed, aborting maintenance")
            showActionRequiredNotification(
                title = "Anmeldung erforderlich",
                message = "Dein Kalender kann nicht synchronisiert werden. Bitte öffne die App und melde dich an."
            )
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
            showActionRequiredNotification(
                title = "Keine Kalender ausgewählt",
                message = "Es wurden keine Kalender für die Synchronisation ausgewählt. Bitte öffne die App und wähle die gewünschten Kalender aus."
            )
            return
        }
        
        // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
        val eventsResult = calendarUseCase.getCalendarEventsWithCache(
            calendarIds = selectedCalendars,
            forceRefresh = false
        )
        
        if (eventsResult.isFailure) {
            Logger.e(LogTags.MAINTENANCE, "Event loading failed", eventsResult.exceptionOrNull())
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
        } else {
            Logger.e(LogTags.MAINTENANCE, "Alarm sync failed", syncResult.exceptionOrNull())
        }
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
    
    /**
     * Shows a user-facing notification indicating action is required (e.g. login or calendar selection)
     */
    private fun showActionRequiredNotification(title: String, message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        
        val channelId = "alarm_maintenance_alerts"
        val channel = NotificationChannel(
            channelId,
            "Wartungshinweise",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Wichtige Hinweise zur Wecker-Synchronisation"
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
        
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                applicationContext,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
            
        notificationManager.notify(1002, notification)
    }
}
