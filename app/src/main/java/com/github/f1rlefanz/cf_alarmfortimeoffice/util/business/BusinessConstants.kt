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
/**
 * Datums-/Zeitformate der App.
 *
 * **Die Konstanten zerfallen in zwei Gruppen, die sich NICHT teilen duerfen:**
 * - **ANZEIGE-Formate** ([STANDARD_DATETIME], [TIME_ONLY], [DATE_ONLY], [DATE_SHORT], [DAY_TIME],
 *   [FILE_STAMP]): frei aenderbar, sie beeinflussen nur, was der Nutzer liest.
 * - **FESTGESCHRIEBENE Formate** ([PERSIST_TIME], [ID_DATE_COMPACT]): ihr Wert ist Teil
 *   gespeicherter Daten bzw. berechneter Alarm-IDs. Eine Aenderung ist ein Datenformat-Wechsel
 *   mit Migrationsbedarf, keine Kosmetik.
 *
 * Deshalb stehen hier bewusst zwei Paare mit identischem Wert nebeneinander
 * ([TIME_ONLY] / [PERSIST_TIME]). Sie zusammenzulegen spart eine Zeile und riskiert einen
 * stillen Wecker — die schlimmste Fehlerklasse dieser App.
 */
object DateTimeFormats {
    /** ANZEIGE: Standard-Datum-Zeit-Format für UI-Anzeige */
    const val STANDARD_DATETIME = "dd.MM.yyyy HH:mm"

    /**
     * ANZEIGE: wie [STANDARD_DATETIME], aber MIT Sekunden.
     *
     * Eigene Konstante statt „einfach [STANDARD_DATETIME] nehmen": das einzige Ziel
     * (Token-Ablauf im Diagnose-Log) braucht die Sekunden. Bei einem Token, das nach einer
     * Stunde abläuft, ist minutengenau zu grob, um zwei dicht aufeinanderfolgende Refreshs im
     * Log auseinanderzuhalten — und genau das ist der Fall, den die Rotationsketten-Prüfung in
     * `OAuth2TokenManager.refresh()` auswertbar machen muss.
     */
    const val STANDARD_DATETIME_SECONDS = "dd.MM.yyyy HH:mm:ss"

    /**
     * ANZEIGE: Uhrzeit-Format für die Oberfläche.
     *
     * **Nicht für Persistenz oder ID-Bildung verwenden** — dafür gibt es [PERSIST_TIME] bzw.
     * [ID_DATE_COMPACT]. Dieser Wert darf jederzeit geändert werden (z. B. auf "HH.mm" oder ein
     * 12-Stunden-Format), ohne dass gespeicherte Daten davon berührt werden. Genau das ist der
     * Grund, warum er nicht mit den festgeschriebenen Formaten geteilt wird.
     */
    const val TIME_ONLY = "HH:mm"

    /** ANZEIGE: Datum ohne Uhrzeit */
    const val DATE_ONLY = "dd.MM.yyyy"

    /** ANZEIGE: Kurzdatum ohne Jahr (z. B. für Termine im laufenden Jahr) */
    const val DATE_SHORT = "dd.MM"

    /** ANZEIGE: Wochentag + Kurzdatum + Uhrzeit (Zeitleisten, Vorschauen) */
    const val DAY_TIME = "EE dd.MM. HH:mm"

    /** ANZEIGE: Zeitstempel für generierte Dateinamen (z. B. Konfigurations-Export) */
    const val FILE_STAMP = "yyyy-MM-dd-HHmm"

    /**
     * PERSISTENZ — **festgeschrieben, nicht ändern.**
     *
     * Serialisierungsformat der Weckzeiten in der Schicht-Konfiguration
     * (`LocalTimeSerializer`, gespeichert im DataStore). Der Wert ist identisch mit
     * [TIME_ONLY], die Bedeutung ist eine völlig andere: hier steht er in bereits
     * gespeicherten Nutzerdaten.
     *
     * Wird dieses Format geändert, lässt sich jede vorhandene Schicht-Konfiguration nicht mehr
     * dekodieren. In dieser App heißt das: keine lesbare Konfiguration → keine erkannten
     * Schichten → keine Wecker. Deshalb teilt sich diese Konstante ausdrücklich NICHTS mit den
     * Anzeige-Formaten — sonst würde eine harmlose UI-Umstellung die gespeicherten Daten
     * unlesbar machen.
     */
    const val PERSIST_TIME = "HH:mm"

    /**
     * ID-BILDUNG — **festgeschrieben, nicht ändern.**
     *
     * Datumsanteil der manuellen Alarm-IDs (`AlarmViewModel.createManualAlarmId()`: der
     * formatierte String geht über `hashCode()` in die Alarm-ID ein).
     *
     * Ändert sich dieses Format, bekommen bestehende Alarme neue IDs. Die bereits im
     * AlarmManager armierten System-Alarme sind dann über ihre alte ID nicht mehr abbrechbar
     * (verwaiste, scharfe Wecker, die bis zum nächsten Geräte-Neustart feuern), während die neu
     * berechneten Alarme zusätzlich daneben laufen.
     */
    const val ID_DATE_COMPACT = "yyyyMMdd"
}
