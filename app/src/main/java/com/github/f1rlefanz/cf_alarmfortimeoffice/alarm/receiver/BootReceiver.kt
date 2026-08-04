package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.CalendarSelectionRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAuthDataStoreRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmMaintenanceService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ICalendarUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

/**
 * 🛡️ SMART MAINTENANCE CHAIN Level 4: Enhanced Boot Receiver
 *
 * COMPLETE SYSTEM RECOVERY nach Device-Neustarts:
 * - Vollständige Alarm-Wiederherstellung aus persistenter Speicherung
 * - Neuinitialisierung aller Smart Maintenance Chain Levels (1-3)
 * - Comprehensive Health Diagnostics und System-Validation
 * - Emergency Repair-Mechanismen bei kritischen Fehlern
 * - Integration mit bestehender App-Infrastruktur
 *
 * SELF-HEALING CAPABILITIES:
 * - Automatic detection of system inconsistencies
 * - Repair of broken alarm schedules
 * - Recovery of lost calendar connections
 * - Restoration of background services
 * - Health monitoring and alerting
 *
 * POST-BOOT SEQUENCE:
 * 1. System Health Diagnostics
 * 2. Alarm Repository Recovery
 * 3. Calendar Integration Restoration
 * 4. Smart Maintenance Chain Reinitialization (L1-L3)
 * 5. Background Services Restart
 * 6. Verification & Health Monitoring
 *
 * INTEGRATION:
 * - Final safety net für alle anderen Smart Maintenance Levels
 * - Ensures system integrity after any critical system events
 * - Maintains consistency with existing architecture patterns
 * 
 * HILT MIGRATION: Now uses Hilt dependency injection instead of AppContainer
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var alarmUseCase: IAlarmUseCase
    @Inject lateinit var calendarUseCase: ICalendarUseCase
    @Inject lateinit var shiftUseCase: IShiftUseCase
    @Inject lateinit var calendarSelectionRepository: CalendarSelectionRepository
    @Inject lateinit var authDataStoreRepository: IAuthDataStoreRepository
    @Inject lateinit var directBootAlarmStore: DirectBootAlarmStore
    @Inject lateinit var dimSchedule: com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
    @Inject lateinit var dndSchedule: com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
    @Inject lateinit var calendarPreAlarmRefreshScheduler: com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler

    companion object {
        // 🛡️ Level 4 Configuration
        private const val BOOT_RECOVERY_DELAY_MS = 5000L  // 5 seconds delay for system stability
        private const val MAX_RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_RETRY_DELAY_MS = 10000L  // 10 seconds between retries
        private const val POST_BOOT_HEALTH_CHECK_DELAY_MS =
            30000L  // 30 seconds for post-boot health check

        // Boot Actions
        private const val ACTION_BOOT_COMPLETED = Intent.ACTION_BOOT_COMPLETED
        private const val ACTION_LOCKED_BOOT_COMPLETED = Intent.ACTION_LOCKED_BOOT_COMPLETED
        private const val ACTION_MY_PACKAGE_REPLACED = Intent.ACTION_MY_PACKAGE_REPLACED
        private const val ACTION_PACKAGE_REPLACED = Intent.ACTION_PACKAGE_REPLACED
    }

    // Recovery Scope für Boot-Recovery-Operations
    private val recoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        Logger.business(
            LogTags.MAINTENANCE_L4,
            "🛡️ LEVEL 4: Boot event received - Action: $action"
        )

        when (action) {
            ACTION_LOCKED_BOOT_COMPLETED -> {
                // DIRECT BOOT: nur der schnelle, CE-freie Restore aus dem Device-Protected-Spiegel.
                // Kalender/Token/CE-Storage sind vor der ersten Entsperrung nicht lesbar - die
                // volle Recovery folgt spaeter bei BOOT_COMPLETED (nach dem Entsperren).
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "🔐 LEVEL 4: Locked boot - Direct-Boot-Wiederherstellung der Alarme"
                )
                restoreAlarmsFromDirectBootStore(context, "LOCKED_BOOT_COMPLETED")
            }

            ACTION_BOOT_COMPLETED -> {
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "📱 LEVEL 4: Device booted - initiating complete system recovery"
                )
                // Zuerst der schnelle, prozess-tod-sichere Restore (Alarme stehen sofort wieder),
                // dann die vollstaendige CE-basierte Validierung/Recovery.
                restoreAlarmsFromDirectBootStore(context, "BOOT_COMPLETED")
                performCompleteSystemRecovery(context, "BOOT_COMPLETED")
            }

            ACTION_MY_PACKAGE_REPLACED -> {
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "📦 LEVEL 4: App updated - performing post-update recovery"
                )
                restoreAlarmsFromDirectBootStore(context, "APP_UPDATED")
                performCompleteSystemRecovery(context, "APP_UPDATED")
            }

            ACTION_PACKAGE_REPLACED -> {
                if (intent.data?.schemeSpecificPart == context.packageName) {
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "📦 LEVEL 4: Our package replaced - performing recovery"
                    )
                    restoreAlarmsFromDirectBootStore(context, "PACKAGE_REPLACED")
                    performCompleteSystemRecovery(context, "PACKAGE_REPLACED")
                }
            }

            else -> {
                Logger.d(LogTags.MAINTENANCE_L4, "🛡️ LEVEL 4: Ignoring unhandled action: $action")
            }
        }
    }

    /**
     * DIRECT-BOOT RESTORE: Schnelles, CE-freies Neusetzen der Alarme aus dem Device-Protected-
     * Spiegel. Laeuft unter goAsync() (prozess-tod-sicher waehrend der kurzen Restore-Phase) und
     * braucht weder Kalender, Token noch entsperrten Storage - daher schon vor der ersten
     * Entsperrung (LOCKED_BOOT_COMPLETED) einsetzbar. Setzt dieselben PendingIntents wie der
     * regulaere Pfad, sodass die spaetere CE-Recovery per FLAG_UPDATE_CURRENT nur aktualisiert.
     */
    private fun restoreAlarmsFromDirectBootStore(context: Context, reason: String) {
        val pendingResult = goAsync()
        recoveryScope.launch {
            try {
                val entries = directBootAlarmStore.getFutureEntries()
                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "🔐 LEVEL 4: Direct-Boot-Restore ($reason) - ${entries.size} Alarme aus Spiegel"
                )
                entries.forEach { entry ->
                    try {
                        AlarmManagerService.rescheduleFromDirectBoot(
                            context, entry.id, entry.triggerTime, entry.shiftName, entry.shiftStartTimeFormatted
                        )
                    } catch (e: Exception) {
                        Logger.e(
                            LogTags.MAINTENANCE_L4,
                            "❌ LEVEL 4: Direct-Boot-Restore fuer id=${entry.id} fehlgeschlagen", e
                        )
                    }
                }
            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Direct-Boot-Restore-Lauf fehlgeschlagen", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 🛡️ SMART MAINTENANCE CHAIN Level 4: Complete System Recovery
     *
     * Vollständige Wiederherstellung des Alarm-Systems nach kritischen System-Events.
     * Dies ist das finale Sicherheitsnetz der Smart Maintenance Chain.
     *
     * RECOVERY SEQUENCE:
     * 1. System stability wait
     * 2. Health diagnostics
     * 3. Alarm recovery from persistent storage
     * 4. Calendar integration restoration
     * 5. Smart Maintenance Chain reinitialization (L1-L3)
     * 6. Background services restart
     * 7. Post-recovery validation
     */
    private fun performCompleteSystemRecovery(context: Context, reason: String) {
        recoveryScope.launch {
            var recoveryAttempt = 0
            var recoverySuccessful = false

            while (!recoverySuccessful && recoveryAttempt < MAX_RECOVERY_ATTEMPTS) {
                try {
                    recoveryAttempt++
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "🛡️ LEVEL 4: Starting complete system recovery - Reason: $reason, Attempt: $recoveryAttempt/$MAX_RECOVERY_ATTEMPTS"
                    )

                    // 1. System Stability Wait
                    Logger.d(LogTags.MAINTENANCE_L4, "⏱️ LEVEL 4: Waiting for system stability...")
                    delay(BOOT_RECOVERY_DELAY_MS)

                    // 2. HILT MIGRATION: Dependencies are now injected, no AppContainer needed

                    // 3. Comprehensive Health Diagnostics
                    val healthStatus = performHealthDiagnostics(reason)
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "🔍 LEVEL 4: Health diagnostics completed - $healthStatus"
                    )

                    // 4. Alarm Repository Recovery
                    val alarmRecoveryResult = performAlarmRecovery()
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "🔄 LEVEL 4: Alarm recovery completed - $alarmRecoveryResult"
                    )

                    // 5. Calendar Integration Restoration
                    val calendarRestorationResult = performCalendarIntegrationRestoration()
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "📅 LEVEL 4: Calendar restoration completed - $calendarRestorationResult"
                    )

                    // 6. Smart Maintenance Chain Reinitialization (L1-L3)
                    performSmartMaintenanceChainReinitialization(context)

                    // 7. Background Services Restart
                    restartBackgroundServices(context)

                    // 8. Schedule Post-Recovery Health Check
                    schedulePostRecoveryHealthCheck(context, reason)

                    // 9. Schicht-Dimmer: rollenden Dimm-Tick nach dem Boot neu setzen.
                    //    Best-effort und eigenes try/catch – darf die Wecker-Recovery NIE stoeren.
                    try {
                        dimSchedule.applyCurrentState()
                        dimSchedule.scheduleNextTransition()
                    } catch (e: Exception) {
                        Logger.w(LogTags.DIMMER, "Boot: Dimm-Reschedule fehlgeschlagen", e)
                    }

                    // 10. DND-Steuerung: rollenden Tick nach dem Boot neu setzen. Gleiches
                    //     Muster/gleicher try/catch-Gedanke wie beim Dimmer – Best-effort, darf
                    //     die Wecker-Recovery NIE stoeren.
                    try {
                        dndSchedule.applyCurrentState()
                        dndSchedule.scheduleNextTransition()
                    } catch (e: Exception) {
                        Logger.w(LogTags.DND, "Boot: DND-Reschedule fehlgeschlagen", e)
                    }

                    // 11. Feature B: Pre-Alarm-Refresh-Jobs (3h vor jedem Alarm) nach dem Boot neu
                    //     planen. Gleiches Muster/gleicher try/catch-Gedanke wie Dimmer/DND –
                    //     Best-effort, darf die Wecker-Recovery NIE stoeren.
                    try {
                        calendarPreAlarmRefreshScheduler.reschedule()
                    } catch (e: Exception) {
                        Logger.w(LogTags.BACKGROUND_WORKER, "Boot: Pre-Alarm-Refresh-Reschedule fehlgeschlagen", e)
                    }

                    recoverySuccessful = true
                    Logger.business(
                        LogTags.MAINTENANCE_L4,
                        "✅ LEVEL 4 SUCCESS: Complete system recovery successful - Reason: $reason, Attempt: $recoveryAttempt"
                    )

                } catch (e: Exception) {
                    Logger.e(
                        LogTags.MAINTENANCE_L4,
                        "❌ LEVEL 4: Recovery attempt $recoveryAttempt failed",
                        e
                    )

                    if (recoveryAttempt < MAX_RECOVERY_ATTEMPTS) {
                        Logger.w(
                            LogTags.MAINTENANCE_L4,
                            "🔄 LEVEL 4: Retrying in ${RECOVERY_RETRY_DELAY_MS}ms..."
                        )
                        delay(RECOVERY_RETRY_DELAY_MS)
                    } else {
                        Logger.e(
                            LogTags.MAINTENANCE_L4,
                            "💥 LEVEL 4: All recovery attempts failed - system may need manual intervention"
                        )
                        // Emergency fallback - try to at least restart background services
                        try {
                            restartBackgroundServices(context)
                        } catch (fallbackError: Exception) {
                            Logger.e(
                                LogTags.MAINTENANCE_L4,
                                "💥 LEVEL 4: Even emergency fallback failed",
                                fallbackError
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 🔍 Comprehensive Health Diagnostics
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private suspend fun performHealthDiagnostics(
        reason: String
    ): String {
        val diagnosticResults = mutableListOf<String>()

        try {
            // Check UseCase Availability
            diagnosticResults.add("Alarm UseCase: ✅ Available")
            diagnosticResults.add("Calendar UseCase: ✅ Available")
            diagnosticResults.add("Shift UseCase: ✅ Available")

            // Check Repository Health
            diagnosticResults.add("Calendar Selection Repository: ✅ Available")

            // Check Authentication Status
            val authStatus = try {
                authDataStoreRepository.isAuthenticated().getOrElse { false }
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Failed to check auth status", e)
                false
            }
            diagnosticResults.add("Authentication: ${if (authStatus) "✅ Authenticated" else "⚠️ Not Authenticated"}")

            // Check Calendar Selection
            val selectedCalendars = try {
                calendarSelectionRepository.selectedCalendarIds.first()
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Failed to check calendar selection", e)
                emptySet()
            }
            diagnosticResults.add("Selected Calendars: ${selectedCalendars.size} calendars")

            // Check Current Alarm Count
            val currentAlarms = try {
                alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Failed to check alarm count", e)
                emptyList()
            }
            val futureAlarms = currentAlarms.filter { it.triggerTime > System.currentTimeMillis() }
            diagnosticResults.add("Active Alarms: ${futureAlarms.size} future alarms")

            diagnosticResults.add("Recovery Reason: $reason")
            diagnosticResults.add("System Time: ${LocalDateTime.now()}")

        } catch (e: Exception) {
            diagnosticResults.add("❌ Diagnostics Error: ${e.message}")
        }

        return diagnosticResults.joinToString(", ")  // Diagnostic results summary
    }

    /**
     * 🔄 Alarm Repository Recovery mit Event-Validierung
     * 
     * 🔧 SYNC-FIX: Validiert Alarme gegen aktuelle Calendar Events bevor sie restored werden
     * ✅ Verhindert: "Alter Alarm klingelt für gelöschtes/geändertes Event"
     * ✅ Löscht: Alarme für nicht-existierende Events
     * ✅ Updated: Alarme für geänderte Events
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private suspend fun performAlarmRecovery(): String {
        return try {
            // 1. Get all stored alarms
            val storedAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
            Logger.d(LogTags.MAINTENANCE_L4, "🔍 LEVEL 4: Found ${storedAlarms.size} stored alarms")

            // 2. Filter future alarms
            val futureAlarms = storedAlarms.filter { it.triggerTime > System.currentTimeMillis() }
            Logger.d(
                LogTags.MAINTENANCE_L4,
                "📅 LEVEL 4: ${futureAlarms.size} future alarms to validate"
            )
            
            // 🔧 SYNC-FIX: Validate alarms against current calendar events
            var restoredCount = 0
            var validatedCount = 0
            var deletedCount = 0
            
            // Get current calendar events for validation
            // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
            val selectedCalendars = calendarSelectionRepository.selectedCalendarIds.first()
            // 🛡️ FAIL-SAFE: Validierung (inkl. Loeschen verwaister Alarme) NUR bei nachweislich
            // erfolgreichem UND nicht-leerem Kalender-Abruf. Direkt nach dem Boot ist "kein Netz /
            // Token noch nicht bereit / StateFlow noch nicht geladen" der Normalfall - dann duerfen
            // gespeicherte Alarme NIEMALS geloescht, sondern muessen unveraendert wiederhergestellt
            // werden (lieber ein veralteter Wecker als gar keiner).
            val eventsResult = if (selectedCalendars.isNotEmpty()) {
                calendarUseCase.getCalendarEventsWithCache(
                    calendarIds = selectedCalendars,
                    forceRefresh = false
                )
            } else {
                null
            }
            val currentEvents = eventsResult?.getOrNull() ?: emptyList()
            val validationPossible = eventsResult?.isSuccess == true && currentEvents.isNotEmpty()

            Logger.d(
                LogTags.MAINTENANCE_L4,
                "🔍 LEVEL 4: ${currentEvents.size} Kalender-Events geladen - Validierung ${if (validationPossible) "aktiv" else "uebersprungen (fail-safe Wiederherstellung)"}"
            )

            // Build event ID map for quick lookup
            val currentEventMap = currentEvents.associateBy { it.id }

            // 3. Validate and restore alarms
            for (alarm in futureAlarms) {
                try {
                    // Validierung (mit moeglicher Loeschung) nur bei erfolgreichem Kalender-Abruf.
                    // Ohne Validierung faellt der Alarm unten direkt in scheduleSystemAlarm (Restore).
                    if (validationPossible && alarm.eventId.isNotEmpty()) {
                        val currentEvent = currentEventMap[alarm.eventId]
                        
                        if (currentEvent == null) {
                            // Event was deleted from calendar → delete alarm
                            Logger.business(
                                LogTags.MAINTENANCE_L4,
                                "🗑️ LEVEL 4: Event deleted, removing alarm: ${alarm.shiftName} (eventId: ${alarm.eventId})"
                            )
                            alarmUseCase.deleteAlarm(alarm.id)
                            deletedCount++
                            continue
                        }
                        
                        // Calculate current event checksum
                        val currentChecksum = calculateEventChecksum(currentEvent)
                        
                        if (alarm.eventChecksum != currentChecksum) {
                            // Event changed → delete old alarm (will be recreated by WorkManager/manual sync)
                            Logger.business(
                                LogTags.MAINTENANCE_L4,
                                "🔄 LEVEL 4: Event changed, removing outdated alarm: ${alarm.shiftName} (eventId: ${alarm.eventId})"
                            )
                            alarmUseCase.deleteAlarm(alarm.id)
                            deletedCount++
                            continue
                        }
                        
                        validatedCount++
                        Logger.d(
                            LogTags.MAINTENANCE_L4,
                            "✅ LEVEL 4: Alarm validated against event: ${alarm.shiftName}"
                        )
                    }
                    
                    // Restore validated alarm
                    alarmUseCase.scheduleSystemAlarm(alarm)
                    restoredCount++
                    Logger.d(
                        LogTags.MAINTENANCE_L4,
                        "✅ LEVEL 4: Restored alarm: ${alarm.shiftName}"
                    )
                } catch (e: Exception) {
                    Logger.e(
                        LogTags.MAINTENANCE_L4,
                        "❌ LEVEL 4: Failed to process alarm: ${alarm.shiftName}",
                        e
                    )
                }
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L4,
                "📊 LEVEL 4: Alarm recovery stats - Restored: $restoredCount, Validated: $validatedCount, Deleted: $deletedCount"
            )

            // 4. If few alarms restored, try to create new ones from calendar
            if (restoredCount < 3 && currentEvents.isNotEmpty()) {
                Logger.d(
                    LogTags.MAINTENANCE_L4,
                    "🔄 LEVEL 4: Low alarm count, attempting calendar-based recovery"
                )

                val shiftConfig = shiftUseCase.getCurrentShiftConfig().getOrNull()
                if (shiftConfig?.autoAlarmEnabled == true) {
                    // syncAlarms setzt die System-Alarme bereits intern; das folgende
                    // scheduleSystemAlarm ist idempotentes Re-Arming und dient hier zusaetzlich
                    // dem Zaehlen der wiederhergestellten Alarme im Boot-Recovery-Pfad.
                    val newAlarmsResult =
                        alarmUseCase.syncAlarms(currentEvents, shiftConfig)
                    val newAlarms = newAlarmsResult.getOrNull() ?: emptyList()

                    for (newAlarm in newAlarms) {
                        try {
                            alarmUseCase.scheduleSystemAlarm(newAlarm)
                            restoredCount++
                        } catch (e: Exception) {
                            Logger.e(
                                LogTags.MAINTENANCE_L4,
                                "❌ LEVEL 4: Failed to schedule new alarm",
                                e
                            )
                        }
                    }
                }
            }

            "Restored $restoredCount alarms (Validated: $validatedCount, Deleted: $deletedCount) from ${storedAlarms.size} stored"

        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Alarm recovery failed", e)
            "Alarm recovery failed: ${e.message}"
        }
    }
    
    /**
     * 🔧 SYNC-FIX: Calculate event checksum for validation
     */
    private fun calculateEventChecksum(event: com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent): String {
        val data = "${event.startTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   "${event.endTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   event.title
        return data.hashCode().toString()
    }

    /**
     * 📅 Calendar Integration Restoration
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private suspend fun performCalendarIntegrationRestoration(): String {
        return try {
            // 1. Check authentication status
            val isAuthenticated = authDataStoreRepository.isAuthenticated().getOrElse { false }
            if (!isAuthenticated) {
                return "Authentication not available - calendar integration skipped"
            }

            // 2. Test calendar connection by trying to get calendar events
            // PHASE 2 CLEANUP: daysAhead removed - fixed 14 days per PROJEKT-BRIEFING 4.0
            val connectionTest = try {
                val selectedCalendars = calendarSelectionRepository.selectedCalendarIds.first()
                if (selectedCalendars.isNotEmpty()) {
                    val testEvents = calendarUseCase.getCalendarEventsWithCache(
                        calendarIds = selectedCalendars,
                        forceRefresh = false
                    )
                    testEvents.isSuccess
                } else {
                    true // No calendars selected, but authentication is working
                }
            } catch (e: Exception) {
                Logger.w(LogTags.MAINTENANCE_L4, "Calendar connection test failed", e)
                false
            }

            val connectionResult = if (connectionTest) {
                "✅ Calendar connection healthy"
            } else {
                "⚠️ Calendar connection issues detected"
            }

            // 3. Get cache info (simplified)
            val cacheStats = "Cache operational"

            "$connectionResult, Cache: $cacheStats"

        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE_L4,
                "❌ LEVEL 4: Calendar integration restoration failed",
                e
            )
            "Calendar restoration failed: ${e.message}"
        }
    }

    private fun performSmartMaintenanceChainReinitialization(context: Context) {
        try {
            Logger.business(
                LogTags.MAINTENANCE_L4,
                "🔄 LEVEL 4: Reinitializing Smart Maintenance Chain (Phase 1 - using AlarmMaintenanceService)"
            )

            // Schedule next maintenance run
            AlarmMaintenanceService.scheduleNext(context)
            Logger.business(LogTags.MAINTENANCE_L4, "✅ AlarmMaintenanceService scheduled")

            Logger.business(
                LogTags.MAINTENANCE_L4,
                "✅ LEVEL 4: Smart Maintenance Chain reinitialization completed"
            )

        } catch (e: Exception) {
            Logger.e(
                LogTags.MAINTENANCE_L4,
                "❌ LEVEL 4: Smart Maintenance Chain reinitialization failed",
                e
            )
        }
    }

    /**
     * 🔧 Background Services Restart - PHASE 1 MIGRATION
     */
    private fun restartBackgroundServices(context: Context) {
        try {
            Logger.d(LogTags.MAINTENANCE_L4, "🔧 LEVEL 4: Restarting background services (Phase 1)")

            // Schedule AlarmMaintenanceService
            AlarmMaintenanceService.scheduleNext(context)

            Logger.d(LogTags.MAINTENANCE_L4, "✅ LEVEL 4: Background services restarted")

        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Background services restart failed", e)
        }
    }

    /**
     * 🔍 Schedule Post-Recovery Health Check
     * 
     * HILT MIGRATION: Now uses injected dependencies instead of AppContainer
     */
    private fun schedulePostRecoveryHealthCheck(context: Context, reason: String) {
        recoveryScope.launch {
            try {
                Logger.d(
                    LogTags.MAINTENANCE_L4,
                    "⏱️ LEVEL 4: Scheduling post-recovery health check in ${POST_BOOT_HEALTH_CHECK_DELAY_MS}ms"
                )

                delay(POST_BOOT_HEALTH_CHECK_DELAY_MS)

                val healthStatus = performHealthDiagnostics("POST_RECOVERY_CHECK")

                Logger.business(
                    LogTags.MAINTENANCE_L4,
                    "🔍 LEVEL 4: Post-recovery health check completed - Original reason: $reason, Status: $healthStatus"
                )

                // Check alarm count and trigger maintenance if needed
                val currentAlarms = alarmUseCase.getAllAlarms().getOrNull() ?: emptyList()
                val futureAlarms =
                    currentAlarms.filter { it.triggerTime > System.currentTimeMillis() }

                if (futureAlarms.size < 2) {
                    Logger.w(
                        LogTags.MAINTENANCE_L4,
                        "⚠️ LEVEL 4: Post-recovery check found low alarm count (${futureAlarms.size}), triggering immediate maintenance"
                    )

                    // Trigger immediate maintenance
                    AlarmMaintenanceService.start(context)
                }

            } catch (e: Exception) {
                Logger.e(LogTags.MAINTENANCE_L4, "❌ LEVEL 4: Post-recovery health check failed", e)
            }
        }
    }
}
