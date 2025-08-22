package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Intelligentes Logging-System zur Reduzierung von Log Spam
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ Conditional Logging basierend auf Build-Type
 * ✅ Rate-Limiting für repetitive Logs mit Coroutine-Mutex
 * ✅ Strukturierte Log-Level-Strategie
 * ✅ Performance-optimiert für Production-Builds
 * ✅ Thread-safe Operations ohne Blocking
 */
object Logger {
    
    // Rate-Limiting für repetitive Logs mit Coroutine-Mutex
    private val rateLimitMutex = Mutex()
    private val lastLogTimes = mutableMapOf<String, Long>()
    private const val MIN_LOG_INTERVAL_MS = 1000L // 1 Sekunde zwischen gleichen Logs
    
    /**
     * ERROR: Nur für echte Fehler, die die App-Funktionalität beeinträchtigen
     * Wird in ALLEN Builds geloggt
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }
    
    /**
     * WARN: Für potenzielle Probleme oder unerwartete Situationen
     * Wird in ALLEN Builds geloggt
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).w(throwable, message)
        } else {
            Timber.tag(tag).w(message)
        }
    }
    
    /**
     * INFO: Für wichtige Business-Events und User-Aktionen
     * Wird in ALLEN Builds geloggt
     */
    fun i(tag: String, message: String) {
        Timber.tag(tag).i(message)
    }
    
    /**
     * DEBUG: Für Debugging-Informationen
     * Wird NUR in DEBUG-Builds geloggt
     */
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Timber.tag(tag).d(message)
        }
    }
    
    /**
     * VERBOSE: Für detaillierte Traces
     * Wird NUR in DEBUG-Builds geloggt
     */
    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Timber.tag(tag).v(message)
        }
    }
    
    /**
     * DEBUG mit Rate-Limiting - verhindert Log Spam bei repetitiven Aufrufen
     * Wird NUR in DEBUG-Builds geloggt und maximal einmal pro Sekunde pro unique message
     * PERFORMANCE: Verwendet Coroutine-Mutex für thread-safe Rate-Limiting
     */
    suspend fun dThrottled(tag: String, message: String) {
        if (!BuildConfig.DEBUG) return
        
        val shouldLog = rateLimitMutex.withLock {
            val key = "$tag:$message"
            val now = System.currentTimeMillis()
            val lastTime = lastLogTimes[key] ?: 0L
            
            if (now - lastTime >= MIN_LOG_INTERVAL_MS) {
                lastLogTimes[key] = now
                true
            } else {
                false
            }
        }
        
        if (shouldLog) {
            Timber.tag(tag).d(message)
        }
    }
    
    /**
     * Performance-Log: Für Performance-Messungen
     * Wird NUR in DEBUG-Builds geloggt
     */
    fun performance(tag: String, operation: String, durationMs: Long) {
        if (BuildConfig.DEBUG) {
            Timber.tag(tag).d("⏱️ $operation took ${durationMs}ms")
        }
    }
    
    /**
     * Business-Event: Für wichtige User-Aktionen (immer geloggt, aber strukturiert)
     */
    fun business(tag: String, event: String, details: String? = null) {
        val message = if (details != null) {
            "📊 $event: $details"
        } else {
            "📊 $event"
        }
        Timber.tag(tag).i(message)
    }
    
    /**
     * Cache-Event: Spezielle Logs für Cache-Operationen (nur in Debug)
     */
    fun cache(tag: String, operation: String, result: String) {
        if (BuildConfig.DEBUG) {
            Timber.tag(tag).d("💾 Cache $operation: $result")
        }
    }
    
    /**
     * Network-Event: Für API-Aufrufe und Netzwerk-Operationen
     */
    fun network(tag: String, operation: String, details: String? = null) {
        val message = if (details != null) {
            "🌐 $operation: $details"
        } else {
            "🌐 $operation"
        }
        
        if (BuildConfig.DEBUG) {
            Timber.tag(tag).d(message)
        } else {
            // In Production nur wichtige Network-Events
            if (operation.contains("failed", ignoreCase = true) || 
                operation.contains("error", ignoreCase = true)) {
                Timber.tag(tag).w(message)
            }
        }
    }
    
    /**
     * Cleanup für Rate-Limiting Map
     * PERFORMANCE: Thread-safe cleanup mit Coroutine-Mutex
     */
    suspend fun cleanup() {
        rateLimitMutex.withLock {
            val now = System.currentTimeMillis()
            lastLogTimes.entries.removeAll { (_, time) ->
                now - time > MIN_LOG_INTERVAL_MS * 10 // Entferne Einträge älter als 10 Sekunden
            }
        }
    }
}

/**
 * Extension functions für einfachere Nutzung
 */
fun Any.logd(message: String) = Logger.d(this::class.simpleName ?: "Unknown", message)
fun Any.logi(message: String) = Logger.i(this::class.simpleName ?: "Unknown", message)
fun Any.logw(message: String, throwable: Throwable? = null) = Logger.w(this::class.simpleName ?: "Unknown", message, throwable)
fun Any.loge(message: String, throwable: Throwable? = null) = Logger.e(this::class.simpleName ?: "Unknown", message, throwable)
fun Any.logBusiness(event: String, details: String? = null) = Logger.business(this::class.simpleName ?: "Unknown", event, details)
fun Any.logNetwork(operation: String, details: String? = null) = Logger.network(this::class.simpleName ?: "Unknown", operation, details)
