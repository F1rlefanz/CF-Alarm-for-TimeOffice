package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker for Daily Schedule Planning
 * 
 * PHASE 2: Runs once daily to recalculate and reschedule health checks
 * based on updated alarm times and shift patterns
 */
class DailySchedulePlanningWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val smartScheduler = HueSmartScheduler.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Logger.i(LogTags.HUE_BRIDGE, "📅 DAILY-PLANNER: Starting daily schedule recalculation")
        
        return@withContext try {
            // Recalculate and reschedule health checks for the next period
            smartScheduler.calculateAndScheduleNextHealthChecks()
            
            Logger.i(LogTags.HUE_BRIDGE, "✅ DAILY-PLANNER: Daily schedule planning completed successfully")
            Result.success()
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ DAILY-PLANNER: Failed to complete daily planning", e)
            
            // Retry once after 1 hour if planning fails
            Result.retry()
        }
    }
}
