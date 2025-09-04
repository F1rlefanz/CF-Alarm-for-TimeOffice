package com.github.f1rlefanz.cf_alarmfortimeoffice.service.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.github.f1rlefanz.cf_alarmfortimeoffice.CFAlarmApplication
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import java.time.ZoneId
import kotlin.random.Random

/**
 * ⚡ SMART MAINTENANCE CHAIN Level 3: Calendar Content Observer
 * 
 * Real-time reaktive Komponente der Smart Maintenance Chain.
 * Reagiert sofort auf Kalender-Änderungen und löst intelligente Alarm-Updates aus.
 * 
 * STROMSPARENDSTE LÖSUNG:
 * - Nur aktiv bei echten Kalender-Änderungen (event-driven)
 * - Debouncing verhindert excessive Updates bei Batch-Änderungen
 * - Probabilistic execution für Battery-Optimierung
 * - Arbeitet mit bestehender Level 1 + Level 2 Infrastruktur zusammen
 * 
 * REAL-TIME RESPONSIVENESS:
 * - Minimale Latenz zwischen Kalender-Update und Alarm-Aktualisierung
 * - Intelligente Änderungserkennung mit relevance filtering
 * - Background coroutines verhindern UI-Blocking
 * 
 * INTEGRATION:
 * - Ergänzt Level 1 (opportunistic) und Level 2 (scheduled)
 * - Shared logic mit bestehenden Maintenance-Komponenten
 * - Graceful failure handling - andere Levels bleiben aktiv
 */
