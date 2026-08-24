package com.github.f1rlefanz.cf_alarmfortimeoffice.util

/**
 * Zentrale Log-Tags für strukturiertes Logging
 *
 * VORTEILE:
 * - Konsistente Tag-Namensgebung
 * - Einfache Filterung in Logcat
 * - Vermeidung von Typos in Log-Tags
 * - Kategorisierte Log-Gruppen
 *
 * DIE LISTE IST EINE VOKABELLISTE, KEIN VORRATSLAGER. Sie hat nur dann einen Wert, wenn jeder
 * Eintrag ein Subsystem benennt, das auch wirklich protokolliert - sonst sucht man beim Filtern
 * nach Tags, unter denen nie etwas steht, und der Zweck kehrt sich um.
 *
 * Deshalb die Regel, die beim Aufräumen am 25.08.2026 aufgeschrieben wurde: **Ein neues Subsystem
 * bekommt hier eine Konstante, bevor die erste Logzeile geschrieben wird - und NIEMALS ein
 * String-Literal am Aufrufort.** Umgekehrt fliegt raus, was keiner benutzt. Damals waren das 19
 * Einträge, darunter `MAINTENANCE_L2`/`L3` für Wartungsebenen, die es nie gab (nur L4 existiert,
 * 58 Fundstellen), fünf Hue-Unterteilungen neben den sieben tatsächlich benutzten Hue-Tags und
 * eine `ALARM_TESTING`-Kategorie im Produktionscode.
 */
object LogTags {
    
    // === AUTHENTICATION & TOKEN ===
    const val AUTH = "CFAlarm.Auth"
    const val TOKEN = "CFAlarm.Token"
    
    // === CALENDAR OPERATIONS ===
    const val CALENDAR = "CFAlarm.Calendar"

    // === HUE INTEGRATION ===
    const val HUE = "CFAlarm.Hue"
    const val HUE_BRIDGE = "CFAlarm.Hue.Bridge"
    const val HUE_LIGHTS = "CFAlarm.Hue.Lights"
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

    // === USER INTERFACE ===
    const val UI = "CFAlarm.UI"
    const val NAVIGATION = "CFAlarm.Navigation"

    // === DATA PERSISTENCE ===
    const val DATASTORE = "CFAlarm.DataStore"
    const val REPOSITORY = "CFAlarm.Repository"
    const val DI = "CFAlarm.DI"

    // === SYSTEM & LIFECYCLE ===
    const val APP = "CFAlarm.App"
    const val LIFECYCLE = "CFAlarm.Lifecycle"
    const val PERMISSIONS = "CFAlarm.Permissions"
    const val BATTERY = "CFAlarm.Battery" // Phase 1: Battery optimization
    const val UNUSED_APP_RESTRICTIONS = "CFAlarm.UnusedAppRestrictions"
    const val TIMEOFFICE_HEALTH = "CFAlarm.TimeOfficeHealth"

    // === PERFORMANCE & DEBUG ===
    const val PERFORMANCE = "CFAlarm.Performance"
    const val CACHE = "CFAlarm.Cache"
    const val NETWORK = "CFAlarm.Network"
    const val HUE_NETWORK = "CFAlarm.Hue.Network"
    const val BACKGROUND_WORKER = "CFAlarm.Background.Worker"

    // === SMART MAINTENANCE CHAIN ===
    const val MAINTENANCE = "CFAlarm.Maintenance" // Phase 1: AlarmMaintenanceService
    const val DIMMER = "CFAlarm.Dimmer" // Schicht-basiertes Screen-Dimming
    const val DND = "CFAlarm.Dnd" // Schicht-basierte "Nicht stoeren"-Steuerung
    const val MASTER_PAUSE = "CFAlarm.MasterPause" // Globaler Pause-Schalter fuer alle Hintergrunddienste
    const val MAINTENANCE_L4 = "CFAlarm.Maintenance.L4"

    // === PHASE 2: SECURITY & VALIDATION ===
    const val SECURITY = "CFAlarm.Security"

    // === ERROR & RECOVERY ===
    const val ERROR = "CFAlarm.Error"
    const val RECOVERY = "CFAlarm.Recovery"
    const val OFFLINE = "CFAlarm.Offline"
    const val VALIDATION = "CFAlarm.Validation"
    const val SYSTEM = "CFAlarm.System"
    const val FILE_SYSTEM = "CFAlarm.FileSystem"
    const val PREFERENCES = "CFAlarm.Preferences"
}
