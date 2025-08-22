package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.workers

import android.content.Context
import androidx.work.*
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.connection.HueBridgeConnectionManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * WorkManager Worker for Pre-Alarm Health Checks
 * 
 * PHASE 2: Smart Scheduling - Critical health check before alarm execution
 * Ensures Hue Bridge connection is validated 10 minutes before alarm time
 */
class PreAlarmHealthCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val bridgeManager = HueBridgeConnectionManager.getInstance(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val alarmTimeString = inputData.getString("alarm_time")
        val checkIndex = inputData.getInt("check_index", 0)
        
        Logger.i(LogTags.HUE_BRIDGE, "🚨 PRE-ALARM-WORKER: Starting critical health check before alarm")
        Logger.d(LogTags.HUE_BRIDGE, "⏰ PRE-ALARM-WORKER: Alarm time: $alarmTimeString, Index: $checkIndex")
        
        return@withContext try {
            // Perform critical health check
            val healthCheckResult = bridgeManager.forceHealthCheck()
            
            if (healthCheckResult) {
                Logger.i(LogTags.HUE_BRIDGE, "✅ PRE-ALARM-WORKER: Health check successful - Bridge ready for alarm")
                
                // Optional: Pre-load light targets for faster alarm execution
                // This could be added later to further optimize alarm response time
                
                Result.success()
            } else {
                Logger.w(LogTags.HUE_BRIDGE, "⚠️ PRE-ALARM-WORKER: Health check failed - Bridge may not be ready")
                
                // Don't fail the work - this is just a preparatory check
                // The actual alarm execution will handle connection recovery
                Result.success()
            }
            
        } catch (e: Exception) {
            Logger.e(LogTags.HUE_BRIDGE, "❌ PRE-ALARM-WORKER: Health check failed with exception", e)
            
            // Don't retry on exception - this is a preparatory check
            // The alarm execution will handle any connection issues
            Result.success()
        }
    }
}
