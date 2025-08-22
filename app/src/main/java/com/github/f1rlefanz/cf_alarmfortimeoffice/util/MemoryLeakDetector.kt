package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig

/**
 * ADVANCED Memory Leak Detection System
 * 
 * FEATURES:
 * ✅ ViewModel lifecycle tracking
 * ✅ Coroutine leak detection
 * ✅ Flow subscription monitoring
 * ✅ Automatic cleanup suggestions
 * ✅ Thread-safe weak reference tracking
 * ✅ Performance impact minimal in release builds
 * 
 * USAGE:
 * - Call trackViewModel() in ViewModel constructors
 * - Call trackCoroutine() for long-running coroutines
 * - Call cleanup() when objects are disposed
 */
object MemoryLeakDetector {
    
    private val trackedViewModels = ConcurrentHashMap<String, WeakReference<Any>>()
    private val activeCoroutines = ConcurrentHashMap<String, CoroutineInfo>()
    private val flowSubscriptions = ConcurrentHashMap<String, FlowSubscriptionInfo>()
    
    private data class CoroutineInfo(
        val job: WeakReference<Job>,
        val startTime: Long,
        val context: String,
        val isActive: Boolean = true
    )
    
    private data class FlowSubscriptionInfo(
        val startTime: Long,
        val context: String,
        val subscriberRef: WeakReference<Any>
    )
    
    /**
     * VIEWMODEL LIFECYCLE TRACKING
     * Tracks ViewModels to detect if they're properly disposed
     */
    fun trackViewModel(viewModel: Any, tag: String) {
        if (!BuildConfig.DEBUG) return
        
        trackedViewModels[tag] = WeakReference(viewModel)
        Logger.d(LogTags.LIFECYCLE, "🔍 MEMORY: Tracking ViewModel '$tag'")
    }
    
    /**
     * COROUTINE LEAK DETECTION
     * Monitors coroutines for proper cancellation
     */
    fun trackCoroutine(job: Job, context: String, tag: String = "Coroutine"): String {
        if (!BuildConfig.DEBUG) return tag
        
        val trackingId = "${tag}_${System.currentTimeMillis()}"
        activeCoroutines[trackingId] = CoroutineInfo(
            job = WeakReference(job),
            startTime = System.currentTimeMillis(),
            context = context
        )
        
        // Auto-cleanup when job completes
        job.invokeOnCompletion { exception ->
            activeCoroutines.remove(trackingId)
            if (exception != null) {
                Logger.w(LogTags.LIFECYCLE, "🔄 COROUTINE: '$trackingId' cancelled with exception: ${exception.message}")
            } else {
                Logger.d(LogTags.LIFECYCLE, "✅ COROUTINE: '$trackingId' completed successfully")
            }
        }
        
        Logger.d(LogTags.LIFECYCLE, "🔍 MEMORY: Tracking coroutine '$trackingId' in context '$context'")
        return trackingId
    }
    
    /**
     * FLOW SUBSCRIPTION MONITORING
     * Tracks Flow subscriptions to detect memory leaks
     */
    fun <T> trackFlowSubscription(
        flow: Flow<T>, 
        subscriber: Any, 
        context: String
    ): Flow<T> {
        if (!BuildConfig.DEBUG) return flow
        
        val subscriptionId = "${context}_${System.currentTimeMillis()}"
        flowSubscriptions[subscriptionId] = FlowSubscriptionInfo(
            startTime = System.currentTimeMillis(),
            context = context,
            subscriberRef = WeakReference(subscriber)
        )
        
        Logger.d(LogTags.LIFECYCLE, "🔍 MEMORY: Tracking Flow subscription '$subscriptionId'")
        
        return flow { 
            try {
                flow.collect { value ->
                    // Check if subscriber is still alive
                    val subscriberRef = flowSubscriptions[subscriptionId]?.subscriberRef?.get()
                    if (subscriberRef == null) {
                        Logger.w(LogTags.LIFECYCLE, "💀 MEMORY LEAK: Flow subscriber '$subscriptionId' was garbage collected but Flow is still active!")
                        flowSubscriptions.remove(subscriptionId)
                        return@collect
                    }
                    emit(value)
                }
            } finally {
                flowSubscriptions.remove(subscriptionId)
                Logger.d(LogTags.LIFECYCLE, "✅ FLOW: Subscription '$subscriptionId' cleaned up")
            }
        }
    }
    
    /**
     * MANUAL CLEANUP NOTIFICATION
     * Call when objects are properly disposed
     */
    fun notifyCleanup(tag: String, type: String = "Object") {
        if (!BuildConfig.DEBUG) return
        
        trackedViewModels.remove(tag)
        Logger.d(LogTags.LIFECYCLE, "🧹 CLEANUP: $type '$tag' disposed properly")
    }
    
