package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.HellZeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.OHNE_TAGESLICHT
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndShiftSpanResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.weckzeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/*
 * Dimmen und "Nicht stoeren" fuer die Tagesleiste (Umbau #194, SPEC 3.3 Punkt 3) - AUS DEN HEUTIGEN
 * REGELN, ohne eine zweite Fensterlogik. Der Adapter erfindet fuer den Typ einen Mustertag, laesst
 * `DimWindowResolver.buildRuleSpans` darauf laufen (dieselbe Funktion wie `DimScheduleUseCase`) und
 * rechnet die Epoch-Bereiche in Minuten der Leiste um. Regelwahl, Blockposition, Unterdrueckung durch
 * eine leere Fensterliste, `startNurAmWecktag`, Weckzeit-Nachlauf und "Hell ab" kommen damit von dort.
 *
 * DER MUSTERTAG liegt in UTC (wie `kappenAmRufCutoff` in Tagesleiste.kt): dort faellt Wanduhr und
 * Instant zusammen, und die Leiste zeigt ohnehin einen Normaltag ohne Zeitumstellung. Er ist ein
 * fester Kalendertag, der in keiner Ausgabe vorkommt. Datum und Minute-Konstante stehen hier ein
 * zweites Mal, weil die in Tagesleiste.kt `private` sind; `TagesleisteDimmenDndTest` haelt das Ende
 * "beim Wecker" gegen `weckpunktMin()`, damit die beiden Mustertage nicht auseinanderlaufen.
 *
 * Die Eingabe ist dieselbe wie bei der Leiste ([TagesleisteEingabe]); `dimmen`, `nichtStoeren` und
 * `licht` darin sind hier ohne Bedeutung. Der Aufrufer setzt das Ergebnis danach selbst ein:
 * `eingabe.copy(dimmen = r.dimmen, nichtStoeren = r.nichtStoeren)`.
 */

private val MUSTERTAG: LocalDate = LocalDate.of(2001, 1, 2)
private val MUSTER_ZONE = ZoneOffset.UTC
private val MUSTERTAG_MS: Long = MUSTERTAG.atStartOfDay().toInstant(MUSTER_ZONE).toEpochMilli()
private const val MINUTE_MS = 60_000L
private const val TAG_MS = MINUTEN_PRO_TAG * MINUTE_MS

/**
 * Die heutigen Einstellungen, aus denen die Leiste rechnet.
 *
 * @param dimEnabled `dim_enabled`, der eine Schalter des Dimmers. Aus heisst KEINE Dimm-Spur - und
 *   auch kein "Nicht stoeren, solange gedimmt wird": das liest die Zeitleiste des Dimmers, und die ist
 *   dann leer (`DimScheduleUseCase.computeWindowsIntern`).
 * @param regeln alle Dimm-Regeln, wie `DimRuleRepository` sie liefert.
 * @param nichtStoeren die beiden Quellen-Schalter von "Nicht stoeren".
 * @param ausgenommeneSchichten Schichtnamen, die "waehrend der Dienstzeit" ausnimmt
 *   (`DndPrefs.shiftExcludedShifts`).
 * @param hellUm Uhrzeit, zu der es am Mustertag hell wird; `null` = kein Standort, keine solche
 *   Zeit. Dann gilt der volle Nachlauf wie ohne Standort im Dimmer (`OHNE_TAGESLICHT`).
 */
data class DimDndEinstellungen(
    val dimEnabled: Boolean,
    val regeln: List<DimRule>,
    val nichtStoeren: DndPrefs.Toggles,
    val ausgenommeneSchichten: Set<String> = emptySet(),
    val hellUm: (HellAb) -> LocalTime? = { null }
)

/** Was die Leiste aus [dimmenUndNichtStoeren] bekommt. */
data class DimUndNichtStoeren(val dimmen: List<DimBereich>, val nichtStoeren: List<DndBereich>) {
    companion object {
        val LEER = DimUndNichtStoeren(emptyList(), emptyList())
    }
}

