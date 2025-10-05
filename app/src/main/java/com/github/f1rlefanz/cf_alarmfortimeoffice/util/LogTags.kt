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
    const val CALENDAR_CACHE = "CFAlarm.Cal.Cache"
    const val CALENDAR_API = "CFAlarm.Cal.API"
    
    // === HUE INTEGRATION ===
    const val HUE = "CFAlarm.Hue"
    const val HUE_BRIDGE = "CFAlarm.Hue.Bridge"
    const val HUE_LIGHTS = "CFAlarm.Hue.Lights"
    const val HUE_CONFIG = "CFAlarm.Hue.Config"
    const val HUE_DISCOVERY = "CFAlarm.Hue.Discovery"
    const val HUE_USECASE = "CFAlarm.Hue.UseCase"
    const val HUE_VIEWMODEL = "CFAlarm.Hue.ViewModel"
    const val HUE_NETWORK = "CFAlarm.Hue.Network"
    
    // === SHIFT MANAGEMENT ===
    const val SHIFT_CONFIG = "CFAlarm.Shift.Config"
    const val SHIFT_RECOGNITION = "CFAlarm.Shift.Recognition"
    
    // === ALARM SYSTEM ===
    const val ALARM = "CFAlarm.Alarm"
    const val ALARM_MANAGER = "CFAlarm.Alarm.Manager"
    const val ALARM_RECEIVER = "CFAlarm.Alarm.Receiver"
    const val ALARM_SKIP = "CFAlarm.Alarm.Skip"
    const val ALARM_AUDIO = "CFAlarm.Alarm.Audio"
    const val WAKE_LOCK = "CFAlarm.Alarm.WakeLock"
    const val BATTERY_OPTIMIZATION = "CFAlarm.Alarm.Battery"
    
    // === USER INTERFACE ===
    const val UI = "CFAlarm.UI"
    const val NAVIGATION = "CFAlarm.Navigation"
    
    // === DATA PERSISTENCE ===
    const val DATASTORE = "CFAlarm.DataStore"
    const val REPOSITORY = "CFAlarm.Repository"
    
    // === SYSTEM & LIFECYCLE ===
    const val APP = "CFAlarm.App"
    const val LIFECYCLE = "CFAlarm.Lifecycle"
    const val PERMISSIONS = "CFAlarm.Permissions"
    
    // === PERFORMANCE & DEBUG ===
    const val PERFORMANCE = "CFAlarm.Performance"
    const val NETWORK = "CFAlarm.Network"
    const val BACKGROUND_WORKER = "CFAlarm.Background.Worker"
    
    // === ERROR HANDLING ===
    const val ERROR = "CFAlarm.Error"
    const val VALIDATION = "CFAlarm.Validation"
    const val SYSTEM = "CFAlarm.System"
    const val FILE_SYSTEM = "CFAlarm.FileSystem"
    const val PREFERENCES = "CFAlarm.Preferences"
}
