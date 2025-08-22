package com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import kotlinx.coroutines.flow.Flow

/**
 * Interface for alarm skip repository operations.
 * Defines the contract for managing alarm skip state persistence.
 */
interface IAlarmSkipRepository {
    suspend fun setNextAlarmSkipped(alarmId: Int, reason: String = "Manuell übersprungen"): Result<Unit>
    suspend fun clearSkipStatus(): Result<Unit>
    suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean>
    suspend fun getSkipStatus(): Result<AlarmSkipState>
    val skipStatusFlow: Flow<AlarmSkipState>
}
