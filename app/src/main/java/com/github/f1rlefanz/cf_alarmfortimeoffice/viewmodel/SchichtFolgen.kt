package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.passtAufSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb

/**
 * Was eine Schicht AUSSERHALB des Weckers ausloest - rein lesend, fuer die Statuszeile
 * "Dimmen: … · Licht: … · Nicht stören: …" unter jeder Schichtkarte (Issue #70).
 *
 * DIE EINE WAHRHEIT: Dimmen und "Nicht stoeren" liest die Zeile seit #219 AM TYP
 * (`ShiftDefinition.dimmen`, `ShiftDefinition.nichtStoeren`) - dort, wo auch Dimmer und DND zur Laufzeit
 * lesen. Es gibt keine Regel und keinen globalen Schalter mehr, die verwaisen oder doppelt belegt sein
 * koennten; Umbenennen kostet beides nichts (die Einstellungen reisen mit dem Typ), Loeschen nimmt sie
 * mit. Hue-Regeln binden dagegen noch ueber den Schichtnamen ([passtAufSchicht], eigene UND allgemeine
 * feuern gemeinsam) - bis Etappe 4.
 *
 * Bewusst NICHT in der Zeile: die Master-Pause (voruebergehend, steht an anderer Stelle - die
 * Zeile beschreibt die Konfiguration) und zwei Schichten am selben Tag (die Zeile beschreibt EINEN Typ).
 */
data class SchichtFolgen(
    val dimmen: DimmStatus,
    val licht: LichtStatus,
    val dnd: DndStatus
)

sealed interface DimmStatus {
    /** "Dimmen an diesem Tag" ([com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen.an]) ist aus - kein Fenster wirkt. */
    data object Aus : DimmStatus

    /** Dimmen ist an, aber der Typ hat kein (deutbares) Fenster: er dimmt nicht und unterdrueckt das Dimmen seines Tages. */
    data object Keine : DimmStatus

    /**
     * Der Typ hat [fenster] wirksame Dimm-Fenster. [ohneWirkung] ist seit #219 immer 0 (am Typ gibt es
     * keine zweite Regel, die nie greift) und bleibt nur, weil `WeckerTage.kt` und seine Tests die Form
     * kennen - offen als Folge-Aufgabe des Umbaus.
     */
    data class Wirksam(val art: DimmArt, val ohneWirkung: Int, val fenster: Int = 1) : DimmStatus
}

/**
 * Seit #219 erzeugt die Statuszeile nur noch [EIGENE]; [ALLGEMEINE] und [UNTERDRUECKT] stammen aus dem
 * Regel-Modell (UNIVERSAL bzw. leere Fensterliste) und bleiben nur, weil `WeckerTage.kt` und seine Tests
 * sie nennen.
 */
enum class DimmArt {
    /** Der Typ hat eigene Fenster. */
    EIGENE,

    /** Frueher: nur die `SHIFT_UNIVERSAL`-Regel traf. Wird nicht mehr erzeugt. */
    ALLGEMEINE,

    /** Frueher: die wirksame Regel hatte eine leere Fensterliste. Am Typ ist das [DimmStatus.Keine]. */
    UNTERDRUECKT
}

sealed interface LichtStatus {
    /** Die Hue-Regeln liessen sich nicht lesen - "keine" waere hier eine Behauptung. */
    data object NichtLesbar : LichtStatus

    /**
     * Stille Schicht: kein Hue auf BEIDEN Pfaden - der `AlarmReceiver` ueberspringt die Regeln zur
     * Weckzeit, und `HueSmartScheduler.sonnenaufgangsKandidaten` plant keinen Vorab-Sonnenaufgang.
     * Wer einen dritten Hue-Pfad baut, muss die Stille dort ebenfalls pruefen, sonst luegt diese Zeile.
     */
    data object StilleSchicht : LichtStatus

