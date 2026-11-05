package com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndOnCallCutoffResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.weckzeitpunkt
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/*
 * Die Tagesleiste der Typ-Seite "So laeuft ein Tag <Name>" als REINE Rechnung (Umbau #193, SPEC 3.3
 * Punkt 3). Vorlage ist die Rechenlogik des Prototyps (`Main.dc.html`: `ereignisse`, `kappen`,
 * `dndVereinen`, `eventsIn`, `balken`), nach Kotlin uebertragen.
 *
 * DIE EINHEIT. Alles hier - Eingaben wie Ausgaben - sind ganze MINUTEN AB 18:00 AM VORTAG (0..2880):
 * der Mustertag beginnt bei 360 und endet bei 1800, 2880 ist 18:00 am Folgetag. Der Prototyp zaehlt
 * dagegen ab dem Mitternacht des Mustertags (1440); wer von dort uebersetzt, rechnet `-1080`.
 * Eine einzige Einheit fuer alle Adapter (#194 Dimmen/DND, #195 Licht), damit keiner eine eigene
 * Umrechnung erfindet.
 *
 * Gerechnet wird auf der Wanduhr, ohne Datum und ohne Zone. Die Leiste zeigt einen Normaltag und
 * ignoriert Zeitumstellungen - dieselbe Haltung wie `weckzeitpunkt()` (#192).
 */

/** Minuten je Tag. */
const val MINUTEN_PRO_TAG: Int = 1440

/** Laenge des Fensters: 18:00 am Vortag bis 18:00 am Folgetag. */
const val TAGESLEISTE_MIN: Int = 2880

/** 00:00 des Mustertags, in Minuten ab 18:00 am Vortag. */
const val MUSTERTAG_BEGINN_MIN: Int = 360

/** 00:00 des Folgetags, in Minuten ab 18:00 am Vortag. */
const val FOLGETAG_BEGINN_MIN: Int = MUSTERTAG_BEGINN_MIN + MINUTEN_PRO_TAG

/**
 * Ein beliebiger fester Kalendertag, auf den die Uhrzeiten gelegt werden - nur damit
 * `weckzeitpunkt()` ein Datum bekommt. Er kommt in keiner Ausgabe vor.
 */
private val MUSTERTAG: LocalDate = LocalDate.of(2001, 1, 2)

/** Die sechs Spuren der Leiste, in der Reihenfolge des Prototyps. */
enum class Spur { EINTRAG, DIMMEN, NICHT_STOEREN, LICHT, RUF_ABFRAGE, WECKER }

/**
 * Ein Dimm-Bereich mit Staerke - fertig aufgeloest (Adapter #194, ab Etappe 3 aus den Fenstern am Typ).
 * Der Bereich haengt weder an `DimRule` noch an `DimWindow`.
 *
 * @param endetBeimWecker das Ende wurde durch die Weckzeit bestimmt ("Schon beim Wecker enden");
 *   nur der Ereignistext sagt das, die Minuten sind schon eingerechnet.
 * @param positionen an welchen Tagen eines Blocks der Bereich gilt; der Default gilt immer.
 */
data class DimBereich(
    val vonMin: Int,
    val bisMin: Int,
    val prozent: Int,
    val endetBeimWecker: Boolean = false,
    val positionen: Set<Blockposition> = Blockposition.ALLE
)

/** Woher ein "Nicht stoeren"-Bereich kommt (E7: zwei Schalter) - bestimmt nur den Ereignistext. */
enum class DndQuelle { DIMM_FENSTER, KALENDEREINTRAG }

/** Ein "Nicht stoeren"-Bereich, fertig aufgeloest. [positionen] wie bei [DimBereich]. */
data class DndBereich(
    val vonMin: Int,
    val bisMin: Int,
    val quelle: DndQuelle,
    val positionen: Set<Blockposition> = Blockposition.ALLE
)

/** Wie das Licht beginnt: ein Sonnenaufgang vor dem Wecker oder "Licht an" zum Wecker. */
enum class LichtArt { SONNENAUFGANG, AN }

/** Ein Licht-Bereich, fertig aufgeloest (Adapter #195, Beginn und Ende rechnet Hue). */
data class LichtBereich(val vonMin: Int, val bisMin: Int, val art: LichtArt)

/**
 * Was die Leiste braucht. Ein ungespeicherter Entwurf geht genauso hinein wie ein gespeicherter Typ.
 *
 * @param weckzeit `null`, solange die eingetippte Weckzeit kein gueltiges HH:mm ist - dann gibt es
 *   noch keinen Weckpunkt.
 * @param still der Typ hat einen stummen Wecker ("Stummer Wecker" statt "Wecker").
 * @param dienstzeit was [dienstzeitAus] fand, ROH; die Regel je Kategorie wendet [tagesleiste] an.
 * @param blockposition an welchem Tag eines Blocks der Mustertag liegt.
 * @param rufCutoffMinuten Minuten seit Mitternacht, bis zu denen "Nicht stoeren" bei
 *   Rufbereitschaft spaetestens endet.
 */