class CalendarContentObserver(
    private val context: Context
) : ContentObserver(Handler(Looper.getMainLooper())) {
    
    companion object {
        // ⚡ Level 3 Configuration
        private const val DEBOUNCE_DELAY_MS = 2000L  // 2 seconds debouncing
        private const val EXECUTION_PROBABILITY = 0.7f  // 70% execution rate for battery optimization
        private const val MINIMUM_FUTURE_ALARMS_L3 = 2  // Lower threshold for reactive updates
        private const val REACTIVE_LOOKAHEAD_DAYS = 14L  // 2 weeks for reactive updates
        private const val MAX_BATCH_PROCESSING_DELAY = 10000L  // Max 10 seconds for batch processing
        
        // URIs to observe
        val CALENDAR_EVENT_URI: Uri = CalendarContract.Events.CONTENT_URI
        val CALENDAR_INSTANCES_URI: Uri = CalendarContract.Instances.CONTENT_URI
        
        /**
         * Registriert den Calendar Content Observer beim System
         */
        fun register(context: Context): CalendarContentObserver {
            val observer = CalendarContentObserver(context)
            val contentResolver = context.contentResolver
            
            // Register for calendar events changes
            contentResolver.registerContentObserver(
                CALENDAR_EVENT_URI,
                true, // notifyForDescendants
                observer
            )
            
            // Register for calendar instances changes (recurring events)
            contentResolver.registerContentObserver(
                CALENDAR_INSTANCES_URI,
                true,
                observer
            )
            
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "⚡ LEVEL 3: Calendar Content Observer registered",
                "URIs: Events, Instances"
            )
            
            return observer
        }
        
        /**
         * Unregistriert den Calendar Content Observer
         */
        fun unregister(context: Context, observer: CalendarContentObserver) {
            context.contentResolver.unregisterContentObserver(observer)
            observer.cleanup()
            
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "🛑 LEVEL 3: Calendar Content Observer unregistered"
            )
        }
    }
    
    // Coroutine Scope für Background-Operations
    private val observerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Debouncing Job Management
    private var debounceJob: Job? = null
    private var lastChangeTime = 0L
    private var pendingChanges = 0
    
    override fun onChange(selfChange: Boolean) {
        onChange(selfChange, null)
    }
    
    override fun onChange(selfChange: Boolean, uri: Uri?) {
        val currentTime = System.currentTimeMillis()
        lastChangeTime = currentTime
        pendingChanges++
        
        Logger.d(
            LogTags.MAINTENANCE_L3,
            "⚡ LEVEL 3: Calendar change detected",
            "URI: $uri, SelfChange: $selfChange, PendingChanges: $pendingChanges"
        )
        
        // Cancel previous debounce job and start new one
        debounceJob?.cancel()
        debounceJob = observerScope.launch {
            try {
                // Debouncing: Wait for changes to settle
                delay(DEBOUNCE_DELAY_MS)
                
                // Check if more recent changes occurred during debounce
                if (System.currentTimeMillis() - lastChangeTime >= DEBOUNCE_DELAY_MS) {
                    performReactiveAlarmMaintenance(uri, pendingChanges)
                    pendingChanges = 0  // Reset after processing
                } else {
                    Logger.d(LogTags.MAINTENANCE_L3, "⚡ LEVEL 3: More recent changes detected, extending debounce")
                }
                
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Error in debounced change processing", e)
            }
        }
    }
    
    /**
     * ⚡ SMART MAINTENANCE CHAIN Level 3: Reactive Alarm Maintenance
     * 
     * Triggered by actual calendar changes for maximum efficiency.
     * Uses lower thresholds and shorter lookahead than scheduled maintenance.
     * Complements Level 1 + Level 2 for complete coverage.
     */
    private suspend fun performReactiveAlarmMaintenance(uri: Uri?, changeCount: Int) {
        // Probabilistic execution for battery optimization
        if (Random.nextFloat() > EXECUTION_PROBABILITY) {
            Logger.d(LogTags.MAINTENANCE_L3, "🎲 LEVEL 3: Skipping reactive check (probabilistic battery optimization)")
            return
        }
        
        Logger.business(
            LogTags.MAINTENANCE_L3,
            "⚡ LEVEL 3: Starting reactive alarm maintenance",
            "URI: $uri, Changes: $changeCount"
        )
        
        try {
            val appContainer = (context.applicationContext as CFAlarmApplication).appContainer
            val alarmUseCase = appContainer.alarmUseCase
            val calendarUseCase = appContainer.calendarUseCase
            val calendarSelectionRepository = appContainer.calendarSelectionRepository
            val shiftUseCase = appContainer.shiftUseCase
            val shiftRecognitionEngine = appContainer.shiftRecognitionEngine
            
            // 1. Quick analysis of current alarm situation
            val currentAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
            val futureAlarms = currentAlarms.filter { 
                it.triggerTime > System.currentTimeMillis() 
            }
            
            Logger.d(
                LogTags.MAINTENANCE_L3,
                "📊 LEVEL 3 ANALYSIS: ${futureAlarms.size} future alarms found"
            )
            
            // 2. Check if reactive maintenance is needed (lower threshold than Level 2)
            if (futureAlarms.size >= MINIMUM_FUTURE_ALARMS_L3 && changeCount <= 3) {
                Logger.d(LogTags.MAINTENANCE_L3, "✅ LEVEL 3: Sufficient alarms for minor changes (${futureAlarms.size} >= $MINIMUM_FUTURE_ALARMS_L3)")
                return
            }
            
            // 3. For significant changes or low alarm count, proceed with maintenance
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "🔄 LEVEL 3: Reactive maintenance needed",
                "Alarms: ${futureAlarms.size}, Changes: $changeCount"
            )
            
            // 4. Get selected calendars
            val selectedCalendarIds = calendarSelectionRepository.selectedCalendarIds.first()
            if (selectedCalendarIds.isEmpty()) {
                Logger.w(LogTags.MAINTENANCE_L3, "⚠️ LEVEL 3: No calendars selected, skipping reactive maintenance")
                return
            }
            
            // 5. Reactive calendar scan (shorter than Level 2 for efficiency)
            Logger.d(LogTags.MAINTENANCE_L3, "🔍 LEVEL 3 REACTIVE SCAN: ${REACTIVE_LOOKAHEAD_DAYS} days ahead for ${selectedCalendarIds.size} calendars")
            
            val reactiveEventsResult = calendarUseCase.getCalendarEventsWithCache(
                calendarIds = selectedCalendarIds,
                daysAhead = REACTIVE_LOOKAHEAD_DAYS.toInt(),
                forceRefresh = true // Force refresh to get latest changes
            )
            
            if (reactiveEventsResult.isFailure) {
                Logger.w(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Failed to get reactive calendar events", reactiveEventsResult.exceptionOrNull())
                return
            }
            
            val reactiveEvents = reactiveEventsResult.getOrNull() ?: emptyList()
            Logger.business(LogTags.MAINTENANCE_L3, "📅 LEVEL 3 SCAN: Found ${reactiveEvents.size} events in reactive range")
            
            // 6. Recognize shifts and identify new alarms needed
            val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(reactiveEvents)
            val newShiftMatches = shiftMatches.filter { shiftMatch ->
                // Only future shifts that don't already have alarms
                val alarmTime = shiftMatch.calculatedAlarmTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                alarmTime > System.currentTimeMillis() && 
                !futureAlarms.any { existingAlarm -> 
                    Math.abs(existingAlarm.triggerTime - alarmTime) < 60 * 1000 // 1 minute tolerance
                }
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "🆕 LEVEL 3: Found ${newShiftMatches.size} new shifts to schedule reactively"
            )
            
            if (newShiftMatches.isEmpty()) {
                // Check if we need to remove any alarms (events might have been deleted)
                val orphanedAlarms = futureAlarms.filter { alarm ->
                    // Check if this alarm's corresponding event still exists
                    !reactiveEvents.any { event ->
                        val eventAlarmTime = shiftRecognitionEngine.getAllMatchingShifts(listOf(event))
                            .firstOrNull()?.calculatedAlarmTime
                            ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                        eventAlarmTime?.let { Math.abs(it - alarm.triggerTime) < 60 * 1000 } ?: false
                    }
                }
                
                if (orphanedAlarms.isNotEmpty()) {
                    Logger.business(
                        LogTags.MAINTENANCE_L3,
                        "🗑️ LEVEL 3: Found ${orphanedAlarms.size} orphaned alarms to clean up"
                    )
                    
                    // Clean up orphaned alarms
                    for (orphanedAlarm in orphanedAlarms) {
                        try {
                            alarmUseCase.deleteAlarm(orphanedAlarm.id)
                            Logger.d(LogTags.MAINTENANCE_L3, "✅ LEVEL 3: Cleaned up orphaned alarm: ${orphanedAlarm.shiftName}")
                        } catch (e: Exception) {
                            Logger.e(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Failed to clean up orphaned alarm: ${orphanedAlarm.shiftName}", e)
                        }
                    }
                } else {
                    Logger.d(LogTags.MAINTENANCE_L3, "💡 LEVEL 3: No new shifts or orphaned alarms - calendar changes were non-alarm-relevant")
                }
                return
            }
            
            // 7. Check if auto-alarm is enabled
            val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
            if (shiftConfig == null || !shiftConfig.autoAlarmEnabled) {
                Logger.d(LogTags.MAINTENANCE_L3, "⚠️ LEVEL 3: Auto-alarm disabled, skipping reactive alarm creation")
                return
            }
            
            // 8. Create new alarms reactively
            val newEvents = newShiftMatches.map { it.calendarEvent }
            val createResult = alarmUseCase.createAlarmsFromEvents(newEvents, shiftConfig)
            
            if (createResult.isSuccess) {
                val createdAlarms = createResult.getOrNull() ?: emptyList()
                
                // 9. Schedule system alarms
                var successfulSystemAlarms = 0
                for (newAlarm in createdAlarms) {
                    try {
                        alarmUseCase.scheduleSystemAlarm(newAlarm)
                        successfulSystemAlarms++
                        Logger.d(LogTags.MAINTENANCE_L3, "✅ LEVEL 3: Reactive system alarm scheduled: ${newAlarm.shiftName}")
                    } catch (e: Exception) {
                        Logger.e(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Failed to schedule reactive alarm: ${newAlarm.shiftName}", e)
                    }
                }
                
                Logger.business(
                    LogTags.MAINTENANCE_L3,
                    "✅ LEVEL 3 SUCCESS: Created $successfulSystemAlarms reactive alarms!",
                    "Total future alarms: ${futureAlarms.size + successfulSystemAlarms}, Response time: ~${DEBOUNCE_DELAY_MS}ms"
                )
            } else {
                Logger.w(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Failed to create reactive alarms", createResult.exceptionOrNull())
            }
            
        } catch (e: Exception) {
            // CRITICAL: Never let Level 3 errors affect other levels
            Logger.e(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Critical error during reactive maintenance (other levels still active)", e)
        }
    }
    
    /**
     * Cleanup resources when observer is no longer needed
     */
    fun cleanup() {
        debounceJob?.cancel()
        // observerScope will be cleaned up by GC since it uses SupervisorJob
        
        Logger.d(LogTags.MAINTENANCE_L3, "🧹 LEVEL 3: Observer cleanup completed")
    }
    
    /**
     * Get current observer statistics for debugging
     */
    fun getObserverStats(): String {
        return buildString {
            appendLine("=== LEVEL 3 CALENDAR OBSERVER STATS ===")
            appendLine("Last change: ${if (lastChangeTime > 0) java.time.Instant.ofEpochMilli(lastChangeTime) else "Never"}")
            appendLine("Pending changes: $pendingChanges")
            appendLine("Debounce job active: ${debounceJob?.isActive ?: false}")
            appendLine("Execution probability: ${(EXECUTION_PROBABILITY * 100).toInt()}%")
            appendLine("Reactive lookahead: $REACTIVE_LOOKAHEAD_DAYS days")
            appendLine("Minimum alarms threshold: $MINIMUM_FUTURE_ALARMS_L3")
            appendLine("Debounce delay: ${DEBOUNCE_DELAY_MS}ms")
            appendLine("===========================================")
        }
    }
}
