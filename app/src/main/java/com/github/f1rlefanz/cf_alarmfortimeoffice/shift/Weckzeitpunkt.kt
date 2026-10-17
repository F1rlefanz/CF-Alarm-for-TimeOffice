package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Laengster Vorlauf, bei dem eine Weckzeit nach Schichtbeginn noch als Nachtdienst am Vortag gilt. */
internal val MAX_NIGHT_SHIFT_LEAD_TIME: Duration = Duration.ofHours(12)

/**
 * Auf welchen Zeitpunkt der Wecker eines Termins faellt (Umbau #192): die Weckzeit am Tag des
 * Beginns, beim Nachtdienst am Vortag, bei einem ganztaegigen Termin am Tag selbst.
 *
 * Das ist DIE Weckzeit-Regel der App. [ShiftRecognitionEngine] rechnet ihre Treffer damit, und die
 * Tagesleiste der Typ-Seite soll es auch tun, statt eine zweite Wahrheit zu fuehren. Deshalb nimmt
 * sie keinen `CalendarEvent` und keine `ShiftDefinition`, sondern nur Beginn, Ganztaegig und
 * Weckzeit - so laesst sie sich auch mit einer Dienstzeit aus [ShiftSpanStore] und der Weckzeit
 * eines ungespeicherten Entwurfs aufrufen.
 *
 * Gerechnet wird auf der WANDUHR (`LocalDateTime`), auch die 12-h-Grenze: am Tag der
 * Zeitumstellung sind 18:00 bis 06:00 auf der Uhr 12 h, verstrichen aber 13 h bzw. 11 h. Echte Dauer
 * verschoebe den Wecker an diesen Tagen um einen Tag. `WeckzeitpunktTest` haelt beide Tage fest.
 */
fun weckzeitpunkt(beginn: LocalDateTime, ganztaegig: Boolean, weckzeit: LocalTime): LocalDateTime {
    // Calculate alarm time on the same date as the shift
    val alarmDateTime = LocalDateTime.of(beginn.toLocalDate(), weckzeit)

    // Liegt die Weckzeit nach Schichtbeginn, koennte es eine Nachtschicht sein (Weckzeit
    // kurz vor Mitternacht, Schicht beginnt kurz nach Mitternacht) - dann einen Tag
    // zurueckrechnen. Die Vorlaufzeit NACH diesem Abzug muss aber plausibel bleiben (<=
    // MAX_NIGHT_SHIFT_LEAD_TIME), sonst wuerde z.B. eine Weckzeit nur 5min nach
    // Schichtbeginn (Konfigurationsfehler oder knapp getakteter Fall) faelschlich einen
    // Tag zu frueh wecken. Wichtig: die Grenze gilt fuer die Vorlaufzeit NACH dem
    // Tagesabzug, nicht fuer die rohe Differenz am selben Kalendertag - beim echten
    // Mitternachts-Fall (Schicht 00:30, Weckzeit 23:30 Vortag) betraegt die rohe Differenz
    // ~23h, die tatsaechliche Vorlaufzeit nach Abzug aber nur 1h.
    //
    // GANZTAEGIGE TERMINE SIND AUSGENOMMEN: sie haben keinen Schichtbeginn (00:00 ist nur der
    // Anker des Kalendertags), die Heuristik schluege sonst bei jeder Weckzeit ab 12:00 zu.
    // `ShiftRecognitionEngineTest` haelt beide Seiten fest.
    // Hergang: Skill cfalarm-kalender-und-schichten, reference/kalender-datenfluss.md.
    if (!ganztaegig && alarmDateTime.isAfter(beginn)) {
        val previousDayAlarm = alarmDateTime.minusDays(1)
        if (Duration.between(previousDayAlarm, beginn) <= MAX_NIGHT_SHIFT_LEAD_TIME) {
            return previousDayAlarm
        }
    }
    return alarmDateTime
}

/**
 * Dieselbe Regel fuer einen Beginn als Zeitpunkt, etwa `ShiftSpan.startTime` aus [ShiftSpanStore]:
 * [zone] liest ihn nur in Ortszeit um, gerechnet wird danach auf der Wanduhr wie oben.
 *
 * Die Erkennung ruft die Ortszeit-Variante direkt, weil `CalendarEvent.startTime` schon Ortszeit
 * der Systemzone ist. Ein Umweg ueber `Instant` und zurueck waere dort keine Identitaet, wenn die
 * Zone inzwischen eine andere ist.
 */
fun weckzeitpunkt(beginn: Instant, ganztaegig: Boolean, weckzeit: LocalTime, zone: ZoneId): LocalDateTime =
    weckzeitpunkt(LocalDateTime.ofInstant(beginn, zone), ganztaegig, weckzeit)