data class TagesleisteEingabe(
    val name: String,
    val kategorie: ShiftKategorie,
    val weckerAn: Boolean,
    val weckzeit: LocalTime?,
    val still: Boolean,
    val dienstzeit: Dienstzeit?,
    val blockposition: Blockposition = standardBlockposition(kategorie),
    val dimmen: List<DimBereich> = emptyList(),
    val nichtStoeren: List<DndBereich> = emptyList(),
    val licht: List<LichtBereich> = emptyList(),
    val rufCutoffMinuten: Int = DndPrefs.DEFAULT_ONCALL_CUTOFF_MIN
)

/** Die Vorgabe der Blockposition: mittendrin bei Dienst und Rufbereitschaft, sonst einzeln. */
fun standardBlockposition(kategorie: ShiftKategorie): Blockposition = when (kategorie) {
    ShiftKategorie.DIENST, ShiftKategorie.RUFBEREITSCHAFT -> Blockposition.MITTLERER
    else -> Blockposition.EINZELNER
}

/** Ein Balken einer Spur, auf das Fenster 0..2880 gekappt. */
data class Balken(val spur: Spur, val vonMin: Int, val bisMin: Int)

/** Der Wecker als Punkt auf der Leiste. */
data class Weckpunkt(val minute: Int, val still: Boolean)

/** Eine Zeile der Ereignisliste. [zeit] traegt "Vortag"/"Folgetag" vor der Uhrzeit. */
data class Ereignis(val minute: Int, val spur: Spur, val zeit: String, val text: String)

/** Was die Leiste dem Nutzer ausserdem sagen muss. */
enum class Hinweis(val text: String) {
    /** Dienst oder Rufbereitschaft ohne Spanne: eine Dienstzeit wird nicht erfunden. */
    KEIN_TERMIN_IM_KALENDER("Im Kalender steht noch kein Termin dieses Typs – darum zeigt die Leiste keinen Eintrag."),
}

/**
 * Das Ergebnis.
 *
 * @param balken alle Balken, gekappt auf 0..2880; ohne Balken ausserhalb des Fensters.
 * @param weckpunkte hoechstens einer; leer bei Wecker aus, Krank, Ignorieren, ungueltiger Weckzeit
 *   und einem Wecker, der ausserhalb des Fensters liegt.
 * @param rufAbfragen die Zeitpunkte der stuendlichen Kalender-Abfrage (Beginn + k * 1 h, solange
 *   vor dem Ende); nur bei Rufbereitschaft mit Dienstzeit.
 * @param ereignisse nach Zeit sortiert, nur was im Fenster liegt.
 */
data class Tagesleiste(
    val balken: List<Balken>,
    val weckpunkte: List<Weckpunkt>,
    val rufAbfragen: List<Int>,
    val ereignisse: List<Ereignis>,
    val hinweis: Hinweis?
) {
    companion object {
        val LEER = Tagesleiste(emptyList(), emptyList(), emptyList(), emptyList(), null)
    }
}

private const val STUNDE_MIN = 60

/** Ein Balken in ungekappter Form - die Ereignisse brauchen die echten Grenzen. */
private data class Stueck(
    val spur: Spur,
    val von: Int,
    val bis: Int,
    val an: String?,
    val aus: String?,
    /** Ein "Nicht stoeren"-Bereich, den der Ruf-Cutoff gekuerzt hat. */
    val geklippt: Boolean = false
)

/**
 * Die Minute des Weckers auf der Leiste, oder `null`, wenn es keinen gibt: Wecker aus, Krank,
 * Ignorieren, keine gueltige Weckzeit oder ein Wecker ausserhalb des Fensters.
 *
 * Die Regel ist `weckzeitpunkt()` aus #192 (Nachtdienst: Vortag), angewandt auf die Dienstzeit der
 * Kategorie. Ohne Dienstzeit (Dienst/Ruf ohne Spanne) kennt die Leiste keinen Beginn und legt den
 * Wecker auf die Weckzeit des Mustertags selbst.
 */
