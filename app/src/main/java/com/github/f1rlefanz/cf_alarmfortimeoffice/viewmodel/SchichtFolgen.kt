package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.betrifftSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
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
 * WARUM ES DIE ZEILE GIBT: Dimmer-, Hue- und DND-Regeln binden ueber den Schichtnamen, werden aber
 * an drei anderen Stellen der App eingerichtet. Ob eine Schicht verwaist ist (keine Regel) oder
 * doppelt belegt (eine zweite Dimm-Regel, die nie wirkt), sah man bisher nur durch Abklappern
 * aller drei Screens.
 *
 * DIE EINE WAHRHEIT: Gezaehlt wird ueber DIESELBEN Funktionen wie zur Laufzeit -
 * `DimRuleUseCase.findRuleForShift` (genau EINE wirksame Dimm-Regel), [passtAufSchicht] (eigene
 * UND allgemeine Hue-Regeln feuern gemeinsam) und der exakte, Gross-/Kleinschreibung beachtende
 * Mengenabgleich von `DndShiftSpanResolver.buildShiftSpans`. Eine eigene Nachbildung waere eine
 * zweite Wahrheit, und die Zeile wuerde genau dann luegen, wenn sie gebraucht wird.
 *
 * Bewusst NICHT in der Zeile: die Master-Pause (voruebergehend, steht an anderer Stelle - die
 * Zeile beschreibt die Konfiguration) und zwei Schichten am selben Tag (Konflikte zeigt die
 * Dimmer-Regelliste).
 */
data class SchichtFolgen(
    val dimmen: DimmStatus,
    val licht: LichtStatus,
    val dnd: DndStatus
)

sealed interface DimmStatus {
    /** Der Dimmer-Hauptschalter (`dim_enabled`) ist aus - keine Regel wirkt. */
    data object Aus : DimmStatus

    /** `findRuleForShift` liefert nichts: weder eine eigene noch eine allgemeine Regel. */
    data object Keine : DimmStatus

    /**
     * Die eine wirksame Regel. [ohneWirkung] zaehlt weitere AKTIVE Regeln, die denselben Namen
     * tragen, aber nie greifen, weil `findRuleForShift` die erste nimmt.
     */
    data class Wirksam(val art: DimmArt, val ohneWirkung: Int) : DimmStatus
}

enum class DimmArt {
    /** Eigene Regel mit Fenstern. */
    EIGENE,

    /** Nur die `SHIFT_UNIVERSAL`-Regel trifft. */
    ALLGEMEINE,

