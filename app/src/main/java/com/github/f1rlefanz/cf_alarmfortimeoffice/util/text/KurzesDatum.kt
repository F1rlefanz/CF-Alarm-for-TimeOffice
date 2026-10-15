package com.github.f1rlefanz.cf_alarmfortimeoffice.util.text

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val TAG_MONAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMAN)

/**
 * "Mi 14.10." - Wochentag ohne Punkt, damit er nicht wie ein Satzende aussieht.
 *
 * Die EINE Kopie (#146): Oberflaeche, ViewModels und die Krankmeldungs-Benachrichtigung in `alarm/`
 * nutzen sie gemeinsam. Sie liegt in `util/`, weil `alarm/` nichts aus `ui.` importieren darf
 * (tools/invarianten/pruefe_code.py) - vorher stand deshalb eine zweite Kopie im Notifier.
 */
internal fun kurzesDatum(datum: LocalDate): String =
    datum.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix(".") +
        " " + datum.format(TAG_MONAT)