fun weckpunktMin(
    kategorie: ShiftKategorie,
    weckerAn: Boolean,
    weckzeit: LocalTime?,
    dienstzeit: Dienstzeit?
): Int? {
    if (!weckerAn || weckzeit == null) return null
    if (kategorie == ShiftKategorie.KRANK || kategorie == ShiftKategorie.IGNORIEREN) return null
    val wirksam = wirksameDienstzeit(kategorie, dienstzeit)
    val beginn = MUSTERTAG.atTime(wirksam?.beginn ?: LocalTime.MIDNIGHT)
    val wecker = weckzeitpunkt(beginn, wirksam?.ganztaegig ?: true, weckzeit)
    val minute = MUSTERTAG_BEGINN_MIN + Duration.between(MUSTERTAG.atStartOfDay(), wecker).toMinutes().toInt()
    return minute.takeIf { it in 0 until TAGESLEISTE_MIN }
}

/** Rechnet die Leiste. Rein: gleiche Eingabe, gleiches Ergebnis. */
fun tagesleiste(eingabe: TagesleisteEingabe): Tagesleiste =
    if (eingabe.kategorie == ShiftKategorie.IGNORIEREN) Tagesleiste.LEER else eingabe.rechne()

private fun TagesleisteEingabe.rechne(): Tagesleiste {
    val wirksam = wirksameDienstzeit(kategorie, dienstzeit)
    val hinweis = Hinweis.KEIN_TERMIN_IM_KALENDER.takeIf { wirksam == null }
    val rufDienstzeit = wirksam?.takeIf { kategorie == ShiftKategorie.RUFBEREITSCHAFT }

    val stuecke = mutableListOf<Stueck>()
    if (wirksam != null) {
        stuecke += Stueck(
            Spur.EINTRAG, wirksam.beginnMin, wirksam.endeMin,
            an = if (wirksam.ganzerTag) "$name (ganztägig)" else "$name beginnt",
            aus = if (wirksam.ganzerTag) null else "$name endet"
        )
    }
    dimmen.filter { blockposition in it.positionen && it.bisMin > it.vonMin }.forEach { d ->
        stuecke += Stueck(
            Spur.DIMMEN, d.vonMin, d.bisMin,
            an = "Dimmen beginnt – ${d.prozent} % ($name)",
            aus = "Dimmen endet ($name)" + if (d.endetBeimWecker) " – der Wecker klingelt" else ""
        )
    }
    licht.filter { it.bisMin > it.vonMin }.forEach { l ->
        stuecke += when (l.art) {
            LichtArt.SONNENAUFGANG -> Stueck(Spur.LICHT, l.vonMin, l.bisMin, "Licht: Sonnenaufgang beginnt", null)
            LichtArt.AN -> Stueck(Spur.LICHT, l.vonMin, l.bisMin, "Licht an", "Licht aus")
        }
    }
    if (rufDienstzeit != null) {
        stuecke += Stueck(
            Spur.RUF_ABFRAGE, rufDienstzeit.beginnMin, rufDienstzeit.endeMin,
            an = "Rufbereitschaft: Kalender wird stündlich geprüft",
            aus = "Stündliche Prüfung endet"
        )
    }

    // "Nicht stoeren" ist EIN Schalter: erst am Ruf-Cutoff kappen, DANN aneinanderstossende
    // Bereiche vereinen (Reihenfolge des Prototyps und von DndScheduleUseCase: jedes Fenster wird
    // fuer sich geklippt). Umgekehrt wuerde ein Bereich, der um 06:00 beginnt, mit dem davor an der
    // Grenze 05:00 gekappten zu einem Bereich verschmelzen und den Cutoff verschlucken.
    val dnd = nichtStoeren.filter { blockposition in it.positionen && it.bisMin > it.vonMin }.map { d ->
        Stueck(
            Spur.NICHT_STOEREN, d.vonMin, d.bisMin,
            an = "„Nicht stören“ an (${if (d.quelle == DndQuelle.KALENDEREINTRAG) "Kalendereintrag" else "Dimm-Fenster"})",
            aus = "„Nicht stören“ aus"
        )
    }
    val amCutoff = if (rufDienstzeit != null) kappenAmRufCutoff(dnd, name, rufDienstzeit, rufCutoffMinuten) else dnd
    stuecke += vereinen(amCutoff)

    val weckMin = weckpunktMin(kategorie, weckerAn, weckzeit, dienstzeit)
    val weckpunkte = listOfNotNull(weckMin?.let { Weckpunkt(it, still) })
    val rufAbfragen = rufDienstzeit?.let(::rufRaster).orEmpty()

    val ereignisse = buildList {
        stuecke.forEach { s ->
            if (s.an != null && s.von in 0 until TAGESLEISTE_MIN) add(Ereignis(s.von, s.spur, zeitText(s.von), s.an))
            if (s.aus != null && s.bis in 0 until TAGESLEISTE_MIN) add(Ereignis(s.bis, s.spur, zeitText(s.bis), s.aus))
        }
        weckpunkte.forEach { w ->
            add(Ereignis(w.minute, Spur.WECKER, zeitText(w.minute), (if (w.still) "Stummer Wecker – " else "Wecker – ") + name))
        }
        // Der Text nennt den Cutoff nur, wenn er einen Bereich WIRKLICH beendet: ohne "Nicht stoeren"
        // am Rufbereitschaftstag steht nichts, was spaetestens enden koennte.
        stuecke.filter { it.geklippt && it.bis in 0 until TAGESLEISTE_MIN }.forEach { s ->
            add(Ereignis(s.bis, Spur.NICHT_STOEREN, zeitText(s.bis), "„Nicht stören“ endet spätestens jetzt – erreichbar sein (Rufbereitschaft)"))
        }
    }.sortedBy { it.minute }

    val balken = stuecke
        .filter { it.bis > 0 && it.von < TAGESLEISTE_MIN }
        .map { Balken(it.spur, maxOf(it.von, 0), minOf(it.bis, TAGESLEISTE_MIN)) }

    return Tagesleiste(balken, weckpunkte, rufAbfragen.filter { it in 0 until TAGESLEISTE_MIN }, ereignisse, hinweis)
}


