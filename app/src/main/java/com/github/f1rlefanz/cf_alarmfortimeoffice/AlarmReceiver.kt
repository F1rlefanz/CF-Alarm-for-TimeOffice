package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.random.Random

/**
 * Enhanced BroadcastReceiver with Smart Maintenance Chain Level 1 integration.
 * 
 * CORE FEATURES:
 * - Reliable wake lock management
 * - Android 14+ Full-Screen Intent compatibility  
 * - Enhanced notification with high priority
 * - 🎨 HUE INTEGRATION: Automatic light control based on shift patterns
 * - 🔄 SMART MAINTENANCE CHAIN Level 1: Opportunistic alarm checking
 * 
 * NEW: Smart Maintenance Chain Level 1 - Opportunistic Alarm Prüfung
 * Nach jedem Alarm wird intelligent geprüft, ob ausreichend zukünftige Alarme vorhanden sind.
 * Falls nicht, werden automatisch neue Alarme geplant - stromsparend als Piggyback-Operation.
 * 
 * Philosophy: If the alarm works (and it does!), keep it simple + add intelligent maintenance.
 */
class AlarmReceiver : BroadcastReceiver() {
    
    companion object {
        const val EXTRA_SHIFT_NAME = "shift_name"
        const val EXTRA_SHIFT_TIME = "shift_time"
        const val EXTRA_ALARM_ID = "alarm_id"
        
        private const val CHANNEL_ID = "shift_alarm_channel"
        private const val NOTIFICATION_ID = 2001
        private const val WAKE_LOCK_TAG = "CFAlarm:WakeLock"
        private const val WAKE_LOCK_TIMEOUT = 60000L // 1 Minute
        
        // 🔄 SMART MAINTENANCE CHAIN Level 1 Configuration
        private const val MINIMUM_FUTURE_ALARMS = 3
        private const val EXTENDED_LOOKAHEAD_DAYS = 21L  // 3 Wochen statt 7 Tage
        private const val OPPORTUNISTIC_CHECK_PROBABILITY = 0.8f  // 80% der Alarme prüfen
        
        // Log Tags für Smart Maintenance
        private const val TAG_MAINTENANCE = LogTags.MAINTENANCE_L1
    }
    
    // Coroutine Scope für Background-Operations
    private val maintenanceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        val shiftName = intent.getStringExtra(EXTRA_SHIFT_NAME) ?: "Schicht"
        
        // CRITICAL: Skip-Check VOR Alarm-Trigger
        val appContainer = (context.applicationContext as CFAlarmApplication).appContainer
        val skipUseCase = appContainer.alarmSkipUseCase
        
        // Skip-Check durchführen
        try {
            val skipResult = runBlocking { 
                skipUseCase.checkAndProcessSkip(alarmId) 
            }
            
            when (skipResult.getOrNull()) {
                SkipProcessResult.ALARM_SKIPPED -> {
                    Logger.business(LogTags.ALARM_RECEIVER, "⏭️ Alarm $alarmId ($shiftName) SKIPPED by user")
                    showSkipNotification(context, shiftName)
                    return // EARLY RETURN: Alarm nicht ausführen
                }
                SkipProcessResult.ALARM_EXECUTED -> {
                    Logger.business(LogTags.ALARM_RECEIVER, "✅ Alarm $alarmId ($shiftName) EXECUTED normally")
                    // Continue with normal alarm logic below
                }
                null -> {
                    Logger.w(LogTags.ALARM_RECEIVER, "Skip check failed, executing alarm normally")
                    // Continue with normal alarm logic
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "Error during skip check, executing alarm normally", e)
            // Continue with normal alarm logic
        }
        
        // Existing alarm logic continues here...
        Logger.business(LogTags.ALARM_RECEIVER, "📱 ALARM TRIGGERED! Shift: $shiftName")
        
        // 🎨 NEW: HUE INTEGRATION - Execute matching light rules
        executeHueRulesForAlarm(context, shiftName)
        
        // Wake Lock to ensure device wakes up
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            WAKE_LOCK_TAG
        ).apply {
            acquire(WAKE_LOCK_TIMEOUT)
        }
        
