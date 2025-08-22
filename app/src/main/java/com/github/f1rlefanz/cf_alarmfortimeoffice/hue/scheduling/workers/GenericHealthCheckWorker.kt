package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager Worker for Generic Health Checks
 * 
 * PHASE 2: Fallback health checks when no specific alarms are scheduled
 * Runs every 6 hours as a safety net to maintain basic connection health
 */
class GenericHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Logger.d(LogTags.HUE_BRIDGE, "🔄 GENERIC-WORKER: Starting fallback health check")
        
        return@withContext try {
            // Perform basic health check
            val healthCheckResult = bridgeManager.forceHealthCheck()
            
            if (healthCheckResult) {
                Logger.d(LogTags.HUE_BRIDGE, "✅ GENERIC-WORKER: Fallback health check successful")
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "⚠️ GENERIC-WORKER: Fallback health check failed")
            }
            
            // Always return success for generic checks
            // These are non-critical maintenance checks
            Result.success()
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ GENERIC-WORKER: Health check failed with exception", e)
            
            // Don't retry generic health checks aggressively
            Result.success()
        }
    }
}
