package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartSchedulerEntryPoint
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker that starts a pre-alarm sunrise ramp.
 *
 * Scheduled by HueSmartScheduler at (alarmTime - sunriseDuration) for shifts that have an
 * enabled "start before alarm" sunrise rule, so the gradual warm→cool ramp finishes exactly
 * at the alarm time. Sunrises configured to start AT the alarm are handled by the at-alarm
 * path in AlarmReceiver instead.
 */
class SunriseStartWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val shiftName = inputData.getString("shift_name")
        if (shiftName.isNullOrBlank()) {
            Logger.w(LogTags.HUE_BRIDGE, "🌅 SUNRISE-WORKER: No shift name provided, nothing to do")
            return@withContext Result.success()
        }

        Logger.i(LogTags.HUE_BRIDGE, "🌅 SUNRISE-WORKER: Starting pre-alarm sunrise for shift: $shiftName")

        return@withContext try {
            // Ensure the bridge is reachable before kicking off the ramp.
            bridgeManager.forceHealthCheck()

            val ruleUseCase = EntryPointAccessors
                .fromApplication(applicationContext, HueSmartSchedulerEntryPoint::class.java)
                .hueRuleUseCase()

            val result = ruleUseCase.executeSunrisePreAlarm(shiftName)
            if (result.isSuccess) {
                val exec = result.getOrNull()
                Logger.i(
                    LogTags.HUE_BRIDGE,
                    "🌅✅ SUNRISE-WORKER: Sunrise started (${exec?.successfulActions ?: 0}/${exec?.actionsExecuted ?: 0} targets)"
                )
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "🌅⚠️ SUNRISE-WORKER: Sunrise execution failed", result.exceptionOrNull())
            }

            Result.success()
        } catch (e: Exception) {
            // Don't retry: the at-alarm path finalizes the lights anyway, so a missed ramp
            // never leaves the user without their wake-up light.
            Logger.e(LogTags.HUE_BRIDGE, "🌅❌ SUNRISE-WORKER: Failed to start sunrise", e)
            Result.success()
        }
    }
}
