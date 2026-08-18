package com.github.f1rlefanz.cf_alarmfortimeoffice.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.error.SafeExecutor
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmSkipState
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmSkipRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmManagerService
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.AlarmSkipResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmSkipUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.ManualAlarmSnapshot
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.SkipProcessResult
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case implementation for alarm skip functionality.
 * Handles business logic for skipping alarms.
 *
 * CRITICAL FIX: Ensures system alarm is cancelled when alarm is skipped
 * ✅ Prevents "ghost alarms" that trigger despite being skipped
 * ✅ Properly cleans up both DataStore state AND Android AlarmManager
 *
 * EIN UEBERSPRUNGENER ALARM IST WEG - fuer JEDE Alarmart (siehe [skipNextAlarm]). Damit
 * "Aufheben" trotzdem umkehrbar bleibt, wird ein MANUELLER Wecker vorher als
 * [ManualAlarmSnapshot] im Skip-Zustand gesichert; ein kalenderbasierter braucht das nicht, er
 * entsteht aus dem Kalenderstand neu.
 */
class AlarmSkipUseCase @Inject constructor(
    private val alarmSkipRepository: IAlarmSkipRepository,
    private val alarmRepository: IAlarmRepository,
    private val alarmManagerService: AlarmManagerService
) : IAlarmSkipUseCase {

    companion object {
        /**
         * Praefix der `shiftId` eines von Hand gestellten Weckers.
         *
         * Quelle des Werts ist `AlarmViewModel.ManualAlarmConstants.MANUAL_SHIFT_ID_PREFIX`; hier
         * bewusst als eigene Konstante gefuehrt, weil ein UseCase nicht auf ein ViewModel
         * zugreifen darf. Damit daraus keine zweite Wahrheit wird, haelt
         * `AlarmSkipManualAlarmTest` beide Werte gegeneinander fest - laufen sie auseinander,
         * faellt der Test um, nicht der Wecker.
         */
        internal const val MANUAL_SHIFT_ID_PREFIX = "manual_"

        /** Nur ein manueller Alarm braucht den Schnappschuss - siehe [skipNextAlarm]. */
        internal fun isManualAlarm(alarmInfo: AlarmInfo): Boolean =
            alarmInfo.shiftId.startsWith(MANUAL_SHIFT_ID_PREFIX)
    }

    override val skipStatusFlow: Flow<AlarmSkipState> = alarmSkipRepository.skipStatusFlow
    
    override suspend fun skipNextAlarm(): Result<AlarmSkipResult> = 
        SafeExecutor.safeExecute("AlarmSkipUseCase.skipNextAlarm") {
            // 1. Nächsten Alarm ermitteln
            val nextAlarm = findNextAlarm()
                ?: throw IllegalStateException("Kein aktiver Alarm gefunden")
            
            // 2. Skip-Status setzen - MIT Schnappschuss, falls es ein manueller Wecker ist.
            //
            // Die Sicherung passiert VOR dem Loeschen und ist der einzige Schritt hier, dessen
            // Fehlschlag den ganzen Vorgang abbricht (getOrThrow): laesst sich der Zustand nicht
            // schreiben, bleibt der Wecker unangetastet stehen und klingelt - das ist die
            // richtige Richtung. Umgekehrt (erst loeschen, dann sichern) waere ein Absturz
            // dazwischen ein spurlos verschwundener Wecker.
            val manualSnapshot = if (isManualAlarm(nextAlarm)) {
                ManualAlarmSnapshot.encode(nextAlarm)
            } else {
                null
            }
            alarmSkipRepository.setNextAlarmSkipped(
                alarmId = nextAlarm.id,
                triggerTime = nextAlarm.triggerTime,
                manualAlarmSnapshot = manualSnapshot
            ).getOrThrow()
            
            // 3. ✅ UX-FIX: Systemalarm SOFORT löschen für direktes User-Feedback
            // User erwartet dass der Alarm aus der Statusleiste verschwindet wenn er "überspringen" drückt
            Logger.business(LogTags.ALARM_SKIP, "⏭️ SKIP-IMMEDIATE: Deleting system alarm ${nextAlarm.id} immediately for better UX")
            try {
                alarmManagerService.cancelSystemAlarm(nextAlarm.id)
                Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-IMMEDIATE: System alarm cancelled - user sees immediate feedback")
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-IMMEDIATE: Failed to cancel system alarm", e)
            }
            
            // 4. Alarm aus Repository loeschen - IMMER, fuer jede Alarmart.
            //
            // Die Reihenfolge (erst cancelSystemAlarm, dann deleteAlarm) ist Pflicht, und dass
            // hier nichts stehenbleibt, ebenfalls: mehrere unabhaengige Stellen der App lesen den
            // Alarm-Bestand OHNE Skip-Filter - der Direct-Boot-Spiegel, aus dem der BootReceiver
            // jeden Zukunfts-Eintrag direkt wieder armiert (am Skip-Backstop vorbei), und die
            // Hue-Tagesplanung, die daraus ihre Sonnenaufgangs-Starts baut. Ein "uebersprungener"
            // Eintrag, der liegenbleibt, klingelt nach einem naechtlichen Neustart trotzdem und
            // schaltet am uebersprungenen Morgen das Licht ein. Die Umkehrbarkeit fuer manuelle
            // Wecker traegt der Schnappschuss oben, nicht ein geschonter Eintrag.
            try {
                alarmRepository.deleteAlarm(nextAlarm.id).getOrThrow()
                Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-IMMEDIATE: Alarm deleted from repository")
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-IMMEDIATE: Failed to delete alarm from repository", e)
            }

            // 5. Result erstellen
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
                Logger.business(LogTags.ALARM_SKIP, "⏭️ SKIP-FIX: Processing skip for alarm $alarmId")
                
                // CRITICAL FIX Step 1: Cancel the system alarm (Android AlarmManager)
                // This prevents the alarm from triggering again
                try {
                    alarmManagerService.cancelSystemAlarm(alarmId)
                    Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-FIX: System alarm $alarmId cancelled successfully")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-FIX: Failed to cancel system alarm $alarmId", e)
                    // Continue anyway - we still want to clear the skip status
                }
                
                // CRITICAL FIX Step 2: Delete the alarm from repository
                // This removes it from the app's internal alarm list
                try {
                    alarmRepository.deleteAlarm(alarmId).getOrThrow()
                    Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-FIX: Alarm $alarmId deleted from repository")
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_SKIP, "❌ SKIP-FIX: Failed to delete alarm from repository", e)
                    // Continue anyway - we still want to clear the skip status
                }
                
                // CRITICAL FIX Step 3: Clear skip status after successful processing
                // This prevents the skip from affecting other alarms
                alarmSkipRepository.clearSkipStatus().getOrThrow()
                Logger.business(LogTags.ALARM_SKIP, "✅ SKIP-FIX: Alarm $alarmId successfully skipped and cleaned up")
                
                SkipProcessResult.ALARM_SKIPPED
            } else {
                Logger.business(LogTags.ALARM_SKIP, "▶️ Alarm $alarmId executed normally (not skipped)")
                SkipProcessResult.ALARM_EXECUTED
            }
        }
    
    override suspend fun getSkipStatus(): Result<AlarmSkipState> =
        alarmSkipRepository.getSkipStatus()

    override suspend fun clearExpiredSkip(): Result<Boolean> =
        SafeExecutor.safeExecute("AlarmSkipUseCase.clearExpiredSkip") {
            val skipState = alarmSkipRepository.getSkipStatus().getOrThrow()
            val isExpired = skipState.isNextAlarmSkipped &&
                skipState.skippedAlarmTriggerTime > 0 &&
                System.currentTimeMillis() > skipState.skippedAlarmTriggerTime

            if (isExpired) {
                alarmSkipRepository.clearSkipStatus().getOrThrow()
                Logger.business(
                    LogTags.ALARM_SKIP,
                    "⏰ Skip abgelaufen (Ziel-Alarm ${skipState.skippedAlarmId} längst verstrichen) – automatisch zurückgesetzt"
                )
            }

            isExpired
        }

    private suspend fun findNextAlarm(): AlarmInfo? {
        val currentTime = System.currentTimeMillis()
        return alarmRepository.getAllAlarms()
            .getOrNull()
            ?.filter { it.triggerTime > currentTime }
            ?.minByOrNull { it.triggerTime }
    }
}
