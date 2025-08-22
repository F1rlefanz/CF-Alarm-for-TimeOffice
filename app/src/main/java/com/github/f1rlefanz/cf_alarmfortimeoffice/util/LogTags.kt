package com.github.f1rlefanz.cf_alarmfortimeoffice.util

/**
 * Zentrale Log-Tags für strukturiertes Logging
 * 
 * VORTEILE:
 * - Konsistente Tag-Namensgebung
 * - Einfache Filterung in Logcat
 * - Vermeidung von Typos in Log-Tags
 * - Kategorisierte Log-Gruppen
 */
object LogTags {
    
    // === AUTHENTICATION & TOKEN ===
    const val AUTH = "CFAlarm.Auth"
    const val TOKEN = "CFAlarm.Token"
    const val OAUTH = "CFAlarm.OAuth"
    
    // === CALENDAR OPERATIONS ===
    const val CALENDAR = "CFAlarm.Calendar"
    
    // === HUE INTEGRATION ===
    const val HUE = "CFAlarm.Hue"
    const val HUE_INTEGRATION = "CFAlarm.Hue.Integration"
    const val HUE_BRIDGE = "CFAlarm.Hue.Bridge"
    const val HUE_LIGHTS = "CFAlarm.Hue.Lights"
    const val HUE_RULES = "CFAlarm.Hue.Rules"
    const val HUE_CONFIG = "CFAlarm.Hue.Config"
    const val HUE_DISCOVERY = "CFAlarm.Hue.Discovery"
    const val HUE_USECASE = "CFAlarm.Hue.UseCase"
    const val HUE_VIEWMODEL = "CFAlarm.Hue.ViewModel"
    const val CALENDAR_CACHE = "CFAlarm.Cal.Cache"
    const val CALENDAR_API = "CFAlarm.Cal.API"
    
    // === SHIFT MANAGEMENT ===
    const val SHIFT = "CFAlarm.Shift"
    const val SHIFT_CONFIG = "CFAlarm.Shift.Config"
    const val SHIFT_RECOGNITION = "CFAlarm.Shift.Recognition"
    
    // === ALARM SYSTEM ===
    const val ALARM = "CFAlarm.Alarm"
    const val ALARM_MANAGER = "CFAlarm.Alarm.Manager"
    const val ALARM_RECEIVER = "CFAlarm.Alarm.Receiver"
    const val ALARM_SKIP = "CFAlarm.Alarm.Skip"
    const val ALARM_TESTING = "CFAlarm.Alarm.Testing" // 🚨 DEBUG: For alarm testing
    const val ALARM_AUDIO = "CFAlarm.Alarm.Audio"
    const val WAKE_LOCK = "CFAlarm.Alarm.WakeLock"
    const val BATTERY_OPTIMIZATION = "CFAlarm.Alarm.Battery"
    
    // === USER INTERFACE ===
    const val UI = "CFAlarm.UI"
    const val NAVIGATION = "CFAlarm.Navigation"
    const val VIEWMODEL = "CFAlarm.ViewModel"
    
    // === DATA PERSISTENCE ===
    const val DATASTORE = "CFAlarm.DataStore"
    const val REPOSITORY = "CFAlarm.Repository"
    const val DI = "CFAlarm.DI"
    
    // === SYSTEM & LIFECYCLE ===
    const val APP = "CFAlarm.App"
    const val LIFECYCLE = "CFAlarm.Lifecycle"
    const val PERMISSIONS = "CFAlarm.Permissions"
    
    // === PERFORMANCE & DEBUG ===
    const val PERFORMANCE = "CFAlarm.Performance"
    const val CACHE = "CFAlarm.Cache"
    const val NETWORK = "CFAlarm.Network"
    const val HUE_NETWORK = "CFAlarm.Hue.Network"
    const val BACKGROUND_SYNC = "CFAlarm.Background.Sync"
    
    // === PHASE 2: SECURITY & VALIDATION ===
    const val SECURITY = "CFAlarm.Security"
    const val ROOT_DETECTION = "CFAlarm.Security.Root"
    const val ENCRYPTION = "CFAlarm.Security.Encryption"
    const val INTEGRITY = "CFAlarm.Security.Integrity"
    const val NETWORK_SECURITY = "CFAlarm.Security.Network"
    
    // === HTTPS-FIRST HUE INTEGRATION ===
    const val HUE_SECURITY = "CFAlarm.Hue.Security"
    const val HUE_HTTPS = "CFAlarm.Hue.HTTPS"
    const val HUE_PROTOCOL = "CFAlarm.Hue.Protocol"
    
    // === ERROR & RECOVERY ===
    const val ERROR = "CFAlarm.Error"
    const val RECOVERY = "CFAlarm.Recovery"
    const val OFFLINE = "CFAlarm.Offline"
    const val VALIDATION = "CFAlarm.Validation"
    const val SYSTEM = "CFAlarm.System"
    const val FILE_SYSTEM = "CFAlarm.FileSystem"
    const val PREFERENCES = "CFAlarm.Preferences"
}

/**
 * Log-Level-Richtlinien für bessere Signal-to-Noise Ratio
 */
object LogPriority {
    
    /**
     * ERROR: Nur für kritische Fehler
     * - App-Crashes
     * - API-Fehler die die Funktionalität beeinträchtigen
     * - Datenverlust-Risiken
     */
    const val ERROR_GUIDELINES = """
        ERROR-Logs für:
        ❌ App-Crashes und kritische Fehler
        ❌ API-Fehler die User-Features blockieren  
        ❌ Datenverlust oder -korruption
        ❌ Security-relevante Probleme
    """
    
    /**
     * WARN: Für potenzielle Probleme
     * - Deprecated API Usage
     * - Fallback-Mechanismen aktiviert
     * - Performance-Probleme
     * - Konfigurationsfehler
     */
    const val WARN_GUIDELINES = """
        WARN-Logs für:
        ⚠️ Deprecated APIs oder veraltete Methoden
        ⚠️ Fallback auf alternative Implementierung
        ⚠️ Performance-Probleme (lange Ladezeiten)
        ⚠️ Konfigurationsfehler die behoben werden können
    """
    
    /**
     * INFO: Für wichtige Business-Events
     * - User-Aktionen (Login, Kalenderwahl, etc.)
     * - App-Lifecycle-Events
     * - Erfolgreiche API-Aufrufe
     * - Cache-Statistiken
     */
    const val INFO_GUIDELINES = """
        INFO-Logs für:
        ℹ️ Wichtige User-Aktionen (Login, Auswahl, etc.)
        ℹ️ App-Lifecycle (Start, Background, etc.)
        ℹ️ Erfolgreiche API-Operationen mit Ergebnissen
        ℹ️ Cache-Treffer und Performance-Verbesserungen
    """
    
    /**
     * DEBUG: Nur für Development (BuildConfig.DEBUG)
     * - Detaillierte Flow-Traces
     * - Parameter-Werte
     * - Internal State Changes
     * - Cache-Operationen
     */
    const val DEBUG_GUIDELINES = """
        DEBUG-Logs für (nur in Debug-Builds):
        🐛 Detaillierte Flow-Traces durch die App
        🐛 Parameter-Werte und State-Changes
        🐛 Internal Operations (Cache, DB, etc.)
        🐛 Debugging-spezifische Informationen
    """
}
