package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.components

import com.github.f1rlefanz.cf_alarmfortimeoffice.util.text.kurzesDatum
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * Die EINE Formatierung einer Dienstzeit - fuer die Tagesliste im Wecker-Tab, die Karte „Nächster
 * Wecker“ und die Karte „Nächste Schicht“. Rein und ohne Compose, damit jeder Fall als JVM-Test
 * pruefbar ist.
 *
 * WARUM (Fairphone, 14.10.2026): ein ganztaegiger Abrufdienst stand als „Abrufdienst ·
 * 00:00–00:00“ da. Ein ganztaegiger Kalendertermin beginnt und endet um Mitternacht; als
 * Uhrzeitspanne gelesen sagt das nichts. Mehrtaegige Termine verloren zusaetzlich ihr Ende.
 */

/** Geschuetztes Leerzeichen: haelt "ab 06:00" und "Mi 14.10. · …" zusammen. */
private const val NBSP = ' '

private val UHRZEIT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN)

/**
 * PURE, TESTBAR: die Dienstzeit als kurzer Text, `null`, wenn der Beginn unbekannt ist.
 *
 * - Beginn und Ende auf Mitternacht, Ende an einem spaeteren Tag: „ganztägig“, ueber mehrere Tage
 *   „ganztägig bis So 18.10.“ (der letzte Tag, an dem der Dienst noch laeuft - das Ende um 00:00
 *   gehoert schon zum Folgetag).
 *   Bewusst ueber KALENDERTAGE und nicht ueber „mindestens 24 h“: am Tag der Zeitumstellung ist ein
 *   ganzer Tag 23 oder 25 Stunden lang, ein Millisekunden-Vergleich zeigte dort wieder 00:00–00:00.
 * - Ende am selben Tag, oder am Folgetag VOR der Beginnzeit (Nachtdienst unter 24 h):
 *   „06:00–14:12“ bzw. „22:00–06:00“. Genau 24 h (Rettungsdienst, Feuerwehr: Mi 07:00 bis Do 07:00)
 *   gehoert NICHT dazu - „07:00–07:00“ saehe aus wie eine Spanne der Laenge null.
 * - Laenger: „06:00 bis So 18.10. 06:00“ - sonst saehe ein Dienst ueber zwei Naechte aus wie einer
 *   ueber eine.
 * - Ende unbekannt (0) oder nicht nach dem Beginn: „ab 06:00“ - eine erfundene Zeit waere
 *   schlechter als keine.
 *
 * Normale Leerzeichen NUR um „bis“: dort darf die lange Form in der schmalen Tageskarte (320 dp,
 * Schrift 1,3) umbrechen - als ein einziges Wort braeche sie mitten im Wort (Skill
 * cfalarm-ui-und-navigation). Datum und Uhrzeit bleiben am Stueck. Das gilt auch fuer die Karte
 * „Nächster Wecker“: ihre Textspalte ist bei 320 dp nur ~208 dp breit.
 *
 * @param beginn, ende Epoch-Millis; 0 = unbekannt (so steht es in `AlarmInfo` bei manuellen Weckern).
 */
internal fun dienstzeitText(beginn: Long, ende: Long, zone: ZoneId): String? {
    if (beginn == 0L) return null
    val b = Instant.ofEpochMilli(beginn).atZone(zone).toLocalDateTime()
    if (ende == 0L || ende <= beginn) return "ab$NBSP${b.format(UHRZEIT)}"
    val e = Instant.ofEpochMilli(ende).atZone(zone).toLocalDateTime()

    val tage = ChronoUnit.DAYS.between(b.toLocalDate(), e.toLocalDate())
    if (b.toLocalTime() == LocalTime.MIDNIGHT && e.toLocalTime() == LocalTime.MIDNIGHT && tage >= 1) {
        return if (tage == 1L) "ganztägig" else "ganztägig bis ${datumAmStueck(e.toLocalDate().minusDays(1))}"
    }
    val eineSpanne = tage == 0L || (tage == 1L && e.toLocalTime().isBefore(b.toLocalTime()))
    return if (eineSpanne) {
        "${b.format(UHRZEIT)}–${e.format(UHRZEIT)}"
    } else {
        "${b.format(UHRZEIT)} bis ${datumAmStueck(e.toLocalDate())}$NBSP${e.format(UHRZEIT)}"
    }
}

private fun datumAmStueck(datum: LocalDate): String = kurzesDatum(datum).replace(' ', NBSP)

/**
 * PURE, TESTBAR: wann eine Schicht ist, mit Datum - fuer die Karte „Nächste Schicht“:
 * „Mi 14.10. · 06:00–14:12“ bzw. „Sa 17.10. · ganztägig“. Bis 1.50.0 stand dort nur der Beginn
 * („17.10.2026 00:00“) - bei einem ganztaegigen Dienst eine Uhrzeit, die es nicht gibt.
 */
internal fun schichtTerminText(beginn: Long, ende: Long, zone: ZoneId): String {
    val datum = kurzesDatum(Instant.ofEpochMilli(beginn).atZone(zone).toLocalDate())
    return dienstzeitText(beginn, ende, zone)?.let { "$datum$NBSP·$NBSP$it" } ?: datum
}
