package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Shift UseCase Operations
 * 
 * TESTING IMPROVEMENT: Interface ermöglicht Mock-Implementierungen
 * - Dependency Inversion: ViewModel abhängig von Abstraktion
 * - Testbarkeit: ViewModel kann mit Mock-UseCase getestet werden
 * - Business Logic Separation: Kapselt Shift-spezifische Geschäftslogik
 */
interface IShiftUseCase {
    
    /**
     * Flow für reaktive Beobachtung der Schicht-Konfiguration
     * 
     * @return Flow<ShiftConfig> der bei Änderungen automatisch emittiert
     */
    val shiftConfig: Flow<ShiftConfig>
    
    /**
     * Speichert oder aktualisiert die Schicht-Konfiguration
     * 
     * @param config Neue Schicht-Konfiguration
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit>
    
    /**
     * Lädt die aktuelle Schicht-Konfiguration (einmalig)
     * 
     * @return Result mit aktueller ShiftConfig oder Fehler
     */
    suspend fun getCurrentShiftConfig(): Result<ShiftConfig>
    
    /**
     * Fügt eine neue Schicht-Definition hinzu
     * 
     * @param definition Neue Schicht-Definition
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun addShiftDefinition(definition: ShiftDefinition): Result<Unit>
    
    /**
     * Aktualisiert eine bestehende Schicht-Definition
     * 
     * @param definition Aktualisierte Schicht-Definition
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun updateShiftDefinition(definition: ShiftDefinition): Result<Unit>
    
    /**
     * Löscht eine Schicht-Definition anhand der ID
     * 
     * @param definitionId ID der zu löschenden Schicht-Definition
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun deleteShiftDefinition(definitionId: String): Result<Unit>
    
    /**
     * Erkennt Schichten in Kalender-Events basierend auf der aktuellen Konfiguration
     * 
     * @param events Liste der Kalender-Events
     * @return Result mit Liste der erkannten Schicht-Matches oder Fehler
     */
    suspend fun recognizeShiftsInEvents(events: List<CalendarEvent>): Result<List<ShiftMatch>>
    
    /**
     * Setzt die Schicht-Konfiguration auf Standardwerte zurück
     * 
     * @return Result mit Erfolgs- oder Fehlerinformation
     */
    suspend fun resetToDefaults(): Result<Unit>
    
    /**
     * Prüft ob eine gültige Schicht-Konfiguration existiert
     * 
     * @return Result mit Boolean (true wenn gültige Config vorhanden) oder Fehler
     */
    suspend fun hasValidConfig(): Result<Boolean>
}
