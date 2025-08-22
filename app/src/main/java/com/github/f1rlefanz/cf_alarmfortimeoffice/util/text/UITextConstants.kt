package com.github.f1rlefanz.cf_alarmfortimeoffice.util.text

/**
 * UI Text Constants für benutzerfreundliche Texte und Labels
 */

// ============================
// UI TEXT CONSTANTS
// ============================
object UIText {
    // Allgemeine Texte
    const val OVERVIEW = "Übersicht"
    const val REFRESH = "Aktualisieren"
    const val LOADING = "Lädt..."
    const val ERROR = "Fehler"
    const val WARNING = "Warnung"
    const val SUCCESS = "Erfolg"
    const val SAVE = "Speichern"
    const val CANCEL = "Abbrechen"
    const val DELETE = "Löschen"
    const val EDIT = "Bearbeiten"
    const val ADD = "Hinzufügen"
    const val BACK = "Zurück"
    const val OK = "OK"
    const val SELECTED = "Ausgewählt"
    
    // App-spezifische Texte
    const val APP_ICON_LETTERS = "CF"
    const val APP_TITLE = "CF-Alarm for TimeOffice"
    const val APP_SUBTITLE = "Automatische Alarmverwaltung für Ihre Schichten"
    const val GOOGLE_SIGN_IN = "Mit Google anmelden"
    const val PERMISSION_EXPLANATION = "Diese App benötigt Zugriff auf Ihren Google Kalender, " +
            "um Schichten zu erkennen und Alarme zu setzen."
    
    // Status-bezogene Texte
    const val SYSTEM_STATUS = "System Status"
    const val AUTHENTICATION = "Authentifizierung"
    const val LOGGED_IN_AS = "Angemeldet als:"
    const val UNKNOWN = "Unbekannt"
    const val NOT_LOGGED_IN = "Nicht angemeldet"
    const val NO_CALENDAR_SELECTED_STATUS = "Kein Kalender ausgewählt"
    const val NO_CALENDARS_AVAILABLE_STATUS = "Keine Kalender verfügbar"
    const val CALENDARS_SELECTED = "Kalender ausgewählt"
    const val SHIFT_TYPES_DEFINED = "Schichttypen definiert"
    const val NO_CONFIGURATION_AVAILABLE = "Keine Konfiguration vorhanden"
    const val SHIFT_RECOGNITION = "Schicht-Erkennung"
    const val NO_SHIFTS_RECOGNIZED = "Keine Schichten erkannt"
    const val ALARMS = "Alarme"
    const val ALARMS_SET = "Alarme gesetzt"
    const val DEBUG_INFORMATION = "Debug-Informationen"
    const val EVENTS_LOADED = "Events geladen"
    const val NO_SHIFT_SHORT = "Keine"
    const val NEXT_ALARM = "Nächster"
    const val CALENDAR = "Kalender"
    
    // Alarm-bezogene Texte
    const val ALARM_CONTROL = "Alarm-Steuerung"
    const val NEXT_SHIFT = "Nächste Schicht:"
    const val ALARM_STATUS = "Alarm Status"
    const val SET_ALARM = "Alarm setzen"
    const val CANCEL_ALARM = "Alarm löschen"
    const val ACTIVE_ALARMS = "aktive Alarme"
    const val NO_ACTIVE_ALARMS = "Keine aktiven Alarme"
    const val EXACT_ALARM_DISABLED = "Exakte Alarme sind in den Einstellungen deaktiviert"
    const val TIME_UNTIL_ALARM = "Zeit bis zum Wecker"
    const val TIME_EXPIRED = "Zeit abgelaufen!"
    const val ALARM_ACTIVE = "ALARM AKTIV!"
    const val AUTO_ALARMS = "Automatische Alarme"
    const val AUTO_ALARMS_DESCRIPTION = "Alarme automatisch für erkannte Schichten setzen"
    
    // Zeit-Einheiten
    const val UNIT_DAYS = "Tage"
    const val UNIT_HOURS = "Std"
    const val UNIT_MINUTES = "Min"
    const val UNIT_SECONDS = "Sek"
    
    // Schicht-bezogene Texte
    const val NEXT_SHIFT_LABEL = "Nächste Schicht"
    const val NO_SHIFT_DETECTED = "Keine Schicht erkannt"
    const val SHIFTS_RECOGNIZED = "als Schichten erkannt"
    const val SHIFT_CONFIGURATION = "Schicht-Konfiguration"
    const val ADD_SHIFT = "Schicht hinzufügen"
    const val SHIFT_TYPES = "Schichttypen"
    const val NO_SHIFT_TYPES_DEFINED = "Keine Schichttypen definiert"
    const val ADD_SHIFT_TYPES_HINT = "Füge Schichttypen hinzu, um die automatische Erkennung zu aktivieren"
    const val RESET_TO_DEFAULTS = "Auf Standardwerte zurücksetzen"
    const val PATTERN_LABEL = "Muster:"
    const val ALARM_LABEL = "Alarm:"
    
    // Kalender-bezogene Texte
    const val CALENDAR_EVENTS = "Kalender-Events"
    const val NO_EVENTS_LOADED = "Keine Events geladen"
    const val EVENTS_IN_NEXT_DAYS = "Events in den nächsten"
    const val DAYS_LABEL = "Tagen"
    const val SELECT_CALENDARS = "Kalender auswählen"
    const val SELECT_CALENDARS_INSTRUCTION = "Wähle einen oder mehrere Kalender aus, die für die Schichterkennung verwendet werden sollen."
    const val NO_CALENDARS_AVAILABLE = "Keine Kalender verfügbar. Stelle sicher, dass die Kalenderberechtigung erteilt wurde."
    
    // Dialog-Texte
    const val EDIT_SHIFT = "Schicht bearbeiten"
    const val ADD_SHIFT_DIALOG = "Schicht hinzufügen"
    const val DIALOG_IMPLEMENTATION_PENDING = "Dialog-Implementierung folgt..."
}
