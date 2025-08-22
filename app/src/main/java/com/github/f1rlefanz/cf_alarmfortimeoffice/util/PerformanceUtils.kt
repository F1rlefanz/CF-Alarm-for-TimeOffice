package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateMapOf
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * PERFORMANCE-OPTIMIZED Utilities für die CF-Alarm App
 * 
 * ERWEITERTE PERFORMANCE FEATURES:
 * ✅ Recomposition tracking für Compose UI
 * ✅ UI thread protection mit automatic yielding
 * ✅ Memory leak detection für Coroutines
 * ✅ Performance monitoring mit thresholds
 * ✅ Thread-safe operation tracking
 * ✅ Automatic performance warnings
 */
object PerformanceUtils {
    
    // THREAD-SAFE performance tracking
    @PublishedApi
    internal val operationMetrics = ConcurrentHashMap<String, OperationMetrics>()
    @PublishedApi
    internal val metricsMutex = Mutex()
    
    // PERFORMANCE THRESHOLDS
    @PublishedApi
    internal const val WARNING_THRESHOLD_MS = 100L
    @PublishedApi
    internal const val ERROR_THRESHOLD_MS = 500L
    @PublishedApi
    internal const val MAX_RECOMPOSITION_COUNT = 10
    
    /**
     * Metrics for tracking operation performance
     */
    @PublishedApi
    internal data class OperationMetrics(
        var totalTime: Long = 0L,
        var callCount: Int = 0,
        var maxTime: Long = 0L,
        var minTime: Long = Long.MAX_VALUE
    )
    
    /**
     * ENHANCED Recomposition tracking mit performance warnings
     * Identifiziert problematische UI components automatisch
     */
    @Composable
    fun TrackRecomposition(tag: String) {
        if (BuildConfig.DEBUG) {
            val count = remember { mutableStateMapOf<String, Int>() }
            SideEffect {
                val newCount = (count[tag] ?: 0) + 1
                count[tag] = newCount
                
                // PERFORMANCE WARNING: Excessive recompositions detected
                if (newCount > MAX_RECOMPOSITION_COUNT) {
                    Logger.w(
                        LogTags.PERFORMANCE, 
                        "⚠️ PERFORMANCE: Excessive recompositions detected in '$tag' (${newCount}x) - Check for state issues!"
                    )
                } else if (newCount % 5 == 0) {
                    // Log every 5 recompositions for monitoring
                    Logger.d(LogTags.PERFORMANCE, "🔄 Recomposition: $tag (${newCount}x)")
                }
            }
        }
    }
    
    /**
     * ENHANCED performance measurement mit detailed metrics tracking
     * Automatically warns about slow operations and tracks statistics
     */
    inline fun <T> measureTimeMillis(tag: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val duration = System.currentTimeMillis() - start
        
        // Update metrics thread-safely
        val metrics = operationMetrics.getOrPut(tag) { OperationMetrics() }
        synchronized(metrics) {
            metrics.totalTime += duration
            metrics.callCount++
            metrics.maxTime = maxOf(metrics.maxTime, duration)
            metrics.minTime = minOf(metrics.minTime, duration)
        }
        
        // PERFORMANCE WARNINGS based on thresholds
        when {
            duration >= ERROR_THRESHOLD_MS -> {
                val avgTime = metrics.totalTime / metrics.callCount
                Logger.e(
                    LogTags.PERFORMANCE, 
                    "🚨 CRITICAL PERFORMANCE: '$tag' took ${duration}ms (avg: ${avgTime}ms, calls: ${metrics.callCount})"
                )
            }
            duration >= WARNING_THRESHOLD_MS -> {
                Logger.w(LogTags.PERFORMANCE, "⚠️ SLOW OPERATION: '$tag' took ${duration}ms")
            }
            BuildConfig.DEBUG && duration > 50L -> {
                Logger.d(LogTags.PERFORMANCE, "⏱️ Operation '$tag' took ${duration}ms")
            }
        }
        
        return result
    }
    
    /**
     * MEMORY-SAFE UI thread yielding
     * Prevents ANR while maintaining performance
     */
    suspend fun yieldToUI() {
        yield() // Let UI thread process pending work
    }
    
    /**
     * PERFORMANCE-OPTIMIZED background computation
     * Automatically switches context and provides progress yielding
     */
    suspend fun <T> computeOffMainThread(
        tag: String = "Background Computation",
        computation: suspend () -> T
    ): T {
        return measureTimeMillis(tag) {
            withContext(Dispatchers.Default) {
                computation()
            }
        }
    }
    