    /** Keine Bridge eingerichtet - es geht kein Licht an, egal wie viele Regeln es gibt. */
    data object KeineBridge : LichtStatus

    /**
     * [eigene] und [allgemeine] feuern GEMEINSAM; beide 0 heisst "keine". [nurBeiDunkelheit]
     * zaehlt, wie viele davon eine Tageslicht-Schwelle tragen (#140) - sie sind MITGEZAEHLT,
     * nicht abgezogen: ob eine solche Regel an einem Morgen entfaellt, haengt an der Weckzeit.
     */
    data class Regeln(val eigene: Int, val allgemeine: Int, val nurBeiDunkelheit: Int = 0) : LichtStatus
}

/**
 * Die Quelle "Waehrend des Kalendereintrags" von "Nicht stoeren" aus Sicht EINES Typs. [AUSGENOMMEN]
 * (frueher: die globale Ausnahmenliste) wird seit #219 nicht mehr erzeugt - der Schalter steht am Typ -
 * und bleibt nur, weil `WeckerTage.kt` und seine Tests es nennen.
 */
enum class DienstzeitDnd { AUS, AN, AUSGENOMMEN }

data class DndStatus(
    val dienstzeit: DienstzeitDnd,
    val nachDimmer: Boolean,
    /** Nur `true`, wenn mindestens eine Quelle an ist - allein kuerzt der Cutoff nichts. */
    val rufbereitschaft: Boolean
) {
    val aus: Boolean get() = dienstzeit == DienstzeitDnd.AUS && !nachDimmer
}

/**
 * Leitet die [SchichtFolgen] einer Schichtdefinition ab. Rein, ohne Seiteneffekt, als JVM-Test
 * pruefbar.
 *
 * @param hueRegeln das Ergebnis von `IHueConfigRepository.getScheduleRules()`; ein Fehlschlag
 *   wird "nicht lesbar", nie "keine".
 */
internal fun ermittleSchichtFolgen(
    definition: ShiftDefinition,
    hueRegeln: Result<List<HueSchedule>>,
    hueKonfiguriert: Boolean
): SchichtFolgen = SchichtFolgen(
    dimmen = dimmStatus(definition),
    licht = lichtStatus(definition, hueRegeln, hueKonfiguriert),
    dnd = dndStatus(definition)
)

/** Wirksam ist ein Fenster nur bei "Dimmen an" - wie `TypDimmQuelle`, das beides zur Laufzeit liest. */
private fun dimmStatus(definition: ShiftDefinition): DimmStatus {
    val dimmen = definition.dimmen
    if (!dimmen.an) return DimmStatus.Aus
    val fenster = dimmen.fenster.size
    return if (fenster == 0) DimmStatus.Keine else DimmStatus.Wirksam(DimmArt.EIGENE, ohneWirkung = 0, fenster = fenster)
}

private fun lichtStatus(
    definition: ShiftDefinition,
    hueRegeln: Result<List<HueSchedule>>,
    hueKonfiguriert: Boolean
): LichtStatus {
    val regeln = hueRegeln.getOrElse { return LichtStatus.NichtLesbar }
    if (definition.isSilent) return LichtStatus.StilleSchicht
    if (!hueKonfiguriert) return LichtStatus.KeineBridge
    // Dieselbe Auswahl wie `HueRuleUseCase.findApplicableRules`: aktiv UND passtAufSchicht.
    //
    // Die Tageslicht-Bedingung (#140, `laeuftBeiTageslicht`) wird hier bewusst NICHT angewendet,
    // nur GEZAEHLT: sie vergleicht die Weckzeit mit dem Morgen des WECKTAGS, und diese Zeile
    // beschreibt eine Schichtdefinition, keinen Tag - es gibt keine Weckzeit, mit der man
    // vergleichen koennte. Ein Filter mit "heute" wuerde je nach Uhrzeit des Hinschauens
    // wechseln und die Regel als "keine" ausweisen, obwohl sie am naechsten dunklen Morgen laeuft.
    val treffer = regeln.filter { it.enabled && it.passtAufSchicht(definition.name) }
    val allgemeine = treffer.count {
        it.shiftPattern.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true)
    }
    return LichtStatus.Regeln(
        eigene = treffer.size - allgemeine,
        allgemeine = allgemeine,
        nurBeiDunkelheit = treffer.count { it.hellAb != HellAb.IMMER }
    )
}

