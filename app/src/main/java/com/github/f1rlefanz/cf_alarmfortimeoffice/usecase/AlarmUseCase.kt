package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IShiftConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftRecognitionEngine
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.AlarmConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.CalendarConstants
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UseCase für alle Alarm-bezogenen Operationen - implementiert IAlarmUseCase
 *
 * REFACTORED:
 * ✅ Implementiert IAlarmUseCase Interface für bessere Testbarkeit
 * ✅ Verwendet Repository-Interfaces statt konkrete Implementierungen
 * ✅ Erweiterte Business Logic für Event-zu-Alarm Transformation
 * ✅ Result-basierte API für konsistente Fehlerbehandlung
 * ✅ Integration mit ShiftRecognitionEngine für intelligente Alarm-Erstellung
 */
@Singleton
class AlarmUseCase @Inject constructor(
    private val alarmRepository: IAlarmRepository,
    private val alarmManagerService: AlarmManagerService,
    private val shiftConfigRepository: IShiftConfigRepository,
    private val shiftRecognitionEngine: ShiftRecognitionEngine
) : IAlarmUseCase {
    
    /**
     * REACTIVE OPTIMIZATION: Direct repository StateFlow for immediate UI updates
     * 
     * FIXED: Replaced polling-based Flow with reactive StateFlow from repository
     * ✅ Eliminates 10-second delay for UI updates
     * ✅ Provides immediate reactivity when alarms change
     * ✅ Better performance (no unnecessary polling)
     * ✅ Follows reactive programming principles
     */
    override val activeAlarms: Flow<List<AlarmInfo>> = 
        alarmRepository.activeAlarms
            .distinctUntilChanged { old, new -> 
                // PERFORMANCE: Only emit when alarm list actually changes
                old.size == new.size && old.zip(new).all { (a, b) -> a.id == b.id && a.triggerTime == b.triggerTime }
            }
    
    /**
     * ORCHESTRATOR: Einziger Einstiegspunkt der Event→Alarm-Pipeline.
     *
     * Delta-Synchronisation statt "alles löschen + neu erstellen":
     * ✅ Erkennt gelöschte Events → löscht nur diese Alarme
     * ✅ Erkennt geänderte Events → aktualisiert nur diese Alarme
     * ✅ Erkennt neue Events → erstellt nur diese Alarme
     * ✅ Unveränderte Events → System-Alarm wird idempotent re-armed (Aufrufer schedulen nicht mehr selbst)
     *
     * Nebenläufigkeit: Ein einziger [alarmSyncMutex] serialisiert ALLE mutierenden
     * Set-Operationen (Sync, deleteAll, deleteAlarm). Ersetzt den früheren
     * check-then-act-`@Volatile`-Boolean (P1) samt "Skip → leere Liste", das der
     * Maintenance-Service als "nichts zu tun" fehldeutete.
     */
    private val alarmSyncMutex = Mutex()

    override suspend fun syncAlarms(
        events: List<CalendarEvent>,
        shiftConfig: ShiftConfig
    ): Result<List<AlarmInfo>> = withContext(Dispatchers.IO) {
        // Serialisiert konkurrierende Aufrufer, statt den zweiten mit leerer Liste abzuweisen.
        alarmSyncMutex.withLock {
            SafeExecutor.safeExecute("AlarmUseCase.syncAlarms") {
                if (!shiftConfig.autoAlarmEnabled) {
                    Logger.d(LogTags.ALARM, "Auto-alarm disabled, not creating alarms")
                    return@safeExecute emptyList()
                }
                
                if (events.isEmpty()) {
                    Logger.business(LogTags.ALARM, "✅ SYNC: No calendar events found - clearing all alarms")
                    // No events → delete all alarms
                    clearInternalAlarms()
                    return@safeExecute emptyList()
                }
                
                Logger.business(LogTags.ALARM, "🔄 SYNC: Starting intelligent alarm synchronization for ${events.size} events")
                
                // 🔧 SYNC-FIX: INTELLIGENT SYNCHRONIZATION statt blind clearing
                val existingAlarms = alarmRepository.getAllAlarms().getOrNull() ?: emptyList()
                val shiftMatches = shiftRecognitionEngine.getAllMatchingShifts(events)
                
                if (shiftMatches.isEmpty()) {
                    Logger.business(LogTags.ALARM, "✅ SYNC: No matching shifts found - clearing all alarms")
                    clearInternalAlarms()
                    return@safeExecute emptyList()
                }
                
                // Build checksum map for events
                val eventChecksumMap = events.associate { event ->
                    event.id to calculateEventChecksum(event)
                }
                
                // Build map of new alarms we want to create
                val newAlarmsMap = mutableMapOf<String, AlarmInfo>()  // eventId -> AlarmInfo
                val now = LocalDateTime.now()
                
                for (shiftMatch in shiftMatches) {
                    try {
                        if (shiftMatch.calculatedAlarmTime.isBefore(now)) {
                            Logger.w(LogTags.ALARM, "⏰ SYNC: Skipping alarm in the past: ${shiftMatch.shiftDefinition.name}")
                            continue
                        }
                        
                        val eventId = shiftMatch.calendarEvent.id
                        val checksum = eventChecksumMap[eventId] ?: ""
                        val alarmInfo = createAlarmFromShiftMatch(shiftMatch, eventId, checksum)
                        newAlarmsMap[eventId] = alarmInfo
                    } catch (e: Exception) {
                        Logger.e(LogTags.ALARM, "❌ SYNC: Error processing shift: ${shiftMatch.shiftDefinition.name}", e)
                    }
                }
                
                // 🔧 SYNC-FIX Step 1: Delete alarms for events that no longer exist
                var deletedCount = 0
                for (existingAlarm in existingAlarms) {
                    if (existingAlarm.eventId.isNotEmpty() && !newAlarmsMap.containsKey(existingAlarm.eventId)) {
                        // Event was deleted from calendar
                        Logger.business(LogTags.ALARM, "🗑️ SYNC: Deleting alarm for deleted event: ${existingAlarm.shiftName} (eventId: ${existingAlarm.eventId})")
                        alarmRepository.deleteAlarm(existingAlarm.id).getOrThrow()
                        alarmManagerService.cancelSystemAlarm(existingAlarm.id)
                        deletedCount++
                    }
                }
                
                // 🔧 SYNC-FIX Step 2: Update changed alarms & create new ones
                var updatedCount = 0
                var createdCount = 0
                val resultAlarms = mutableListOf<AlarmInfo>()
                
                for ((eventId, newAlarm) in newAlarmsMap) {
                    val existingAlarm = existingAlarms.find { it.eventId == eventId }
                    
                    if (existingAlarm != null) {
                        // Alarm exists - check if event changed
                        if (existingAlarm.eventChecksum != newAlarm.eventChecksum || 
                            existingAlarm.triggerTime != newAlarm.triggerTime) {
                            // Event changed → update alarm
                            Logger.business(LogTags.ALARM, "🔄 SYNC: Updating changed alarm: ${newAlarm.shiftName} (eventId: $eventId)")
                            
                            // Delete old
                            alarmRepository.deleteAlarm(existingAlarm.id).getOrThrow()
                            alarmManagerService.cancelSystemAlarm(existingAlarm.id)
                            
                            // Create new with updated data
                            alarmRepository.saveAlarm(newAlarm).getOrThrow()
                            scheduleSystemAlarm(newAlarm).getOrThrow()
                            resultAlarms.add(newAlarm)
                            updatedCount++
                        } else {
                            // Unchanged - keep existing, aber System-Alarm idempotent re-armen,
                            // damit der Aufrufer nicht mehr selbst schedulen muss (kein Doppel-Scheduling).
                            Logger.d(LogTags.ALARM, "✅ SYNC: Alarm unchanged, re-arming system alarm: ${existingAlarm.shiftName}")
                            scheduleSystemAlarm(existingAlarm).getOrThrow()
                            resultAlarms.add(existingAlarm)
                        }
                    } else {
                        // New event → create alarm
                        Logger.business(LogTags.ALARM, "➕ SYNC: Creating alarm for new event: ${newAlarm.shiftName} (eventId: $eventId)")
                        alarmRepository.saveAlarm(newAlarm).getOrThrow()
                        scheduleSystemAlarm(newAlarm).getOrThrow()
                        resultAlarms.add(newAlarm)
                        createdCount++
                    }
                }
                
                Logger.business(
                    LogTags.ALARM, 
                    "✅ SYNC: Intelligent synchronization complete - " +
                    "Created: $createdCount, Updated: $updatedCount, Deleted: $deletedCount, " +
                    "Total: ${resultAlarms.size} alarms"
                )
                
                resultAlarms
            }
        }
    }

    /**
     * 🔧 SYNC-FIX: Calculate event checksum for change detection
     */
    private fun calculateEventChecksum(event: CalendarEvent): String {
        // Simple checksum: hash of critical fields
        val data = "${event.startTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   "${event.endTime.toEpochSecond(java.time.ZoneOffset.UTC)}" +
                   event.title
        return data.hashCode().toString()
    }
    
    override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> = 
        alarmRepository.saveAlarm(alarmInfo)
    
    
    /**
     * Internes Clearing (System-Alarme + Repository). Kein eigener Guard: läuft immer
     * unter [alarmSyncMutex] (aufgerufen aus [syncAlarms], [deleteAllAlarms], Legacy-Pfad).
     */
    private suspend fun clearInternalAlarms() {
        Logger.d(LogTags.ALARM, "🧹 INTERNAL-CLEAR: Fast internal clearing (system + repository)")

        // Step 1: Cancel system alarms
        val activeAlarmsList = alarmRepository.activeAlarms.first()
        for (alarm in activeAlarmsList) {
            alarmManagerService.cancelSystemAlarm(alarm.id)
        }

        // Step 2: Clear repository
        alarmRepository.deleteAllAlarms().getOrThrow()

        Logger.d(LogTags.ALARM, "✅ INTERNAL-CLEAR: Fast clearing completed")
    }

    override suspend fun deleteAlarm(alarmId: Int): Result<Unit> =
        SafeExecutor.safeExecute("AlarmUseCase.deleteAlarm") {
            // Serialisiert gegen syncAlarms/deleteAllAlarms über denselben Mutex.
            alarmSyncMutex.withLock {
                Logger.d(LogTags.ALARM, "🧹 ATOMIC-SINGLE: Deleting single alarm ID=$alarmId")

                // Cancel system alarm first, then repository
                cancelSystemAlarm(alarmId).getOrThrow()
                alarmRepository.deleteAlarm(alarmId).getOrThrow()

                Logger.d(LogTags.ALARM, "✅ ATOMIC-SINGLE: Alarm $alarmId deleted successfully")
            }
        }

    override suspend fun deleteAllAlarms(): Result<Unit> =
        SafeExecutor.safeExecute("AlarmUseCase.deleteAllAlarms") {
            // Serialisiert gegen syncAlarms/deleteAlarm über denselben Mutex.
            alarmSyncMutex.withLock {
                clearInternalAlarms()
            }
        }
    
    override suspend fun scheduleSystemAlarm(alarmInfo: AlarmInfo): Result<Unit> = 
        SafeExecutor.safeExecute("AlarmUseCase.scheduleSystemAlarm") {
            // Create dummy ShiftMatch for AlarmManagerService compatibility
            val shiftDefinition = ShiftDefinition(
                id = alarmInfo.shiftId,
                name = alarmInfo.shiftName,
                keywords = listOf(),
                alarmTime = LocalTime.of(AlarmConstants.DEFAULT_ALARM_HOUR, AlarmConstants.DEFAULT_ALARM_MINUTE), // Default
                isEnabled = true
            )
            
            val calendarEvent = CalendarEvent(
                id = alarmInfo.id.toString(), // Convert Int to String
                title = alarmInfo.shiftName,
                startTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(alarmInfo.triggerTime),
                    ZoneId.systemDefault()
                ),
                endTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(alarmInfo.triggerTime + CalendarConstants.DEFAULT_EVENT_DURATION_MS), // +1 hour
                    ZoneId.systemDefault()
                ),
                calendarId = ""
            )
            
            val shiftMatch = ShiftMatch(
                shiftDefinition = shiftDefinition,
                calendarEvent = calendarEvent,
                calculatedAlarmTime = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(alarmInfo.triggerTime),
                    ZoneId.systemDefault()
                )
            )
            
            val config = shiftConfigRepository.getCurrentShiftConfig().getOrThrow()
            alarmManagerService.setAlarmFromShiftMatch(shiftMatch, config.autoAlarmEnabled, alarmInfo.id)
        }
    
    override suspend fun cancelSystemAlarm(alarmId: Int): Result<Unit> = 
        SafeExecutor.safeExecute("AlarmUseCase.cancelSystemAlarm") {
            alarmManagerService.cancelSystemAlarm(alarmId)
        }
    
    override suspend fun getAllAlarms(): Result<List<AlarmInfo>> = 
        alarmRepository.getAllAlarms()
    
    /**
     * Erstellt AlarmInfo aus ShiftMatch mit Event-Tracking
     */
    private fun createAlarmFromShiftMatch(shiftMatch: ShiftMatch, eventId: String, eventChecksum: String): AlarmInfo {
        val alarmTime = shiftMatch.calculatedAlarmTime
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        
        return AlarmInfo(
            id = shiftMatch.calendarEvent.id.hashCode(), // Convert String ID to Int
            shiftId = shiftMatch.shiftDefinition.id,
            shiftName = shiftMatch.shiftDefinition.name,
            triggerTime = alarmTime,
            formattedTime = formatAlarmTime(alarmTime),
            eventId = eventId,
            eventChecksum = eventChecksum,
            shiftEndTime = shiftMatch.calendarEvent.endTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli(),
            shiftStartTime = shiftMatch.calendarEvent.startTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        )
    }
    
    /**
     * Formatiert Alarm-Zeit für Anzeige - public für ViewModel-Zugriff
     */
    fun formatAlarmTime(timeInMillis: Long): String {
        val instant = java.time.Instant.ofEpochMilli(timeInMillis)
        val localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        return DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME).format(localDateTime)
    }
    
}