        try {
            val shiftTime = intent.getStringExtra(EXTRA_SHIFT_TIME) ?: ""
            
            // Create notification channel (only needed once)
            createNotificationChannel(context)
            
            // Show alarm notification with sound
            showAlarmNotification(context, shiftName, shiftTime, alarmId)
            
            // Start full-screen alarm activity
            showFullScreenAlarm(context, shiftName, shiftTime, alarmId)
            
            Logger.business(LogTags.ALARM_RECEIVER, "✅ Alarm $alarmId for $shiftName triggered successfully!")
            
            // 🔄 NEW: SMART MAINTENANCE CHAIN Level 1 - Opportunistic Alarm Check
            performOpportunisticAlarmMaintenance(context, alarmId, shiftName)
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "❌ Error handling alarm", e)
        } finally {
            // Release wake lock
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
    
    /**
     * 🔄 SMART MAINTENANCE CHAIN Level 1: Opportunistic Alarm Maintenance
     * 
     * Wird nach jedem Alarm ausgeführt (als Piggyback-Operation).
     * Prüft intelligent, ob genügend zukünftige Alarme vorhanden sind.
     * Falls nicht, plant automatisch neue Alarme mit erweiterter Vorausschau.
     * 
     * STROMSPAREND: Läuft als Piggyback ohne zusätzlichen Wake-up
     * INTELLIGENT: Probabilistische Prüfung (80% der Alarme)
     * RESILIENT: Fehler brechen den Hauptalarm nicht ab
     */
    private fun performOpportunisticAlarmMaintenance(context: Context, alarmId: Int, shiftName: String) {
        // Probabilistische Prüfung - nicht bei jedem Alarm (stromsparen)
        if (Random.nextFloat() > OPPORTUNISTIC_CHECK_PROBABILITY) {
            Logger.d(TAG_MAINTENANCE, "🎲 Skipping opportunistic check (probabilistic)")
            return
        }
        
        Logger.business(TAG_MAINTENANCE, "🔄 OPPORTUNISTIC: Starting maintenance check after alarm $alarmId")
        
        // Background-Coroutine to avoid blocking the alarm
        maintenanceScope.launch {
            try {
                val appContainer = (context.applicationContext as CFAlarmApplication).appContainer
                val alarmUseCase = appContainer.alarmUseCase
                val calendarUseCase = appContainer.calendarUseCase
                val shiftRecognitionEngine = appContainer.shiftRecognitionEngine
                
                // 1. Analysiere aktuelle Alarm-Situation
                val currentAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
                val futureAlarms = currentAlarms.filter { 
                    it.triggerTime > System.currentTimeMillis() 
                }
                
                Logger.business(
                    TAG_MAINTENANCE, 
                    "📊 OPPORTUNISTIC ANALYSIS: ${futureAlarms.size} future alarms found"
                )
                
                // 2. Prüfe ob neue Alarme benötigt werden
                if (futureAlarms.size >= MINIMUM_FUTURE_ALARMS) {
                    Logger.d(TAG_MAINTENANCE, "✅ OPPORTUNISTIC: Sufficient alarms (${futureAlarms.size} >= $MINIMUM_FUTURE_ALARMS)")
                    return@launch
                }
                
                Logger.business(
                    TAG_MAINTENANCE, 
                    "🔄 OPPORTUNISTIC: Need more alarms! Found ${futureAlarms.size}, need $MINIMUM_FUTURE_ALARMS"
                )
                
                // 3. Hole erweiterte Kalenderdaten (21 Tage statt 7)
                // Bekomme zuerst die ausgewählten Kalender-IDs
                val calendarSelectionRepository = appContainer.calendarSelectionRepository
                val selectedCalendarIds = calendarSelectionRepository.selectedCalendarIds.first()
                
                if (selectedCalendarIds.isEmpty()) {
                    Logger.w(TAG_MAINTENANCE, "⚠️ OPPORTUNISTIC: No calendars selected, skipping maintenance")
                    return@launch
                }
                
                val extendedStartDate = LocalDateTime.now()
                val extendedEndDate = extendedStartDate.plusDays(EXTENDED_LOOKAHEAD_DAYS)
                
                Logger.d(TAG_MAINTENANCE, "🔍 EXTENDED LOOKAHEAD: Scanning $EXTENDED_LOOKAHEAD_DAYS days ahead for ${selectedCalendarIds.size} calendars")
                
                val extendedEventsResult = calendarUseCase.getCalendarEventsWithCache(
                    calendarIds = selectedCalendarIds,
                    daysAhead = EXTENDED_LOOKAHEAD_DAYS.toInt(),
                    forceRefresh = false
                )
                
                if (extendedEventsResult.isFailure) {
                    Logger.w(TAG_MAINTENANCE, "❌ OPPORTUNISTIC: Failed to get extended calendar events", extendedEventsResult.exceptionOrNull())
                    return@launch
                }
                
                val extendedEvents = extendedEventsResult.getOrNull() ?: emptyList()
                Logger.business(TAG_MAINTENANCE, "📅 EXTENDED SCAN: Found ${extendedEvents.size} events in extended range")
                
                // 4. Erkenne neue Schichten und erstelle Alarme
                val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(extendedEvents)
                val newShiftMatches = shiftMatches.filter { shiftMatch ->
                    // Nur zukünftige Schichten, die noch keinen Alarm haben
                    val alarmTime = shiftMatch.calculatedAlarmTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    alarmTime > System.currentTimeMillis() && 
                    !futureAlarms.any { existingAlarm -> 
                        Math.abs(existingAlarm.triggerTime - alarmTime) < 60 * 1000 // 1 Minute Toleranz
                    }
                }
                
                Logger.business(
                    TAG_MAINTENANCE, 
                    "🆕 OPPORTUNISTIC: Found ${newShiftMatches.size} new shifts to schedule"
                )
                
                if (newShiftMatches.isEmpty()) {
                    Logger.d(TAG_MAINTENANCE, "💡 OPPORTUNISTIC: No new shifts found to schedule")
                    return@launch
                }
                
                // 5. Erstelle neue Alarme
                val shiftConfig = appContainer.shiftUseCase.getCurrentShiftConfig().getOrNull()
                if (shiftConfig == null || !shiftConfig.autoAlarmEnabled) {
                    Logger.d(TAG_MAINTENANCE, "⚠️ OPPORTUNISTIC: Auto-alarm disabled, skipping alarm creation")
                    return@launch
                }
                
                // Erstelle Events-Liste für den AlarmUseCase
                val newEvents = newShiftMatches.map { it.calendarEvent }
                val createResult = alarmUseCase.createAlarmsFromEvents(newEvents, shiftConfig)
                
                if (createResult.isSuccess) {
                    val createdAlarms = createResult.getOrNull() ?: emptyList()
                    Logger.business(
                        TAG_MAINTENANCE, 
                        "✅ OPPORTUNISTIC SUCCESS: Created ${createdAlarms.size} new alarms automatically!"
                    )
                    
                    // 6. System-Alarme setzen
                    for (newAlarm in createdAlarms) {
                        try {
                            alarmUseCase.scheduleSystemAlarm(newAlarm)
                            Logger.d(TAG_MAINTENANCE, "✅ System alarm scheduled for: ${newAlarm.shiftName}")
                        } catch (e: Exception) {
                            Logger.e(TAG_MAINTENANCE, "❌ Failed to schedule system alarm for: ${newAlarm.shiftName}", e)
                        }
                    }
                } else {
                    Logger.w(TAG_MAINTENANCE, "❌ OPPORTUNISTIC: Failed to create alarms", createResult.exceptionOrNull())
                }
                
            } catch (e: Exception) {
                // KRITISCH: Niemals den Hauptalarm crashen lassen wegen Maintenance-Fehlern
                Logger.e(TAG_MAINTENANCE, "❌ OPPORTUNISTIC: Critical error during maintenance (alarm still worked!)", e)
            }
        }
    }
    
    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Schicht-Wecker",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Benachrichtigungen für Schicht-Alarme"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
            setBypassDnd(true) // Bypass "Do Not Disturb" mode
        }
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        Logger.d(LogTags.ALARM_RECEIVER, "✅ Notification channel created")
    }
    
    private fun showAlarmNotification(context: Context, shiftName: String, shiftTime: String, alarmId: Int) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Intent to open the full-screen activity
            val fullScreenIntent = Intent(context, AlarmFullScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("shift_name", shiftName)
                putExtra("alarm_time", shiftTime)
                putExtra("alarm_id", alarmId)
                putExtra("triggered_via", "full_screen_notification")
            }
            
            // Android 14+ ENHANCEMENT: Modern Full-Screen Intent with ActivityOptions
            val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ (API 34+): Use ActivityOptions for enhanced reliability
                val activityOptions = ActivityOptions.makeBasic().apply {
                    pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                
                PendingIntent.getActivity(
                    context,
                    alarmId,
                    fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    activityOptions.toBundle()
                )
            } else {
                // Pre-Android 14: Traditional approach
                PendingIntent.getActivity(
                    context,
                    alarmId,
                    fullScreenIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            
            // Enhanced alarm sound configuration
            val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            
            // Enhanced notification: Maximum priority and visibility for alarm reliability
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ Zeit für $shiftName!")
                .setContentText("Deine Schicht beginnt um $shiftTime")
                .setPriority(NotificationCompat.PRIORITY_MAX) // Highest priority
                .setCategory(NotificationCompat.CATEGORY_ALARM) // Alarm category for system recognition
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setSound(alarmSound)
                .setVibrate(longArrayOf(0, 1000, 500, 1000, 500, 1000))
                .setFullScreenIntent(pendingIntent, true) // Critical: Full-screen notification
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Show on lock screen
                .setOngoing(true) // Can't be dismissed by swiping
                .setDefaults(NotificationCompat.DEFAULT_ALL) // Use all system defaults
                .setTimeoutAfter(5 * 60 * 1000) // Auto-dismiss after 5 minutes
                .build()
            
            notificationManager.notify(NOTIFICATION_ID, notification)
            Logger.business(LogTags.ALARM_RECEIVER, "✅ Enhanced alarm notification displayed with Android ${Build.VERSION.SDK_INT} compatibility")
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "❌ Error showing notification", e)
            // Fallback: Try to start activity directly if notification fails
            showFullScreenAlarm(context, shiftName, shiftTime, alarmId)
        }
    }
    
    private fun showFullScreenAlarm(context: Context, shiftName: String, shiftTime: String, alarmId: Int) {
        try {
            val fullScreenIntent = Intent(context, AlarmFullScreenActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or 
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS // Exclude from recent apps
                putExtra("shift_name", shiftName)
                putExtra("alarm_time", shiftTime)
                putExtra("alarm_id", alarmId)
                putExtra("triggered_via", "direct_activity_start")
                setPackage(context.packageName)
            }
            
            // Enhanced: Try to start activity with modern approach
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+: Enhanced activity start options
                val activityOptions = ActivityOptions.makeBasic().apply {
                    // These options improve reliability on Android 14+
                    pendingIntentCreatorBackgroundActivityStartMode = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
                context.startActivity(fullScreenIntent, activityOptions.toBundle())
                Logger.business(LogTags.ALARM_RECEIVER, "✅ Full-screen alarm activity started with Android 14+ enhancements")
            } else {
                context.startActivity(fullScreenIntent)
                Logger.business(LogTags.ALARM_RECEIVER, "✅ Full-screen alarm activity started")
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "❌ Error starting full-screen activity", e)
        }
    }

    private fun showSkipNotification(context: Context, shiftName: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val notification = NotificationCompat.Builder(context, "skip_channel")
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle("Alarm übersprungen")
                .setContentText("$shiftName-Alarm wurde wie gewünscht übersprungen")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setTimeoutAfter(30000) // 30 Sekunden
                .build()
            
            notificationManager.notify(9999, notification)
            Logger.business(LogTags.ALARM_RECEIVER, "✅ Skip notification shown")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_RECEIVER, "Error showing skip notification", e)
        }
    }
    
    /**
     * 🎨 HUE INTEGRATION: Execute matching light rules for alarm
     * 
     * Creates a synthetic ShiftMatch from available alarm data and executes
     * any applicable Hue rules configured for this shift pattern.
     */
    private fun executeHueRulesForAlarm(context: Context, shiftName: String) {
        try {
            // Get AppContainer for Hue services
            val appContainer = (context.applicationContext as CFAlarmApplication).appContainer
            val hueRuleUseCase = appContainer.hueRuleUseCase
            val shiftUseCase = appContainer.shiftUseCase
            
            Logger.business(LogTags.ALARM_RECEIVER, "🎨 Starting Hue rule execution for shift: $shiftName")
            
            // Execute in background coroutine to avoid blocking the alarm
            runBlocking {
                try {
                    // Try to find matching shift definition
                    val shiftConfigResult = shiftUseCase.getCurrentShiftConfig()
                    
                    if (shiftConfigResult.isSuccess) {
                        val shiftConfig = shiftConfigResult.getOrNull()
                        val matchingShiftDef = shiftConfig?.definitions?.find { shiftDef ->
                            // Match by name or keywords
                            shiftDef.name.equals(shiftName, ignoreCase = true) ||
                            shiftDef.keywords.any { keyword -> 
                                shiftName.contains(keyword, ignoreCase = true) ||
                                keyword.contains(shiftName, ignoreCase = true)
                            }
                        }
                        
                        if (matchingShiftDef != null) {
                            // Create synthetic ShiftMatch for Hue rules
                            val syntheticShiftMatch = createSyntheticShiftMatch(
                                shiftDefinition = matchingShiftDef,
                                shiftName = shiftName
                            )
                            
                            // Execute Hue rules for this shift
                            val currentTime = LocalTime.now()
                            val executionResult = hueRuleUseCase.executeRulesForAlarm(
                                shift = syntheticShiftMatch,
                                alarmTime = currentTime
                            )
                            
                            if (executionResult.isSuccess) {
                                val result = executionResult.getOrNull()
                                if (result != null && result.rulesExecuted > 0) {
                                    Logger.business(
                                        LogTags.ALARM_RECEIVER, 
                                        "🎨✅ Hue rules executed successfully: ${result.rulesExecuted} rules, " +
                                        "${result.successfulActions}/${result.actionsExecuted} actions successful"
                                    )
                                    
                                    if (result.errors.isNotEmpty()) {
                                        Logger.w(LogTags.ALARM_RECEIVER, "🎨⚠️ Some Hue actions failed: ${result.errors}")
                                    }
                                } else {
                                    Logger.d(LogTags.ALARM_RECEIVER, "🎨💡 No Hue rules configured for shift: $shiftName")
                                }
                            } else {
                                Logger.w(LogTags.ALARM_RECEIVER, "🎨❌ Hue rule execution failed", executionResult.exceptionOrNull())
                            }
                        } else {
                            Logger.d(LogTags.ALARM_RECEIVER, "🎨💡 No shift definition found for: $shiftName (skipping Hue rules)")
                        }
                    } else {
                        Logger.w(LogTags.ALARM_RECEIVER, "🎨⚠️ Could not load shift configuration for Hue rules", shiftConfigResult.exceptionOrNull())
                    }
                    
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_RECEIVER, "🎨❌ Exception during Hue rule execution", e)
                }
            }
            
        } catch (e: Exception) {
            // Don't let Hue errors crash the alarm
            Logger.e(LogTags.ALARM_RECEIVER, "🎨❌ Critical error in Hue integration", e)
        }
    }
    
    /**
     * Creates a synthetic ShiftMatch from available alarm data
     * 
     * Since the AlarmReceiver doesn't have access to the original ShiftMatch,
     * we reconstruct the essential information needed for Hue rule execution.
     */
    private fun createSyntheticShiftMatch(
        shiftDefinition: ShiftDefinition,
        shiftName: String
    ): ShiftMatch {
        val now = LocalDateTime.now()
        
        // Create synthetic calendar event
        val syntheticCalendarEvent = CalendarEvent(
            id = "synthetic_$shiftName",
            title = shiftName,
            startTime = now,
            endTime = now.plusHours(8), // Assume 8-hour shift
            calendarId = "synthetic",
            isAllDay = false
        )
        
        // Calculate synthetic alarm time (now, since the alarm just triggered)
        val calculatedAlarmTime = now
        
        return ShiftMatch(
            shiftDefinition = shiftDefinition,
            calendarEvent = syntheticCalendarEvent,
            calculatedAlarmTime = calculatedAlarmTime
        )
    }
}