private fun dndStatus(definition: ShiftDefinition): DndStatus {
    val schalter = definition.nichtStoeren
    val eineQuelleAn = schalter.waehrendEintrag || schalter.solangeGedimmt
    return DndStatus(
        dienstzeit = if (schalter.waehrendEintrag) DienstzeitDnd.AN else DienstzeitDnd.AUS,
        nachDimmer = schalter.solangeGedimmt,
        // Wie `DndTypSchalter.rufCutoffMinuten` zur Laufzeit: die KATEGORIE traegt den Cutoff.
        rufbereitschaft = definition.kategorie == ShiftKategorie.RUFBEREITSCHAFT && eineQuelleAn
    )
}

/**
 * Die Statuszeile als Text.
 *
 * UMBRUCH (320 dp, siehe Skill cfalarm-ui-und-navigation): Innerhalb eines Abschnitts stehen
 * geschuetzte Leerzeichen, damit "Dimmen: 2 Fenster" nie in "Dimmen:" / "2 Fenster"
 * zerfaellt. Umbrochen wird regulaer nur HINTER einem " · " (davor steht ebenfalls ein
 * geschuetztes Leerzeichen, damit der Punkt nicht an den Zeilenanfang rutscht). Die einzigen
 * weiteren Bruchstellen liegen VOR dem Zusatz "(N nur bei Dunkelheit)" sowie HINTER dem Komma vor
 * "Rufbereitschaft": ein Abschnitt mit Zusatz ist
 * laenger als eine Kartenzeile bei 320 dp, und ein durchgehend geschuetzter Abschnitt wuerde
 * dann mitten im Wort umbrechen.
 */
internal fun SchichtFolgen.alsText(): String = abschnitte(mitLicht = true)

/**
 * Wie [alsText], aber ohne den Licht-Abschnitt: Hue-Regeln laufen nur zu einem ALARM
 * (`AlarmReceiver` -> `executeRulesForAlarm`, `HueSmartScheduler` liest nur Alarme). Ein Typ mit
 * `weckerAn = false` stellt keinen - sein Licht ist keine Aussage, die die Zeile machen darf
 * (SPEC E8: ohne Weckzeit gibt es kein Licht).
 */
internal fun SchichtFolgen.alsTextOhneLicht(): String = abschnitte(mitLicht = false)

private fun SchichtFolgen.abschnitte(mitLicht: Boolean): String =
    listOfNotNull(
        teil(Texte.DIMMEN, dimmen.teile()),
        if (mitLicht) teil(Texte.LICHT, licht.teile()) else null,
        teil(Texte.DND, dnd.teile())
    ).joinToString(Texte.TRENNER)

