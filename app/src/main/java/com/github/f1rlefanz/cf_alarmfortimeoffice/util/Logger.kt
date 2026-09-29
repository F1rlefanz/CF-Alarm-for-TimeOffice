package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import com.github.f1rlefanz.cf_alarmfortimeoffice.BuildConfig
import timber.log.Timber

/**
 * Logcat nur im Debug; Datei-Log im Debug alles, im Release nur WARN+
 * (siehe Skill `cfalarm-bauen-und-testen`).
 */
object Logger {

    /**
     * ERROR: Nur für echte Fehler, die die App-Funktionalität beeinträchtigen.
     * Steht auch im Release-Datei-Log.
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }
    
    /**
     * WARN: Für potenzielle Probleme oder unerwartete Situationen.
     * Steht auch im Release-Datei-Log.
     */
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).w(throwable, message)
        } else {
            Timber.tag(tag).w(message)
        }
    }
    
    /**
     * INFO: Für wichtige Business-Events und User-Aktionen.
     * Nur im Debug sichtbar - das Release-Datei-Log nimmt erst WARN+.
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
     * Business-Event: Für wichtige User-Aktionen, auf INFO - also nur im Debug sichtbar.
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
}