/**
 * Der Raster der stuendlichen Kalender-Abfrage: Beginn + k * 1 h, solange vor dem Ende. Ab dem Ende
 * zaehlt kein Termin mehr. Die Kette selbst steht in `RufbereitschaftAbfrage` und bleibt unberuehrt;
 * `TagesleisteTest` haelt beide gegeneinander.
 */
private fun rufRaster(dienstzeit: Dienstzeit): List<Int> =
    generateSequence(dienstzeit.beginnMin) { it + STUNDE_MIN }.takeWhile { it < dienstzeit.endeMin }.toList()

/**
 * Kuerzt jeden Bereich, in dem der Ruf-Cutoff liegt, auf den Cutoff - ueber dieselben Funktionen wie
 * `DndScheduleUseCase` (`cutoffInstants` rollt den Cutoff-Tag, `clip` kuerzt nur, was ihn ECHT
 * enthaelt). Der Mustertag liegt dafuer in UTC: dort faellt Wanduhr und Instant zusammen.
 */
private fun kappenAmRufCutoff(
    bereiche: List<Stueck>,
    name: String,
    dienstzeit: Dienstzeit,
    cutoffMinuten: Int
): List<Stueck> {
    val mitternacht = MUSTERTAG.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
    val beginn = MUSTERTAG.atTime(dienstzeit.beginn).toInstant(ZoneOffset.UTC).toEpochMilli()
    val cutoffs = DndOnCallCutoffResolver.cutoffInstants(
        alarms = listOf(DndOnCallCutoffResolver.AlarmSlot(name, beginn)),
        onCallShifts = setOf(name),
        cutoffMinutes = cutoffMinuten.coerceIn(0, MINUTEN_PRO_TAG - 1),
        zone = ZoneOffset.UTC
    ).map { (it - mitternacht) / MINUTE_MS + MUSTERTAG_BEGINN_MIN }
    val gekuerzt = DndOnCallCutoffResolver.clip(bereiche.map { it.von.toLong()..it.bis.toLong() }, cutoffs)
    return bereiche.mapIndexed { i, b ->
        if (gekuerzt[i].last.toInt() != b.bis) b.copy(bis = gekuerzt[i].last.toInt(), aus = null, geklippt = true) else b
    }
}

private const val MINUTE_MS = 60_000L

/** Bereiche, die sich beruehren oder ueberlappen, zu einem; Beginn-Text vom ersten, Ende-Text vom letzten. */
private fun vereinen(bereiche: List<Stueck>): List<Stueck> {
    val ergebnis = mutableListOf<Stueck>()
    bereiche.sortedBy { it.von }.forEach { b ->
        val letzter = ergebnis.lastOrNull()
        when {
            letzter == null || b.von > letzter.bis -> ergebnis += b
            b.bis > letzter.bis -> ergebnis[ergebnis.lastIndex] = letzter.copy(bis = b.bis, aus = b.aus, geklippt = b.geklippt)
        }
    }
    return ergebnis
}

/** "Vortag 22:00", "07:00", "Folgetag 05:00" - die Uhrzeit einer Minute der Leiste. */
internal fun zeitText(minute: Int): String {
    val uhr = Math.floorMod(minute + 18 * STUNDE_MIN, MINUTEN_PRO_TAG)
    val zeit = "%02d:%02d".format(java.util.Locale.ROOT, uhr / STUNDE_MIN, uhr % STUNDE_MIN)
    return when {
        minute < MUSTERTAG_BEGINN_MIN -> "Vortag $zeit"
        minute >= FOLGETAG_BEGINN_MIN -> "Folgetag $zeit"
        else -> zeit
    }
}
