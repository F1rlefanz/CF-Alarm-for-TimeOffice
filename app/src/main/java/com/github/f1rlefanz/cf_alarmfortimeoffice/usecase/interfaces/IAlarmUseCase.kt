package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Alarm UseCase Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: ViewModel abhängig von Abstraktion
 * - Testbarkeit: ViewModel kann mit Mock-UseCase getestet werden
 * - Business Logic Separation: Kapselt Alarm-spezifische Geschäftslogik
 */
interface IAlarmUseCase {
    
    /**
     * Flow für reaktive Beobachtung aktiver Alarme
     * 
     * @return Flow<List<AlarmInfo>> der bei Änderungen automatisch emittiert
     */
    val activeAlarms: Flow<List<AlarmInfo>>
    
    /**
     * Synchronisiert den Alarm-Bestand mit den übergebenen Kalender-Events (Soll-Zustand).
     *
     * EINZIGER Einstiegspunkt für die Event→Alarm-Pipeline (Orchestrator). Führt eine
     * mutex-serialisierte, idempotente Delta-Synchronisation durch:
     * - Event entfernt   → zugehöriger Alarm wird gelöscht (Repository + System-Alarm)
     * - Event geändert   → Alarm wird aktualisiert und neu gesetzt
     * - Event neu        → Alarm wird erstellt und gesetzt
     * - Event unverändert → System-Alarm wird idempotent re-armed
     * Manuell erstellte Alarme (leere eventId) bleiben unangetastet.
     *
     * Der System-Alarm wird für JEDEN Alarm des Soll-Zustands intern gesetzt — Aufrufer
     * dürfen (und sollen) danach NICHT mehr selbst schedulen.
     *
     * @param events Vollständige Liste der Kalender-Events (der Soll-Zustand)
     * @param shiftConfig Aktuelle Schicht-Konfiguration
     * @return Result mit dem resultierenden Alarm-Bestand oder Fehler
     */
    suspend fun syncAlarms(
        events: List<CalendarEvent>,
        shiftConfig: ShiftConfig
    ): Result<List<AlarmInfo>>
    
    /**
     * Speichert oder aktualisiert einen Alarm
     * 
     * @param alarmInfo Alarm-Information die gespeichert werden soll
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit>
    
    /**
     * Löscht einen Alarm anhand der ID
     * 
     * @param alarmId Eindeutige ID des zu löschenden Alarms
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteAlarm(alarmId: Int): Result<Unit>
    
    /**
     * Löscht alle Alarme
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteAllAlarms(): Result<Unit>
    
    /**
     * Aktiviert einen System-Alarm für die angegebene Alarm-Info
     * 
     * @param alarmInfo Alarm-Information für die der System-Alarm gesetzt werden soll
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun scheduleSystemAlarm(alarmInfo: AlarmInfo): Result<Unit>
    
    /**
     * Deaktiviert einen System-Alarm
     * 
     * @param alarmId ID des zu deaktivierenden Alarms
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun cancelSystemAlarm(alarmId: Int): Result<Unit>
    
    /**
     * Lädt alle aktiven Alarme (einmalig)
     * 
     * @return Result mit Liste aller aktiven Alarme oder Fehler
     */
    suspend fun getAllAlarms(): Result<List<AlarmInfo>>
}