/**
 * Die Kurzform einer Zeile der Schichttypen-Liste (SPEC E9 / 3.1, Prototyp `zeile`) - `null`, wenn die
 * Zeile keine hat und ihre Statuszeile darueber schon alles sagt.
 *
 * EINE Zeile, in dieser Reihenfolge, mit " · " verbunden: `[kein Wecker]` · `[dimmKurz]` · `Licht: …` ·
 * `Nicht stören: …`. [dimmKurz] ersetzt hier den Abschnitt „Dimmen: …“ der Statuszeile ([alsText]) an
 * derselben Stelle („dimmt 20:00–Wecker“ statt „Dimmen: 1 Fenster“); `null` heisst: kein Dimm-Abschnitt
 * (kein Fenster, Krank, Ignorieren - die Liste ist kein Ort fuer „Nicht dimmen“). Der Text kommt aus
 * der Definition, nicht aus [folgen] (`viewmodel/` importiert kein `ui.`; die Zeile der Liste ruft
 * `listenKurzform` in `ShiftConfigScreen.kt`) und steht deshalb auch ohne gelesene Folgen.
 * [alsText] bleibt fuer die Tagesliste unveraendert.
 *
 * Zwei Faelle kennt die Statuszeile nicht: „kein Wecker“ (`weckerAn = false`, steht VOR den Abschnitten)
 * und „wird übergangen“ (Ignorieren). Bei „kein Wecker“ fehlt der Licht-Abschnitt: ohne Alarm laeuft keine
 * Hue-Regel ([alsTextOhneLicht]).
 *
 * - **Ignorieren** zuerst: ein solcher Typ wirkt weder mit Wecker noch mit Dimmer, Licht oder DND,
 *   auch wenn das ViewModel ihm [SchichtFolgen] liefert (er passiert dort den Filter). Ein
 *   AUSGESCHALTETER Ignorieren-Typ uebergeht nichts - dann gibt es keine Kurzform.
 * - **Ausgeschaltet und Krank** haben keine: ihre Statuszeile sagt, was sie tun bzw. nicht tun.
 * - **`folgen == null`** heisst „noch nicht gelesen“: Licht und „Nicht stören“ stehen dann nicht, eine
 *   Zeile „Licht: keine“ waere eine erfundene Aussage. Uebrig bleibt, was die Definition allein sagt:
 *   [dimmKurz] und „kein Wecker“ - oder `null`.
 */
internal fun kurzform(definition: ShiftDefinition, folgen: SchichtFolgen?, dimmKurz: String?): String? {
    if (definition.kategorie == ShiftKategorie.IGNORIEREN) {
        return if (definition.isEnabled) schuetze(SchichttypTexte.WIRD_UEBERGANGEN) else null
    }
    if (!definition.isEnabled || definition.kategorie == ShiftKategorie.KRANK) return null
    // Geschuetzt wie die Abschnitte der Statuszeile: umbrochen wird nur HINTER dem Trenner.
    val keinWecker = if (definition.weckerAn) null else schuetze(SchichttypTexte.KEIN_WECKER)
    val dimmen = dimmKurz?.let(::schuetze)
    val rest = folgen?.kurzAbschnitte(dimmen, mitLicht = definition.weckerAn) ?: dimmen
    return listOfNotNull(keinWecker, rest).joinToString(Texte.TRENNER).ifEmpty { null }
}

/** Wie [abschnitte], aber mit dem Dimm-Abschnitt der Liste ([dimmen], bereits geschuetzt) statt „Dimmen: …“. */
private fun SchichtFolgen.kurzAbschnitte(dimmen: String?, mitLicht: Boolean): String =
    listOfNotNull(
        dimmen,
        if (mitLicht) teil(Texte.LICHT, licht.teile()) else null,
        teil(Texte.DND, dnd.teile())
    ).joinToString(Texte.TRENNER)

/**
 * Ein Abschnitt: Beschriftung + erstes Stueck geschuetzt, jedes weitere Stueck ebenfalls in sich
 * geschuetzt und mit einem NORMALEN Leerzeichen angehaengt - nur dort darf umbrochen werden.
 */
private fun teil(beschriftung: String, stuecke: List<String>): String =
    (listOf("$beschriftung ${stuecke.first()}") + stuecke.drop(1))
        .joinToString(" ") { schuetze(it) }

private fun schuetze(text: String): String = text.replace(' ', GESCHUETZT)