/**
 * Die Dimm- und "Nicht stoeren"-Bereiche eines Mustertags dieses Typs, so wie Dimmer und DND sie heute
 * rechnen. Rein: gleiche Eingabe, gleiches Ergebnis.
 *
 * WELCHE REGEL GILT. Ein erkannter Typ ist ein SCHICHTTAG mit seiner Weckzeit, auch mit Wecker aus
 * (#181): `findRuleForShift` (Name, sonst UNIVERSAL). Das gilt auch fuer Frei, Urlaub und Fortbildung -
 * die FREI-Regel greift heute nur an einem Tag OHNE jeden Eintrag. Krank (Tag freigegeben) und
 * Ignorieren ohne weiteren Eintrag haben keine Schichtspanne und laufen wie ein freier Tag:
 * `findRuleForFreeDay` (FREI, sonst UNIVERSAL). Ohne gueltige Weckzeit hat ein Schichttag keinen
 * Anker - dann gibt es weder Dimmen noch "folgt dem Dimmer", nur den Kalendereintrag.
 *
 * DIE NACHBARTAGE. Je [TagesleisteEingabe.blockposition] steht am Vortag und/oder am Folgetag derselbe
 * Typ mit derselben Weckzeit (ERSTER: Folgetag, MITTLERER: beide, LETZTER: Vortag, EINZELNER: keiner).
 * Daran liest `buildRuleSpans` die Blockposition ab, und die Wecker der Nachbarn beenden ein
 * "bis Weckzeit, spaetestens um X"-Fenster genau wie im Betrieb. Die Leiste zeigt aber nur die Fenster
 * DES TAGES dieses Typs (den Kalendertag seiner Weckzeit, wie `buildRuleSpans` ihn datiert), nicht die
 * der Nachbartage: "So laeuft ein Tag F" heisst die Regel von F - eine leere Fensterliste an F zeigt
 * keinen Balken, auch wenn UNIVERSAL am freien Vortag die Nacht davor dimmt. Das ist die Zuordnung, die
 * Etappe 3 mit den Fenstern am Typ ohnehin hat.
 *
 * WECKER AUS. Die Weckzeit bleibt Anker und Ende-Weckzeit (heute fuehrt `persistShiftSpans` sie auch
 * dann; offen als #181), aber `endetBeimWecker` ist dann falsch - der Ereignistext soll keinen Wecker
 * behaupten, der nicht klingelt.
 *
 * NICHT HIER: der Ruf-Cutoff. Die Leiste kappt "Nicht stoeren" selbst (`kappenAmRufCutoff`, #193) und
 * braucht dafuer das UNGEKAPPTE Ende - kappte der Adapter vor, fände sie nichts mehr zu kuerzen, und
 * "endet spaetestens jetzt" stuende nie im Ereignistext.
 *
 * @param regelwahl nur wegen `findRuleForShift`/`findRuleForFreeDay`: die Auswahl geht durch die
 *   echte Funktion statt durch eine Kopie. Es wird kein Repository gelesen.
 */
fun dimmenUndNichtStoeren(
    eingabe: TagesleisteEingabe,
    einstellungen: DimDndEinstellungen,
    regelwahl: DimRuleUseCase
): DimUndNichtStoeren {
    if (eingabe.kategorie == ShiftKategorie.IGNORIEREN) return DimUndNichtStoeren.LEER
    val wirksam = wirksameDienstzeit(eingabe.kategorie, eingabe.dienstzeit)

    val nichtStoeren = mutableListOf<DndBereich>()
    // "Waehrend des Kalendereintrags": die Spanne des Typs, ohne ausgenommene Schichten. Krank hat nie
    // eine Schichtspanne (die Erkennung zweigt es davor ab), also auch kein DND "waehrend der Dienstzeit".
    if (einstellungen.nichtStoeren.duringShiftEnabled && eingabe.kategorie != ShiftKategorie.KRANK && wirksam != null) {
        val beginn = MUSTERTAG.atTime(wirksam.beginn).toInstant(MUSTER_ZONE).toEpochMilli()
        DndShiftSpanResolver.buildShiftSpans(
            listOf(DndShiftSpanResolver.AlarmSlot(eingabe.name, beginn, beginn + wirksam.dauerMin * MINUTE_MS)),
            einstellungen.ausgenommeneSchichten
        ).forEach { nichtStoeren += DndBereich(it.first.alsMinute(), it.last.alsMinute(), DndQuelle.KALENDEREINTRAG) }
    }

    val tag = erfindeTag(eingabe, wirksam)
    if (!einstellungen.dimEnabled || tag == null) {
        return DimUndNichtStoeren(emptyList(), nichtStoeren.sortedBy { it.vonMin })
    }

    val dimmen = rechne(tag, einstellungen, regelwahl, mitWeckzeitNachlauf = true, hellZeitpunkt = hellZeitpunktAus(einstellungen.hellUm))
        .map { span ->
            DimBereich(
                vonMin = span.range.first.alsMinute(),
                bisMin = span.range.last.alsMinute(),
                prozent = span.strength,
                endetBeimWecker = eingabe.weckerAn && span.range.last in tag.weckzeiten
            )
        }
        .beruehrtLeiste { it.vonMin to it.bisMin }

    if (einstellungen.nichtStoeren.followDimmerEnabled) {
        // OHNE den Nachlauf nach der Weckzeit und ohne "Hell ab" - wie `DndScheduleUseCase.computeWindows`:
        // wer gerade geweckt wurde, soll erreichbar sein. Dieselbe Zeitleiste wie `previewTimeline`.
        val spans = rechne(tag, einstellungen, regelwahl, mitWeckzeitNachlauf = false, hellZeitpunkt = OHNE_TAGESLICHT)
        nichtStoeren += DimWindowResolver.mergeToTimeline(spans)
            .map { DndBereich(it.range.first.alsMinute(), it.range.last.alsMinute(), DndQuelle.DIMM_FENSTER) }
            .beruehrtLeiste { it.vonMin to it.bisMin }
    }
    return DimUndNichtStoeren(dimmen.sortedBy { it.vonMin }, nichtStoeren.sortedBy { it.vonMin })
}

