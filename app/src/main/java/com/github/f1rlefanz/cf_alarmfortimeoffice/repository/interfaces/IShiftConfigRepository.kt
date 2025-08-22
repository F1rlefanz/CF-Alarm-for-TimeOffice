package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Shift Configuration Repository Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: Abstraktion statt konkrete Implementierung
 * - Testbarkeit: UseCase/ViewModel kann mit Mock-Repository getestet werden
 * - Flexibilität: Implementierung austauschbar (DataStore/SharedPrefs/Database)
 */
interface IShiftConfigRepository {
    
    /**
     * Flow für reaktive Beobachtung der Shift-Konfiguration
     * 
     * @return Flow<ShiftConfig> der bei Änderungen automatisch emittiert
     */
    val shiftConfig: Flow<ShiftConfig>
    
    /**
     * Speichert oder aktualisiert die Shift-Konfiguration
     * 
     * @param config Neue Shift-Konfiguration
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit>
    
    /**
     * Lädt die aktuelle Shift-Konfiguration (einmalig)
     * 
     * @return Result mit aktueller ShiftConfig oder Fehler
     */
    suspend fun getCurrentShiftConfig(): Result<ShiftConfig>
    
    /**
     * Setzt die Shift-Konfiguration auf Standardwerte zurück
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun resetToDefaults(): Result<Unit>
    
    /**
     * Prüft ob eine gültige Shift-Konfiguration existiert
     * 
     * @return Result mit Boolean (true wenn gültige Config vorhanden) oder Fehler
     */
    suspend fun hasValidConfig(): Result<Boolean>
}
