package com.github.f1rlefanz.cf_alarmfortimeoffice.util.business

/**
 * Business Logic Constants für Alarm, Shift und Calendar-spezifische Konfigurationen
 */

// ============================
// CALENDAR & TIME CONSTANTS
// ============================
object CalendarConstants {
    /** Standard-Vorausschau für Kalender-Events in Tagen */
    const val DEFAULT_DAYS_AHEAD = 7
    
    /** Maximale Vorausschau für Kalender-Events in Tagen */
    const val MAX_DAYS_AHEAD = 30
    
    /** Maximale Anzahl von Events pro Kalendar-Abfrage */
    const val MAX_EVENTS_PER_QUERY = 50
    
    /** Token-Gültigkeitsdauer in Millisekunden (1 Stunde) */
    const val TOKEN_VALIDITY_MS = 3600000L
    
    /** Auto-Refresh Intervall für Kalender-Daten in Minuten */
    const val AUTO_REFRESH_INTERVAL_MINUTES = 15
    
    /** Alarm-Polling Intervall in Millisekunden */
    const val ALARM_POLLING_INTERVAL_MS = 5000L
    
    /** Standard Event-Dauer in Millisekunden (1 Stunde) */
    const val DEFAULT_EVENT_DURATION_MS = 3600000L
}

// ============================
// ALARM CONSTANTS
// ============================
object AlarmConstants {
    /** Standard-Vorlaufzeit für Alarme in Minuten vor Schichtbeginn */
    const val DEFAULT_ALARM_LEAD_TIME_MINUTES = 30
    
    /** Minimale Vorlaufzeit für Alarme in Minuten */
    const val MIN_ALARM_LEAD_TIME_MINUTES = 5
    
    /** Maximale Vorlaufzeit für Alarme in Minuten */
    const val MAX_ALARM_LEAD_TIME_MINUTES = 180
    
    /** Standard-Snooze-Zeit in Minuten */
    const val DEFAULT_SNOOZE_MINUTES = 5
    
    /** Maximale Anzahl von Snooze-Wiederholungen */
    const val MAX_SNOOZE_COUNT = 3
    
    /** Standard-Alarmzeit (Stunde) für Fallback-Fälle */
    const val DEFAULT_ALARM_HOUR = 6
    
    /** Standard-Alarmzeit (Minute) für Fallback-Fälle */
    const val DEFAULT_ALARM_MINUTE = 0
}

// ============================
// SHIFT RECOGNITION CONSTANTS
// ============================
object ShiftConstants {
    /** Minimale Schichtdauer in Stunden */
    const val MIN_SHIFT_DURATION_HOURS = 2
    
    /** Maximale Schichtdauer in Stunden */
    const val MAX_SHIFT_DURATION_HOURS = 16
    
    /** Standard-Toleranz für Schichterkennung in Minuten */
    const val SHIFT_RECOGNITION_TOLERANCE_MINUTES = 15
    
    /** Mindestabstand zwischen Schichten in Stunden */
    const val MIN_BREAK_BETWEEN_SHIFTS_HOURS = 8
}

// ============================
// DATA STORAGE CONSTANTS
// ============================
object StorageConstants {
    /** DataStore-Namen */
    const val AUTH_DATASTORE_NAME = "auth_prefs"
    const val SHIFT_DATASTORE_NAME = "shift_prefs"
    
    /** Maximale Anzahl von gespeicherten Alarm-Einträgen */
    const val MAX_STORED_ALARMS = 50
    
    /** Cache-Gültigkeitsdauer in Millisekunden */
    const val CACHE_VALIDITY_MS = 300000L // 5 Minuten
}

// ============================
// DATE & TIME FORMAT CONSTANTS
// ============================
object DateTimeFormats {
    /** Standard-Datum-Zeit-Format für UI-Anzeige */
    const val STANDARD_DATETIME = "dd.MM.yyyy HH:mm"
    
    /** Kurzes Datum-Format */
    const val SHORT_DATE = "dd.MM.yyyy"
    
    /** Zeit-Format für Uhrzeiten */
    const val TIME_ONLY = "HH:mm"
    
    /** Ausführliches Datum-Zeit-Format */
    const val VERBOSE_DATETIME = "EEEE, dd. MMMM yyyy HH:mm"
}

// ============================
// APP METADATA CONSTANTS
// ============================
object AppConstants {
    const val APP_NAME = "CF-Alarm for TimeOffice"
    const val VERSION_NAME = "1.0.0"
    const val VERSION_CODE = 1
    
    // OAuth Client ID ist jetzt ausschließlich in BuildConfig verfügbar
    // BuildConfig.GOOGLE_WEB_CLIENT_ID wird zur Laufzeit aus build.gradle.kts geladen
}
