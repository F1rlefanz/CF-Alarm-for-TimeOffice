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
 */
class AutoOffWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val lightIds = inputData.getStringArray("light_ids") ?: emptyArray()
        val groupIds = inputData.getStringArray("group_ids") ?: emptyArray()

        if (lightIds.isEmpty() && groupIds.isEmpty()) {
            Logger.w(LogTags.HUE_BRIDGE, "💡 AUTO-OFF-WORKER: No targets provided, nothing to do")
            return@withContext Result.success()
        }

        Logger.i(LogTags.HUE_BRIDGE, "💡 AUTO-OFF-WORKER: Turning off ${lightIds.size} lights, ${groupIds.size} groups")

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
            if (result.isSuccess) {
                val batch = result.getOrNull()
                Logger.i(LogTags.HUE_BRIDGE, "💡✅ AUTO-OFF-WORKER: Switched off ${batch?.successfulActions ?: 0}/${batch?.totalActions ?: 0} targets")
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "💡⚠️ AUTO-OFF-WORKER: Auto-off failed", result.exceptionOrNull())
            }

            Result.success()
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "💡❌ AUTO-OFF-WORKER: Failed to switch off lights", e)
            Result.success()
        }
    }
}
