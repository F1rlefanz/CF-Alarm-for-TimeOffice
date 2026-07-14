package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartSchedulerEntryPoint
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightAction
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker that switches lights/groups OFF again after a rule's auto-off duration.
 *
 * Scheduled by HueSmartScheduler at (alarmTime + duration) for rules that turn lights ON with
 * a configured auto-off time. Turning already-off lights off is a harmless no-op, so a skipped
 * alarm causes no surprises.
 *
 * RETRY-VERHALTEN: Der Worker meldet NUR Erfolg, wenn die Bridge wirklich erreicht wurde.
 * Scheitert er (typisch: Handy ist nicht im Heim-WLAN, Bridge also unerreichbar), gibt er
 * Result.retry() zurueck und WorkManager versucht es mit Backoff erneut - spaetestens, wenn
 * das Handy wieder daheim ist. Frueher lieferte jeder Fehlerpfad Result.success(): der Job
 * galt damit als erledigt, obwohl nichts passiert war, und die Lampen blieben fuer immer an.
 *
 * Die Constraint NetworkType.CONNECTED ist ebenfalls erfuellt, wenn das Handy nur im
 * Mobilfunk haengt - deshalb reicht sie als Schutz nicht aus und der Retry ist zwingend.
 */
class AutoOffWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    companion object {
        /**
         * Nach ~2 Stunden Rueckversuchen aufgeben. Ein Auto-Off, das einen halben Tag zu spaet
         * kommt, ist keine Hilfe mehr, sondern eine Ueberraschung (Licht geht abends von selbst
         * aus, waehrend jemand im Raum sitzt).
         */
        private const val MAX_ATTEMPTS = 6
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lightIds = inputData.getStringArray("light_ids") ?: emptyArray()
        val groupIds = inputData.getStringArray("group_ids") ?: emptyArray()

        if (lightIds.isEmpty() && groupIds.isEmpty()) {
            Logger.w(LogTags.HUE_BRIDGE, "💡 AUTO-OFF-WORKER: No targets provided, nothing to do")
            return@withContext Result.success()
        }

        Logger.i(LogTags.HUE_BRIDGE, "💡 AUTO-OFF-WORKER: Turning off ${lightIds.size} lights, ${groupIds.size} groups (Versuch ${runAttemptCount + 1}/$MAX_ATTEMPTS)")

        return@withContext try {
            bridgeManager.forceHealthCheck()

            val lightUseCase = EntryPointAccessors
                .fromApplication(applicationContext, HueSmartSchedulerEntryPoint::class.java)
                .hueLightUseCase()

            val actions = buildList {
                lightIds.forEach { add(LightAction(targetId = it, isGroup = false, on = false)) }
                groupIds.forEach { add(LightAction(targetId = it, isGroup = true, on = false)) }
            }

            val result = lightUseCase.executeBatchLightActions(actions)
            val batch = result.getOrNull()

            when {
                result.isFailure -> {
                    Logger.w(LogTags.HUE_BRIDGE, "💡⚠️ AUTO-OFF-WORKER: Auto-off fehlgeschlagen", result.exceptionOrNull())
                    retryOrGiveUp()
                }

                // Teilerfolg zaehlt als Fehlschlag: solange auch nur eine Lampe nicht erreicht
                // wurde, bleibt sie an - genau der Zustand, den dieser Worker verhindern soll.
                batch == null || batch.successfulActions < batch.totalActions -> {
                    Logger.w(
                        LogTags.HUE_BRIDGE,
                        "💡⚠️ AUTO-OFF-WORKER: Nur ${batch?.successfulActions ?: 0}/${batch?.totalActions ?: actions.size} Ziele erreicht"
                    )
                    retryOrGiveUp()
                }

                else -> {
                    Logger.i(LogTags.HUE_BRIDGE, "💡✅ AUTO-OFF-WORKER: Switched off ${batch.successfulActions}/${batch.totalActions} targets")
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "💡❌ AUTO-OFF-WORKER: Failed to switch off lights", e)
            retryOrGiveUp()
        }
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Logger.w(
                LogTags.HUE_BRIDGE,
                "💡🛑 AUTO-OFF-WORKER: Nach $MAX_ATTEMPTS Versuchen aufgegeben - Lampen bleiben an. " +
                    "Bridge war durchgehend unerreichbar (Handy nicht im Heim-WLAN?)."
            )
            Result.failure()
        } else {
            Result.retry()
        }
}
