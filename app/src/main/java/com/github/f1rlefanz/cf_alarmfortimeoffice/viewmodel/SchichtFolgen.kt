package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.betrifftSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.passtAufSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition

/**
 * Was eine Schicht AUSSERHALB des Weckers ausloest - rein lesend, fuer die Statuszeile
 * "Dimmen: … · Licht: … · DND: …" unter jeder Schichtkarte (Issue #70).
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

    /** [eigene] und [allgemeine] feuern GEMEINSAM; beide 0 heisst "keine". */
    data class Regeln(val eigene: Int, val allgemeine: Int) : LichtStatus
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
    val treffer = regeln.filter { it.enabled && it.passtAufSchicht(definition.name) }
    val allgemeine = treffer.count {
        it.shiftPattern.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true)
    }
    return LichtStatus.Regeln(eigene = treffer.size - allgemeine, allgemeine = allgemeine)
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
 * weiteren Bruchstellen liegen VOR den Zusaetzen "(+N ohne Wirkung)" und "(Dienstzeit
 * ausgenommen)" sowie HINTER dem Komma vor "Rufbereitschaft": ein Abschnitt mit Zusatz ist
 * laenger als eine Kartenzeile bei 320 dp, und ein durchgehend geschuetzter Abschnitt wuerde
 * dann mitten im Wort umbrechen.
 */
internal fun SchichtFolgen.alsText(): String =
    listOf(
        teil(Texte.DIMMEN, dimmen.teile()),
        teil(Texte.LICHT, licht.teile()),
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

private fun LichtStatus.teile(): List<String> = listOf(
    when (this) {
        LichtStatus.NichtLesbar -> Texte.NICHT_LESBAR
        LichtStatus.StilleSchicht -> Texte.STILLE_SCHICHT
        LichtStatus.KeineBridge -> Texte.KEINE_BRIDGE
        is LichtStatus.Regeln -> when {
            eigene > 0 && allgemeine > 0 -> "${regelAnzahl(eigene)} + allgemeine"
            eigene > 0 -> regelAnzahl(eigene)
            allgemeine == 1 -> Texte.ALLGEMEINE_REGEL
            allgemeine > 1 -> "$allgemeine allgemeine Regeln"
            else -> Texte.KEINE
        }
    }
)

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
    const val DND = "DND:"

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
    const val DIENSTZEIT = "Dienstzeit"
    const val NACH_DIMMER = "nach Dimmer"
    const val AUSGENOMMEN = "ausgenommen"
    const val RUFBEREITSCHAFT = "Rufbereitschaft"
}
