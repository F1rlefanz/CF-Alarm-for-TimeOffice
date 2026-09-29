package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import kotlinx.coroutines.flow.Flow

/**
 * Interface für Shift Configuration Repository Operations
 */
interface IShiftConfigRepository {
    
    /**
     * Flow für reaktive Beobachtung der Shift-Konfiguration
     */
    val shiftConfig: Flow<ShiftConfig>
    
    /**
     * Speichert oder aktualisiert die Shift-Konfiguration
     */
    suspend fun saveShiftConfig(config: ShiftConfig): Result<Unit>
    
    /**
     * Lädt die aktuelle Shift-Konfiguration (einmalig)
     */
    suspend fun getCurrentShiftConfig(): Result<ShiftConfig>
    
    /**
     * Setzt die Shift-Konfiguration auf Standardwerte zurück
     */
    suspend fun resetToDefaults(): Result<Unit>
}
