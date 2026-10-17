package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Die Dienstzeit eines Typs auf dem MUSTERTAG der Tagesleiste (Umbau #193): nur Uhrzeiten, kein
 * Datum. Die Tagesleiste zeigt einen Tag "so wie er bei diesem Typ aussieht", nicht einen
 * bestimmten Kalendertag.
 *
 * Gerechnet wird auf der WANDUHR: [dauerMin] ist die Differenz der beiden Ortszeiten, nicht die
 * verstrichene Zeit. Am Umstellungstag ist ein Nachtdienst 20:45 bis 07:00 auf der Uhr 10 h 15,
 * verstrichen 9 h oder 11 h 15 - die Leiste zeigt den Normalfall, und `weckzeitpunkt()` (#192)
 * rechnet ebenso.
 *
 * @param beginn Uhrzeit des Beginns.
 * @param dauerMin Minuten bis zum Ende, 1..1440. Die Spanne ist hoechstens EIN Tag lang: eine
 *   Spanne von einem Tag oder laenger auf der Wanduhr (Rufbereitschaft Mi 07:00 bis Do 07:00, ein
 *   Wochenende, 25 h) zeigt ihren ersten Tag ab dem Beginn, nicht ihre ganze Laenge.
 * @param ganztaegig der Termin war ein GANZTAEGIGER Kalendertermin (`CalendarEvent.isAllDay`) - und
 *   nur dann. Das ist die Frage, die `weckzeitpunkt()` stellt: bei einem ganztaegigen Termin
 *   weckt die Weckzeit am Tag selbst, bei einem zeitgebundenen Termin 00:00 bis 00:00 kann sie
 *   (Weckzeit nach Beginn, Vorlauf <= 12 h) auf den Vortag fallen. Ob die Spanne den GANZEN Tag
 *   fuellt, sagt [ganzerTag].
 */
data class Dienstzeit(
    val beginn: LocalTime,
    val dauerMin: Int,
    val ganztaegig: Boolean
) {
    init {
        require(dauerMin in 1..MINUTEN_PRO_TAG) { "dauerMin muss 1..$MINUTEN_PRO_TAG sein, war $dauerMin" }
    }

    /** Beginn als Minute der Tagesleiste (0 = 18:00 am Vortag). */
    val beginnMin: Int get() = MUSTERTAG_BEGINN_MIN + beginn.hour * 60 + beginn.minute

    /** Ende als Minute der Tagesleiste; ueber 1800, wenn die Spanne am Folgetag endet. */
    val endeMin: Int get() = beginnMin + dauerMin

    /** Beginn um 00:00 und 24 h lang - ein zeitgebundener 00:00-bis-00:00-Termin zaehlt ebenso. */
    val ganzerTag: Boolean get() = beginn == LocalTime.MIDNIGHT && dauerMin == MINUTEN_PRO_TAG

    companion object {
        /** Ein ganzer Kalendertag, wie ihn ein Termin ohne Uhrzeit hat. */
        val GANZTAEGIG = Dienstzeit(LocalTime.MIDNIGHT, MINUTEN_PRO_TAG, ganztaegig = true)
    }
}

/**
 * Ein Google-Ganztagstermin steht im [ShiftSpan] als 00:00 des ersten bis 23:59 des LETZTEN Tages
 * (`CalendarEventConverter`: `end.date` ist exklusiv, davon eine Minute abgezogen). Der Span selbst
 * traegt kein `isAllDay` - dieses Ende ist sein Fingerabdruck. Ein zeitgebundener Termin 00:00 bis
 * 00:00 endet NICHT um 23:59 und bleibt zeitgebunden.
 */
private const val GANZTAGS_ENDE_MIN = 23 * 60 + 59

/**
 * Die Dienstzeit des Typs [typName] aus den Schichtspannen: die NAECHSTE ANSTEHENDE (noch nicht
 * beendete, also auch eine laufende), sonst die LETZTE. `null`, wenn der Typ keine Spanne hat -
 * dann gibt es keine erfundene Dienstzeit, die Tagesleiste zeigt keine Eintrag-Spur.
 *
 * Der Name muss dem in der Spanne gespeicherten gleichen (wie bei `RufbereitschaftAbfrage`). Ein
 * Entwurf mit neuem Namen hat deshalb keine Spanne, bis er gespeichert und neu synchronisiert ist:
 * der Aufrufer fragt mit dem GESPEICHERTEN Namen. Freigegebene Tage werden nicht herausgefiltert -
 * die Leiste zeigt, wie der Typ aussieht, nicht, ob er heute gilt.
 *
 * @param zone die Zone, in der die Uhrzeiten gelesen werden; kommt vom Aufrufer, nie von hier.
 */
fun dienstzeitAus(spans: List<ShiftSpan>, typName: String, jetzt: Long, zone: ZoneId): Dienstzeit? {
    val seine = spans.filter { it.shiftName == typName && it.endTime - it.startTime >= MINUTE_MS }
    val gewaehlt = seine.filter { it.endTime > jetzt }.minByOrNull { it.startTime }
        ?: seine.maxByOrNull { it.startTime }
        ?: return null
    return aufMustertag(gewaehlt, zone)
}

private const val MINUTE_MS = 60_000L

internal fun aufMustertag(span: ShiftSpan, zone: ZoneId): Dienstzeit? {
    val beginn = LocalDateTime.ofInstant(Instant.ofEpochMilli(span.startTime), zone).truncatedTo(ChronoUnit.MINUTES)
    val ende = LocalDateTime.ofInstant(Instant.ofEpochMilli(span.endTime), zone).truncatedTo(ChronoUnit.MINUTES)
    val beginnMin = beginn.hour * 60 + beginn.minute
    val endeMin = ende.hour * 60 + ende.minute
    if (beginnMin == 0 && endeMin == GANZTAGS_ENDE_MIN) {
        return Dienstzeit(beginn.toLocalTime(), MINUTEN_PRO_TAG, ganztaegig = true)
    }
    // Gerechnet wird auf der Wanduhr. Nicht positiv ist die Wanduhr-Dauer nur bei einer Spanne
    // innerhalb der Stunde, die die Rueckstellung doppelt hat (02:30 bis 02:30, verstrichen 1 h):
    // dafuer gibt es keine Dienstzeit, aber auch keinen Absturz an `require` in [Dienstzeit].
    val wanduhrMin = Duration.between(beginn, ende).toMinutes()
    if (wanduhrMin <= 0) return null
    // Ein Tag oder mehr (Rufbereitschaft Mi 07:00 bis Do 07:00, ein Wochenende, 25 h): die Leiste
    // zeigt EINEN Tag ab dem Beginn, nicht die ganze Laenge.
    return Dienstzeit(beginn.toLocalTime(), minOf(wanduhrMin, MINUTEN_PRO_TAG.toLong()).toInt(), ganztaegig = false)
}

/**
 * Die Dienstzeit, mit der die Leiste eines Typs der Kategorie [kategorie] rechnet. [dienstzeit] ist,
 * was [dienstzeitAus] fand.
 *
 * - Frei, Urlaub, Fortbildung und Krank: ohne Spanne GANZTAEGIG. Sie stehen im Kalender meist ohne
 *   Uhrzeit, und Krank hat nie eine Spanne (die Erkennung zweigt es davor ab). Mit zeitgebundener
 *   Spanne (eine Fortbildung 08:00 bis 16:00) gilt diese.
 * - Dienst und Rufbereitschaft: ohne Spanne `null` - eine Dienstzeit wird nicht erfunden.
 * - Ignorieren: immer `null`, der Typ hat keinen Tag.
 */
fun wirksameDienstzeit(kategorie: ShiftKategorie, dienstzeit: Dienstzeit?): Dienstzeit? = when (kategorie) {
    ShiftKategorie.IGNORIEREN -> null
    ShiftKategorie.FREI,
    ShiftKategorie.URLAUB,
    ShiftKategorie.FORTBILDUNG,
    ShiftKategorie.KRANK -> dienstzeit ?: Dienstzeit.GANZTAEGIG
    ShiftKategorie.DIENST,
    ShiftKategorie.RUFBEREITSCHAFT -> dienstzeit
}
