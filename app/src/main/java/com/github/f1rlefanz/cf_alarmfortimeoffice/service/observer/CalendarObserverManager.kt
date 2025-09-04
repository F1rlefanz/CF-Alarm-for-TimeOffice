package com.github.f1rlefanz.cf_alarmfortimeoffice.service.observer

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags

/**
 * ⚡ SMART MAINTENANCE CHAIN Level 3: Calendar Observer Manager
 * 
 * Verwaltet den Lifecycle des CalendarContentObservers als Teil der Smart Maintenance Chain.
 * Stellt sicher, dass Level 3 (reactive updates) nahtlos mit Level 1 + Level 2 zusammenarbeitet.
 * 
 * LIFECYCLE MANAGEMENT:
 * - Automatic registration/unregistration basierend auf App-Lifecycle
 * - Integration mit bestehenden Service-Management-Patterns
 * - Graceful error handling ohne Beeinträchtigung anderer Levels
 * 
 * INTEGRATION POINTS:
 * - MainActivity für App-Foreground/Background-Lifecycle
 * - CFAlarmApplication für App-weite Initialisierung
 * - Bestehende Service-Manager für konsistentes Pattern
 */
object CalendarObserverManager {
    
    private var currentObserver: CalendarContentObserver? = null
    private var isRegistered: Boolean = false
    
    /**
     * Startet Level 3 der Smart Maintenance Chain
     * 
     * @param context Application Context für Observer Registration
     * @return Boolean - true wenn erfolgreich gestartet
     */
    fun startCalendarObserver(context: Context): Boolean {
        return try {
            if (isRegistered) {
                Logger.d(LogTags.MAINTENANCE_L3, "⚡ LEVEL 3: Observer already registered, skipping")
                return true
            }
            
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "⚡ LEVEL 3: Starting Calendar Content Observer",
                "Integration with Level 1 + Level 2"
            )
            
            currentObserver = CalendarContentObserver.register(context.applicationContext)
            isRegistered = true
            
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "✅ LEVEL 3: Calendar Observer started successfully",
                "Smart Maintenance Chain Level 3 active"
            )
            
            true
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Failed to start Calendar Observer", e)
            isRegistered = false
            currentObserver = null
            false
        }
    }
    
    /**
     * Stoppt Level 3 der Smart Maintenance Chain
     * 
     * @param context Application Context für Observer Unregistration
     */
    fun stopCalendarObserver(context: Context) {
        try {
            if (!isRegistered || currentObserver == null) {
                Logger.d(LogTags.MAINTENANCE_L3, "⚡ LEVEL 3: Observer not registered, skipping stop")
                return
            }
            
            Logger.business(LogTags.MAINTENANCE_L3, "🛑 LEVEL 3: Stopping Calendar Content Observer")
            
            CalendarContentObserver.unregister(context.applicationContext, currentObserver!!)
            currentObserver = null
            isRegistered = false
            
            Logger.business(
                LogTags.MAINTENANCE_L3,
                "✅ LEVEL 3: Calendar Observer stopped successfully",
                "Level 1 + Level 2 continue operating normally"
            )
            
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L3, "❌ LEVEL 3: Error stopping Calendar Observer", e)
            // Force cleanup on error
            currentObserver = null
            isRegistered = false
        }
    }
    
    /**
     * Prüft ob Level 3 aktiv ist
     * 
     * @return Boolean - true wenn Observer registriert und aktiv
     */
    fun isObserverActive(): Boolean {
        return isRegistered && currentObserver != null
    }
    
    /**
     * Restart Level 3 (z.B. nach Konfigurationsänderungen)
     * 
     * @param context Application Context
     * @return Boolean - true wenn erfolgreich restarted
     */
    fun restartCalendarObserver(context: Context): Boolean {
        Logger.business(LogTags.MAINTENANCE_L3, "🔄 LEVEL 3: Restarting Calendar Observer")
        
        stopCalendarObserver(context)
        return startCalendarObserver(context)
    }
    
    /**
     * Gibt Observer-Status und Statistiken zurück
     * 
     * @return String mit detailliertem Status für Debugging
     */
    fun getObserverStatus(): String {
        return buildString {
            appendLine("=== SMART MAINTENANCE CHAIN LEVEL 3 STATUS ===")
            appendLine("Observer registered: $isRegistered")
            appendLine("Observer instance: ${if (currentObserver != null) "Active" else "Null"}")
            appendLine("Integration status: ${if (isObserverActive()) "✅ Active" else "❌ Inactive"}")
            
            currentObserver?.let { observer ->
                appendLine("")
                appendLine(observer.getObserverStats())
            }
            
            appendLine("===============================================")
        }
    }
    
    /**
     * Emergency cleanup - für kritische Situationen
     */
    fun emergencyCleanup() {
        try {
            Logger.w(LogTags.MAINTENANCE_L3, "🚨 LEVEL 3: Emergency cleanup initiated")
            
            currentObserver?.cleanup()
            currentObserver = null
            isRegistered = false
            
            Logger.w(LogTags.MAINTENANCE_L3, "✅ LEVEL 3: Emergency cleanup completed")
        } catch (e: Exception) {
            Logger.e(LogTags.MAINTENANCE_L3, "💥 LEVEL 3: Error during emergency cleanup", e)
        }
    }
}