private fun DimmStatus.teile(): List<String> = when (this) {
    DimmStatus.Aus -> listOf(Texte.AUS)
    DimmStatus.Keine -> listOf(Texte.KEINE)
    is DimmStatus.Wirksam -> buildList {
        // ALLGEMEINE und UNTERDRUECKT erzeugt die Ableitung nicht mehr (siehe DimmArt); beide waeren
        // hier ein Typ ohne eigene Fenster.
        add(if (art == DimmArt.EIGENE) fensterAnzahl(fenster) else Texte.KEINE)
    }
}

private fun LichtStatus.teile(): List<String> = when (this) {
    LichtStatus.NichtLesbar -> listOf(Texte.NICHT_LESBAR)
    LichtStatus.StilleSchicht -> listOf(Texte.STILLE_SCHICHT)
    LichtStatus.KeineBridge -> listOf(Texte.KEINE_BRIDGE)
    is LichtStatus.Regeln -> buildList {
        add(
            when {
                eigene > 0 && allgemeine > 0 -> "${regelAnzahl(eigene)} + allgemeine"
                eigene > 0 -> regelAnzahl(eigene)
                allgemeine == 1 -> Texte.ALLGEMEINE_REGEL
                allgemeine > 1 -> "$allgemeine allgemeine Regeln"
                else -> Texte.KEINE
            }
        )
        // Eigenes Stueck, damit davor umbrochen werden darf (wie "(+N ohne Wirkung)").
        if (nurBeiDunkelheit > 0) add("($nurBeiDunkelheit ${Texte.NUR_BEI_DUNKELHEIT})")
    }
}

private fun regelAnzahl(n: Int): String = if (n == 1) "1 Regel" else "$n Regeln"

private fun fensterAnzahl(n: Int): String = if (n == 1) "1 Fenster" else "$n Fenster"

private fun DndStatus.teile(): List<String> {
    if (aus) return listOf(Texte.AUS)
    val haupt = when (dienstzeit) {
        DienstzeitDnd.AUS -> listOf(Texte.NACH_DIMMER)
        DienstzeitDnd.AN ->
            if (nachDimmer) listOf("${Texte.DIENSTZEIT} + ${Texte.NACH_DIMMER}") else listOf(Texte.DIENSTZEIT)
        // Wird seit #219 nicht mehr erzeugt (siehe DienstzeitDnd); wie "aus" gelesen.
        DienstzeitDnd.AUSGENOMMEN -> if (nachDimmer) listOf(Texte.NACH_DIMMER) else listOf(Texte.AUS)
    }
    if (!rufbereitschaft) return haupt
    // Das Komma gehoert zum Vorgaenger, damit der Umbruch HINTER ihm liegt, nie davor.
    return haupt.dropLast(1) + "${haupt.last()}," + Texte.RUFBEREITSCHAFT
}

/** Geschuetztes Leerzeichen (U+00A0). */
private val GESCHUETZT: Char = Char(0x00A0)

/** Deutsche Literale der Statuszeile - eine Stelle, damit Test und Anzeige nicht auseinanderlaufen. */
private object Texte {
    const val DIMMEN = "Dimmen:"
    const val LICHT = "Licht:"
    const val DND = "Nicht stören:"

    /** Umbruch nur HINTER dem Punkt: davor geschuetzt, danach ein normales Leerzeichen. */
    val TRENNER = "${GESCHUETZT}· "

    const val AUS = "aus"
    const val KEINE = "keine"
    const val ALLGEMEINE_REGEL = "allgemeine Regel"
    const val NICHT_LESBAR = "nicht lesbar"
    const val STILLE_SCHICHT = "aus (stille Schicht)"
    const val KEINE_BRIDGE = "keine Bridge"
    /** Gleicher Wortlaut wie das Etikett `tageslicht_etikett_dunkel` in der Hue-Regelliste. */
    const val NUR_BEI_DUNKELHEIT = "nur bei Dunkelheit"
    const val DIENSTZEIT = "Dienstzeit"
    const val NACH_DIMMER = "nach Dimmer"
    const val RUFBEREITSCHAFT = "Rufbereitschaft"
}
