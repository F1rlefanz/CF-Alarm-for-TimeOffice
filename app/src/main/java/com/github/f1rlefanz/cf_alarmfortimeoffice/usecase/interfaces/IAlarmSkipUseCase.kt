package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import kotlinx.coroutines.flow.Flow

enum class SkipProcessResult {
    ALARM_SKIPPED,    // Alarm wurde übersprungen
    ALARM_EXECUTED    // Alarm normal ausgeführt
}

data class AlarmSkipResult(
    val alarmId: Int,
    val alarmName: String,
    val formattedTime: String
)

/**
 * Interface for alarm skip use case operations.
 * Defines the contract for alarm skip business logic.
 */
interface IAlarmSkipUseCase {
    suspend fun skipNextAlarm(): Result<AlarmSkipResult>
    suspend fun cancelSkip(): Result<Unit>
    suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult>
    suspend fun getSkipStatus(): Result<AlarmSkipState>

    /**
     * Loescht ein abgelaufenes Skip-Flag: der urspruenglich uebersprungene Alarm-Zeitpunkt liegt
     * in der Vergangenheit, der Skip hat seinen Zweck also erfuellt. Faengt den Fall ab, dass der
     * eigentliche Rueckmeldepfad (checkAndProcessSkip via AlarmReceiver) nie erreicht wird, weil
     * der System-Alarm beim Ueberspringen sofort geloescht wurde und darum nie mehr feuert.
     * Gibt true zurueck, wenn ein abgelaufener Skip tatsaechlich zurueckgesetzt wurde.
     */
    suspend fun clearExpiredSkip(): Result<Boolean>

    val skipStatusFlow: Flow<AlarmSkipState>
}
