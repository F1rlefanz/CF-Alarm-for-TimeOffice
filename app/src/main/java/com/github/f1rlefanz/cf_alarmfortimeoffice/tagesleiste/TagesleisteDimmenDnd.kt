package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.HellZeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.OHNE_TAGESLICHT
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndOnCallCutoffResolver
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
 * Ignorieren ohne weiteren Eintrag haben keine Schichtspanne und laufen wie ein freier Tag (die Leiste
 * selbst bleibt fuer Ignorieren leer, `tagesleiste()`):
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
 * RUF-CUTOFF. Der EIGENE Cutoff gehoert der Leiste (`kappenAmRufCutoff`, #193): sie braucht das UNGEKAPPTE
 * Ende - kappte der Adapter vor, fände sie nichts mehr zu kuerzen, und "endet spaetestens jetzt" stuende
 * nie im Ereignistext. Die Cutoffs der NACHBARTAGE im Block kennt sie nicht; die kappt
 * [kappenAmNachbarCutoff], wie der Betrieb es ueber alle Spannen tut.
 *
 * @param regelwahl nur wegen `findRuleForShift`/`findRuleForFreeDay`: die Auswahl geht durch die
 *   echte Funktion statt durch eine Kopie. Es wird kein Repository gelesen.
 */
fun dimmenUndNichtStoeren(
    eingabe: TagesleisteEingabe,
    einstellungen: DimDndEinstellungen,
    regelwahl: DimRuleUseCase
): DimUndNichtStoeren {
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
        return DimUndNichtStoeren(emptyList(), kappenAmNachbarCutoff(nichtStoeren, eingabe, wirksam).sortedBy { it.vonMin })
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
    return DimUndNichtStoeren(
        dimmen.sortedBy { it.vonMin },
        kappenAmNachbarCutoff(nichtStoeren, eingabe, wirksam).sortedBy { it.vonMin }
    )
}

/**
 * Der Ruf-Cutoff der NACHBARTAGE im Block. `DndScheduleUseCase.computeWindows` bildet die Cutoffs ueber
 * ALLE Rufbereitschafts-Spannen und kappt jedes Fenster am fruehesten, das es enthaelt - also auch am
 * Cutoff des Vortags (Beginn 07:00 am Vortag: Cutoff 05:00 am Mustertag). Die Leiste kennt nur den
 * eigenen Slot (`kappenAmRufCutoff`, #193); ohne diesen Schritt endete "Nicht stoeren" in der Mitte
 * eines Ruf-Blocks (die Vorgabe der Rufbereitschaft) eine Stunde zu spaet.
 *
 * Der EIGENE Cutoff bleibt der Leiste: sie braucht das Ende, um "endet spaetestens jetzt" zu sagen.
 * Bei einem Nachbar-Cutoff steht dafuer "Nicht stoeren aus" (offen: #204).
 */
private fun kappenAmNachbarCutoff(
    bereiche: List<DndBereich>,
    eingabe: TagesleisteEingabe,
    wirksam: Dienstzeit?
): List<DndBereich> {
    if (eingabe.kategorie != ShiftKategorie.RUFBEREITSCHAFT || wirksam == null) return bereiche
    val beginn = MUSTERTAG.atTime(wirksam.beginn).toInstant(MUSTER_ZONE).toEpochMilli()
    val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
        alarms = nachbarVersatz(eingabe.blockposition).map { DndOnCallCutoffResolver.AlarmSlot(eingabe.name, beginn + it) },
        onCallShifts = setOf(eingabe.name),
        cutoffMinutes = eingabe.rufCutoffMinuten.coerceIn(0, MINUTEN_PRO_TAG - 1),
        zone = MUSTER_ZONE
    ).map { it.alsMinute().toLong() }
    val gekuerzt = DndOnCallCutoffResolver.clip(bereiche.map { it.vonMin.toLong()..it.bisMin.toLong() }, cutoffs)
    return bereiche.mapIndexed { i, b -> b.copy(bisMin = gekuerzt[i].last.toInt()) }
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

/** Um wie viel der Vortag bzw. Folgetag desselben Typs im Block gegen den Mustertag verschoben liegt. */
private fun nachbarVersatz(position: Blockposition): List<Long> = buildList {
    if (position == Blockposition.MITTLERER || position == Blockposition.LETZTER) add(-TAG_MS)
    if (position == Blockposition.ERSTER || position == Blockposition.MITTLERER) add(TAG_MS)
}

/** `null`, wo es keinen Anker gibt: ein Schichttag ohne gueltige Weckzeit. */
private fun erfindeTag(eingabe: TagesleisteEingabe, wirksam: Dienstzeit?): Mustertag? {
    if (eingabe.kategorie == ShiftKategorie.KRANK || eingabe.kategorie == ShiftKategorie.IGNORIEREN) {
        return Mustertag(MUSTERTAG, emptyList(), emptySet())
    }
    val weckzeit = eingabe.weckzeit ?: return null
    // Dieselbe Regel wie `weckpunktMin()`, aber ohne dessen "Wecker aus gibt null": die Spanne fuehrt
    // die Weckzeit auch bei Wecker aus.
    val beginn = MUSTERTAG.atTime(wirksam?.beginn ?: LocalTime.MIDNIGHT)
    val wecker = weckzeitpunkt(beginn, wirksam?.ganztaegig ?: true, weckzeit)
    val weckerMs = wecker.toInstant(MUSTER_ZONE).toEpochMilli()
    val endeMs = wirksam?.let { beginn.toInstant(MUSTER_ZONE).toEpochMilli() + it.dauerMin * MINUTE_MS } ?: 0L

    val versatz = listOf(0L) + nachbarVersatz(eingabe.blockposition)
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
