package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fallback health check every 6 hours when no specific alarms are scheduled.
 */
class GenericHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_BRIDGE, "🔄 GENERIC-WORKER: Starting fallback health check")
        
        return@withContext try {
            val healthCheckResult = bridgeManager.forceHealthCheck()
            
            if (healthCheckResult) {
                Logger.d(LogTags.HUE_BRIDGE, "✅ GENERIC-WORKER: Fallback health check successful")
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "⚠️ GENERIC-WORKER: Fallback health check failed")
            }
            
            // Non-critical maintenance check - never fail or retry the work
            Result.success()
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ GENERIC-WORKER: Health check failed with exception", e)
            Result.success()
        }
    }
}