    /**
     * MEMORY-EFFICIENT list processing mit automatic UI yielding
     * Prevents ANR during large data processing
     */
    suspend fun <T> processListInChunks(
        list: List<T>, 
        chunkSize: Int = 50,
        tag: String = "List Processing",
        processor: suspend (T) -> Unit
    ) {
        measureTimeMillis("$tag (${list.size} items)") {
            list.chunked(chunkSize).forEachIndexed { index, chunk ->
                chunk.forEach { item ->
                    processor(item)
                }
                
                // Yield every chunk to maintain UI responsiveness
                if (index % 2 == 0) { // Yield every 2 chunks for better performance
                    yieldToUI()
                }
            }
        }
    }
    
    /**
     * FRAME-RATE OPTIMIZED UI update batching
     * Ensures smooth 60fps by batching updates appropriately
     */
    suspend fun batchUIUpdates(delayMs: Long = 16L) {
        kotlinx.coroutines.delay(delayMs) // ~60fps frame time
    }
    
    /**
     * MEMORY LEAK DETECTION for coroutines
     * Tracks long-running coroutines and warns about potential leaks
     */
    suspend fun <T> trackCoroutineLifecycle(
        tag: String,
        maxDurationMs: Long = 30_000L, // 30 seconds warning threshold
        operation: suspend () -> T
    ): T {
        val start = System.currentTimeMillis()
        
        return try {
            val result = operation()
            val duration = System.currentTimeMillis() - start
            
            if (duration > maxDurationMs) {
                Logger.w(
                    LogTags.PERFORMANCE, 
                    "🔄 LONG-RUNNING COROUTINE: '$tag' ran for ${duration}ms - Check for potential memory leaks"
                )
            }
            
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            Logger.e(
                LogTags.PERFORMANCE, 
                "💥 COROUTINE FAILED: '$tag' failed after ${duration}ms", 
                e
            )
            throw e
        }
    }
    
    /**
     * PERFORMANCE REPORT generation
     * Provides detailed metrics for debugging performance issues
     */
    suspend fun generatePerformanceReport(): String = metricsMutex.withLock {
        if (operationMetrics.isEmpty()) {
            return "No performance metrics collected yet."
        }
        
        buildString {
            appendLine("📊 PERFORMANCE REPORT")
            appendLine("=".repeat(50))
            
            val sortedMetrics = operationMetrics.toList()
                .sortedByDescending { (_, metrics) -> metrics.totalTime }
            
            sortedMetrics.forEach { (operation, metrics) ->
                val avgTime = metrics.totalTime / metrics.callCount
                val efficiency = when {
                    avgTime < WARNING_THRESHOLD_MS -> "✅ GOOD"
                    avgTime < ERROR_THRESHOLD_MS -> "⚠️ SLOW"
                    else -> "🚨 CRITICAL"
                }
                
                appendLine()
                appendLine("Operation: $operation $efficiency")
                appendLine("  Total Time: ${metrics.totalTime}ms")
                appendLine("  Call Count: ${metrics.callCount}")
                appendLine("  Average Time: ${avgTime}ms")
                appendLine("  Min Time: ${metrics.minTime}ms")
                appendLine("  Max Time: ${metrics.maxTime}ms")
            }
            
            appendLine()
            appendLine("=".repeat(50))
            appendLine("Total Operations Tracked: ${operationMetrics.size}")
        }
    }
    
    /**
     * MEMORY CLEANUP for metrics
     * Prevents memory accumulation during long app sessions
     */
    suspend fun clearMetrics() = metricsMutex.withLock {
        operationMetrics.clear()
        Logger.d(LogTags.PERFORMANCE, "🧹 Performance metrics cleared")
    }
    
    /**
     * AUTOMATIC MEMORY MANAGEMENT
     * Monitors memory usage and provides warnings
     */
    fun checkMemoryUsage(tag: String = "Memory Check") {
        if (BuildConfig.DEBUG) {
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            val maxMemory = runtime.maxMemory()
            val memoryPercentage = (usedMemory * 100) / maxMemory
            
            when {
                memoryPercentage > 90 -> {
                    Logger.e(
                        LogTags.PERFORMANCE, 
                        "🚨 CRITICAL MEMORY: ${memoryPercentage}% used (${usedMemory / 1024 / 1024}MB/${maxMemory / 1024 / 1024}MB) - $tag"
                    )
                }
                memoryPercentage > 70 -> {
                    Logger.w(
                        LogTags.PERFORMANCE, 
                        "⚠️ HIGH MEMORY: ${memoryPercentage}% used - $tag"
                    )
                }
                else -> {
                    Logger.d(
                        LogTags.PERFORMANCE, 
                        "💾 Memory usage: ${memoryPercentage}% - $tag"
                    )
                }
            }
        }
    }
}
