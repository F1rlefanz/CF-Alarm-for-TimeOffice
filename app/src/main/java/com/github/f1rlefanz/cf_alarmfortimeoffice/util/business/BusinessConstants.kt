package com.github.f1rlefanz.cf_alarmfortimeoffice.util.business

/**
 * Business Logic Constants für Alarm, Shift und Calendar-spezifische Konfigurationen
 */

// ============================
// CALENDAR & TIME CONSTANTS
// ============================
object CalendarConstants {
    /** Standard-Vorausschau für Kalender-Events in Tagen - FIXED at 14 days as per Briefing 4.0 */
    const val DEFAULT_DAYS_AHEAD = 14

    /** Maximale Anzahl von Events pro Kalendar-Abfrage */
    const val MAX_EVENTS_PER_QUERY = 50

    /** Standard Event-Dauer in Millisekunden (1 Stunde) */
    const val DEFAULT_EVENT_DURATION_MS = 3600000L
}

// ============================
// ALARM CONSTANTS
// ============================
object AlarmConstants {
    /** Standard-Alarmzeit (Stunde) für Fallback-Fälle */
    const val DEFAULT_ALARM_HOUR = 6

    /** Standard-Alarmzeit (Minute) für Fallback-Fälle */
    const val DEFAULT_ALARM_MINUTE = 0
}

// ============================
// DATE & TIME FORMAT CONSTANTS
// ============================
object DateTimeFormats {
    /** Standard-Datum-Zeit-Format für UI-Anzeige */
    const val STANDARD_DATETIME = "dd.MM.yyyy HH:mm"

    /** Zeit-Format für Uhrzeiten */
    const val TIME_ONLY = "HH:mm"
}
