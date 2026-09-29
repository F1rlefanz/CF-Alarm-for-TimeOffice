package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs once daily to recalculate and reschedule the Hue health checks from the current alarms.
 */
class DailySchedulePlanningWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val smartScheduler = HueSmartScheduler.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_BRIDGE, "📅 DAILY-PLANNER: Starting daily schedule recalculation")
        
        return@withContext try {
            smartScheduler.calculateAndScheduleNextHealthChecks()
            
            Logger.i(LogTags.HUE_BRIDGE, "✅ DAILY-PLANNER: Daily schedule planning completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ DAILY-PLANNER: Failed to complete daily planning", e)
            Result.retry()
        }
    }
}
