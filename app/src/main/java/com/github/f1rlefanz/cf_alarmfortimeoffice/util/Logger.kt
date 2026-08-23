package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import timber.log.Timber

/**
 * Intelligentes Logging-System zur Reduzierung von Log Spam
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ Conditional Logging basierend auf Build-Type
 * ✅ Strukturierte Log-Level-Strategie
 * ✅ Performance-optimiert für Production-Builds
 * ✅ Thread-safe Operations ohne Blocking
 */
object Logger {

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
}