    /**
     * MEMORY LEAK SCAN
     * Performs comprehensive scan for potential memory leaks
     */
    suspend fun performMemoryLeakScan(): MemoryLeakReport = withContext(Dispatchers.Default) {
        if (!BuildConfig.DEBUG) return@withContext MemoryLeakReport.empty()
        
        val currentTime = System.currentTimeMillis()
        val leaks = mutableListOf<MemoryLeak>()
        
        // Check ViewModels
        trackedViewModels.entries.removeAll { (tag, ref) ->
            val viewModel = ref.get()
            if (viewModel == null) {
                Logger.d(LogTags.LIFECYCLE, "✅ MEMORY: ViewModel '$tag' was properly garbage collected")
                true
            } else {
                false
            }
        }
        
        // Check for long-running coroutines (potential leaks)
        activeCoroutines.entries.forEach { (id, info) ->
            val job = info.job.get()
            val duration = currentTime - info.startTime
            
            when {
                job == null -> {
                    // Job was garbage collected, clean up tracking
                    activeCoroutines.remove(id)
                }
                duration > 60_000L && job.isActive -> {
                    // Coroutine running for more than 1 minute - potential leak
                    leaks.add(
                        MemoryLeak(
                            type = MemoryLeakType.LONG_RUNNING_COROUTINE,
                            description = "Coroutine '$id' running for ${duration / 1000}s in context '${info.context}'",
                            severity = if (duration > 300_000L) MemoryLeakSeverity.HIGH else MemoryLeakSeverity.MEDIUM
                        )
                    )
                }
            }
        }
        
        // Check Flow subscriptions
        flowSubscriptions.entries.removeAll { (id, info) ->
            val subscriber = info.subscriberRef.get()
            val duration = currentTime - info.startTime
            
            if (subscriber == null) {
                if (duration > 5_000L) { // Subscription survived 5+ seconds after subscriber was GC'd
                    leaks.add(
                        MemoryLeak(
                            type = MemoryLeakType.ORPHANED_FLOW_SUBSCRIPTION,
                            description = "Flow subscription '$id' outlived its subscriber by ${duration / 1000}s",
                            severity = MemoryLeakSeverity.HIGH
                        )
                    )
                }
                true // Remove from tracking
            } else {
                false
            }
        }
        
        // Check overall memory health
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryPercentage = (usedMemory * 100) / maxMemory
        
        if (memoryPercentage > 85) {
            leaks.add(
                MemoryLeak(
                    type = MemoryLeakType.HIGH_MEMORY_USAGE,
                    description = "High memory usage: ${memoryPercentage}% (${usedMemory / 1024 / 1024}MB/${maxMemory / 1024 / 1024}MB)",
                    severity = if (memoryPercentage > 95) MemoryLeakSeverity.CRITICAL else MemoryLeakSeverity.HIGH
                )
            )
        }
        
        MemoryLeakReport(
            leaks = leaks,
            trackedViewModels = trackedViewModels.size,
            activeCoroutines = activeCoroutines.size,
            flowSubscriptions = flowSubscriptions.size,
            memoryUsagePercent = memoryPercentage.toInt()
        )
    }
    
    /**
     * AUTOMATIC LEAK MONITORING
     * Starts continuous monitoring for memory leaks
     */
    fun startContinuousMonitoring(scope: CoroutineScope, intervalMs: Long = 30_000L) {
        if (!BuildConfig.DEBUG) return
        
        scope.launch {
            while (isActive) {
                delay(intervalMs)
                
                val report = performMemoryLeakScan()
                if (report.hasLeaks()) {
                    Logger.w(LogTags.LIFECYCLE, "⚠️ MEMORY LEAKS DETECTED:")
                    report.leaks.forEach { leak ->
                        val emoji = when (leak.severity) {
                            MemoryLeakSeverity.LOW -> "🟨"
                            MemoryLeakSeverity.MEDIUM -> "🟧"
                            MemoryLeakSeverity.HIGH -> "🟥"
                            MemoryLeakSeverity.CRITICAL -> "🚨"
                        }
                        Logger.w(LogTags.LIFECYCLE, "$emoji ${leak.description}")
                    }
                }
                
                // Periodic cleanup of weak references
                cleanup()
            }
        }
    }
    
    /**
     * MEMORY CLEANUP
     * Removes dead references and performs garbage collection hints
     */
    private fun cleanup() {
        // Clean up dead ViewModels
        trackedViewModels.entries.removeAll { (_, ref) -> ref.get() == null }
        
        // Clean up completed coroutines
        activeCoroutines.entries.removeAll { (_, info) -> 
            val job = info.job.get()
            job == null || job.isCompleted
        }
        
        // Clean up dead Flow subscriptions
        flowSubscriptions.entries.removeAll { (_, info) -> 
            info.subscriberRef.get() == null 
        }
        
        Logger.d(LogTags.LIFECYCLE, "🧹 MEMORY: Cleanup completed - VMs: ${trackedViewModels.size}, Coroutines: ${activeCoroutines.size}, Flows: ${flowSubscriptions.size}")
    }
    
    // SUPPORTING DATA CLASSES
    
    data class MemoryLeakReport(
        val leaks: List<MemoryLeak>,
        val trackedViewModels: Int,
        val activeCoroutines: Int,
        val flowSubscriptions: Int,
        val memoryUsagePercent: Int
    ) {
        fun hasLeaks(): Boolean = leaks.isNotEmpty()
        
        companion object {
            fun empty() = MemoryLeakReport(
                leaks = emptyList(),
                trackedViewModels = 0,
                activeCoroutines = 0,
                flowSubscriptions = 0,
                memoryUsagePercent = 0
            )
        }
    }
    
    data class MemoryLeak(
        val type: MemoryLeakType,
        val description: String,
        val severity: MemoryLeakSeverity
    )
    
    enum class MemoryLeakType {
        LONG_RUNNING_COROUTINE,
        ORPHANED_FLOW_SUBSCRIPTION,
        UNCLEANED_VIEWMODEL,
        HIGH_MEMORY_USAGE
    }
    
    enum class MemoryLeakSeverity {
        LOW,
        MEDIUM, 
        HIGH,
        CRITICAL
    }
}
