package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.coroutines.flow.Flow

/** Alarm-Operationen; Interface fuer Mock-Implementierungen in ViewModel-Tests. */
interface IAlarmUseCase {
    
    /** Flow für reaktive Beobachtung aktiver Alarme */
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
    
    /** Speichert oder aktualisiert einen Alarm */
    suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit>
    
    /** Löscht einen Alarm anhand der ID */
    suspend fun deleteAlarm(alarmId: Int): Result<Unit>
    
    /** Löscht alle Alarme */
    suspend fun deleteAllAlarms(): Result<Unit>
    
    /** Aktiviert einen System-Alarm für die angegebene Alarm-Info */
    suspend fun scheduleSystemAlarm(alarmInfo: AlarmInfo): Result<Unit>
    
    /** Deaktiviert einen System-Alarm */
    suspend fun cancelSystemAlarm(alarmId: Int): Result<Unit>
    
    /** Lädt alle aktiven Alarme (einmalig) */
    suspend fun getAllAlarms(): Result<List<AlarmInfo>>
}