    /**
     * Die wirksame Regel hat eine LEERE Fensterliste: sie unterdrueckt das Dimmen an diesem Tag
     * (`DimWindowResolver.regelFuerTag`, Schritt 1) - das gilt fuer eine eigene wie fuer eine
     * allgemeine Regel gleichermassen.
     */
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

/** Die Dienstzeit-Quelle von "Nicht stoeren" aus Sicht EINER Schicht. */
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
 * @param ruleForShift MUSS `DimRuleUseCase.findRuleForShift` sein (an [dimRegeln] gebunden) -
 *   dieselbe Auswahl wie bei Wirkung und Konfliktanzeige.
 * @param hueRegeln das Ergebnis von `IHueConfigRepository.getScheduleRules()`; ein Fehlschlag
 *   wird "nicht lesbar", nie "keine".
 * @param dndAusgenommen `DndPrefs.shiftExcludedShifts` - EXAKT abgeglichen, wie im Resolver.
 */
internal fun ermittleSchichtFolgen(
    definition: ShiftDefinition,
    dimAn: Boolean,
    dimRegeln: List<DimRule>,
    ruleForShift: (String) -> DimRule?,
    hueRegeln: Result<List<HueSchedule>>,
    hueKonfiguriert: Boolean,
    dndToggles: DndPrefs.Toggles,
    dndAusgenommen: Set<String>
): SchichtFolgen {
    val name = definition.name
    return SchichtFolgen(
        dimmen = dimmStatus(name, dimAn, dimRegeln, ruleForShift),
        licht = lichtStatus(definition, hueRegeln, hueKonfiguriert),
        dnd = dndStatus(definition, dndToggles, dndAusgenommen)
    )
}

private fun dimmStatus(
    name: String,
    dimAn: Boolean,
    dimRegeln: List<DimRule>,
    ruleForShift: (String) -> DimRule?
): DimmStatus {
    if (!dimAn) return DimmStatus.Aus
    val wirksam = ruleForShift(name) ?: return DimmStatus.Keine
    val istAllgemein = wirksam.shiftPattern == DimRule.SHIFT_UNIVERSAL
    val art = when {
        wirksam.windows.isEmpty() -> DimmArt.UNTERDRUECKT
        istAllgemein -> DimmArt.ALLGEMEINE
        else -> DimmArt.EIGENE
    }
    // Weitere AKTIVE Regeln auf denselben Namen - `betrifftSchicht` vergleicht wie
    // `findRuleForShift` ohne Gross-/Kleinschreibung und laesst die Sondermuster aus. Ist die
    // wirksame Regel allgemein, gibt es keine solche (sonst haette `findRuleForShift` sie genommen).
    val ohneWirkung = dimRegeln.count { regel ->
        regel.enabled && regel.id != wirksam.id && regel.betrifftSchicht(name)
    }
    return DimmStatus.Wirksam(art, ohneWirkung)
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

private fun dndStatus(
    definition: ShiftDefinition,
    toggles: DndPrefs.Toggles,
    ausgenommen: Set<String>
): DndStatus {
    val dienstzeit = when {
        !toggles.duringShiftEnabled -> DienstzeitDnd.AUS
        // EXAKT wie `DndShiftSpanResolver.buildShiftSpans` (`shiftName in excludedShifts`): die
        // Ausnahme wirkt nur auf die Dienstzeit-Quelle, nie auf "folgt dem Dimmer".
        definition.name in ausgenommen -> DienstzeitDnd.AUSGENOMMEN
        else -> DienstzeitDnd.AN
    }
    val eineQuelleAn = toggles.duringShiftEnabled || toggles.followDimmerEnabled
    return DndStatus(
        dienstzeit = dienstzeit,
        nachDimmer = toggles.followDimmerEnabled,
        rufbereitschaft = definition.isOnCall && eineQuelleAn
    )
}

/**
 * Die Statuszeile als Text.
 *
 * UMBRUCH (320 dp, siehe Skill cfalarm-ui-und-navigation): Innerhalb eines Abschnitts stehen
 * geschuetzte Leerzeichen, damit "Dimmen: eigene Regel" nie in "Dimmen:" / "eigene Regel"
 * zerfaellt. Umbrochen wird regulaer nur HINTER einem " · " (davor steht ebenfalls ein
 * geschuetztes Leerzeichen, damit der Punkt nicht an den Zeilenanfang rutscht). Die einzigen
 * weiteren Bruchstellen liegen VOR den Zusaetzen "(+N ohne Wirkung)", "(N nur bei Dunkelheit)"
 * und "(Dienstzeit ausgenommen)" sowie HINTER dem Komma vor "Rufbereitschaft": ein Abschnitt mit Zusatz ist
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
 * Die Kurzform einer Zeile der Schichttypen-Liste (Umbau Etappe 1, SPEC 3.1) - `null`, wenn die
 * Zeile keine hat und ihre Statuszeile darueber schon alles sagt.
 *
 * In Etappe 1 ist sie die heutige Statuszeile ([alsText]) plus zwei Faelle, die die Statuszeile nicht
 * kennt: „kein Wecker“ (`weckerAn = false`, steht VOR der Statuszeile) und „wird übergangen“
 * (Ignorieren). Dimm-Kurzformen wie „dimmt 20:00–Wecker“ kommen erst mit Etappe 3. Bei „kein Wecker“ fehlt der Licht-Abschnitt
 * der Statuszeile ([alsTextOhneLicht]): ohne Alarm laeuft keine Hue-Regel.
 *
 * - **Ignorieren** zuerst: ein solcher Typ wirkt weder mit Wecker noch mit Dimmer, Licht oder DND,
 *   auch wenn das ViewModel ihm [SchichtFolgen] liefert (er passiert dort den Filter). Ein
 *   AUSGESCHALTETER Ignorieren-Typ uebergeht nichts - dann gibt es keine Kurzform.
 * - **Ausgeschaltet und Krank** haben keine: ihre Statuszeile sagt, was sie tun bzw. nicht tun.
 * - **`folgen == null`** heisst „noch nicht gelesen“ und bleibt `null` - eine Zeile „Dimmen: keine“
 *   waere eine erfundene Aussage.
 */
internal fun kurzform(definition: ShiftDefinition, folgen: SchichtFolgen?): String? {
    if (definition.kategorie == ShiftKategorie.IGNORIEREN) {
        return if (definition.isEnabled) schuetze(SchichttypTexte.WIRD_UEBERGANGEN) else null
    }
    if (!definition.isEnabled || definition.kategorie == ShiftKategorie.KRANK) return null
    if (definition.weckerAn) return folgen?.alsText()
    // Geschuetzt wie die Abschnitte der Statuszeile: umbrochen wird nur HINTER dem Trenner.
    val keinWecker = schuetze(SchichttypTexte.KEIN_WECKER)
    return if (folgen == null) keinWecker else keinWecker + Texte.TRENNER + folgen.alsTextOhneLicht()
}

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
        add(
            when (art) {
                DimmArt.EIGENE -> Texte.EIGENE_REGEL
                DimmArt.ALLGEMEINE -> Texte.ALLGEMEINE_REGEL
                DimmArt.UNTERDRUECKT -> Texte.UNTERDRUECKT
            }
        )
        if (ohneWirkung > 0) add("(+$ohneWirkung ohne Wirkung)")
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

private fun DndStatus.teile(): List<String> {
    if (aus) return listOf(Texte.AUS)
    val haupt = when (dienstzeit) {
        DienstzeitDnd.AUS -> listOf(Texte.NACH_DIMMER)
        DienstzeitDnd.AN ->
            if (nachDimmer) listOf("${Texte.DIENSTZEIT} + ${Texte.NACH_DIMMER}") else listOf(Texte.DIENSTZEIT)
        DienstzeitDnd.AUSGENOMMEN ->
            if (nachDimmer) listOf(Texte.NACH_DIMMER, "(Dienstzeit ausgenommen)") else listOf(Texte.AUSGENOMMEN)
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
    const val EIGENE_REGEL = "eigene Regel"
    const val ALLGEMEINE_REGEL = "allgemeine Regel"
    const val UNTERDRUECKT = "unterdrückt"
    const val NICHT_LESBAR = "nicht lesbar"
    const val STILLE_SCHICHT = "aus (stille Schicht)"
    const val KEINE_BRIDGE = "keine Bridge"
    /** Gleicher Wortlaut wie das Etikett `tageslicht_etikett_dunkel` in der Hue-Regelliste. */
    const val NUR_BEI_DUNKELHEIT = "nur bei Dunkelheit"
    const val DIENSTZEIT = "Dienstzeit"
    const val NACH_DIMMER = "nach Dimmer"
    const val AUSGENOMMEN = "ausgenommen"
    const val RUFBEREITSCHAFT = "Rufbereitschaft"
}