/**
 * Der erfundene Mustertag: die Slots, die `buildRuleSpans` bekommt, und die Weckzeiten fuer das Ende
 * "bis Weckzeit".
 *
 * @property datum der Kalendertag, dessen Fenster die Leiste zeigt: der Tag der Weckzeit des Typs.
 * @property slots leer = ein freier Tag (Krank).
 * @property weckzeiten die Weckzeit des Typs und die seiner Nachbartage im Block - wie im Betrieb alle
 *   `alarmTriggerTime` der Schichtspannen, auch bei Wecker aus.
 */
private class Mustertag(val datum: LocalDate, val slots: List<DimWindowResolver.AlarmSlot>, val weckzeiten: Set<Long>)

/** `null`, wo es keinen Anker gibt: ein Schichttag ohne gueltige Weckzeit. */
private fun erfindeTag(eingabe: TagesleisteEingabe, wirksam: Dienstzeit?): Mustertag? {
    if (eingabe.kategorie == ShiftKategorie.KRANK) return Mustertag(MUSTERTAG, emptyList(), emptySet())
    val weckzeit = eingabe.weckzeit ?: return null
    // Dieselbe Regel wie `weckpunktMin()`, aber ohne dessen "Wecker aus gibt null": die Spanne fuehrt
    // die Weckzeit auch bei Wecker aus.
    val beginn = MUSTERTAG.atTime(wirksam?.beginn ?: LocalTime.MIDNIGHT)
    val wecker = weckzeitpunkt(beginn, wirksam?.ganztaegig ?: true, weckzeit)
    val weckerMs = wecker.toInstant(MUSTER_ZONE).toEpochMilli()
    val endeMs = wirksam?.let { beginn.toInstant(MUSTER_ZONE).toEpochMilli() + it.dauerMin * MINUTE_MS } ?: 0L

    val versatz = buildList {
        add(0L)
        if (eingabe.blockposition == Blockposition.MITTLERER || eingabe.blockposition == Blockposition.LETZTER) add(-TAG_MS)
        if (eingabe.blockposition == Blockposition.ERSTER || eingabe.blockposition == Blockposition.MITTLERER) add(TAG_MS)
    }
    val slots = versatz.map {
        DimWindowResolver.AlarmSlot(weckerMs + it, eingabe.name, if (endeMs == 0L) 0L else endeMs + it)
    }
    return Mustertag(wecker.toLocalDate(), slots, slots.map { it.triggerTime }.toSet())
}

private fun rechne(
    tag: Mustertag,
    einstellungen: DimDndEinstellungen,
    regelwahl: DimRuleUseCase,
    mitWeckzeitNachlauf: Boolean,
    hellZeitpunkt: HellZeitpunkt
): List<DimWindowResolver.DimSpan> {
    fun lauf(horizontTage: Int) = DimWindowResolver.buildRuleSpans(
        alarms = tag.slots,
        horizonDays = horizontTage,
        today = tag.datum,
        zone = MUSTER_ZONE,
        ruleForShift = { regelwahl.findRuleForShift(it, einstellungen.regeln) },
        ruleForFreeDay = { regelwahl.findRuleForFreeDay(einstellungen.regeln) },
        weckzeiten = tag.weckzeiten.toList(),
        mitWeckzeitNachlauf = mitWeckzeitNachlauf,
        hellZeitpunkt = hellZeitpunkt
    )
    // Ein Tag mehr Horizont fuegt der Schleife genau `tag.datum` hinzu; was davor liegt (der Rueckblick
    // der Schleife) steht in beiden Laeufen und faellt in der Differenz heraus. So haengt der Adapter
    // nicht an der Zahl der Rueckblick-Tage.
    val eigene = lauf(1).toMutableList()
    lauf(0).forEach { eigene.remove(it) }
    return eigene
}

/** Die Hell-Uhrzeit als Zeitpunkt auf dem erfundenen UTC-Kalender; ohne Uhrzeit (`null`) der volle Nachlauf. */
private fun hellZeitpunktAus(hellUm: (HellAb) -> LocalTime?): HellZeitpunkt = { datum, art ->
    hellUm(art)?.let { datum.atTime(it).toInstant(MUSTER_ZONE).toEpochMilli() }
}

/** Epoch-Millis des erfundenen Kalenders als Minute der Leiste (0 = 18:00 am Vortag). */
private fun Long.alsMinute(): Int = (Math.floorDiv(this - MUSTERTAG_MS, MINUTE_MS) + MUSTERTAG_BEGINN_MIN).toInt()

/** Nur Bereiche, die das Fenster 0..2880 beruehren - die Leiste kappt die Balken, braucht aber die echten Grenzen. */
private fun <T> List<T>.beruehrtLeiste(minuten: (T) -> Pair<Int, Int>): List<T> =
    filter { val (von, bis) = minuten(it); bis > 0 && von < TAGESLEISTE_MIN }
