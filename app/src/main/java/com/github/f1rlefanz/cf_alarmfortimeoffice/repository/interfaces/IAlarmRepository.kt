package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Alarm Repository Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: Abstraktion statt konkrete Implementierung
 * - Testbarkeit: UseCase/ViewModel kann mit Mock-Repository getestet werden
 * - Flexibilität: Implementierung austauschbar (Database/SharedPrefs/InMemory)
 * 
 * REACTIVE ENHANCEMENT: Added activeAlarms Flow for immediate UI updates
 */
interface IAlarmRepository {
    
    /**
     * REACTIVE: Flow of active alarms for immediate UI updates
     * 
     * @return Flow that emits current alarm list whenever it changes
     */
    val activeAlarms: Flow<List<AlarmInfo>>
    
    /**
     * Speichert oder aktualisiert eine Alarm-Information
     * 
     * @param alarmInfo Alarm-Information die gespeichert werden soll
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit>
    
    /**
     * Lädt alle gespeicherten Alarm-Informationen
     * 
     * @return Result mit Liste aller Alarme oder Fehler
     */
    suspend fun getAllAlarms(): Result<List<AlarmInfo>>
    
    /**
     * Lädt spezifische Alarm-Information anhand der ID
     * 
     * @param alarmId Eindeutige ID des Alarms
     * @return Result mit AlarmInfo oder Fehler wenn nicht gefunden
     */
    suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?>
    
    /**
     * Löscht einen Alarm anhand der ID
     * 
     * @param alarmId Eindeutige ID des zu löschenden Alarms
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteAlarm(alarmId: Int): Result<Unit>
    
    /**
     * Löscht alle gespeicherten Alarme
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteAllAlarms(): Result<Unit>
    
    /**
     * Prüft ob ein Alarm mit der angegebenen ID existiert
     * 
     * @param alarmId Eindeutige ID des Alarms
     * @return Result mit Boolean (true wenn vorhanden) oder Fehler
     */
    suspend fun alarmExists(alarmId: Int): Result<Boolean>
}
