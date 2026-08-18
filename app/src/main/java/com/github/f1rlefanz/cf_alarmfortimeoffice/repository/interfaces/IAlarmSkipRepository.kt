package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import kotlinx.coroutines.flow.Flow

/**
 * Interface for alarm skip repository operations.
 * Defines the contract for managing alarm skip state persistence.
 */
interface IAlarmSkipRepository {
    /**
     * @param manualAlarmSnapshot Vollstaendiger Wecker als JSON - NUR bei einem manuell
     *   gestellten Wecker, sonst `null`. Siehe [AlarmSkipState.skippedManualAlarm]. `null`
     *   LOESCHT einen etwaigen frueheren Schnappschuss, damit kein Merker aus einem vorherigen
     *   Skip stehenbleibt.
     */
    suspend fun setNextAlarmSkipped(
        alarmId: Int,
        triggerTime: Long,
        reason: String = "Manuell übersprungen",
        manualAlarmSnapshot: String? = null
    ): Result<Unit>
    suspend fun clearSkipStatus(): Result<Unit>
    suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean>
    suspend fun getSkipStatus(): Result<AlarmSkipState>
    val skipStatusFlow: Flow<AlarmSkipState>
}
