package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.flow.Flow

/**
 * Use case implementation for alarm skip functionality.
 * Handles business logic for skipping alarms.
 */
class AlarmSkipUseCase(
    private val alarmSkipRepository: IAlarmSkipRepository,
    private val alarmRepository: IAlarmRepository
) : IAlarmSkipUseCase {
    
    override val skipStatusFlow: Flow<AlarmSkipState> = alarmSkipRepository.skipStatusFlow
    
    override suspend fun skipNextAlarm(): Result<AlarmSkipResult> = 
        SafeExecutor.safeExecute("AlarmSkipUseCase.skipNextAlarm") {
            // 1. Nächsten Alarm ermitteln
            val nextAlarm = findNextAlarm()
                ?: throw IllegalStateException("Kein aktiver Alarm gefunden")
            
            // 2. Skip-Status setzen
            alarmSkipRepository.setNextAlarmSkipped(nextAlarm.id).getOrThrow()
            
            // 3. Result erstellen
            AlarmSkipResult(
                alarmId = nextAlarm.id,
                alarmName = nextAlarm.shiftName,
                formattedTime = nextAlarm.formattedTime
            )
        }
    
    override suspend fun cancelSkip(): Result<Unit> = 
        alarmSkipRepository.clearSkipStatus()
    
    override suspend fun checkAndProcessSkip(alarmId: Int): Result<SkipProcessResult> = 
        SafeExecutor.safeExecute("AlarmSkipUseCase.checkAndProcessSkip") {
            val isSkipped = alarmSkipRepository.isAlarmSkipped(alarmId).getOrThrow()
            
            if (isSkipped) {
                // Skip-Status nach erfolgreicher Verarbeitung löschen
                alarmSkipRepository.clearSkipStatus().getOrThrow()
                Logger.business(LogTags.ALARM_SKIP, "Alarm $alarmId successfully skipped")
                SkipProcessResult.ALARM_SKIPPED
            } else {
                Logger.business(LogTags.ALARM_SKIP, "Alarm $alarmId executed normally")
                SkipProcessResult.ALARM_EXECUTED
            }
        }
    
    override suspend fun getSkipStatus(): Result<AlarmSkipState> = 
        alarmSkipRepository.getSkipStatus()
    
    private suspend fun findNextAlarm(): AlarmInfo? {
        val currentTime = System.currentTimeMillis()
        return alarmRepository.getAllAlarms()
            .getOrNull()
            ?.filter { it.triggerTime > currentTime }
            ?.minByOrNull { it.triggerTime }
    }
}
