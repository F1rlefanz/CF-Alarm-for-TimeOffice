package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.os.PowerManager
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

/**
 * Manages WakeLocks for alarm operations with proper resource management
 * and automatic cleanup to prevent battery drain.
 * 
 * Features:
 * - Automatic release after timeout
 * - Resource leak prevention
 * - Structured lifecycle management
 * - Thread-safe operations
 */
interface IWakeLockManager {
    /**
     * Acquires a partial wake lock for alarm operations
     * @param tag Unique tag for this wake lock
     * @param timeoutMs Maximum time to hold the lock (default: 60 seconds)
     * @return Success/failure result
     */
    suspend fun acquireAlarmWakeLock(tag: String, timeoutMs: Long = 60_000): Result<Unit>
    
    /**
     * Releases a specific wake lock
     * @param tag Tag of the wake lock to release
     */
    suspend fun releaseWakeLock(tag: String): Result<Unit>
    
    /**
     * Releases all wake locks (cleanup)
     */
    suspend fun releaseAllWakeLocks()
    
    /**
     * Checks if a wake lock is currently held
     */
    fun isWakeLockHeld(tag: String): Boolean
}

class WakeLockManager(
    private val context: Context
) : IWakeLockManager {
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activeLocks = ConcurrentHashMap<String, WakeLockInfo>()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private data class WakeLockInfo(
        val wakeLock: PowerManager.WakeLock,
        val timeoutJob: Job
    )
    
    override suspend fun acquireAlarmWakeLock(tag: String, timeoutMs: Long): Result<Unit> {
        return try {
            // Release existing lock with same tag first
            releaseWakeLock(tag)
            
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CFAlarm::$tag"
            ).apply {
                setReferenceCounted(false) // Manual management
            }
            
            wakeLock.acquire(timeoutMs)
            
            // Setup automatic cleanup
            val timeoutJob = cleanupScope.launch {
                delay(timeoutMs)
                Logger.w(LogTags.WAKE_LOCK, "WakeLock timeout reached, auto-releasing: $tag")
                releaseWakeLock(tag)
            }
            
            activeLocks[tag] = WakeLockInfo(wakeLock, timeoutJob)
            
            Logger.d(LogTags.WAKE_LOCK, "✅ WakeLock acquired: $tag (timeout: ${timeoutMs}ms)")
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.WAKE_LOCK, "❌ Failed to acquire WakeLock: $tag", e)
            Result.failure(e)
        }
    }
    
    override suspend fun releaseWakeLock(tag: String): Result<Unit> {
        return try {
            activeLocks.remove(tag)?.let { lockInfo ->
                // Cancel timeout job
                lockInfo.timeoutJob.cancel()
                
                // Release wake lock if still held
                if (lockInfo.wakeLock.isHeld) {
                    lockInfo.wakeLock.release()
                    Logger.d(LogTags.WAKE_LOCK, "✅ WakeLock released: $tag")
                } else {
                    Logger.d(LogTags.WAKE_LOCK, "⚠️ WakeLock already released: $tag")
                }
            }
            Result.success(Unit)
            
        } catch (e: Exception) {
            Logger.e(LogTags.WAKE_LOCK, "❌ Failed to release WakeLock: $tag", e)
            Result.failure(e)
        }
    }
    
    override suspend fun releaseAllWakeLocks() {
        Logger.i(LogTags.WAKE_LOCK, "🧹 Releasing all WakeLocks (${activeLocks.size} active)")
        
        val lockTags = activeLocks.keys.toList()
        lockTags.forEach { tag ->
            releaseWakeLock(tag)
        }
        
        // Cancel cleanup scope
        cleanupScope.cancel()
    }
    
    override fun isWakeLockHeld(tag: String): Boolean {
        return activeLocks[tag]?.wakeLock?.isHeld ?: false
    }
    
    /**
     * Gets debug information about active locks
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== WakeLock Manager Debug ===")
            appendLine("Active locks: ${activeLocks.size}")
            activeLocks.forEach { (tag, lockInfo) ->
                appendLine("- $tag: held=${lockInfo.wakeLock.isHeld}")
            }
            appendLine("============================")
        }
    }
    
    companion object {
        // Common WakeLock tags
        const val TAG_ALARM_RECEIVER = "AlarmReceiver"
        const val TAG_ALARM_SOUND = "AlarmSound"
        const val TAG_FULLSCREEN_ACTIVITY = "FullScreenActivity"
    }
}

/**
 * Extension function for easy WakeLock usage with automatic resource management
 */
suspend inline fun <T> IWakeLockManager.withWakeLock(
    tag: String,
    timeoutMs: Long = 60_000,
    action: suspend () -> T
): Result<T> {
    return try {
        acquireAlarmWakeLock(tag, timeoutMs).getOrThrow()
        val result = action()
        releaseWakeLock(tag)
        Result.success(result)
    } catch (e: Exception) {
        releaseWakeLock(tag)
        Result.failure(e)
    }
}
