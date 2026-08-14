package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import kotlinx.coroutines.flow.Flow

/**
 * Interface for Hue Configuration repository operations
 * Follows Clean Architecture principles with testable abstractions
 */
interface IHueConfigRepository {
    
    /**
     * Get configuration as Flow for reactive updates
     */
    fun getConfiguration(): Flow<HueConfiguration>
    
    /**
     * Save bridge connection details
     */
    suspend fun saveBridgeConfig(bridgeIp: String, username: String): Result<Unit>
    
    /**
     * Get all saved schedule rules
     */
    suspend fun getScheduleRules(): Result<List<HueSchedule>>
    
    /**
     * Save a schedule rule
     */
    suspend fun saveScheduleRule(rule: HueSchedule): Result<Unit>
    
    /**
     * Delete a schedule rule
     */
    suspend fun deleteScheduleRule(ruleId: String): Result<Unit>
    
    /**
     * Update an existing schedule rule
     */
    suspend fun updateScheduleRule(rule: HueSchedule): Result<Unit>
    
    /**
     * Read-Modify-Write ueber den GESAMTEN Regelbestand, INNERHALB einer einzigen
     * `dataStore.edit{}`-Transaktion (dasselbe Muster wie [saveScheduleRule]/[deleteScheduleRule]).
     *
     * WARUM NICHT "Liste rein, Liste raus": der Aufrufer (Ziel-Abgleich nach einem Bridge-Wechsel)
     * braucht eine Weile fuer seine Bridge-Abfrage. Wuerde er die vorher gelesene Liste
     * zurueckschreiben, ginge eine zwischenzeitliche Nutzer-Aenderung verloren. [transform] laeuft
     * deshalb auf dem FRISCH gelesenen Bestand innerhalb der Transaktion.
     *
     * Ist der gespeicherte Wert nicht dekodierbar, scheitert der Aufruf - es wird NICHTS
     * geschrieben. Ein Rueckfall auf "keine Regeln" wuerde den Bestand endgueltig loeschen.
     * Liefert [transform] eine unveraenderte Liste, wird ebenfalls nicht geschrieben.
     */
    suspend fun updateScheduleRules(transform: (List<HueSchedule>) -> List<HueSchedule>): Result<Unit>

    /**
     * Clear all configuration (for reset/logout)
     */
    suspend fun clearConfiguration(): Result<Unit>

    /**
     * Clear ONLY the persisted bridge IP/username (used by "Verbindung trennen / Bridge
     * vergessen" - UX FEATURE B). Unlike [clearConfiguration], this intentionally keeps the
     * saved schedule rules so re-pairing the same (or a replacement) bridge doesn't force the
     * user to recreate them.
     */
    suspend fun clearBridgeConfig(): Result<Unit>
}

/**
 * Data class for Hue configuration
 */
data class HueConfiguration(
    val bridgeIp: String = "",
    val username: String = "",
    val isConfigured: Boolean = false,
    val scheduleRules: List<HueSchedule> = emptyList()
)
