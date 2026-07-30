package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.data.AlarmSkipPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository implementation for alarm skip functionality.
 * Handles persistence of alarm skip state using DataStore.
 */
@Singleton
class AlarmSkipRepository @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) : IAlarmSkipRepository {
    
    override val skipStatusFlow: Flow<AlarmSkipState> = dataStore.data.map { preferences ->
        AlarmSkipState(
            isNextAlarmSkipped = preferences[AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED] ?: false,
            skippedAlarmId = preferences[AlarmSkipPreferences.SKIPPED_ALARM_ID],
            skipActivatedAt = preferences[AlarmSkipPreferences.SKIP_ACTIVATED_AT] ?: 0L,
            skipReason = preferences[AlarmSkipPreferences.SKIP_REASON] ?: "Manuell übersprungen",
            skippedAlarmTriggerTime = preferences[AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME] ?: 0L
        )
    }

    override suspend fun setNextAlarmSkipped(alarmId: Int, triggerTime: Long, reason: String): Result<Unit> =
        SafeExecutor.safeExecute("AlarmSkipRepository.setNextAlarmSkipped") {
            dataStore.edit { preferences ->
                preferences[AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED] = true
                preferences[AlarmSkipPreferences.SKIPPED_ALARM_ID] = alarmId
                preferences[AlarmSkipPreferences.SKIP_ACTIVATED_AT] = System.currentTimeMillis()
                preferences[AlarmSkipPreferences.SKIP_REASON] = reason
                preferences[AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME] = triggerTime
            }
            Logger.business(LogTags.ALARM_SKIP, "Skip activated for alarm $alarmId")
        }

    override suspend fun clearSkipStatus(): Result<Unit> =
        SafeExecutor.safeExecute("AlarmSkipRepository.clearSkipStatus") {
            dataStore.edit { preferences ->
                preferences.remove(AlarmSkipPreferences.IS_NEXT_ALARM_SKIPPED)
                preferences.remove(AlarmSkipPreferences.SKIPPED_ALARM_ID)
                preferences.remove(AlarmSkipPreferences.SKIP_ACTIVATED_AT)
                preferences.remove(AlarmSkipPreferences.SKIP_REASON)
                preferences.remove(AlarmSkipPreferences.SKIPPED_ALARM_TRIGGER_TIME)
            }
            Logger.business(LogTags.ALARM_SKIP, "Skip status cleared")
        }
    
    override suspend fun isAlarmSkipped(alarmId: Int): Result<Boolean> = 
        SafeExecutor.safeExecute("AlarmSkipRepository.isAlarmSkipped") {
            val skipState = skipStatusFlow.first()
            skipState.isNextAlarmSkipped && skipState.skippedAlarmId == alarmId
        }
    
    override suspend fun getSkipStatus(): Result<AlarmSkipState> = 
        SafeExecutor.safeExecute("AlarmSkipRepository.getSkipStatus") {
            skipStatusFlow.first()
        }
}
